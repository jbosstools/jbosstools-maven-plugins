/*******************************************************************************
 * Copyright (c) 2015 Red Hat Inc., and others
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Mickael Istria (Red Hat Inc.) - initial API and implementation
 *     Nick Boldt (Red Hat Inc.) - fixes
 ******************************************************************************/
package org.jboss.tools.tycho.sitegenerator;

import java.io.File;
import java.lang.reflect.Method;
import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.apache.commons.io.FileUtils;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.component.annotations.Requirement;
import org.codehaus.plexus.logging.Logger;
import org.eclipse.sisu.equinox.EquinoxServiceFactory;
import org.eclipse.tycho.IDependencyMetadata.DependencyMetadataType;
import org.eclipse.tycho.ReactorProject;
import org.eclipse.tycho.artifacts.TargetPlatform;
import org.eclipse.tycho.core.osgitools.DefaultReactorProject;
import org.eclipse.tycho.core.shared.TargetEnvironment;
import org.eclipse.tycho.core.utils.TychoProjectUtils;
import org.eclipse.tycho.osgi.adapters.MavenLoggerAdapter;
import org.eclipse.tycho.p2.resolver.facade.P2ResolutionResult;
import org.eclipse.tycho.p2.resolver.facade.P2ResolutionResult.Entry;
import org.eclipse.tycho.p2.resolver.facade.P2Resolver;
import org.eclipse.tycho.p2.resolver.facade.P2ResolverFactory;
import org.eclipse.tycho.p2.target.facade.TargetPlatformConfigurationStub;
import org.osgi.framework.Version;

@Mojo(defaultPhase=LifecyclePhase.VERIFY, requiresProject=false, name="compare-version-with-baselines")
public class CompareWithBaselineMojo extends AbstractMojo {
	
	public static enum ReportBehavior {
		fail, warn
	}
	
	@Parameter(property="project", readonly=true)
	private MavenProject project;

	@Parameter(property="baselines", name="baselines")
	private List<String> baselines;

	@Parameter(property="skip")
	private boolean skip;
	
	@Parameter(property="onIllegalVersion", defaultValue="fail")
	private ReportBehavior onIllegalVersion;

	@Requirement
	@Component
	private EquinoxServiceFactory equinox;

	@Component
	private Logger plexusLogger;

	public void execute() throws MojoExecutionException, MojoFailureException {
		if (skip) {
			getLog().info("Skipped");
			return;
		}
		ReactorProject reactorProject = DefaultReactorProject.adapt(project); 
		Set<?> dependencyMetadata = reactorProject.getDependencyMetadata(DependencyMetadataType.SEED);
		if (dependencyMetadata == null || dependencyMetadata.isEmpty()) {
			getLog().debug("Skipping baseline version comparison, no p2 artifacts created in build.");
			return;
		}

		P2ResolverFactory resolverFactory = this.equinox.getService(P2ResolverFactory.class);
		P2Resolver resolver = resolverFactory.createResolver(new MavenLoggerAdapter(this.plexusLogger, true));

		TargetPlatformConfigurationStub baselineTPStub = new TargetPlatformConfigurationStub();
		baselineTPStub.setForceIgnoreLocalArtifacts(true);
		baselineTPStub.setEnvironments(Collections.singletonList(TargetEnvironment.getRunningEnvironment()));
		for (String baselineRepo : this.baselines) {
			baselineTPStub.addP2Repository(toRepoURI(baselineRepo));
		}
		TargetPlatform baselineTP = resolverFactory.getTargetPlatformFactory().createTargetPlatform(baselineTPStub, TychoProjectUtils.getExecutionEnvironmentConfiguration(reactorProject), null);

		for (Object item : dependencyMetadata) {
			try {
				Method getIdMethod = item.getClass().getMethod("getId");
				Method getVersionMethod = item.getClass().getMethod("getVersion");
				String id = (String)getIdMethod.invoke(item);
				Version version = new Version(getVersionMethod.invoke(item).toString());
				P2ResolutionResult res = resolver.resolveInstallableUnit(baselineTP, id, "0.0.0");

				for (Entry foundInBaseline : res.getArtifacts()) {
					Version baselineVersion = new Version(foundInBaseline.getVersion());
					String delta = "" + (version.getMajor()-baselineVersion.getMajor())  + "." + (version.getMinor() - baselineVersion.getMinor()) + "." + (version.getMicro() - baselineVersion.getMicro());
					
					getLog().debug("Found " + foundInBaseline.getId() + "/" + foundInBaseline.getVersion() + " with delta: " + delta);
					if (version.compareTo(baselineVersion) <= 0) {
						String message = "Version have moved backwards for (" + id + "/" + version + "). Baseline has " + baselineVersion + ") with delta: " + delta;
						if (this.onIllegalVersion == ReportBehavior.warn) {
							getLog().warn(message);
							return;
						} else {
							throw new MojoFailureException(message);
						}
					} else if (version.equals(baselineVersion)) {
						File baselineFile = foundInBaseline.getLocation(true);
						File currentFile = null;
						// TODO: currently, there are only 2 kinds of (known) artifacts, but we could have more
						// and unknown ones. Need to find something smarter to map artifact with actual file.
						if (id.endsWith("source")) {
							currentFile = reactorProject.getArtifact("sources");
						} else {
							currentFile = reactorProject.getArtifact();
						}
						if (!FileUtils.contentEquals(currentFile, baselineFile)) {
							String message = "Duplicate version but different content found for (" + id + "/" + version + "). Also exists in baseline, but its content is different.";
							if (this.onIllegalVersion == ReportBehavior.warn) {
								getLog().warn(message);
								return;
							} else {
								throw new MojoFailureException(message);
							}
						}
					} else if (version.getMajor() == baselineVersion.getMajor() && version.getMinor() == baselineVersion.getMinor() && version.getMicro() == baselineVersion.getMicro()) {
						String message = "Only qualifier changed for (" + id + "/" + version + "). Expected to have bigger x.y.z than what is available in baseline (" + baselineVersion + ")";
						if (this.onIllegalVersion == ReportBehavior.warn) {
							getLog().warn(message);
							return;
						} else {
							throw new MojoFailureException(message);
						}
					}
				}

			} catch (Exception ex) {
				throw new MojoFailureException(ex.getMessage(), ex);
			}
		}
	}

	private URI toRepoURI(String s) throws MojoExecutionException {
		if (new File(s).exists()) {
			return new File(s).toURI();
		}
		if (new File(project.getBasedir(), s).exists()) {
			return new File(project.getBasedir(), s).toURI();
		}
		try {
			return new URI(s);
		} catch (Exception ex) {
			throw new MojoExecutionException("Wrong baseline: '" + s + "'", ex);
		}
	}

}
