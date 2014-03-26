package org.jboss.tools.tycho.dependency;

import java.io.File;
import java.util.Map;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.FileUtils;
import org.eclipse.tycho.core.ArtifactDependencyVisitor;
import org.eclipse.tycho.core.PluginDescription;
import org.eclipse.tycho.core.TychoProject;

/**
 *
 * @author mistria
 * @goal copy-dependencies
 * @requiresProject true
 */
public class CopyDependenciesMojo extends AbstractMojo {

    /**
     * @parameter property="project"
     * @readonly
     */
    private MavenProject project;

    /**
     * @parameter property="outputDir" default-value="${project.build.directory}/dependencies"
     */
    private File outputDir;

    /**
     * @component role="org.eclipse.tycho.core.TychoProject"
     */
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
