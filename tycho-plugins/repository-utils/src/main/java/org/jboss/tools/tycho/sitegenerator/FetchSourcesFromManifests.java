/**
 * Copyright (c) 2013, Red Hat, Inc.
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

import org.apache.maven.artifact.manager.WagonManager;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;
import org.apache.maven.wagon.Wagon;
import org.apache.maven.wagon.repository.Repository;
import org.codehaus.plexus.util.FileUtils;

/**
 * This class performs the following:
 * 
 * a) for a list of plugins (not source plugins!) in the current repo & a list
 * of matching projects b) retrieve the MANIFEST.MF files, eg.,
 * org.jboss.tools.usage_1
 * .2.100.Alpha2-v20140221-1555-B437.jar!/META-INF/MANIFEST.MF c) parse out the
 * commitId from Eclipse-SourceReferences, eg.:
 * 
 * Eclipse-SourceReferences: scm:git:https://github.com/jbosstools/jbosst
 * ools-base.git;path="usage/plugins/org.jboss.tools.usage";commitId=184
 * e18cc3ac7c339ce406974b6a4917f73909cc4
 * 
 * d) turn those into SHAs, eg., 184e18cc3ac7c339ce406974b6a4917f73909cc4 e)
 * fetch source zips for those SHAs, eg.,
 * https://github.com/jbosstools/jbosstools
 * -base/archive/184e18cc3ac7c339ce406974b6a4917f73909cc4.zip
 * 
 * Note: zip contains a single subfolder:
 * !/184e18cc3ac7c339ce406974b6a4917f73909cc4/ f) rename them to be meaningful:
 * jbosstools-base_1.2.100.Alpha2-v20140221-1555-
 * B437_184e18cc3ac7c339ce406974b6a4917f73909cc4_sources.zip (?) g) produce a
 * digest file listing:
 * 
 * github project :: plugin :: version :: SHA :: zipfile jbosstools-base ::
 * org.jboss.tools.usage :: 1.2.100.Alpha2-v20140221-1555-B437 ::
 * 184e18cc3ac7c339ce406974b6a4917f73909cc4 ::
 * https://github.com/jbosstools/jbosstools
 * -base/archive/184e18cc3ac7c339ce406974b6a4917f73909cc4.zip ...
 * 
 * @goal fetch-sources-from-manifests
 * 
 * @phase package
 */
public class FetchSourcesFromManifests extends AbstractMojo {

	/**
	 * Map of projects to plugins, so we know where to get the SHA (git
	 * revision)
	 * 
	 * sourceFetchMap>
	 * jbosstools-aerogear>org.jboss.tools.aerogear.hybrid.core</
	 * jbosstools-aerogear>
	 * jbosstools-arquillian>org.jboss.tools.arquillian.core
	 * </jbosstools-arquillian>
	 * jbosstools-base>org.jboss.tools.common</jbosstools-base>
	 * jbosstools-birt>org.jboss.tools.birt.core</jbosstools-birt>
	 * jbosstools-central>org.jboss.tools.central</jbosstools-central>
	 * jbosstools-forge>org.jboss.tools.forge.core</jbosstools-forge>
	 * jbosstools-
	 * freemarker>org.jboss.ide.eclipse.freemarker</jbosstools-freemarker>
	 * jbosstools-gwt>org.jboss.tools.gwt.core</jbosstools-gwt>
	 * jbosstools-hibernate>org.hibernate.eclipse</jbosstools-hibernate>
	 * jbosstools-javaee>org.jboss.tools.jsf</jbosstools-javaee>
	 * jbosstools-jst>org.jboss.tools.jst.web</jbosstools-jst>
	 * jbosstools-livereload
	 * >org.jboss.tools.livereload.core</jbosstools-livereload>
	 * jbosstools-openshift
	 * >org.jboss.tools.openshift.egit.core</jbosstools-openshift>
	 * jbosstools-portlet>org.jboss.tools.portlet.core</jbosstools-portlet>
	 * jbosstools-server>org.jboss.ide.eclipse.as.core</jbosstools-server>
	 * jbosstools-vpe>org.jboss.tools.vpe</jbosstools-vpe>
	 * jbosstools-webservices>org.jboss.tools.ws.core</jbosstools-webservices>
	 * /sourceFetchMap>
	 * 
	 * @parameter
	 */
	private Map<String, String> sourceFetchMap;

	public Map<String, String> getSourceFetchMap() {
		return sourceFetchMap;
	}

	public void setSourceFetchMap(Map<String, String> sourceFetchMap) {
		this.sourceFetchMap = sourceFetchMap;
	}

	/**
	 * Alternative location to look for zips. Here is the order to process zip
	 * research 1. Look for zip in outputFolder 2. Look for zip in zipCacheDir
	 * (if specified) 3. Look for zip at expected URL
	 * 
	 * @parameter default-value="${basedir}/cache"
	 *            property="fetch-sources-from-manifests.zipCacheFolder"
	 */
	private File zipCacheFolder = new File("cache/");

	public File getZipCacheFolder() {
		return zipCacheFolder;
	}

