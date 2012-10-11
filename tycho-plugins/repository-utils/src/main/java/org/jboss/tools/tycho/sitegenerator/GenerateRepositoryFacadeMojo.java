/**
 * Copyright (c) 2012, Red Hat, Inc.
 * Distributed under license by Red Hat, Inc. All rights reserved.
 * This program is made available under the terms of the
 * Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributor:
 *     Mickael Istria (Red Hat, Inc.) - initial API and implementation
 ******************************************************************************/
package org.jboss.tools.tycho.sitegenerator;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Result;
import javax.xml.transform.Source;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.TransformerFactoryConfigurationError;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.archiver.zip.ZipArchiver;
import org.codehaus.plexus.util.FileUtils;
import org.codehaus.plexus.util.IOUtil;
import org.codehaus.plexus.util.io.RawInputStreamFacade;
import org.eclipse.tycho.ArtifactKey;
import org.eclipse.tycho.ReactorProject;
import org.eclipse.tycho.core.ArtifactDependencyVisitor;
import org.eclipse.tycho.core.FeatureDescription;
import org.eclipse.tycho.core.osgitools.EclipseRepositoryProject;
import org.eclipse.tycho.model.FeatureRef;
import org.eclipse.tycho.model.UpdateSite;
import org.eclipse.tycho.model.UpdateSite.SiteFeatureRef;
import org.eclipse.tycho.packaging.AbstractTychoPackagingMojo;
import org.eclipse.tycho.packaging.UpdateSiteAssembler;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Generates a JBoss-friendly facade and files for this p2 repo
 *
 * @goal generate-repository-facade
 *
 * @phase package
 */
public class GenerateRepositoryFacadeMojo extends AbstractTychoPackagingMojo {
	/**
     * @parameter expression="${session}"
     * @readonly
     */
    private MavenSession session;

    /**
     * @parameter expression="${project}"
     * @readonly
     */
    private MavenProject project;

    /**
     * Additional symbols, to replace in files
     *
     * @parameter
     */
    private Map<String, String> symbols;

    /**
     * template folder for HTML contents
     * @parameter
     */
	private File siteTemplateFolder;

	/**
	 * Additional sites to add to repo associateSites
	 * @parameter
	 */
	private List<String> associateSites;

	/**
	 * name of the file in ${siteTemplateFolder} to use as template for index.html
	 * @parameter default-value="index.html"
	 */
	private String indexName;

    public void execute() throws MojoExecutionException
    {
    	 if (!ArtifactKey.TYPE_ECLIPSE_REPOSITORY.equals(project.getPackaging())) {
             return;
         }

    	 if (this.symbols == null) {
    		 this.symbols = new HashMap<String, String>();
	     }
    	 // TODO populate default symbols: ${update.site.name} & ${update.site.description}

 		File outputRepository = new File(this.project.getBuild().getDirectory(), "repository");
    	File outputSiteXml = generateSiteXml(outputRepository);
        generateSiteProperties(outputRepository, outputSiteXml);
        generateJBossToolsDirectoryXml(outputRepository);
        generateWebStuff(outputRepository, outputSiteXml);
        try {
        	alterContentJar(outputRepository);
        } catch (Exception ex) {
        	throw new MojoExecutionException("Error while altering content.jar",ex);
        }


        File repoZipFile = new File(project.getBuild().getDirectory(), project.getArtifactId() + "-" + project.getVersion() + ".zip");
		repoZipFile.delete();
		try {
	        ZipArchiver archiver = new ZipArchiver();
	        archiver.setDestFile(repoZipFile);
	        archiver.setForced(true);
			archiver.addDirectory(outputRepository);
			archiver.createArchive();
		} catch (Exception ex) {
			throw new MojoExecutionException("Could not create " + repoZipFile.getName(), ex);
		}

    }

	private void generateWebStuff(File outputRepository, File outputSiteXml)
			throws TransformerFactoryConfigurationError, MojoExecutionException {
		// Generate index.html
        try {
	        InputStream siteXsl = getClass().getResourceAsStream("/xslt/site.xsl");
	        Source xsltSource = new StreamSource(siteXsl);
	        Transformer transformer = TransformerFactory.newInstance().newTransformer(xsltSource);
	        ByteArrayOutputStream out = new ByteArrayOutputStream();
	        Result res = new StreamResult(out);
	        transformer.transform(new StreamSource(outputSiteXml), res);
	        siteXsl.close();
	        out.close();
	        symbols.put("${site.contents}", out.toString());
        } catch (Exception ex) {
        	throw new MojoExecutionException("Error occured while generating 'site.properties'", ex);
        }


        try {
        	copyTemplateResources(outputRepository);
        } catch (Exception ex) {
        	throw new MojoExecutionException("Error while copying siteTemplateFolder content to " + outputRepository, ex);
        }
        try {
        	alterIndexFile(outputRepository);
        } catch (Exception ex) {
        	throw new MojoExecutionException("Error writing file " + indexName, ex);
        }
	}

