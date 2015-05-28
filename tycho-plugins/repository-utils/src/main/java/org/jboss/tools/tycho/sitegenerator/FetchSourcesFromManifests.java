/**
 * Copyright (c) 2014-2015, Red Hat, Inc.
 * Distributed under license by Red Hat, Inc. All rights reserved.
 * This program is made available under the terms of the
 * Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributor:
 *	 Nick Boldt (Red Hat, Inc.) - initial API and implementation
 ******************************************************************************/
package org.jboss.tools.tycho.sitegenerator;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.apache.maven.artifact.manager.WagonManager;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.wagon.Wagon;
import org.apache.maven.wagon.repository.Repository;
import org.codehaus.plexus.util.FileUtils;

/**
 * This class performs the following:
 * 
 * a) for a list of projects and a single plugin in current repo
 * 
 * b) retrieve the MANIFEST.MF file:
 * 
 * org.jboss.tools.usage_1.2.100.Alpha2-v20140221-1555-B437.jar!/META-INF/
 * MANIFEST.MF
 * 
 * c) parse out the commitId from Eclipse-SourceReferences:
 * 
 * Eclipse-SourceReferences: scm:git:https://github.com/jbosstools/jbosst
 * ools-base.git;path="usage/plugins/org.jboss.tools.usage";commitId=184
 * e18cc3ac7c339ce406974b6a4917f73909cc4
 * 
 * d) turn those into SHAs, eg., 184e18cc3ac7c339ce406974b6a4917f73909cc4
 * 
 * e) fetch source zips for those SHAs, eg.,
 * https://github.com/jbosstools/jbosstools-base/archive/184e18cc3ac7c339ce406974b6a4917f73909cc4.zip and save as
 * jbosstools-base_184e18cc3ac7c339ce406974b6a4917f73909cc4_sources.zip
 * 
 * digest file listing:
 * 
 * github project, plugin, version, SHA, origin/branch@SHA, remote zipfile, local zipfile
 * 
 * jbosstools-base, org.jboss.tools.usage, 1.2.100.Alpha2-v20140221-1555-B437, 184e18cc3ac7c339ce406974b6a4917f73909cc4,
 * origin/jbosstools-4.1.x@184e18cc3ac7c339ce406974b6a4917f73909cc4, https://github.com/jbosstools/jbosstools-base/archive/184e18cc3ac7c339ce406974b6a4917f73909cc4.zip,
 * jbosstools-base_184e18cc3ac7c339ce406974b6a4917f73909cc4_sources.zip
 * 
 * f) unpack each source zip and combine them into a single zip
 */
@Mojo(name = "fetch-sources-from-manifests", defaultPhase = LifecyclePhase.PACKAGE)
public class FetchSourcesFromManifests extends AbstractMojo {

	// Two modes of operation when merging zips: either store them in cache folder, or just delete them
	private static final int CACHE_ZIPS = 1;
	private static final int PURGE_ZIPS = 2;

	@Parameter(property = "project", required = true, readonly = true)
	private MavenProject project;

