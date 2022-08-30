/**
 * Copyright (c) 2012-2017, Red Hat, Inc.
 * Distributed under license by Red Hat, Inc. All rights reserved.
 * This program is made available under the terms of the
 * Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributor:
 *     Mickael Istria (Red Hat, Inc.) - initial API and implementation
 *     Nick Boldt (Red Hat Inc.) - fixes
 ******************************************************************************/
package org.jboss.tools.tycho.sitegenerator;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
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

import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream;
import org.apache.commons.io.IOUtils;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.archiver.zip.ZipArchiver;
import org.codehaus.plexus.util.FileUtils;
import org.codehaus.plexus.util.IOUtil;
import org.codehaus.plexus.util.io.RawInputStreamFacade;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.tycho.PackagingType;
import org.eclipse.tycho.ReactorProject;
import org.eclipse.tycho.core.ArtifactDependencyVisitor;
import org.eclipse.tycho.core.FeatureDescription;
import org.eclipse.tycho.core.TychoProject;
import org.eclipse.tycho.core.osgitools.DefaultReactorProject;
import org.eclipse.tycho.core.osgitools.EclipseRepositoryProject;
import org.eclipse.tycho.model.FeatureRef;
import org.eclipse.tycho.model.UpdateSite;
import org.eclipse.tycho.model.UpdateSite.SiteFeatureRef;
import org.eclipse.tycho.packaging.AbstractTychoPackagingMojo;
import org.eclipse.tycho.packaging.UpdateSiteAssembler;
import org.jboss.dmr.ModelNode;
import org.jboss.dmr.Property;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Generates a JBoss-friendly facade and files for this p2 repo
 * 
 *  For example, see repository-utils/src/main/resources/generate-repository-facade-example.pom.xml
 */
@Mojo(name = "generate-repository-facade", defaultPhase = LifecyclePhase.PACKAGE)
public class GenerateRepositoryFacadeMojo extends AbstractTychoPackagingMojo {

	private enum ReferenceStrategy {
		embedReferences,
		compositeReferences
	}

	public static final Set<String> defaultSystemProperties = new HashSet<String>(Arrays.asList(new String[] {
		// these are all parameters of the Jenkins job; if not set they'll be null
		// TODO should default to null or "" ?
		"BUILD_ALIAS",
		"JOB_NAME",
		"BUILD_NUMBER",
		"RELEASE",
		"ZIPSUFFIX",
		"TARGET_PLATFORM_VERSION",
		"TARGET_PLATFORM_VERSION_MAXIMUM",
		"NODE_NAME", // The name of the node the current build is running on
		
		// these are environment variables so should be valid when run in Jenkins or for local builds
		"HOSTNAME", // replaces HUDSON_SLAVE: more portable & means the same thing
		"WORKSPACE", // likely the same as user.dir, unless -DWORKSPACE= used to override
		"os.name",
		"os.version",
		"os.arch",
		"java.vendor",
		"java.version",
		"user.dir"
	}));

	private static final String UPSTREAM_ELEMENT = "upstream";
	public static final String BUILDINFO_JSON = "buildinfo.json";

	@Parameter(property = "project", required = true, readonly = true)
	private MavenProject project;

	@Parameter(property = "session", readonly = true)
	private MavenSession session;

	/**
	 * Additional symbols, to replace in files
	 */
	@Parameter
	private Map<String, String> symbols;

	/**
	 * template folder for HTML contents
	 */
	@Parameter
	private File siteTemplateFolder;

	/**
	 * Additional files to add to repo and that are not in the
	 * "siteTemplateFolder". These can be folders.
	 */
	@Parameter
	private List<File> additionalWebResources;

	/**
	 * Additional sites to add to repo associateSites
	 */
	@Parameter
	private List<String> associateSites;

	/**
	 * This can have 2 values: embedReferences or compositeReferences.
	 * "embedReferences" will add the repository references directly to the content.jar
	 * of the repository.
	 * "compositeReferences" will add repository references to a new external content.xml
	 * and will create a composite that composite both content and references. Then top-level
	 * repository won't contain any reference to other repo whereas repository in "withreferences"
	 * will composite the top-level repo, with the additional repo adding references to
	 * associateSites
	 *
	 * "compositeReferences" is preferred in case your site is used by an upstream project
	 * that will manage the dependencies since its output is actually 2 sites: one without
	 * the references for integrators, and one with references for testers/users who just
	 * want dependencies to come without adding sites, so relying on references.
	 */
	@Parameter(defaultValue="embedReferences")
	private ReferenceStrategy referenceStrategy;

	/**
	 * name of the file in ${siteTemplateFolder} to use as template for
	 * index.html
	 */
	@Parameter(defaultValue = "index.html")
	private String indexName;