	private void generateJBossToolsDirectoryXml(File outputRepository)
			throws MojoExecutionException {
		// Generate jbosstools-directory.xml
        File[] org_jboss_tools_central_discovery = new File(outputRepository, "plugins").listFiles(new FileFilter() {
			public boolean accept(File arg0) {
				return arg0.getName().startsWith("org.jboss.tools.central.discovery_") && arg0.getName().endsWith(".jar");
			}
		});
        if (org_jboss_tools_central_discovery.length > 0) {
        	try {
		        FileOutputStream directoryXml = new FileOutputStream(new File(outputRepository, "jbosstools-directory.xml"));
		        directoryXml.write("<directory xmlns=\"http://www.eclipse.org/mylyn/discovery/directory/\">\n".getBytes());
		        directoryXml.write("<entry url=\"plugins/".getBytes());
		        directoryXml.write(org_jboss_tools_central_discovery[0].getName().getBytes());
		        directoryXml.write("\" permitCategories=\"true\"/>\n".getBytes());
		        directoryXml.write("</directory>".getBytes());
		        directoryXml.close();
        	} catch (Exception ex) {
        		throw new MojoExecutionException("Could not write file 'jbosstools-directory.xml'", ex);
        	}
        }
        if (org_jboss_tools_central_discovery.length == 0) {
        	getLog().warn("No org.jboss.tools.central.discovery plugin in repo. Skip generatio of 'jbosstools-directory.xml'");
        }
        if (org_jboss_tools_central_discovery.length > 1) {
        	getLog().warn("Several org.jbosstools.central.discovery plugin in repo");
        }
	}

	private void generateSiteProperties(File outputRepository,
			File outputSiteXml) throws TransformerFactoryConfigurationError,
			MojoExecutionException {
		// Generate site.properties
        try {
	        InputStream siteXsl = getClass().getResourceAsStream("/xslt/site.properties.xsl");
	        Source xsltSource = new StreamSource(siteXsl);
	        Transformer transformer = TransformerFactory.newInstance().newTransformer(xsltSource);
	        FileOutputStream out = new FileOutputStream(new File(outputRepository, "site.properties"));
	        Result res = new StreamResult(out);
	        transformer.transform(new StreamSource(outputSiteXml), res);
	        siteXsl.close();
	        out.close();
        } catch (Exception ex) {
        	throw new MojoExecutionException("Error occured while generating 'site.properties'", ex);
        }

        try {
        	copyTemplateResources(outputRepository);
        } catch (Exception ex) {
        	throw new MojoExecutionException("Error while copying siteTemplateFolder content to " + outputRepository, ex);
        }
	}

	private File generateSiteXml(File outputRepository)
			throws MojoExecutionException {
		// Generate site.xml
        File categoryFile = new File(project.getBasedir(), "category.xml");
        if (!categoryFile.isFile()) {
        	throw new MojoExecutionException("Missing 'category.xml file'");
        }

		UpdateSite site = null;
		try {
			site = UpdateSite.read(categoryFile);
		} catch (IOException ex) {
			throw new MojoExecutionException("Could not read 'category.xml' file", ex);
		}
		new EclipseRepositoryProject().getDependencyWalker(project).traverseUpdateSite(site, new ArtifactDependencyVisitor() {
            @Override
            public boolean visitFeature(FeatureDescription feature) {
                FeatureRef featureRef = feature.getFeatureRef();
                String id = featureRef.getId();
                ReactorProject otherProject = feature.getMavenProject();
                String version;
                if (otherProject != null) {
                    version = otherProject.getExpandedVersion();
                } else {
                    version = feature.getKey().getVersion();
                }
                String url = UpdateSiteAssembler.FEATURES_DIR + id + "_" + version + ".jar";
                ((SiteFeatureRef) featureRef).setUrl(url);
                featureRef.setVersion(version);
                return false; // don't traverse included features
            }
        });

        File outputSiteXml = new File(outputRepository, "site.xml");
        try {
        	 if (!outputSiteXml.exists()) {
             	outputSiteXml.createNewFile();
             }
        	UpdateSite.write(site, outputSiteXml);
        } catch (IOException ex) {
        	ex.printStackTrace();
        	throw new MojoExecutionException("Could not write site.xml to '" + outputSiteXml.getAbsolutePath() + "'", ex);
        }
		return outputSiteXml;
	}