	/**
	 * Map of projects to plugins, so we know where to get the SHA (git
	 * revision)
	 * 
	 * sourceFetchMap>
	 * jbosstools-aerogear>org.jboss.tools.aerogear.hybrid.core</jbosstools-aerogear>
	 * jbosstools-arquillian>org.jboss.tools.arquillian.core</jbosstools-arquillian>
	 * jbosstools-base>org.jboss.tools.common</jbosstools-base>
	 * jbosstools-birt>org.jboss.tools.birt.core</jbosstools-birt>
	 * jbosstools-central>org.jboss.tools.central</jbosstools-central>
	 * jbosstools-forge>org.jboss.tools.forge.core</jbosstools-forge>
	 * jbosstools-freemarker>org.jboss.ide.eclipse.freemarker</jbosstools-freemarker>
	 * jbosstools-gwt>org.jboss.tools.gwt.core</jbosstools-gwt>
	 * jbosstools-hibernate>org.hibernate.eclipse</jbosstools-hibernate>
	 * jbosstools-javaee>org.jboss.tools.jsf</jbosstools-javaee>
	 * jbosstools-jst>org.jboss.tools.jst.web</jbosstools-jst>
	 * jbosstools-livereload>org.jboss.tools.livereload.core</jbosstools-livereload>
	 * jbosstools-openshift>org.jboss.tools.openshift.egit.core</jbosstools-openshift>
	 * jbosstools-portlet>org.jboss.tools.portlet.core</jbosstools-portlet>
	 * jbosstools-server>org.jboss.ide.eclipse.as.core</jbosstools-server>
	 * jbosstools-vpe>org.jboss.tools.vpe</jbosstools-vpe>
	 * jbosstools-webservices>org.jboss.tools.ws.core</jbosstools-webservices>
	 * /sourceFetchMap>
	 */
	@Parameter
	private Map<String, String> sourceFetchMap;

	/**
	 * Alternative location to look for zips. Here is the order to process zip
	 * research
	 * 
	 * 1. Look for zip in zipCacheFolder
	 * 2. Look for zip in outputFolder
	 * 3. Look for zip at expected URL
	 */
	@Parameter(property = "fetch-sources-from-manifests.zipCacheFolder", defaultValue = "${basedir}/cache")
	private File zipCacheFolder;

	/**
	 * Location where to put zips
	 * 
	 * @parameter default-value="${basedir}/zips" property="fetch-sources-from-manifests.outputFolder"
	 */
	@Parameter(property = "fetch-sources-from-manifests.outputFolder", defaultValue = "${basedir}/zips")
	private File outputFolder;

	// the zip file to be created; default is "jbosstools-src.zip" but can override here
	@Parameter(property = "fetch-sources-from-manifests.sourcesZip", defaultValue = "${project.build.directory}/fullSite/all/jbosstools-src.zip")
	private File sourcesZip;

	// the folder at the root of the zip; default is "jbosstools-src.zip!sources/" but can override here
	@Parameter(property = "fetch-sources-from-manifests.sourcesZipRootFolder", defaultValue="sources")
	private String sourcesZipRootFolder;

	@Parameter(property = "fetch-sources-from-manifests.columnSeparator", defaultValue = ",")
	private String columnSeparator;

	@Parameter(property = "fetch-sources-from-manifests.skip", defaultValue = "false")
	private boolean skip;

	/**
	 * @component
	 */
	@Component
	private WagonManager wagonManager;

	private String MANIFEST = "MANIFEST.MF";

