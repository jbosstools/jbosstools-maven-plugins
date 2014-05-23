/**
 * Copyright (c) 2014, Red Hat, Inc.
 * Distributed under license by Red Hat, Inc. All rights reserved.
 * This program is made available under the terms of the
 * Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributor:
 *     Mickael Istria (Red Hat, Inc.) - initial API and implementation
 ******************************************************************************/
package org.jboss.tools.tycho.sitegenerator;

import java.io.File;

import org.apache.commons.io.FileUtils;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.eclipse.tycho.ArtifactKey;

@Mojo(defaultPhase = LifecyclePhase.PACKAGE, name = "generate-discovery-site")
public class GenerateDirectoryXmlMojo extends AbstractMojo {
	
	@Parameter(property = "project", required = true, readonly = true)
	private MavenProject project;
	
	@Parameter(defaultValue = "${project.build.directory}/discovery-site/")
	private File outputDirectory;
	
	@Parameter
	private String discoveryFileName;
	
	public void execute() throws MojoExecutionException, MojoFailureException {
		if (! ArtifactKey.TYPE_ECLIPSE_REPOSITORY.equals(this.project.getPackaging())) {
			return;
		}
		if (!this.outputDirectory.exists()) {
			this.outputDirectory.mkdirs();
		}
		File repositoryPluginsFolder = new File(project.getBuild().getDirectory(), "repository/plugins");
		File discoveryPluginsFolder = new File(this.outputDirectory, "plugins");
		StringBuilder directoryXml = new StringBuilder();
		directoryXml.append("<?xml version='1.0' encoding='UTF-8'?>\n");
		directoryXml.append("<directory xmlns='http://www.eclipse.org/mylyn/discovery/directory/'>\n");
		for (File plugin : repositoryPluginsFolder.listFiles()) {
			directoryXml.append("  <entry url='plugins/");
			directoryXml.append(plugin.getName());
			directoryXml.append("' permitCategories='true'/>");
			directoryXml.append('\n');
			try {
				FileUtils.copyFileToDirectory(plugin, discoveryPluginsFolder);
			} catch (Exception ex) {
				throw new MojoFailureException(ex.getMessage(), ex);
			}
		}
		directoryXml.append("</directory>");
		
		if (this.discoveryFileName == null) {
			this.discoveryFileName = this.project.getArtifactId() + ".xml";
		}
		try {
			FileUtils.writeStringToFile(new File(this.outputDirectory, this.discoveryFileName), directoryXml.toString());
		} catch (Exception ex) {
			throw new MojoFailureException(ex.getMessage(), ex);
		}
	}

}
