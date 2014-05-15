/**
 * Copyright (c) 2013, 2014, Red Hat, Inc.
 * Distributed under license by Red Hat, Inc. All rights reserved.
 * This program is made available under the terms of the
 * Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributor:
 *     Mickael Istria (Red Hat, Inc.) - initial API and implementation
 ******************************************************************************/
package org.jboss.tools.tycho.dependency;

import java.io.File;
import java.util.Map;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.FileUtils;
import org.eclipse.tycho.core.ArtifactDependencyVisitor;
import org.eclipse.tycho.core.PluginDescription;
import org.eclipse.tycho.core.TychoProject;

/**
 *
 * @author mistria
 */
@Mojo(name = "copy-dependencies", requiresProject = true)
public class CopyDependenciesMojo extends AbstractMojo {

	@Parameter(property = "session", readonly = true)
    private MavenSession session;

	@Parameter(property = "project", readonly = true)
	private MavenProject project;

	@Parameter(property = "outputDir", defaultValue = "${project.build.directory}/dependencies")
    private File outputDir;

	@Component(role = TychoProject.class)
    private Map<String, TychoProject> projectTypes;

	public void execute() throws MojoExecutionException, MojoFailureException {
		TychoProject tychoProject = projectTypes.get(this.project.getPackaging());
		if (tychoProject == null) {
			throw new MojoExecutionException("This only applies to Tycho projects");
		}

		if (!this.outputDir.exists()) {
			this.outputDir.mkdirs();
		}
		final StringBuilder errorBuilder = new StringBuilder();
		tychoProject.getDependencyWalker(this.project).walk(new ArtifactDependencyVisitor() {
			@Override
			public void visitPlugin(PluginDescription pluginRef) {
				try {
					File location = pluginRef.getLocation();
					if (location.isFile()) {
						FileUtils.copyFileToDirectory(pluginRef.getLocation(), outputDir);
					} else if (location.isDirectory()) {
						if (pluginRef.getMavenProject() != null) {
							getLog().warn("Reactor projects not yet supported: " + pluginRef.getMavenProject());
						} else {
							getLog().warn("Directory-shaped bundles not yet supported: " + pluginRef.getLocation());
						}
					}
				} catch (Exception ex) {
					errorBuilder.append("Couldn't copy " + pluginRef.getLocation() + " to " + outputDir);
					errorBuilder.append("\n");
				}
			}
		});
		if (errorBuilder.length() > 0) {
			throw new MojoFailureException(errorBuilder.toString());
		}

	}

}