	public void execute() throws MojoExecutionException, MojoFailureException {
		if (this.zipCacheFolder == null) {
			this.zipCacheFolder = new File(project.getBasedir() + File.separator + "cache" + File.separator);
		}
		if (this.zipCacheFolder != null && !this.zipCacheFolder.isDirectory()) {
			try {
				if (!this.zipCacheFolder.exists()) {
					this.zipCacheFolder.mkdirs();
				}
			} catch (Exception ex) {
				throw new MojoExecutionException("'zipCacheFolder' must be a directory", ex);
			}
		}
		if (this.outputFolder == null) {
			this.outputFolder = new File(project.getBasedir() + File.separator + "zips" + File.separator);
		}
		if (this.outputFolder.equals(this.zipCacheFolder)) {
			throw new MojoExecutionException("zipCacheFolder and outputFolder can not be the same folder");
		}

		File zipsDirectory = new File(this.outputFolder, "all");
		if (!zipsDirectory.exists()) {
			zipsDirectory.mkdirs();
		}
		Set<File> zipFiles = new HashSet<File>();
		Properties allBuildProperties = new Properties();

		File digestFile = new File(this.outputFolder, "ALL_REVISIONS.txt");
		FileWriter dfw;
		StringBuffer sb = new StringBuffer();
		String branch = project.getProperties().getProperty("mvngit.branch");
		sb.append("-=> " + project.getGroupId() + ":" + project.getArtifactId() + ":" + project.getVersion() + columnSeparator + branch + " <=-\n");

		String pluginPath = project.getBasedir() + File.separator + "target" + File.separator + "repository" + File.separator + "plugins";
		String sep = " " + columnSeparator + " ";

		if (sourceFetchMap == null) {
			getLog().warn("No <sourceFetchMap> defined in pom. Can't fetch sources without a list of plugins. Did you forget to enable fetch-source-zips profile?");
		} else {
			for (String projectName : this.sourceFetchMap.keySet()) {
				String pluginName = this.sourceFetchMap.get(projectName);
				// jbosstools-base = org.jboss.tools.common
				// getLog().debug(projectName + " = " + pluginName);
	
				// find the first matching plugin jar, eg., target/repository/plugins/org.jboss.tools.common_3.6.0.Alpha2-v20140304-0055-B440.jar
				File[] matchingFiles = listFilesMatching(new File(pluginPath), pluginName + "_.+\\.jar");
				// for (File file : matchingFiles) getLog().debug(file.toString());
				if (matchingFiles.length < 1) {
					throw new MojoExecutionException("No matching plugin found in " + pluginPath + " for " + pluginName + "_.+\\.jar.\nCheck your pom.xml for this line: <" + projectName + ">" + pluginName + "</" + projectName + ">");
				}
				File jarFile = matchingFiles[0];
				File manifestFile = null;
	
				try {
					FileInputStream fin = new FileInputStream(jarFile);
					manifestFile = File.createTempFile(MANIFEST, "");
					OutputStream out = new FileOutputStream(manifestFile);
					BufferedInputStream bin = new BufferedInputStream(fin);
					ZipInputStream zin = new ZipInputStream(bin);
					ZipEntry ze = null;
					while ((ze = zin.getNextEntry()) != null) {
						// getLog().debug(ze.getName());
						if (ze.getName().equals("META-INF/" + MANIFEST)) {
							// getLog().debug("Found " + ze.getName() + " in " +
							// jarFile);
							byte[] buffer = new byte[8192];
							int len;
							while ((len = zin.read(buffer)) != -1) {
								out.write(buffer, 0, len);
							}
							out.close();
							break;
						}
					}
					zin.close();
					// getLog().debug("Saved " + jarFile + "!/META-INF/" + MANIFEST);
				} catch (Exception ex) {
					throw new MojoExecutionException("Error extracting " + MANIFEST + " from " + jarFile, ex);
				}
	
				// retrieve the MANIFEST.MF file, eg., org.jboss.tools.usage_1.2.100.Alpha2-v20140221-1555-B437.jar!/META-INF/MANIFEST.MF
				Manifest manifest;
				try {
					manifest = new Manifest(new FileInputStream(manifestFile));
				} catch (Exception ex) {
					throw new MojoExecutionException("Error while reading manifest file " + MANIFEST, ex);
				}
	
				// parse out the commitId from Eclipse-SourceReferences:
				// scm:git:https://github.com/jbosstools/jbosstools-base.git;path="usage/plugins/org.jboss.tools.usage";commitId=184e18cc3ac7c339ce406974b6a4917f73909cc4
				Attributes attr = manifest.getMainAttributes();
				String ESR = null;
				String SHA = null;
				ESR = attr.getValue("Eclipse-SourceReferences");
				// getLog().debug(ESR);
				if (ESR != null) {
					SHA = ESR.substring(ESR.lastIndexOf(";commitId=") + 10);
					// getLog().debug(SHA);
				} else {
					SHA = "UNKNOWN";
				}
				// cleanup
				manifestFile.delete();
	
				// fetch github source archive for that SHA, eg., https://github.com/jbosstools/jbosstools-base/archive/184e18cc3ac7c339ce406974b6a4917f73909cc4.zip
				// to jbosstools-base_184e18cc3ac7c339ce406974b6a4917f73909cc4_sources.zip
				String URL = "";
				String outputZipName = "";
				try {
					if (SHA == null || SHA.equals("UNKNOWN")) {
						getLog().warn("Cannot fetch " + projectName + " sources: no Eclipse-SourceReferences in " + removePrefix(jarFile.toString(), pluginPath) + " " + MANIFEST);
					} else {
						URL = "https://github.com/jbosstools/" + projectName + "/archive/" + SHA + ".zip";
						outputZipName = projectName + "_" + SHA + "_sources.zip";
						File outputZipFile = new File(zipsDirectory, outputZipName);
	
						boolean diduseCache = false;
						if (this.zipCacheFolder != null && this.zipCacheFolder.exists()) {
							File cachedZip = new File(this.zipCacheFolder, outputZipName);
							if (cachedZip.exists()) {
								FileUtils.copyFile(cachedZip, outputZipFile);
								getLog().debug("Copied " + removePrefix(outputZipFile.getAbsolutePath(), project.getBasedir().toString()));
								getLog().debug("  From " + removePrefix(cachedZip.getAbsolutePath(), project.getBasedir().toString()));
								diduseCache = true;
							}
						}
						// scrub out old versions that we don't want in the cache anymore
						File[] matchingSourceZips = listFilesMatching(this.zipCacheFolder, projectName + "_.+\\.zip");
						for (int i = 0; i < matchingSourceZips.length; i++) {
							// don't delete the file we want, only all others matching projectName_.zip
							if (!outputZipFile.getName().equals(matchingSourceZips[i].getName())) {
								getLog().warn("Delete " + matchingSourceZips[i].getName());
								matchingSourceZips[i].delete();
							}
						}
						File[] matchingSourceMD5s = listFilesMatching(this.zipCacheFolder, projectName + "_.+\\.zip\\.MD5");
						for (int i = 0; i < matchingSourceMD5s.length; i++) {
							// don't delete the file we want, only all others matching projectName_.zip or .MD5
							if (!(outputZipFile.getName() + ".MD5").equals(matchingSourceMD5s[i].getName())) {
								getLog().warn("Delete " + matchingSourceMD5s[i].getName());
								matchingSourceMD5s[i].delete();
							}
						}
						String outputZipFolder = outputZipFile.toString().replaceAll("_sources.zip","");
						if (!diduseCache && (!outputZipFile.exists() || !(new File(outputZipFolder).exists()))) {
							doGet(URL, outputZipFile, true);
						}
	
						allBuildProperties.put(outputZipName + ".filename", outputZipName);
						allBuildProperties.put(outputZipName + ".filesize", Long.toString(outputZipName.length()));
						zipFiles.add(new File(outputZipName));
					}
				} catch (Exception ex) {
					throw new MojoExecutionException("Error while downloading github source archive", ex);
				}
	
				// github project, plugin, version, SHA, origin/branch@SHA, remote zipfile, local zipfile
				String revisionLine = projectName + sep + pluginName + sep + getQualifier(pluginName, jarFile.toString(), true) + sep + SHA + sep + "origin/" + branch + "@" + SHA + sep + URL + sep + outputZipName + "\n";
				// getLog().debug(revisionLine);
				sb.append(revisionLine);
			}
		}
		// JBDS-3364 JBDS-3208 JBIDE-19467 when not using publish.sh, unpack downloaded source zips and combine them into a single zip
		createCombinedZipFile(zipFiles, CACHE_ZIPS);

		// getLog().debug("Generating aggregate site metadata");
		try {
			{
				File buildPropertiesAllXml = new File(this.outputFolder, "build.properties.all.xml");
				if (!buildPropertiesAllXml.exists()) {
					buildPropertiesAllXml.createNewFile();
				}
				FileOutputStream xmlOut = new FileOutputStream(buildPropertiesAllXml);
				allBuildProperties.storeToXML(xmlOut, null);
				xmlOut.close();
			}

			{
				File buildPropertiesFileTxt = new File(this.outputFolder, "build.properties.file.txt");
				if (!buildPropertiesFileTxt.exists()) {
					buildPropertiesFileTxt.createNewFile();
				}
				FileOutputStream textOut = new FileOutputStream(buildPropertiesFileTxt);
				allBuildProperties.store(textOut, null);
				textOut.close();
			}
		} catch (Exception ex) {
			throw new MojoExecutionException("Error while creating 'metadata' files", ex);
		}

		try {
			dfw = new FileWriter(digestFile);
			dfw.write(sb.toString());
			dfw.close();
		} catch (Exception ex) {
			throw new MojoExecutionException("Error writing to " + digestFile.toString(), ex);
		}
		// getLog().debug("Written to " + digestFile.toString() + ":\n\n" + sb.toString());
	}