	public void setZipCacheFolder(File zipCacheFolder) {
		this.zipCacheFolder = zipCacheFolder;
	}

	/**
	 * Location where to put zips
	 * 
	 * @parameter default-value="${basedir}/zips"
	 *            property="fetch-sources-from-manifests.outputFolder"
	 */
	private File outputFolder = new File("zips/");

	public File getOutputFolder() {
		return outputFolder;
	}

	public void setOutputFolder(File outputFolder) {
		this.outputFolder = outputFolder;
	}

	/**
	 * @parameter default-value="false"
	 *            property="fetch-sources-from-manifests.skip"
	 */
	private boolean skip;

	public boolean isSkip() {
		return skip;
	}

	public void setSkip(boolean skip) {
		this.skip = skip;
	}

	/**
	 * @parameter expression="${project}"
	 * @required
	 * @readonly
	 */
	private MavenProject project;

	/**
	 * For transfers
	 * 
	 * @component
	 */
	private WagonManager wagonManager;

	private String MANIFEST = "MANIFEST.MF";

	public void execute() throws MojoExecutionException, MojoFailureException {
		if (this.zipCacheFolder != null && !this.zipCacheFolder.isDirectory()) {
			try {
				if (!this.zipCacheFolder.exists()) {
					this.zipCacheFolder.mkdirs();
				}
			} catch (Exception ex) {
				throw new MojoExecutionException(
						"'zipCacheFolder' must be a directory", ex);
			}
		}
		if (this.outputFolder.equals(this.zipCacheFolder)) {
			throw new MojoExecutionException(
					"zipCacheFolder and outputFolder can not be the same folder");
		}

		File zipsDirectory = new File(this.outputFolder, "all");
		if (!zipsDirectory.exists()) {
			zipsDirectory.mkdir();
		}
		Set<File> writtenFiles = new HashSet<File>();
		Properties allBuildProperties = new Properties();

		for (String projectName : this.sourceFetchMap.keySet()) {
			String pluginName = this.sourceFetchMap.get(projectName);
			// jbosstools-base = org.jboss.tools.common
			// getLog().info(projectName + " = " + pluginName);

			// find the first matching plugin jar, eg.,
			// target/repository/plugins/org.jboss.tools.common_3.6.0.Alpha2-v20140304-0055-B440.jar
			File[] matchingFiles = listFilesMatching(new File(
					"target/repository/plugins/"), pluginName + "_.+\\.jar");
			// for (File file : matchingFiles) getLog().debug(file.toString());
			if (matchingFiles.length < 1) {
				throw new MojoExecutionException(
						"No matching plugin found in target/repository/plugins/ for "
								+ pluginName
								+ "_.+\\.jar.\nCheck your pom.xml for this line: <"
								+ projectName + ">" + pluginName + "</"
								+ projectName + ">");
			}
			File jarFile = matchingFiles[0];

			try {
				FileInputStream fin = new FileInputStream(jarFile);
				OutputStream out = new FileOutputStream(MANIFEST);
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
				throw new MojoExecutionException("Error extracting " + MANIFEST
						+ " from " + jarFile, ex);
			}

			// retrieve the MANIFEST.MF file, eg.,
			// org.jboss.tools.usage_1.2.100.Alpha2-v20140221-1555-B437.jar!/META-INF/MANIFEST.MF
			Manifest manifest;
			File manifestFile = new File(MANIFEST);
			try {
				manifest = new Manifest(new FileInputStream(manifestFile));
			} catch (Exception ex) {
				throw new MojoExecutionException(
						"Error while reading manifest file " + MANIFEST, ex);
			}

			// parse out the commitId from Eclipse-SourceReferences:
			// scm:git:https://github.com/jbosstools/jbosst
			// ools-base.git;path="usage/plugins/org.jboss.tools.usage";commitId=184
			// e18cc3ac7c339ce406974b6a4917f73909cc4
			Attributes attr = manifest.getMainAttributes();
			String ESR = null;
			String SHA = null;
			ESR = attr.getValue("Eclipse-SourceReferences");
			// getLog().info(ESR);
			if (ESR != null) {
				SHA = ESR.substring(ESR.lastIndexOf(";commitId=") + 10);
				// getLog().info(SHA);
			}
			// cleanup
			manifestFile.delete();

			// fetch github source archive for that SHA, eg.,
			// https://github.com/jbosstools/jbosstools-base/archive/184e18cc3ac7c339ce406974b6a4917f73909cc4.zip
			// to
			// jbosstools-base_Alpha2-v20140221-1555-B437_184e18cc3ac7c339ce406974b6a4917f73909cc4_sources.zip
			try {
				if (SHA == null) {
					getLog().warn(
							"No Eclipse-SourceReferences SHA found in "
									+ jarFile + "!/META-INF/" + MANIFEST);
					getLog().warn(
							"Cannot fetch " + projectName + " sources from "
									+ pluginName + "!");
				} else {
					String zipName = SHA + ".zip";
					String outputZipName = projectName + "_"
							+ getQualifier(pluginName, jarFile.toString())
							+ "_" + SHA + "_sources.zip";
					File outputZipFile = new File(zipsDirectory, outputZipName);

					boolean diduseCache = false;
					if (this.zipCacheFolder != null
							&& this.zipCacheFolder.exists()) {
						File cachedZip = new File(this.zipCacheFolder,
								outputZipName);
						if (cachedZip.exists()) {
							FileUtils.copyFile(cachedZip, outputZipFile);
							getLog().info(
									"Copied "
											+ removePrefix(outputZipFile
													.getAbsolutePath(), project
													.getBasedir().toString()));
							getLog().info(
									"  From "
											+ removePrefix(cachedZip
													.getAbsolutePath(), project
													.getBasedir().toString()));
							diduseCache = true;
						}
					}
					if (!diduseCache && !outputZipFile.exists()) {
						doGet("https://github.com/jbosstools/" + projectName
								+ "/archive/" + zipName, outputZipFile);
					}

					// generate MD5 file too
					String md5 = null;
					// Then compare MD5 with current file
					if (outputZipFile.exists()) {
						md5 = getMD5(outputZipFile);
					}
					File outputMD5File = new File(
							outputZipFile.getAbsolutePath() + ".MD5");
					FileWriter fw = new FileWriter(outputMD5File);
					fw.write(md5);
					fw.close();

					allBuildProperties.put(outputZipName + ".filename",
							outputZipName);
					allBuildProperties.put(outputZipName + ".filensize",
							Long.toString(outputZipName.length()));
					allBuildProperties.put(outputZipName + ".filemd5", md5);
					writtenFiles.add(outputMD5File);
					writtenFiles.add(outputZipFile);
				}
			} catch (Exception ex) {
				throw new MojoExecutionException(
						"Error while downloading github source archive", ex);
			}

			// append into digest
			// * github project :: plugin :: version :: SHA :: zipfile
			// * jbosstools-base :: org.jboss.tools.usage ::
			// 1.2.100.Alpha2-v20140221-1555-B437 ::
			// 184e18cc3ac7c339ce406974b6a4917f73909cc4 ::
			// https://github.com/jbosstools/jbosstools-base/archive/184e18cc3ac7c339ce406974b6a4917f73909cc4.zip

		}

		// getLog().info("Generating aggregate site metadata");
		try {
			{
				File buildPropertiesAllXml = new File(this.outputFolder,
						"build.properties.all.xml");
				if (!buildPropertiesAllXml.exists()) {
					buildPropertiesAllXml.createNewFile();
				}
				FileOutputStream xmlOut = new FileOutputStream(
						buildPropertiesAllXml);
				allBuildProperties.storeToXML(xmlOut, null);
				xmlOut.close();
			}

			{
				File buildPropertiesFileTxt = new File(this.outputFolder,
						"build.properties.file.txt");
				if (!buildPropertiesFileTxt.exists()) {
					buildPropertiesFileTxt.createNewFile();
				}
				FileOutputStream textOut = new FileOutputStream(
						buildPropertiesFileTxt);
				allBuildProperties.store(textOut, null);
				textOut.close();
			}
		} catch (Exception ex) {
			throw new MojoExecutionException(
					"Error while creating 'metadata' files", ex);
		}

	}

