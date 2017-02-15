/**
 * Copyright (c) 2017, Red Hat, Inc.
 * Distributed under license by Red Hat, Inc. All rights reserved.
 * This program is made available under the terms of the
 * Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributor:
 *     Nick Boldt (Red Hat Inc.) -  initial API and implementation
 *
 *     This can be run from within a pom (mapped to package phase). See:
 *
 *     	repository-utils/src/main/resources/generate-composite-site-example.pom.xml
 *
 *     Or via commandline w/o a pom. For example:
 *
 *      cd /tmp
 *      mvn org.jboss.tools.tycho-plugins:repository-utils:0.26.1-SNAPSHOT:generate-composite-site \
 *          -Dgenerate-composite-site.compositeSiteName="My Composite Site" \
 *          -Dgenerate-composite-site.compositeSiteFolder=composite-site-folder \
 *          -Dgenerate-composite-site.childSites="http://something http://something.else" \
 *          -Dgenerate-composite-site.collectChildrenFromRemoteURL="http://download.jboss.org/jbosstools/static/neon/stable/updates/core/ \
 *              http://download.jboss.org/jbosstools/static/neon/stable/updates/coretests/" \
 *          -Dgenerate-composite-site.collectChildrenFromRemoteRegex="\d+\.\d+\.\d+\.(AM.+|Alpha.+|Beta.+|CR.+|Final|GA)/" \
 *          -Dgenerate-composite-site.collectChildrenFromRemoteLimit=3
 *
 *      cat /tmp/target/composite/composite*
 *
 ******************************************************************************/
package org.jboss.tools.tycho.sitegenerator;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.eclipse.tycho.packaging.AbstractTychoPackagingMojo;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

/**
 * Generates a composite site into a specified destination path, from 1 or more URLs and a site name
 */
@Mojo(name = "generate-composite-site", defaultPhase = LifecyclePhase.PACKAGE, requiresProject = false)
public class GenerateCompositeSite extends AbstractTychoPackagingMojo {

	@Parameter(property = "project", required = false, readonly = false)
	private MavenProject project;

	/**
	 * Child sites to add into the composite
	 */
	@Parameter(property = "generate-composite-site.childSites")
	private String childSites;

	/**
	 * Folder to create under /target/ containing the composite site
	 */
	@Parameter(property = "generate-composite-site.compositeSiteFolder", defaultValue = "composite")
	private String compositeSiteFolder = "composite";

	/**
	 * Label to use to name the composite site repo; if null, fall back to
	 * project.name or project.artifactId
	 */
	@Parameter(property = "generate-composite-site.compositeSiteName")
	private String compositeSiteName;

	/**
	 * In addition to statically listed childSites, can also create a composite
	 * site by reading this URL and looking for child folders to composite
	 * together
	 */
	@Parameter(property = "generate-composite-site.collectChildrenFromRemoteURL")
	private String collectChildrenFromRemoteURL;

	/**
	 * Regex to use to find children at the above URL, eg.,
	 * \d+\.\d+\.\d+\.(AM.+|Alpha.+|Beta.+|CR.+|Final|GA)/ - append trailing
	 * slash to match folders only
	 */
	@Parameter(property = "generate-composite-site.collectChildrenFromRemoteRegex")
	private String collectChildrenFromRemoteRegex = null;

	/**
	 * Limit of children to collect; if -1, collect all children.
	 */
	@Parameter(property = "generate-composite-site.collectChildrenFromRemoteLimit", defaultValue = "-1")
	private int collectChildrenFromRemoteLimit = -1;

	public void execute() throws MojoFailureException {

		File outputRepository = new File(
				// if a standalone project without a pom, don't write to
				// ${project.basedir}/target but to ./target folder
				this.project.getBuild().getDirectory().equals("${project.basedir}/target") ? "target"
						: this.project.getBuild().getDirectory(),
				compositeSiteFolder);

		List<String> childSitesList = this.childSites != null
				? new ArrayList<String>(Arrays.asList(this.childSites.split("[\\s\t\n\r]+"))) : new ArrayList<String>();

		List<String> collectChildrenFromRemoteURLList = this.collectChildrenFromRemoteURL != null
				? new ArrayList<String>(Arrays.asList(this.collectChildrenFromRemoteURL.split("[\\s\t\n\r]+")))
				: new ArrayList<String>();

		for (String site : collectChildrenFromRemoteURLList) {
			collectChildrenFromRemote(site, collectChildrenFromRemoteRegex, collectChildrenFromRemoteLimit,
					childSitesList);
		}

		try {
			createCompositeReferences(outputRepository, childSitesList);
		} catch (IOException ex) {
			throw new MojoFailureException(ex.getMessage(), ex);
		}

	}