	/*
	 * Given a set of zip files, unpack them and merge them into a single combined source zip
	 * If mode == PURGE_ZIPS, delete zips to save disk space, keeping only the combined zip
	 * If mode == CACHE_ZIPS, move zips into cache folder
	 * 
	 */
	private void createCombinedZipFile(Set<File> zipFiles, int mode)
			throws MojoExecutionException {
		File zipsDirectory = new File(this.outputFolder, "all");
		String combinedZipName = sourcesZip.getAbsolutePath();
		File combinedZipFile = new File(combinedZipName);
		String fullUnzipPath = zipsDirectory.getAbsolutePath() + File.separator + sourcesZipRootFolder;
		File fullUnzipDir = new File(fullUnzipPath);
		fullUnzipDir.mkdirs();

		// unpack the zips into a temp folder
		for (File outputFile : zipFiles) {
			try {
				String zipFileName = zipsDirectory.getAbsolutePath() + File.separator + outputFile.getName();
				getLog().debug("Unpacking: " + zipFileName);
				getLog().debug("Unpack to: " + fullUnzipPath);
				// unpack zip
				unzipToDirectory(zipFileName,fullUnzipPath);
				File zipFile = new File(zipFileName);
				if (mode == PURGE_ZIPS) {
					// delete downloaded zip
					getLog().debug("Delete zip: " + zipFileName);
					zipFile.delete();
				}
				else if (mode == CACHE_ZIPS)
				{
					// move downloaded zip into cache folder
					getLog().debug("Cache " + zipFileName + " in " + this.zipCacheFolder);
					zipFile.renameTo(new File(this.zipCacheFolder,zipFile.getName()));
				}
			} catch (ZipException ex) {
				throw new MojoExecutionException ("Error unpacking " + outputFile.toString() + " to " + fullUnzipPath, ex);
			} catch (IOException ex) {
				throw new MojoExecutionException ("Error unpacking " + outputFile.toString() + " to " + fullUnzipPath, ex);
			}
		}

		// TODO: extract whole process of getting local sources into a zip into a new mojo that can be called by ALL aggregates, not just jbosstools site (reusable for JBDS and other projects too)
		// JBIDE-19814 - include local sources in jbosstools-project-SHA folder (jbosstools-build-sites or jbdevstudio-product)
		// get project name & SHA (from .git metadata) - needed for sourcesZipRootFolder/projectName-SHA folder
		Properties properties = new Properties();
		String projectURL = null;
		String projectName = null;
		String projectSHA = null;
		  try {
			  properties.load(new FileInputStream(this.project.getBuild().getDirectory() + File.separator + "git.properties")); // as defined by build-sites/aggregate/site/pom.xml
			  getLog().debug("git.remote.origin.url = " + properties.get("git.remote.origin.url").toString()); // could be git@github.com:jbosstools/jbosstools... or git://github.com/jbosstools/jbosstools...
			  projectURL = properties.get("git.remote.origin.url").toString();
			  projectName = projectURL.replaceAll(".+/([^/]+).git","$1");
			  getLog().debug("git.commit.id = " + properties.get("git.commit.id").toString()); //5bfba37d042200ae71089678b6a441b57dd00d1f
			  projectSHA = properties.get("git.commit.id").toString();
		} catch (IOException ex) {
			throw new MojoExecutionException ("Error loading " + this.project.getBuild().getDirectory() + File.separator + "git.properties", ex);
		}

		String localCleanSourcesDir = null;
		if (projectName != null) {
			if (projectSHA != null) {
				localCleanSourcesDir = projectName + "-" + projectSHA;
			} else {
				throw new MojoExecutionException ("Could not compute projectSHA!");
			}
		} else {
			throw new MojoExecutionException ("Could not compute projectName or projectSHA!");
		}
		getLog().debug("localCleanSourcesDir = " + localCleanSourcesDir);

		File repoRoot = null;
		try {
			repoRoot = GenerateRepositoryFacadeMojo.findRepoRoot(this.project.getBasedir());
		} catch (FileNotFoundException ex) {
			throw new MojoExecutionException ("Repo root not found in " + this.project.getBasedir(), ex);
		}
		getLog().debug("repoRoot = " + repoRoot);

		// clone local sources into combinedZipFile (dirty files revert to their clean state)
		File gitSourcesArchive = new File("/tmp/" + localCleanSourcesDir + ".zip"); // /tmp/jbosstools-build-sites-3df6b66f70691868e7cc4f1da70f1a0efb952dfc.zip
		getLog().debug("cd " + repoRoot + "; git archive --prefix " + localCleanSourcesDir + " -o " + gitSourcesArchive + " HEAD");
		String command = "git archive --prefix " + localCleanSourcesDir + "/ -o " + gitSourcesArchive + " HEAD";
		try {
			// Note: this can be run from any subfolder in the project tree, but if we run from the root we get everything (not just site/ but jbosstools-build-sites/aggregate/site/)
			// from http://www.javaworld.com/article/2071275/core-java/when-runtime-exec---won-t.html?page=2
			Runtime rt = Runtime.getRuntime();
			Process proc = rt.exec(command, null, repoRoot);
			StreamGobbler errorGobbler = new StreamGobbler(proc.getErrorStream(), "ERROR");
			StreamGobbler outputGobbler = new StreamGobbler(proc.getInputStream(), "OUTPUT");
			errorGobbler.start();
			outputGobbler.start();
			int exitVal = proc.waitFor();
			getLog().debug("Runtime.getRuntime.exec() - exit value: " + exitVal);
			getLog().debug("Packed to: " + gitSourcesArchive);
			double filesize = gitSourcesArchive.length();
			getLog().debug("Pack size: " + (filesize >= 1024 * 1024 ? String.format("%.1f", filesize / 1024 / 1024) + " M" : String.format("%.1f", filesize / 1024) + " k"));
			unzipToDirectory(gitSourcesArchive, fullUnzipPath);
		} catch (IOException ex) {
			throw new MojoExecutionException ("Error cloning from " + repoRoot.toString() + " to " + gitSourcesArchive, ex);
		} catch (InterruptedException ex) {
			throw new MojoExecutionException ("Error cloning from " + repoRoot.toString() + " to " + gitSourcesArchive, ex);
		}

		// JBIDE-19798 - copy target/buildinfo/buildinfo*.json into sourcesZipRootFolder/buildinfo/
		File buildinfoFolder = new File(this.project.getBuild().getDirectory(), "buildinfo");
		if (buildinfoFolder.isDirectory()) {
			try {
				File buildinfoFolderCopy = new File(fullUnzipPath,"buildinfo");
				FileUtils.copyDirectory(buildinfoFolder, buildinfoFolderCopy);
				getLog().debug("Pack from: " + buildinfoFolderCopy);
				
				// remove dupe buildinfo/buildinfo_jbosstools-build-sites.json if projectName = jbosstools-build-sites (should not have an upstream that's the same as the local project)
				File dupeUpstreamBuildinfoFile = new File (buildinfoFolderCopy + File.separator + "buildinfo_" + projectName  + ".json");
				if (dupeUpstreamBuildinfoFile.isFile())
				{
					dupeUpstreamBuildinfoFile.delete();
				}
			} catch (IOException e) {
				throw new MojoExecutionException ("Error copying buildinfo files to " + fullUnzipPath + File.separator + "buildinfo", e);
			}
		} else {
			getLog().warn("No buildinfo files found in " + buildinfoFolder.toString());
		}

		// add the unzipped upstream sources & buildinfo into the existing local sources zip
		try {
			getLog().debug("Pack from: " + fullUnzipPath);
			zipDirectory(fullUnzipDir.getParentFile(), combinedZipFile);
			getLog().debug("Packed to: " + combinedZipFile.getAbsolutePath());
			double filesize = combinedZipFile.length();
			getLog().debug("Pack size: " + (filesize >= 1024 * 1024 ? String.format("%.1f", filesize / 1024 / 1024) + " M" : String.format("%.1f", filesize / 1024) + " k"));
		} catch (IOException e) {
			throw new MojoExecutionException ("Error packing " + combinedZipFile, e);
		}

		// delete temp folder with upstream sources; delete temp zip with git archive sources
		try {
			getLog().debug("Delete dir: " + fullUnzipPath);
			FileUtils.deleteDirectory(new File(fullUnzipPath));
			gitSourcesArchive.delete();
		} catch (IOException ex) {
			throw new MojoExecutionException ("IO Exception:", ex);
		}

	}
	