	/**
	 * name of the file in ${siteTemplateFolder}/web to use for CSS
	 */
	@Parameter(defaultValue = "site.css")
	private String cssName;

	/**
	 * Whether to remove or not the "Uncategorized" default category
	 */
	@Parameter(defaultValue = "false")
	private boolean removeDefaultCategory;

	@Component(role = TychoProject.class)
	private Map<String, TychoProject> projectTypes;

    /**
     * Whether to skip generation of index.html and associated files
     */
    @Parameter(defaultValue = "false")
    private boolean skipWebContentGeneration;

    /**
     * Whether to skip generation of buildinfo.json
     */
    @Parameter(defaultValue = "false")
    private boolean skipBuildInfo;

	@Parameter
	private String p2StatsUrl;

	/**
	 * In case some content is missing on site, use alternate URL pattern as fallback, if provided.
	 * Eg, search
	 * http://download.jboss.org/jbosstools/mars/snapshots/builds/jbosstools-base_master/latest/all/repo/buildinfo.json instead of
	 * http://download.jboss.org/jbosstools/mars/snapshots/builds/jbosstools-base_master/buildinfo.json
	 */
	@Parameter(defaultValue="latest/all/repo")
	private String fallbackToChild;

	@Parameter
	private Set<String> systemProperties;

	private File categoryFile;

	@Override
	public void execute() throws MojoExecutionException, MojoFailureException {
		if (!PackagingType.TYPE_ECLIPSE_REPOSITORY.equals(this.project.getPackaging())) {
			return;
		}
		if (systemProperties == null) {
			systemProperties = defaultSystemProperties;
		}
		this.categoryFile = new File(project.getBasedir(), "category.xml");
		if (!this.categoryFile.isFile()) {
			// happens in case of definition based on .product
			return;
		}

		if (this.symbols == null) {
			this.symbols = new HashMap<String, String>();
		}
		// TODO populate default symbols: ${update.site.name} &
		// ${update.site.description}

		File outputRepository = new File(this.project.getBuild().getDirectory(), "repository");
		File buildinfoFolder = new File(this.project.getBuild().getDirectory(), "buildinfo");
		if (!skipBuildInfo) {
			buildinfoFolder.mkdirs();
		}

		// If a siteTemplateFolder is set, pull index.html and site.css from
		// there; otherwise use defaults
        if (!skipWebContentGeneration) {
            try {
                copyTemplateResources(outputRepository);
            } catch (Exception ex) {
                throw new MojoExecutionException("Error while copying siteTemplateFolder content to " + outputRepository, ex);
            }
            if (this.additionalWebResources != null) {
                for (File resource : this.additionalWebResources) {
                    try {
                        if (resource.isDirectory()) {
                            FileUtils.copyDirectoryStructure(resource, new File(outputRepository, resource.getName()));
                        } else if (resource.isFile()) {
                            FileUtils.copyFile(resource, new File(outputRepository, resource.getName()));
                        }
                    } catch (Exception ex) {
                        throw new MojoExecutionException("Error while copying resource " + resource.getPath(), ex);
                    }
                }
            }

            File outputCategoryXml = generateCategoryXml(outputRepository);
            if (new File(outputRepository, "features").isDirectory()) { //$NON-NLS-1$
                generateSiteProperties(outputRepository, outputCategoryXml);
            }
            generateWebStuff(outputRepository, outputCategoryXml);
		}
		try {
			alterContentJar(outputRepository);
		} catch (Exception ex) {
			throw new MojoExecutionException("Error while altering content.jar", ex);
		}
		if (this.p2StatsUrl != null) {
			try {
				addP2Stats(outputRepository);
			} catch (Exception ex) {
				throw new MojoExecutionException("Error while adding p2.stats to repository", ex);
			}
		}
		if (this.associateSites != null && !this.associateSites.isEmpty() && this.referenceStrategy == ReferenceStrategy.compositeReferences) {
			try {
				createCompositeReferences(outputRepository, this.associateSites);
			} catch (IOException ex) {
				throw new MojoFailureException(ex.getMessage(), ex);
			}
		}

		if (!skipBuildInfo) {
			// collect buildinfo.json files from upstream and store them in target/buildinfo/
			// also save a copy of the merged buildinfo.json in target/repository/
			createBuildInfo(outputRepository, buildinfoFolder);
		}

		File repoZipFile = new File(this.project.getBuild().getDirectory(), this.project.getArtifactId() + "-" + this.project.getVersion() + ".zip");
		repoZipFile.delete();
		ZipArchiver archiver = new ZipArchiver();
		archiver.setDestFile(repoZipFile);
		archiver.setForced(true);
		archiver.addDirectory(outputRepository);
		try {
			archiver.createArchive();
		} catch (IOException ex) {
			throw new MojoFailureException("Could not create " + repoZipFile.getName(), ex);
		}
	}

