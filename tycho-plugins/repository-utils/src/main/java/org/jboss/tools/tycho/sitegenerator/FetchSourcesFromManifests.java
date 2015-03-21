/**
 * Copyright (c) 2014, Red Hat, Inc.
 * Distributed under license by Red Hat, Inc. All rights reserved.
 * This program is made available under the terms of the
 * Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributor:
 *     Nick Boldt (Red Hat, Inc.) - initial API and implementation
 ******************************************************************************/
package org.jboss.tools.tycho.sitegenerator;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import net.lingala.zip4j.core.ZipFile;
import net.lingala.zip4j.exception.ZipException;
import net.lingala.zip4j.model.ZipParameters;
import net.lingala.zip4j.util.Zip4jConstants;

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
 * jbosstools-base_Alpha2-v20140221-1555-B437_184e18cc3ac7c339ce406974b6a4917f73909cc4_sources.zip
 * 
 * digest file listing:
 * 
 * github project, plugin, version, SHA, origin/branch@SHA, remote zipfile, local zipfile
 * 
 * jbosstools-base, org.jboss.tools.usage, 1.2.100.Alpha2-v20140221-1555-B437, 184e18cc3ac7c339ce406974b6a4917f73909cc4,
 * origin/jbosstools-4.1.x@184e18cc3ac7c339ce406974b6a4917f73909cc4, https://github.com/jbosstools/jbosstools-base/archive/184e18cc3ac7c339ce406974b6a4917f73909cc4.zip,
 * jbosstools-base_Alpha2-v20140221-1555-B437_184e18cc3ac7c339ce406974b6a4917f73909cc4_sources.zip
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

	@Parameter(property = "fetch-sources-from-manifests.sourcesZip", defaultValue = "${project.build.directory}/fullSite/all/jbosstools-src.zip")
	private File sourcesZip;

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

		for (String projectName : this.sourceFetchMap.keySet()) {
			String pluginName = this.sourceFetchMap.get(projectName);
			// jbosstools-base = org.jboss.tools.common
			// getLog().info(projectName + " = " + pluginName);

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
					// getLog().info(ze.getName());
					if (ze.getName().equals("META-INF/" + MANIFEST)) {
						// getLog().info("Found " + ze.getName() + " in " +
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
				// getLog().info("Saved " + jarFile + "!/META-INF/" + MANIFEST);
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
			// getLog().info(ESR);
			if (ESR != null) {
				SHA = ESR.substring(ESR.lastIndexOf(";commitId=") + 10);
				// getLog().info(SHA);
			} else {
				SHA = "UNKNOWN";
			}
			// cleanup
			manifestFile.delete();

			// fetch github source archive for that SHA, eg., https://github.com/jbosstools/jbosstools-base/archive/184e18cc3ac7c339ce406974b6a4917f73909cc4.zip
			// to jbosstools-base_Alpha2-v20140221-1555-B437_184e18cc3ac7c339ce406974b6a4917f73909cc4_sources.zip
			String URL = "";
			String outputZipName = "";
			try {
				if (SHA == null || SHA.equals("UNKNOWN")) {
					getLog().warn("Cannot fetch " + projectName + " sources: no Eclipse-SourceReferences in " + removePrefix(jarFile.toString(), pluginPath) + " " + MANIFEST);
				} else {
					String shortQualifier = getQualifier(pluginName, jarFile.toString(), false);
					URL = "https://github.com/jbosstools/" + projectName + "/archive/" + SHA + ".zip";
					outputZipName = projectName + "_" + shortQualifier + "_" + SHA + "_sources.zip";
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
					File[] matchingSourceZips = listFilesMatching(zipsDirectory, projectName + "_.+\\.zip");
					for (int i = 0; i < matchingSourceZips.length; i++) {
						// don't delete the file we want, only all others matching projectName_.zip
						if (!outputZipFile.getName().equals(matchingSourceZips[i].getName())) {
							getLog().warn("Delete " + matchingSourceZips[i].getName());
							matchingSourceZips[i].delete();
						}
					}
					File[] matchingSourceMD5s = listFilesMatching(zipsDirectory, projectName + "_.+\\.zip\\.MD5");
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

					// generate MD5 file too, if necessary
					/*String md5 = null; // use NULL if we didn't download a file
					File outputMD5File = new File(outputZipFile.getAbsolutePath() + ".MD5");
					if (outputZipFile.exists()) {
						md5 = getMD5(outputZipFile); // if we did download a file, generate MD5
						if (!outputMD5File.exists()) { // don't write to file if we already have one
							FileWriter fw = new FileWriter(outputMD5File);
							fw.write(md5);
							fw.close();
						}
					}*/

					allBuildProperties.put(outputZipName + ".filename", outputZipName);
					allBuildProperties.put(outputZipName + ".filensize", Long.toString(outputZipName.length()));
//					allBuildProperties.put(outputZipName + ".filemd5", md5);
					zipFiles.add(new File(outputZipName));
				}
			} catch (Exception ex) {
				throw new MojoExecutionException("Error while downloading github source archive", ex);
			}

			// github project, plugin, version, SHA, origin/branch@SHA, remote zipfile, local zipfile
			String revisionLine = projectName + sep + pluginName + sep + getQualifier(pluginName, jarFile.toString(), true) + sep + SHA + sep + "origin/" + branch + "@" + SHA + sep + URL + sep + outputZipName + "\n";
			// getLog().info(revisionLine);
			sb.append(revisionLine);
		}
		
		// JBDS-3364 JBDS-3208 JBIDE-19467 when not using publish.sh, unpack downloaded source zips and combine them into a single zip
		createCombinedZipFile(zipFiles, CACHE_ZIPS);

		// getLog().info("Generating aggregate site metadata");
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
		// getLog().info("Written to " + digestFile.toString() + ":\n\n" + sb.toString());
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
		ZipFile combinedZipFile = null;
		ZipParameters parameters = new ZipParameters();
		parameters.setCompressionMethod(Zip4jConstants.COMP_DEFLATE);
		parameters.setCompressionLevel(Zip4jConstants.DEFLATE_LEVEL_FAST);
		parameters.setEncryptFiles(false);
		try {
			combinedZipFile = new ZipFile(combinedZipName);
		} catch (ZipException ex) {
			throw new MojoExecutionException ("Error creating " + combinedZipName, ex);
		}
		for (File outputFile : zipFiles) {
			String fullUnzipPath = "";
			try {
				String prettyFolderName = outputFile.getName().replaceAll("_sources.zip",""); // jbosstools-openshift_Alpha2-v20150318-1052-B740_89a7dae47ed944b437fb25071f8467a5768fa5b9
				fullUnzipPath = zipsDirectory.getAbsolutePath() + File.separator + prettyFolderName;
				String zipFileName = zipsDirectory.getAbsolutePath() + File.separator + outputFile.getName();
				getLog().debug("Unpacking: " + zipFileName);
				getLog().debug("Unpack to: " + fullUnzipPath);
				// unpack zip
				(new ZipFile(zipFileName)).extractAll(fullUnzipPath);
				// find subfolder name, eg., directories[0] = jbosstools-openshift-89a7dae47ed944b437fb25071f8467a5768fa5b9
				File outputZipFolder = new File(fullUnzipPath);
				String[] directories = outputZipFolder.list(new FilenameFilter() {
					@Override
					public boolean accept(File current, String name) { return new File(current, name).isDirectory(); } 
				}); 
				String prettyOutputZipFolder = fullUnzipPath + File.separator + prettyFolderName; // /full/path/to/fullSite/all/jbosstools-openshift_Alpha2-v20150318-1052-B740_89a7dae47ed944b437fb25071f8467a5768fa5b9/jbosstools-openshift_Alpha2-v20150318-1052-B740_89a7dae47ed944b437fb25071f8467a5768fa5b9
				// rename directories[0] to  prettyOutputZipFolder
				(new File(fullUnzipPath + File.separator + directories[0])).renameTo(new File(prettyOutputZipFolder));
				// add source contents to combined zip
				getLog().debug("Pack from: " + prettyOutputZipFolder);
				combinedZipFile.addFolder(prettyOutputZipFolder, parameters);
				double filesize = combinedZipFile.getFile().length();
				getLog().debug("Packed to: " + combinedZipFile.getFile().getAbsolutePath());
				// this is a long running operation so give some feedback in the log
				getLog().info(sourcesZip + ": " + (filesize >= 1024 * 1024 ? String.format("%.1f", filesize / 1024 / 1024) + " M" : String.format("%.1f", filesize / 1024) + " k"));
				// delete temp folder
				getLog().debug("Delete dir: " + fullUnzipPath);
				FileUtils.deleteDirectory(new File(fullUnzipPath));
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
				throw new MojoExecutionException ("IO Exception:", ex);
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
		// getLog().info("qualifier[0] = " + qualifier);
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

	// sourced from
	// https://github.com/maven-download-plugin/maven-download-plugin/blob/master/download-maven-plugin/src/main/java/com/googlecode/WGet.java
	private void doGet(String url, File outputFile) throws Exception {
		doGet(url,outputFile,false);
	}

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

	private static String getMD5(File outputZipFile) throws NoSuchAlgorithmException, FileNotFoundException, IOException {
		MessageDigest digest = MessageDigest.getInstance("MD5");
		InputStream zipIs = new FileInputStream(outputZipFile);
		byte[] buffer = new byte[8192];
		int read = 0;
		while ((read = zipIs.read(buffer)) > 0) {
			digest.update(buffer, 0, read);
		}
		byte[] md5sum = digest.digest();
		BigInteger bigInt = new BigInteger(1, md5sum);
		String computedMD5 = bigInt.toString(16);
		zipIs.close();
		return computedMD5;
	}

}
