/**
 * Copyright (c) 2013-2014, Red Hat, Inc.
 * Distributed under license by Red Hat, Inc. All rights reserved.
 * This program is made available under the terms of the
 * Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributor:
 *     Mickael Istria (Red Hat, Inc.) - initial API and implementation
 ******************************************************************************/
package org.jboss.tools.tycho.sitegenerator;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigInteger;
import java.net.URI;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.Set;

import javax.xml.parsers.DocumentBuilderFactory;

import org.apache.maven.artifact.manager.WagonManager;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.wagon.Wagon;
import org.apache.maven.wagon.repository.Repository;
import org.codehaus.plexus.util.FileUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

@Mojo(name = "fetch-zips-for-aggregate", defaultPhase = LifecyclePhase.PACKAGE)
public class FetchZipsFromRepo extends AbstractMojo {

	/**
	 * Repositories from where to fetch Zips
	 */
	@Parameter
	private List<URL> repositories;

	/**
	 * Alternative location to look for zips.
	 * Here is the order to process zip research
	 * 1. Look for zip in outputFolder
	 * 2. Look for zip in zipCacheDir (if specified)
	 * 3. Look for zip at expected URL
	 */
	@Parameter(property = "fetch-zips-for-aggregate.zipCacheFolder")
	private File zipCacheFolder;

	/**
	 * Location where to put zips
	 */
	@Parameter(property = "fetch-zips-for-aggregate.outputFolder", defaultValue = "${basedir}/zips")
	private File outputFolder;

	@Parameter(property = "fetch-zips-for-aggregate.skip", defaultValue = "false")
	private boolean skip;

	/**
	 * For transfers
	 */
	@Component
	private WagonManager wagonManager;