	private void createCompositeReferences(File outputRepository, List<String> associateSites2) throws IOException {
		long timestamp = System.currentTimeMillis();
		String repoName = this.project.getName();
		if (repoName == null) {
			repoName = this.project.getArtifactId();
		}
		File referencesDir = new File(outputRepository, "references");
		referencesDir.mkdir();
		File contentXmlReference = new File(referencesDir, "content.xml");
		StringBuilder content = new StringBuilder();
		content
			.append("<?xml version='1.0' encoding='UTF-8' standalone='yes'?>").append('\n')
			.append("<?metadataRepository version='1.1.0'?>").append('\n')
			.append("<repository name='References for").append(repoName).append("' type='org.eclipse.equinox.internal.p2.metadata.repository.LocalMetadataRepository' version='1'>").append('\n')
			.append("  <properties size='1'>").append('\n')
		    .append("    <property name='p2.timestamp' value='").append(timestamp).append("'/>").append('\n')
		    .append("  </properties>").append('\n')
		    .append("  <references size='").append(2 * associateSites2.size()).append("'>").append('\n');
		for (String site : associateSites2) {
			content.append("      <repository options='1' type='0' uri='").append(site).append("' url='").append(site).append("'/>").append('\n');
			content.append("      <repository options='1' type='1' uri='").append(site).append("' url='").append(site).append("'/>").append('\n');
		}
		content.append("  </references>").append('\n');
		content.append("</repository>");
		org.apache.commons.io.FileUtils.writeStringToFile(contentXmlReference, content.toString());

		File compositeWithRefDir = new File(outputRepository, "withreferences");
		compositeWithRefDir.mkdir();
		{
			File compositeContentXml = new File(compositeWithRefDir, "compositeContent.xml");
			StringBuilder compositeContent = new StringBuilder();
			compositeContent.append("<?compositeMetadataRepository version='1.0.0'?>").append('\n')
				.append("<repository name='").append(repoName).append("' type='org.eclipse.equinox.internal.p2.metadata.repository.CompositeMetadataRepository' version='1.0.0'>").append('\n')
				.append("  <properties size='2'>").append('\n')
			    .append("    <property name='p2.compressed' value='true'/>").append('\n')
			    .append("    <property name='p2.timestamp' value='").append(timestamp).append("'/>").append('\n')
			    .append("  </properties>").append("\n")
			    .append("  <children size='2'>").append("\n")
			    .append("    <child location='../'/>").append('\n')
			    .append("    <child location='../references'/>").append('\n')
			    .append("  </children>").append('\n')
			    .append("</repository>");
			org.apache.commons.io.FileUtils.writeStringToFile(compositeContentXml, compositeContent.toString());
		}
		{
			File compositeArtifactsXml = new File(compositeWithRefDir, "compositeArtifacts.xml");
			StringBuilder compositeArtifact = new StringBuilder();
			compositeArtifact.append("<?compositeArtifactRepository version='1.0.0'?>").append('\n')
				.append("<repository name='").append(repoName).append("' type='org.eclipse.equinox.internal.p2.artifact.repository.CompositeArtifactRepository' version='1.0.0'>").append('\n')
				.append("  <properties size='2'>").append('\n')
			    .append("    <property name='p2.compressed' value='true'/>").append('\n')
			    .append("    <property name='p2.timestamp' value='").append(timestamp).append("'/>").append('\n')
			    .append("  </properties>").append("\n")
			    .append("  <children size='1'>").append("\n")
			    .append("    <child location='../'/>").append('\n')
			    .append("  </children>").append('\n')
			    .append("</repository>");
			org.apache.commons.io.FileUtils.writeStringToFile(compositeArtifactsXml, compositeArtifact.toString());
		}
	}

	private void generateWebStuff(File outputRepository, File outputCategoryXml) throws TransformerFactoryConfigurationError, MojoExecutionException {
		// Generate index.html
		try {
			InputStream siteXsl = getClass().getResourceAsStream("/xslt/site.xsl");
			Source xsltSource = new StreamSource(siteXsl);
			Transformer transformer = TransformerFactory.newInstance().newTransformer(xsltSource);
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			Result res = new StreamResult(out);
			transformer.transform(new StreamSource(outputCategoryXml), res);
			siteXsl.close();
			this.symbols.put("${site.contents}", out.toString());
			out.close();
		} catch (Exception ex) {
			throw new MojoExecutionException("Error occured while generating 'site.xsl'", ex);
		}
		try {
			alterIndexFile(outputRepository);
		} catch (Exception ex) {
			throw new MojoExecutionException("Error writing file " + indexName, ex);
		}
	}