	// from http://stackoverflow.com/questions/981578/how-to-unzip-files-recursively-in-java
	static public void unzipToDirectory(String zipFile, String newPath) throws ZipException, IOException 
	{
		unzipToDirectory(new File(zipFile), newPath);
	}
	static public void unzipToDirectory(File file, String newPath) throws ZipException, IOException 
		{
		int BUFFER = 2048;
		ZipFile zip = new ZipFile(file);
		(new File(newPath)).mkdirs();
		Enumeration<? extends ZipEntry> zipFileEntries = zip.entries();
		// Process each entry
		while (zipFileEntries.hasMoreElements())
		{
			// grab a zip file entry
			ZipEntry entry = (ZipEntry) zipFileEntries.nextElement();
			String currentEntry = entry.getName();
			File destFile = new File(newPath, currentEntry);
			File destinationParent = destFile.getParentFile();
			// create the parent directory structure if needed
			destinationParent.mkdirs();
			if (!entry.isDirectory())
			{
				BufferedInputStream is = new BufferedInputStream(zip.getInputStream(entry));
				int currentByte;
				// establish buffer for writing file
				byte data[] = new byte[BUFFER];

				// write the current file to disk
				FileOutputStream fos = new FileOutputStream(destFile);
				BufferedOutputStream dest = new BufferedOutputStream(fos, BUFFER);

				// read and write until last byte is encountered
				while ((currentByte = is.read(data, 0, BUFFER)) != -1) {
					dest.write(data, 0, currentByte);
				}
				dest.flush();
				dest.close();
				is.close();
			}
		}
		zip.close();
	}