	public void execute() throws MojoExecutionException, MojoFailureException {
		if (this.skip || this.repositories == null || this.repositories.isEmpty()) {
			return;
		}
		if (this.zipCacheFolder != null && !this.zipCacheFolder.isDirectory()) {
			throw new MojoExecutionException("'zipCacheDir' must be a directory");
		}
		if (this.outputFolder.equals(this.zipCacheFolder)) {
			throw new MojoExecutionException("Same value for zipCacheDir and outputFolder is not allowed");
		}

		List<String> componentRepositories = new ArrayList<String>();
		for (URL repository : this.repositories) {
			try {
				URL compositeArtifactsURL = new URL(repository, "compositeArtifacts.xml");
				InputStream stream = null;
				 stream = compositeArtifactsURL.openStream();
				 Document compositeArtifactsDoc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(stream);
				 stream.close();
				 NodeList children =
						 ((Element)((Element)compositeArtifactsDoc.getElementsByTagName("repository").item(0))
						 	.getElementsByTagName("children").item(0)).getElementsByTagName("child");
				 for (int i = 0; i < children.getLength(); i++) {
					 Element child = (Element)children.item(i);
					 URI location = new URI(child.getAttribute("location"));
					 if (!location.toString().contains("/staging.previous/")) {
						 if (location.isAbsolute()) {
							 componentRepositories.add(location.toString());
						 } else {
							 componentRepositories.add(new URL(repository, location.toString()).toString());
						 }
					 }
				 }

			} catch (Exception ex) {
				throw new MojoExecutionException("Repository " +repository.toString() + " is NOT a COMPOSITE repository");
			}
		}

		File zipsDirectory = new File(this.outputFolder, "all");
		if (!zipsDirectory.exists()) {
			zipsDirectory.mkdir();
		}
		Set<File> writtenFiles = new HashSet<File>();
		Properties allBuildProperties = new Properties();
		for (String componentRepository : componentRepositories) {
			 // Remove trailing slash
			 while (componentRepository.charAt(componentRepository.length() - 1) == '/') {
				 componentRepository = componentRepository.substring(0, componentRepository.length() - 1);
			 }
			if (componentRepository.endsWith("/repo")) {
				componentRepository = componentRepository.substring(0, componentRepository.length() - "/repo".length());
			}
			if (componentRepository.endsWith("/all")) {
				componentRepository = componentRepository.substring(0, componentRepository.length() - "/all".length());
			}
			getLog().info("Getting zips and metadata from repo '" + componentRepository + "'");

			try {
				Repository wagonRepository = new Repository(componentRepository, componentRepository);
				Wagon wagon = this.wagonManager.getWagon(wagonRepository.getProtocol());
				// TODO add a download monitor
				wagon.connect(wagonRepository);

				String buildPropertiesPrefix = null;
				InputStream zipListStreamm = null;
				try {
					zipListStreamm = new URL(componentRepository + "/logs/zip.list.txt").openStream();
				} catch (Exception ex) {
					getLog().warn("Could not get zip.list.txt for repo " + componentRepository + ". Ignoring reposiotry.");
					continue;
				}
				Properties zipListProperties = new Properties();
				zipListProperties.load(zipListStreamm);
				zipListStreamm.close();
				for (String zip : zipListProperties.getProperty("ALL_ZIPS").split(",")) {
					zip = zip.trim();
					if (!zip.isEmpty()) {
						String[] segments = zip.split("/");
						String zipName = segments[segments.length - 1];
						if (buildPropertiesPrefix == null) {
							buildPropertiesPrefix = zipName.replace("-Update", "").replace("-Sources", "").replace(".zip", "") + ".build.properties.";
						}
						File outputZipFile = new File(zipsDirectory, zipName);

						// First, get new MD5
						File outputMD5File = new File(outputZipFile.getAbsolutePath() + ".MD5");
						wagon.get(zip + ".MD5", outputMD5File);
						InputStream md5is = new FileInputStream(outputMD5File);
						BufferedReader md5reader = new BufferedReader(new InputStreamReader(md5is));
						String retrievedMD5 = md5reader.readLine();
						md5is.close();

						String md5 = null;
						// Then compare MD5 with current file
						if (outputZipFile.exists()) {
							String computedMD5 = getMD5(outputZipFile);
							if (retrievedMD5.equals(computedMD5)) {
								md5 = retrievedMD5;
								getLog().info("  No change for " + outputZipFile.getAbsolutePath());
							}
						}

						if (md5 == null) { // MDS == null <=> no existing file or out of date
							boolean useCache = false;
							if (this.zipCacheFolder != null) {
								File cachedZip = new File(this.zipCacheFolder, outputZipFile.getName());
								if (cachedZip.exists() && getMD5(cachedZip).equals(retrievedMD5)) {
									FileUtils.copyFile(cachedZip, outputZipFile);
									getLog().info("  Got " + outputZipFile.getAbsolutePath() + " from " + cachedZip.getAbsolutePath());
									useCache = true;
								}
							}
							if (!useCache) {
								wagon.get(zip, outputZipFile);
								getLog().info("  Got " + outputZipFile.getAbsolutePath() + " from " + wagon.getRepository().getUrl());
								if (this.zipCacheFolder != null) {
									File cachedZip = new File(this.zipCacheFolder, outputZipFile.getName());
									if (!cachedZip.exists()) {
										cachedZip.createNewFile();
									}
									FileUtils.copyFile(cachedZip, outputZipFile);
									getLog().info("    Installed in cache: " + cachedZip.getAbsolutePath());
								}
							}
							md5 = getMD5(outputZipFile);
						}

						allBuildProperties.put(zipName + ".filename", zip);
						allBuildProperties.put(zipName + ".filensize", Long.toString(outputZipFile.length()));
						allBuildProperties.put(zipName + ".filemd5", md5);
						writtenFiles.add(outputMD5File);
						writtenFiles.add(outputZipFile);
					}

					InputStream buildPropertiesStream = new URL(componentRepository + "/logs/build.properties").openStream();
					Properties componentBuildProperties = new Properties();
					componentBuildProperties.load(buildPropertiesStream);
					buildPropertiesStream.close();
					for (Entry<Object, Object> entry : componentBuildProperties.entrySet()) {
						String qualifiedKey = buildPropertiesPrefix + ((String)entry.getKey());
						allBuildProperties.put(qualifiedKey, entry.getValue());
					}
				}
				wagon.disconnect();
			} catch (Exception ex) {
				throw new MojoExecutionException("Error while transferring zips from repository '" + componentRepository + "'", ex);
			}
		}

		// Clean useless files
		for (File zip : zipsDirectory.listFiles()) {
			if (!writtenFiles.contains(zip)) {
				zip.delete();
			}
		}

		getLog().info("Generating aggregate components metadata");
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
	}

	private static String getMD5(File outputZipFile) throws NoSuchAlgorithmException, FileNotFoundException, IOException {
		MessageDigest digest = MessageDigest.getInstance("MD5");
		InputStream zipIs = new FileInputStream(outputZipFile);
		byte[] buffer = new byte[8192];
		int read = 0;
		while( (read = zipIs.read(buffer)) > 0) {
			digest.update(buffer, 0, read);
		}
		byte[] md5sum = digest.digest();
		BigInteger bigInt = new BigInteger(1, md5sum);
		String computedMD5 = bigInt.toString(16);
		zipIs.close();
		return computedMD5;
	}

}