	private void generateSiteProperties(File outputRepository, File outputCategoryXml) throws TransformerFactoryConfigurationError, MojoExecutionException {
		// Generate site.properties
		try {
			InputStream siteXsl = getClass().getResourceAsStream("/xslt/site.properties.xsl");
			Source xsltSource = new StreamSource(siteXsl);
			Transformer transformer = TransformerFactory.newInstance().newTransformer(xsltSource);
			FileOutputStream out = new FileOutputStream(new File(outputRepository, "site.properties"));
			Result res = new StreamResult(out);
			transformer.transform(new StreamSource(outputCategoryXml), res);
			siteXsl.close();
			out.close();
		} catch (Exception ex) {
			throw new MojoExecutionException("Error occured while generating 'site.properties'", ex);
		}
	}

	/*
	 * This version of category.xml (including feature/bundle versions) is used to generate list of features in site.properties and index.html
	 */
	private File generateCategoryXml(File outputRepository) throws MojoExecutionException {
		// Generate category.xml
		UpdateSite site = null;
		try {
			site = UpdateSite.read(this.categoryFile);
		} catch (IOException ex) {
			throw new MojoExecutionException("Could not read 'category.xml' file", ex);
		}
		new EclipseRepositoryProject().getDependencyWalker(DefaultReactorProject.adapt(this.project)).traverseUpdateSite(site, new ArtifactDependencyVisitor() {
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

		File outputCategoryXml = new File(outputRepository, "category.xml");
		try {
			if (!outputCategoryXml.exists()) {
				outputCategoryXml.createNewFile();
			}
			UpdateSite.write(site, outputCategoryXml);
		} catch (IOException ex) {
			ex.printStackTrace();
			throw new MojoExecutionException("Could not write category.xml to '" + outputCategoryXml.getAbsolutePath() + "'", ex);
		}
		return outputCategoryXml;
	}

	/**
	 * Alter content.xml, content.jar, content.xml.xz to:
	 * remove default "Uncategorized" category, 
	 * remove 3rd party associate sites, and 
	 * add associate sites defined in site's pom.xml
	 *
	 * @param p2repository
	 * @throws FileNotFoundException
	 * @throws IOException
	 * @throws SAXException
	 * @throws ParserConfigurationException
	 * @throws TransformerFactoryConfigurationError
	 * @throws TransformerConfigurationException
	 * @throws TransformerException
	 * @throws MojoFailureException
	 */
	private void alterContentJar(File p2repository) throws FileNotFoundException, IOException, SAXException, ParserConfigurationException, TransformerFactoryConfigurationError,
			TransformerConfigurationException, TransformerException, MojoFailureException {
		File contentJar = new File(p2repository, "content.jar");
		ZipInputStream contentStream = new ZipInputStream(new FileInputStream(contentJar));
		ZipEntry entry = null;
		Document contentDoc = null;
		boolean done = false;
		while (!done && (entry = contentStream.getNextEntry()) != null) {
			if (entry.getName().equals("content.xml")) {
				contentDoc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(contentStream);
				Element repoElement = (Element) contentDoc.getElementsByTagName("repository").item(0);
				{
					NodeList references = repoElement.getElementsByTagName("references");
					// remove default references
					for (int i = 0; i < references.getLength(); i++) {
						Node currentRef = references.item(i);
						currentRef.getParentNode().removeChild(currentRef);
					}
					// add associateSites
					if (this.associateSites != null && this.associateSites.size() > 0 && this.referenceStrategy == ReferenceStrategy.embedReferences) {
						Element refElement = contentDoc.createElement("references");
						refElement.setAttribute("size", Integer.valueOf(2 * associateSites.size()).toString());
						for (String associate : associateSites) {
							Element rep0 = contentDoc.createElement("repository");
							rep0.setAttribute("uri", associate);
							rep0.setAttribute("url", associate);
							rep0.setAttribute("type", "0");
							rep0.setAttribute("options", "1");
							refElement.appendChild(rep0);
							Element rep1 = (Element) rep0.cloneNode(true);
							rep1.setAttribute("type", "1");
							refElement.appendChild(rep1);
						}
						repoElement.appendChild(refElement);
					}
				}
				// remove default "Uncategorized" category
				if (this.removeDefaultCategory) {
					Element unitsElement = (Element) repoElement.getElementsByTagName("units").item(0);
					NodeList units = unitsElement.getElementsByTagName("unit");
					for (int i = 0; i < units.getLength(); i++) {
						Element unit = (Element) units.item(i);
						String id = unit.getAttribute("id");
						if (id != null && id.contains(".Default")) {
							unit.getParentNode().removeChild(unit);
						}
					}
					unitsElement.setAttribute("size", Integer.toString(unitsElement.getElementsByTagName("unit").getLength()));
				}
				done = true;
			}
		}
		// .close and .closeEntry raise exception:
		// https://issues.apache.org/bugzilla/show_bug.cgi?id=3862
		ZipOutputStream outContentStream = new ZipOutputStream(new FileOutputStream(contentJar));
		ZipEntry contentXmlEntry = new ZipEntry("content.xml");
		outContentStream.putNextEntry(contentXmlEntry);
		Transformer transformer = TransformerFactory.newInstance().newTransformer();
		transformer.setOutputProperty(OutputKeys.INDENT, "yes");
		transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
		transformer.setOutputProperty(OutputKeys.STANDALONE, "yes");
		DOMSource source = new DOMSource(contentDoc);
		StreamResult result = new StreamResult(outContentStream);
		transformer.transform(source, result);
		contentStream.close();
		outContentStream.closeEntry();
		outContentStream.close();
		alterXzFile(new File(p2repository, "content.xml"), new File(p2repository,"content.xml.xz"), transformer, source);
	}

	/**
	 * Add p2 stats to the repository's artifacts.xml (and .jar and .xml.xz) 
	 * See http://wiki.eclipse.org/Equinox_p2_download_stats
	 *
	 * @param p2repository
	 * @throws FileNotFoundException
	 * @throws IOException
	 * @throws SAXException
	 * @throws ParserConfigurationException
	 * @throws TransformerFactoryConfigurationError
	 * @throws TransformerConfigurationException
	 * @throws TransformerException
	 * @throws MojoFailureException
	 */
	private void addP2Stats(File p2repository) throws FileNotFoundException, IOException, SAXException, ParserConfigurationException, TransformerFactoryConfigurationError,
			TransformerConfigurationException, TransformerException, MojoFailureException {
		File artifactsJar = new File(p2repository, "artifacts.jar");
		ZipInputStream contentStream = new ZipInputStream(new FileInputStream(artifactsJar));
		ZipEntry entry = null;
		Document contentDoc = null;
		boolean done = false;
		while (!done && (entry = contentStream.getNextEntry()) != null) {
			if (entry.getName().equals("artifacts.xml")) {
				contentDoc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(contentStream);
				Element repoElement = (Element) contentDoc.getElementsByTagName("repository").item(0);
				// Add p2.StatsURI property
				Element repoProperties = (Element) contentDoc.getElementsByTagName("properties").item(0);
				int newRepoPropertiesSize = Integer.parseInt(repoProperties.getAttribute("size")) + 1;
				repoProperties.setAttribute("size", Integer.toString(newRepoPropertiesSize));
				Element p2statsElement = contentDoc.createElement("property");
				p2statsElement.setAttribute("name", "p2.statsURI");
				p2statsElement.setAttribute("value", this.p2StatsUrl);
				repoProperties.appendChild(p2statsElement);
				// process features
				NodeList artifacts = ((Element) repoElement.getElementsByTagName("artifacts").item(0)).getElementsByTagName("artifact");
				for (int i = 0; i < artifacts.getLength(); i++) {
					Element currentArtifact = (Element) artifacts.item(i);
					if (currentArtifact.getAttribute("classifier").equals("org.eclipse.update.feature")) {
						String iu = currentArtifact.getAttribute("id");
						Element artifactProperties = (Element) currentArtifact.getElementsByTagName("properties").item(0);
						int newArtifactPropertiesSize = Integer.parseInt(artifactProperties.getAttribute("size")) + 1;
						artifactProperties.setAttribute("size", Integer.toString(newArtifactPropertiesSize));
						Element statsElement = contentDoc.createElement("property");
						statsElement.setAttribute("name", "download.stats");
						statsElement.setAttribute("value", iu);
						artifactProperties.appendChild(statsElement);
					}
				}
				done = true;
			}
		}
		// .close and .closeEntry raise exception:
		// https://issues.apache.org/bugzilla/show_bug.cgi?id=3862
		ZipOutputStream outContentStream = new ZipOutputStream(new FileOutputStream(artifactsJar));
		ZipEntry contentXmlEntry = new ZipEntry("artifacts.xml");
		outContentStream.putNextEntry(contentXmlEntry);
		Transformer transformer = TransformerFactory.newInstance().newTransformer();
		transformer.setOutputProperty(OutputKeys.INDENT, "yes");
		transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
		DOMSource source = new DOMSource(contentDoc);
		StreamResult result = new StreamResult(outContentStream);
		transformer.transform(source, result);
		contentStream.close();
		outContentStream.closeEntry();
		outContentStream.close();
		alterXzFile(new File(p2repository, "artifacts.xml"), new File(p2repository,"artifacts.xml.xz"), transformer, source);

	}

	/**
	 * Add p2 stats to the repository's artifacts.xml (and .jar and .xml.xz) 
	 * See http://wiki.eclipse.org/Equinox_p2_download_stats
	 * 
	 * @param theXml
	 * @param theXmlXz
	 * @param transformer
	 * @param source
	 * @throws MojoFailureException
	 * 
	 * */

	private void alterXzFile(File theXml, File theXmlXz, Transformer transformer, DOMSource source) throws MojoFailureException {
		try {
			// JBDS-3929 overwrite the artifacts.xml.xz file too
			// see also https://bugs.eclipse.org/bugs/show_bug.cgi?id=464614
			//getLog().debug("delete " + theXmlXz.toString());
			FileUtils.forceDelete(theXmlXz);
			//getLog().debug("create " + theXml.toString() + " from transformed XML");
			FileOutputStream outStreamXml = new FileOutputStream(theXml);
			StreamResult resultXml = new StreamResult(outStreamXml);
			transformer.transform(source, resultXml);
			outStreamXml.close();
			//getLog().debug("stream " + theXml.toString() + " to " + theXmlXz.toString());
			BufferedInputStream in = new BufferedInputStream(new FileInputStream(theXml));
			XZCompressorOutputStream out = new XZCompressorOutputStream(new FileOutputStream(theXmlXz));
			final byte[] buffer = new byte[1024];
			int n = 0;
			while (-1 != (n = in.read(buffer))) {
			    out.write(buffer, 0, n);
			}
			out.close();
			in.close();
			//getLog().debug("new " + theXmlXz.toString() + " written; remove " + theXml.toString());
			FileUtils.forceDelete(theXml);
		} catch (IOException|TransformerException ex) { 
			getLog().error(ex); 
			throw new MojoFailureException("Error while compressing " + theXml.toString(), ex);
		} 
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
			if (entry.getValue() != null) {
				htmlFile = htmlFile.replace(key, entry.getValue());
			}
		}
		FileOutputStream out = new FileOutputStream(templateFile);
		out.write(htmlFile.getBytes("UTF-8"));
		fis.close();
		out.close();
	}

	private void copyTemplateResources(File outputSite) throws IOException, MojoExecutionException {
		getLog().debug("Using outputSite = " + outputSite);
		getLog().debug("Using siteTemplateFolder = " + this.siteTemplateFolder);
		if (this.siteTemplateFolder != null) {
			if (!this.siteTemplateFolder.isDirectory()) {
				throw new MojoExecutionException("'siteTemplateFolder' not correctly set. " + this.siteTemplateFolder.getAbsolutePath() + " is not a directory");
			}
			if (!outputSite.isDirectory())
			{
				outputSite.mkdirs();
			}
			FileUtils.copyDirectoryStructure(this.siteTemplateFolder, outputSite);

			// verify we have everything we need after copying from the
			// siteTemplateFolder
			if (!new File(outputSite, this.indexName).isFile()) {
				// copy default index
				getLog().warn("No " + this.siteTemplateFolder + "/" + this.indexName + " found; using default.");
				InputStream indexStream = getClass().getResourceAsStream("/index.html");
				FileUtils.copyStreamToFile(new RawInputStreamFacade(indexStream), new File(outputSite, this.indexName));
				indexStream.close();
			}
			File webFolder = new File(outputSite, "web");
			if (!webFolder.exists()) {
				webFolder.mkdir();
			}
			if (!new File(webFolder, this.cssName).isFile()) {
				// copy default css
				getLog().warn("No " + webFolder + "/" + this.cssName + " found; using default.");
				InputStream cssStream = getClass().getResourceAsStream("/web/" + this.cssName);
				FileUtils.copyStreamToFile(new RawInputStreamFacade(cssStream), new File(webFolder, this.cssName));
				cssStream.close();
			}
		} else {
			// copy default index
			InputStream indexStream = getClass().getResourceAsStream("/index.html");
			FileUtils.copyStreamToFile(new RawInputStreamFacade(indexStream), new File(outputSite, this.indexName));
			indexStream.close();
			File webFolder = new File(outputSite, "web");
			if (!webFolder.exists()) {
				webFolder.mkdir();
			}
			// copy default css
			InputStream cssStream = getClass().getResourceAsStream("/web/" + this.cssName);
			FileUtils.copyStreamToFile(new RawInputStreamFacade(cssStream), new File(webFolder, this.cssName));
			cssStream.close();
		}
	}

	/**
	 * @param outputRepository
	 * @throws MojoFailureException
	 * @throws MojoExecutionException
	 */
	private void createBuildInfo(File outputRepository, File buildinfoFolder) throws MojoFailureException, MojoExecutionException {
		ModelNode jsonProperties = new ModelNode();
		jsonProperties.get("timestamp").set(System.currentTimeMillis()); // TODO get it from build metadata

		try {
			jsonProperties.get("revision").set(createRevisionObject());
		} catch (FileNotFoundException ex) {
			getLog().error("Could not add revision to " + BUILDINFO_JSON + ": not a Git repository", ex);
		} catch (Exception ex) {
			throw new MojoFailureException("Could not add revision to " + BUILDINFO_JSON, ex);
		}

		// get hostname and load into HOSTNAME
		java.net.InetAddress localMachine;
		try {
			localMachine = java.net.InetAddress.getLocalHost();
			System.setProperty("HOSTNAME",localMachine.getHostName());
		} catch (UnknownHostException e) {
			e.printStackTrace();
		}
		
		// if WORKSPACE is null, use current directory
		if (System.getProperty("WORKSPACE") == null || System.getProperty("WORKSPACE").equals(""))
		{
			System.setProperty("WORKSPACE",Paths.get("").toAbsolutePath().toString());
		}

		ModelNode sysProps = new ModelNode();
		for (String propertyName : this.systemProperties) {
			sysProps.get(propertyName).set(String.valueOf(System.getProperty(propertyName)));
		}

		jsonProperties.get("properties").set(sysProps);

		try {
			jsonProperties.get(UPSTREAM_ELEMENT).set(aggregateUpstreamMetadata(buildinfoFolder));
		} catch (Exception ex) {
			throw new MojoExecutionException("Could not get upstream metadata", ex);
		}

		try {
			// put buildinfo.json inside the target/buildinfo/ folder
			FileUtils.fileWrite(new File(buildinfoFolder, BUILDINFO_JSON), jsonProperties.toJSONString(false));
			// put a copy inside the target/repository/ folder (and site zip)
			FileUtils.fileWrite(new File(outputRepository, BUILDINFO_JSON), jsonProperties.toJSONString(false));
		} catch (Exception ex) {
			throw new MojoFailureException("Could not generate properties file", ex);
		}
	}

	private ModelNode aggregateUpstreamMetadata(File buildinfoFolder) throws MojoFailureException {
		List<?> repos = this.project.getRepositories();
		ModelNode res = new ModelNode();
		for (Object item : repos) {
			org.apache.maven.model.Repository repo = (org.apache.maven.model.Repository)item;
			if ("p2".equals(repo.getLayout())) {
				String supposedBuildInfoURL = repo.getUrl();
				if (!supposedBuildInfoURL.endsWith("/")) {
					supposedBuildInfoURL += "/";
				}
				supposedBuildInfoURL += BUILDINFO_JSON;
				URL upstreamBuildInfoURL = null;
				InputStream in = null;
				ModelNode obj = null;
				try {
					getLog().debug("Read JSON (1) from: " + supposedBuildInfoURL);
					upstreamBuildInfoURL = new URL(supposedBuildInfoURL);
					in = upstreamBuildInfoURL.openStream();
					obj = ModelNode.fromJSONStream(in);
					writeUpstreamBuildinfoJSONFile(obj, buildinfoFolder);
				} catch (MalformedURLException ex) {
					throw new MojoFailureException("Incorrect URL: " + upstreamBuildInfoURL, ex);
				} catch (IOException ex) {
					supposedBuildInfoURL = repo.getUrl();
					if (fallbackToChild != null && !fallbackToChild.isEmpty()) {
						supposedBuildInfoURL = repo.getUrl();
						if (!supposedBuildInfoURL.endsWith("/")) {
							supposedBuildInfoURL += "/";
						}
						if (!supposedBuildInfoURL.endsWith(fallbackToChild)) {
							supposedBuildInfoURL += fallbackToChild;
						}
						if (!supposedBuildInfoURL.endsWith("/")) {
							supposedBuildInfoURL += "/";
						}
						supposedBuildInfoURL += BUILDINFO_JSON;
						try {
							getLog().debug("Read JSON (2) from: " + supposedBuildInfoURL);
							upstreamBuildInfoURL = new URL(supposedBuildInfoURL);
							in = upstreamBuildInfoURL.openStream();
							obj = ModelNode.fromJSONStream(in);
							writeUpstreamBuildinfoJSONFile(obj, buildinfoFolder);
						} catch (MalformedURLException ex2) {
							throw new MojoFailureException("Incorrect URL: " + upstreamBuildInfoURL, ex);
						} catch (IOException ex2) {
							getLog().warn("Could not access build info at " + upstreamBuildInfoURL + " or " + upstreamBuildInfoURL.toString().replaceAll(fallbackToChild,""));
							res.get(repo.getUrl()).set("Build info file not accessible: " + ex.getMessage());
						} finally {
							IOUtils.closeQuietly(in);
						}
					} else {
						getLog().warn("Could not access build info at " + upstreamBuildInfoURL + "; try setting <fallbackToChild>latest/all/repo</fallbackToChild> in your pom.xml");
						res.get(repo.getUrl()).set("Build info file not accessible: " + ex.getMessage());
					}
				} finally {
					IOUtils.closeQuietly(in);
				}
				if (obj != null) {
					obj.remove(UPSTREAM_ELEMENT); // remove upstream of upstream as it would make a HUGE file
					res.get(repo.getUrl()).set(obj);
				}
			}
			
		}
		return res;
	}

	// write upstream buildinfo.json files into target/buildinfo/ folder
	private void writeUpstreamBuildinfoJSONFile(ModelNode obj, File buildinfoFolder) throws MojoFailureException {
		String projectName;
		projectName = getProjectName(obj);
		if (projectName != null)
		{
			String upstreamJSONFile = buildinfoFolder.toString() + File.separator + "buildinfo_" + projectName + ".json";
			getLog().debug("Save to: " + upstreamJSONFile);
			try {
				buildinfoFolder.mkdirs();
				FileUtils.fileWrite(new File(upstreamJSONFile), obj.toJSONString(false));
			} catch (Exception ex) {
				throw new MojoFailureException("Could not save JSON to " + upstreamJSONFile, ex);
			}
		}
	}

	// for a given JSON object, find /revision/knownReferences[0]/url, then extract the project name from the git repo URL
	private String getProjectName(ModelNode obj) {
		String projectURL = null;
		String projectName = null;
		for (Property prop: obj.get("revision").asPropertyList()) {
			if (projectURL == null && prop.getName().equals("knownReferences") && prop.getValue().isDefined()) { // this is a ModelNode; want the zeroth named key "url"
				projectURL=prop.getValue().asList().get(0).get("url").asString();
				getLog().debug("Upstream repo: " + projectURL);
				// JBIDE-22808 support both git://github.com/jbosstools/jbosstools-portlet.git and https://github.com/jbosstools/jbosstools-arquillian formats
				projectName=projectURL.replaceAll(".+/([^/]+).git","$1").replaceAll(".+/([^/]+)","$1");
				getLog().debug("Upstream proj: " + projectName);
			}
		}
		return projectName;
	}

	private ModelNode createRevisionObject() throws IOException, FileNotFoundException {
		ModelNode res = new ModelNode();
		File repoRoot = findRepoRoot(this.project.getBasedir());
		FileRepositoryBuilder builder = new FileRepositoryBuilder();
		Repository gitRepo = builder.setGitDir(new File(repoRoot, ".git"))
		  .readEnvironment() // scan environment GIT_* variables
		  .findGitDir() // scan up the file system tree
		  .build();
		Ref head = gitRepo.exactRef(Constants.HEAD);
		res.get("HEAD").set(head.getObjectId().getName());
		if (head.getTarget() != null && head.getTarget().getName() != null) {
			res.get("currentBranch").set(head.getTarget().getName());
		}
		ModelNode knownReferences = new ModelNode();
		for (Ref entry : gitRepo.getRefDatabase().getRefs()) {
			if (entry.getName().startsWith(Constants.R_REMOTES) && entry.getObjectId().getName().equals(head.getObjectId().getName())) {
				ModelNode reference = new ModelNode();
				String remoteName = entry.getName().substring(Constants.R_REMOTES.length());
				remoteName = remoteName.substring(0, remoteName.indexOf('/'));
				String remoteUrl = gitRepo.getConfig().getString("remote", remoteName, "url");
				String branchName = entry.getName().substring(Constants.R_REMOTES.length() + 1 + remoteName.length());
				reference.get("name").set(remoteName);
				reference.get("url").set(remoteUrl);
				reference.get("ref").set(branchName);
				knownReferences.add(reference);
			}
		}
		res.get("knownReferences").set(knownReferences);
		return res;
	}

	protected static File findRepoRoot(File repoRoot) throws FileNotFoundException {
		while (repoRoot != null && ! new File(repoRoot, ".git").isDirectory()) {
			repoRoot = repoRoot.getParentFile();
		}
		if (repoRoot == null) {
			throw new FileNotFoundException("Could not find a Git repository (with a .git child folder) in " + repoRoot);
		}
		return repoRoot;
	}
}