	// from http://stackoverflow.com/questions/2403830/recursively-zip-a-directory-containing-any-number-of-files-and-subdirectories-in?rq=1
	public static void zipDirectory(File dir, File zipFile) throws IOException {
		FileOutputStream fout = new FileOutputStream(zipFile);
		ZipOutputStream zout = new ZipOutputStream(fout);
		zipSubDirectory("", dir, zout);
		zout.flush();
		zout.close();
		fout.flush();
		fout.close();
	}

	// from http://stackoverflow.com/questions/2403830/recursively-zip-a-directory-containing-any-number-of-files-and-subdirectories-in?rq=1
	private static void zipSubDirectory(String basePath, File dir, ZipOutputStream zout) throws IOException {
		byte[] buffer = new byte[4096];
		File[] files = dir.listFiles();
		for (File file : files) {
			if (file.isDirectory()) {
				String path = basePath + file.getName() + "/";
				zout.putNextEntry(new ZipEntry(path));
				zipSubDirectory(path, file, zout);
				zout.closeEntry();
			} else {
				FileInputStream fin = new FileInputStream(file);
				zout.putNextEntry(new ZipEntry(basePath + file.getName()));
				int length;
				while ((length = fin.read(buffer)) > 0) {
					zout.write(buffer, 0, length);
				}
				zout.closeEntry();
				fin.close();
			}
		}
	}