	private void alterContentJar(File outputRepository)
			throws FileNotFoundException, IOException, SAXException,
			ParserConfigurationException, TransformerFactoryConfigurationError,
			TransformerConfigurationException, TransformerException {
		File contentJar = new File(outputRepository, "content.jar");
        ZipInputStream contentStream = new ZipInputStream(new FileInputStream(contentJar));
        ZipEntry entry = null;
        Document contentDoc = null;
        boolean done = false;
        while (!done && (entry = contentStream.getNextEntry()) != null) {
        	if (entry.getName().equals("content.xml")) {
        		contentDoc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(contentStream);
        		Element repoElement = (Element)contentDoc.getElementsByTagName("repository").item(0);
        		NodeList references = repoElement.getElementsByTagName("references");
        		for (int i = 0; i < references.getLength(); i++) {
        			Node currentRef = references.item(i);
        			currentRef.getParentNode().removeChild(currentRef);
        		}
        		if (associateSites != null && associateSites.size() > 0) {
        			Element refElement = contentDoc.createElement("references");
        			refElement.setAttribute("size", Integer.valueOf(2 * associateSites.size()).toString());
        			for (String associate : associateSites) {
	        			Element rep0 = contentDoc.createElement("repository");
	        			rep0.setAttribute("uri", associate);
	        			rep0.setAttribute("url", associate);
	        			rep0.setAttribute("type", "0");
	        			rep0.setAttribute("options", "1");
	        			refElement.appendChild(rep0);
	        			Element rep1 = (Element)rep0.cloneNode(true);
	        			refElement.appendChild(rep1);
        			}
        			repoElement.appendChild(refElement);
        		}
        		done = true;
        	}
        }
        // .close and .closeEntry raise exception:
        // https://issues.apache.org/bugzilla/show_bug.cgi?id=3862
        ZipOutputStream outContentStream = new ZipOutputStream(new FileOutputStream(contentJar));
        ZipEntry contentXmlEntry = new ZipEntry("content.xml");
        outContentStream.putNextEntry(contentXmlEntry);
        TransformerFactory tFactory = TransformerFactory.newInstance();
        Transformer transformer = tFactory.newTransformer();
        DOMSource source = new DOMSource(contentDoc);
        StreamResult result = new StreamResult(outContentStream);
        transformer.transform(source, result);
        outContentStream.closeEntry();
        outContentStream.close();
	}

	private void alterIndexFile(File outputSite) throws FileNotFoundException, IOException {
		File templateFile = new File(outputSite, this.indexName);
		FileInputStream fis = new FileInputStream(templateFile);
		String htmlFile = IOUtil.toString(fis, "UTF-8");
		for (Entry<String, String> entry : this.symbols.entrySet()) {
			String key = entry.getKey();
			if (!key.startsWith("${")) {
				key = "${" + key + "}";
			}
			htmlFile = htmlFile.replace(key, entry.getValue());
		}
		FileOutputStream out = new FileOutputStream(templateFile);
		out.write(htmlFile.getBytes("UTF-8"));
		fis.close();
		out.close();
	}

	private void copyTemplateResources(File outputSite) throws IOException, MojoExecutionException {
		if (this.siteTemplateFolder != null) {
			if (!this.siteTemplateFolder.isDirectory()) {
				throw new MojoExecutionException("'siteTemplateFolder' not correctly set. " + this.siteTemplateFolder.getAbsolutePath() + " is not a directory");
			}
			FileUtils.copyDirectoryStructure(this.siteTemplateFolder, outputSite);
			if (!new File(this.siteTemplateFolder, this.indexName).isFile()) {
				// copy default index
				InputStream indexStream = getClass().getResourceAsStream("/index.html");
				FileUtils.copyStreamToFile(new RawInputStreamFacade(indexStream), new File(outputSite, this.indexName));
				indexStream.close();
			}
		} else {
			InputStream indexStream = getClass().getResourceAsStream("/index.html");
			FileUtils.copyStreamToFile(new RawInputStreamFacade(indexStream), new File(outputSite, this.indexName));
			indexStream.close();
			File webFolder = new File(outputSite, "web");
			if (!webFolder.exists()) {
				webFolder.mkdir();
			}
			InputStream cssStream = getClass().getResourceAsStream("/web/site.css");
			FileUtils.copyStreamToFile(new RawInputStreamFacade(cssStream), new File(webFolder, "site.css"));
			cssStream.close();
		}
	}
}