	private void collectChildrenFromRemote(String collectChildrenFromRemoteURL2, String collectChildrenFromRemoteRegex2,
			int collectChildrenFromRemoteLimit2, List<String> childSitesList2) throws MojoFailureException {
		Document doc = null;
		try {
			// getLog().debug("Load children from: " +
			// collectChildrenFromRemoteURL2);
			doc = Jsoup.connect(collectChildrenFromRemoteURL2).get();
			// getLog().debug("Regex to match: " +
			// collectChildrenFromRemoteRegex2);
			Elements links = doc.getElementsByTag("a");

			// sort larges (newest) first
			Collections.sort(links, new Comparator<Element>() {
				@Override
				public int compare(Element e1, Element e2) {
					return e2.attr("href").compareTo(e1.attr("href"));
				}
			});

			int linksAdded = 0;
			for (Element link : links) {
				String linkHref = link.attr("href");
				if (collectChildrenFromRemoteRegex2 == null || (linkHref.matches(collectChildrenFromRemoteRegex2)
						&& (linksAdded < collectChildrenFromRemoteLimit2 || collectChildrenFromRemoteLimit2 < 0))) {
					getLog().debug("Adding: " + linkHref);
					childSitesList2.add(collectChildrenFromRemoteURL2 + linkHref);
					linksAdded++;
				}
			}
		} catch (IOException ex) {
			throw new MojoFailureException(ex.getMessage(), ex);
		}
		doc = null;
	}

	private void createCompositeReferences(File outputRepository, List<String> childSitesList2) throws IOException {
		long timestamp = System.currentTimeMillis();
		String repoName = compositeSiteName;
		if (repoName == null || repoName.equals("")) {
			repoName = this.project.getName();
		}
		if (repoName == null || repoName.equals("")) {
			repoName = this.project.getArtifactId();
		}
		outputRepository.mkdirs();
		{
			File compositeContentXml = new File(outputRepository, "compositeContent.xml");
			StringBuilder compositeContent = new StringBuilder();
			compositeContent.append("<?compositeMetadataRepository version='1.0.0'?>").append('\n')
					.append("<repository name='").append(repoName)
					.append("' type='org.eclipse.equinox.internal.p2.metadata.repository.CompositeMetadataRepository' version='1.0.0'>")
					.append('\n').append("  <properties size='2'>").append('\n')
					.append("    <property name='p2.compressed' value='true'/>").append('\n')
					.append("    <property name='p2.timestamp' value='").append(timestamp).append("'/>").append('\n')
					.append("  </properties>").append("\n").append("  <children size='").append(childSitesList2.size())
					.append("'>").append("\n");
			for (String site : childSitesList2) {
				compositeContent.append("    <child location='").append(site).append("'/>").append('\n');
			}
			compositeContent.append("  </children>").append('\n').append("</repository>\n\n");
			org.apache.commons.io.FileUtils.writeStringToFile(compositeContentXml, compositeContent.toString());
		}
		{
			File compositeArtifactsXml = new File(outputRepository, "compositeArtifacts.xml");
			StringBuilder compositeArtifact = new StringBuilder();
			compositeArtifact.append("<?compositeArtifactRepository version='1.0.0'?>").append('\n')
					.append("<repository name='").append(repoName)
					.append("' type='org.eclipse.equinox.internal.p2.artifact.repository.CompositeArtifactRepository' version='1.0.0'>")
					.append('\n').append("  <properties size='2'>").append('\n')
					.append("    <property name='p2.compressed' value='true'/>").append('\n')
					.append("    <property name='p2.timestamp' value='").append(timestamp).append("'/>").append('\n')
					.append("  </properties>").append("\n").append("  <children size='").append(childSitesList2.size())
					.append("'>").append("\n");
			for (String site : childSitesList2) {
				compositeArtifact.append("    <child location='").append(site).append("'/>").append('\n');
			}
			compositeArtifact.append("  </children>").append('\n').append("</repository>\n\n");
			org.apache.commons.io.FileUtils.writeStringToFile(compositeArtifactsXml, compositeArtifact.toString());
		}
		{
			File compositeP2Index = new File(outputRepository, "p2.index");
			StringBuilder p2Index = new StringBuilder();
			p2Index.append("version = 1").append('\n')
					.append("metadata.repository.factory.order = compositeContent.xml,\\!").append('\n')
					.append("artifact.repository.factory.order = compositeArtifacts.xml,\\!").append('\n');
			org.apache.commons.io.FileUtils.writeStringToFile(compositeP2Index, p2Index.toString());

		}

		getLog().info("Composite site '" + repoName + "' with " + childSitesList2.size() + " child sites written to:");
		getLog().info(outputRepository.toPath().toAbsolutePath().toString());

	}
}