	private String removePrefix(String stringToTrim, String prefix) {
		return stringToTrim.substring(stringToTrim.lastIndexOf(prefix) + prefix.length() + 1);
	}

	// given: pluginName = org.jboss.tools.common
	// given: jarFileName =
	// target/repository/plugins/org.jboss.tools.common_3.6.0.Alpha2-v20140304-0055-B440.jar
	// return 3.6.0.Alpha2-v20140304-0055-B440 (if full = true)
	// return Alpha2-v20140304-0055-B440 (if full = false)
	private String getQualifier(String pluginName, String jarFileName, boolean full) {
		// trim .../pluginName prefix
		String qualifier = removePrefix(jarFileName, pluginName);
		// trim .jar suffix
		qualifier = qualifier.substring(0, qualifier.length() - 4);
		// getLog().debug("qualifier[0] = " + qualifier);
		return full ? qualifier : qualifier.replaceAll("^(\\d+\\.\\d+\\.\\d+\\.)", "");
	}

	// thanks to
	// http://stackoverflow.com/questions/2928680/regex-for-files-in-a-directory
	public static File[] listFilesMatching(File root, String regex) throws MojoExecutionException {
		if (!root.isDirectory()) {
			throw new MojoExecutionException(root + " is not a directory.");
		}
		final Pattern p = Pattern.compile(regex);
		return root.listFiles(new FileFilter() {
			public boolean accept(File file) {
				return p.matcher(file.getName()).matches();
			}
		});
	}