	private String removePrefix(String stringToTrim, String prefix) {
		return stringToTrim.substring(stringToTrim.lastIndexOf(prefix)
				+ prefix.length() + 1);
	}

	// given: pluginName = org.jboss.tools.common
	// given: jarFileName =
	// target/repository/plugins/org.jboss.tools.common_3.6.0.Alpha2-v20140304-0055-B440.jar
	// return Alpha2-v20140304-0055-B440
	private String getQualifier(String pluginName, String jarFileName) {
		// trim .../pluginName prefix
		String qualifier = removePrefix(jarFileName, pluginName);
		// trim .jar suffix
		qualifier = qualifier.substring(0, qualifier.length() - 5);
		// getLog().info("qualifier[0] = " + qualifier);
		qualifier = qualifier.replaceAll("^(\\d+\\.\\d+\\.\\d+\\.)", "");
		// getLog().info("qualifier[1] = " + qualifier);
		return qualifier;
	}

	// thanks to
	// http://stackoverflow.com/questions/2928680/regex-for-files-in-a-directory
	public static File[] listFilesMatching(File root, String regex)
			throws MojoExecutionException {
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
		String[] segments = url.split("/");
		String file = segments[segments.length - 1];
		String repoUrl = url.substring(0, url.length() - file.length() - 1);
		Repository repository = new Repository(repoUrl, repoUrl);

		Wagon wagon = this.wagonManager.getWagon(repository.getProtocol());

		// TODO: this should be retrieved from wagonManager
		// com.googlecode.ConsoleDownloadMonitor downloadMonitor = new
		// com.googlecode.ConsoleDownloadMonitor();
		// wagon.addTransferListener(downloadMonitor);
		wagon.connect(repository,
				this.wagonManager.getProxy(repository.getProtocol()));
		wagon.get(file, outputFile);
		wagon.disconnect();
		// wagon.removeTransferListener(downloadMonitor);
	}

	private static String getMD5(File outputZipFile)
			throws NoSuchAlgorithmException, FileNotFoundException, IOException {
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