	// sourced from https://github.com/maven-download-plugin/maven-download-plugin/blob/master/download-maven-plugin/src/main/java/com/googlecode/WGet.java
	private void doGet(String url, File outputFile, boolean unpack) throws Exception {
		String[] segments = url.split("/");
		String file = segments[segments.length - 1];
		String repoUrl = url.substring(0, url.length() - file.length() - 1);
		Repository repository = new Repository(repoUrl, repoUrl);
		
		Wagon wagon = this.wagonManager.getWagon(repository.getProtocol());

		// TODO: this should be retrieved from wagonManager
		// com.googlecode.ConsoleDownloadMonitor downloadMonitor = new com.googlecode.ConsoleDownloadMonitor();
		// wagon.addTransferListener(downloadMonitor);
		wagon.connect(repository, this.wagonManager.getProxy(repository.getProtocol()));
		wagon.get(file, outputFile);
		wagon.disconnect();
		// wagon.removeTransferListener(downloadMonitor);
		double filesize = outputFile.length();
		getLog().info("Downloaded:  " + outputFile.getName() + " (" + (filesize >= 1024 * 1024 ? String.format("%.1f", filesize / 1024 / 1024) + " M)" : String.format("%.1f", filesize / 1024) + " k)"));
	}

}

// from http://www.javaworld.com/article/2071275/core-java/when-runtime-exec---won-t.html?page=2
class StreamGobbler extends Thread
{
    InputStream is;
    String type;
    
    StreamGobbler(InputStream is, String type)
    {
        this.is = is;
        this.type = type;
    }
    
    public void run()
    {
        try
        {
            InputStreamReader isr = new InputStreamReader(is);
            BufferedReader br = new BufferedReader(isr);
            String line=null;
            while ( (line = br.readLine()) != null)
                System.out.println(type + ">" + line);    
            } catch (IOException ioe)
              {
                ioe.printStackTrace();  
              }
    }
}