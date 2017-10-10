/**
 * Copyright (c) 2012, 2017 Red Hat, Inc.
 * Distributed under license by Red Hat, Inc. All rights reserved.
 * This program is made available under the terms of the
 * Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributor:
 *     Mickael Istria (Red Hat, Inc.) - initial API and implementation
 */
package org.jboss.tools.tycho.targets;

import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.repository.RepositorySystem;
import org.codehaus.plexus.component.annotations.Requirement;
import org.codehaus.plexus.logging.Logger;
import org.eclipse.sisu.equinox.EquinoxServiceFactory;
import org.eclipse.tycho.ArtifactType;
import org.eclipse.tycho.BuildOutputDirectory;
import org.eclipse.tycho.artifacts.TargetPlatform;
import org.eclipse.tycho.core.resolver.shared.IncludeSourceMode;
import org.eclipse.tycho.core.shared.TargetEnvironment;
import org.eclipse.tycho.osgi.adapters.MavenLoggerAdapter;
import org.eclipse.tycho.p2.resolver.TargetDefinitionFile;
import org.eclipse.tycho.p2.resolver.facade.P2ResolutionResult;
import org.eclipse.tycho.p2.resolver.facade.P2ResolutionResult.Entry;
import org.eclipse.tycho.p2.resolver.facade.P2Resolver;
import org.eclipse.tycho.p2.resolver.facade.P2ResolverFactory;
import org.eclipse.tycho.p2.target.facade.TargetDefinition.InstallableUnitLocation;
import org.eclipse.tycho.p2.target.facade.TargetDefinition.Location;
import org.eclipse.tycho.p2.target.facade.TargetDefinition.Repository;
import org.eclipse.tycho.p2.target.facade.TargetDefinition.Unit;
import org.eclipse.tycho.p2.target.facade.TargetPlatformConfigurationStub;
import org.eclipse.tycho.p2.tools.DestinationRepositoryDescriptor;
import org.eclipse.tycho.p2.tools.RepositoryReferences;
import org.eclipse.tycho.p2.tools.mirroring.facade.IUDescription;
import org.eclipse.tycho.p2.tools.mirroring.facade.MirrorApplicationService;
import org.eclipse.tycho.p2.tools.mirroring.facade.MirrorOptions;

/**
 * Mirrors a target file as a p2 repo. Suitable for sharing/caching target/dependency sites.
 *
 * @author mistria
 */
@Mojo(name = "mirror-target-to-repo", requiresProject=false)
public class TargetToRepoMojo extends AbstractMojo {

	private static final List<TargetEnvironment> ALL_SUPPORTED_TARGET_ENVIRONMENTS = Arrays.asList(new TargetEnvironment[] {
		new TargetEnvironment("linux", "gtk", "x86"),
		new TargetEnvironment("linux", "gtk", "x86_64"),
		new TargetEnvironment("win32", "win32", "x86"),
		new TargetEnvironment("win32", "win32", "x86_64"),
		new TargetEnvironment("macosx", "cocoa", "x86_64"),
	});

	@Parameter(property = "project", readonly = true, required = false)
    private MavenProject project;

	@Parameter(property = "session", readonly = true)
	private MavenSession session;

	@Requirement
	@Component
	private RepositorySystem repositorySystem;

	@Parameter(property = "targetDefinition")
	private File sourceTargetFile;

	@Parameter
	private TargetArtifact sourceTargetArtifact;

	@Parameter(property = "mirror-target-to-repo.includeSources", defaultValue = "false")
	private boolean includeSources;
	
	@Parameter(property = "mirror-target-to-repo.includePacked", defaultValue = "true")
	private boolean includePacked;

	@Parameter(defaultValue = "${project.build.directory}/${project.artifactId}.target.repo")
	private File outputRepository;

	@Parameter(defaultValue = "JavaSE-1.7")
	private String executionEnvironment;

	@Component private Logger logger;
	@Component private EquinoxServiceFactory equinox;

	private P2ResolverFactory p2Factory;

	@Component
	private Logger plexusLogger;

	public void execute() throws MojoExecutionException, MojoFailureException {
		try {
			if (this.sourceTargetArtifact != null && this.sourceTargetFile != null) {
				getLog().debug("sourceTargetArtifact: " + this.sourceTargetArtifact.toString());
				getLog().debug("sourceTargetFile; " + this.sourceTargetFile.toString());
				throw new MojoExecutionException("Set either 'sourceTargetArtifact' XOR 'sourceTargetFile'");
			}
			if (this.sourceTargetFile == null && this.sourceTargetArtifact == null && this.project != null) {
				this.sourceTargetFile = new File(this.project.getBasedir(), this.project.getArtifactId() + ".target");
			}
			if (this.sourceTargetArtifact != null) {
				this.sourceTargetFile = this.sourceTargetArtifact.getFile(this.repositorySystem, this.session, this.project);
			}
			if (!this.sourceTargetFile.isFile()) {
				throw new MojoExecutionException("Specified 'targetFile' (value: " + sourceTargetFile + ") is not a valid file");
			}
			this.outputRepository.mkdirs();

			final MirrorApplicationService mirrorService = equinox.getService(MirrorApplicationService.class);

			TargetDefinitionFile target = TargetDefinitionFile.read(sourceTargetFile, IncludeSourceMode.ignore);
			final RepositoryReferences sourceDescriptor = new RepositoryReferences();
			for (final Location loc : target.getLocations()) {
				if (loc instanceof InstallableUnitLocation) {
					for (Repository repo : ((InstallableUnitLocation)loc).getRepositories()) {
						sourceDescriptor.addMetadataRepository(repo.getLocation());
						sourceDescriptor.addArtifactRepository(repo.getLocation());
					}
				}
			}

			final DestinationRepositoryDescriptor destinationDescriptor = new DestinationRepositoryDescriptor(this.outputRepository, this.sourceTargetFile.getName(), true, true,
					false, false, true);

			TargetPlatformConfigurationStub tp = new TargetPlatformConfigurationStub();
			tp.setEnvironments(ALL_SUPPORTED_TARGET_ENVIRONMENTS);
			tp.addTargetDefinition(target);

			this.p2Factory = this.equinox.getService(P2ResolverFactory.class);
			P2Resolver tpResolver = this.p2Factory.createResolver(new MavenLoggerAdapter(this.logger, getLog().isDebugEnabled()));
			tpResolver.setEnvironments(ALL_SUPPORTED_TARGET_ENVIRONMENTS);
			for (final Location loc : target.getLocations()) {
				if (loc instanceof InstallableUnitLocation) {
					for (Unit unit : ((InstallableUnitLocation)loc).getUnits()) {
						tpResolver.addDependency(ArtifactType.TYPE_INSTALLABLE_UNIT , unit.getId(), unit.getVersion());
					}
				}
			}

			P2ResolutionResult mirroredArtifacts = tpResolver.resolveMetadata(tp, this.executionEnvironment);
			List<IUDescription> iusToMirror = new ArrayList<IUDescription>();
			for (Entry entry : mirroredArtifacts.getArtifacts()) {
				if (ArtifactType.TYPE_INSTALLABLE_UNIT.equals(entry.getType()) && !entry.getId().contains("a.jre.javase")) {
					iusToMirror.add(new IUDescription(entry.getId(), entry.getVersion()));
				}
			}
			mirrorService.mirrorStandalone(sourceDescriptor, destinationDescriptor, iusToMirror, createMirrorOptions(), new BuildOutputDirectory(this.project.getBuild().getOutputDirectory()));

			if (this.includeSources) {
				getLog().info("Computing missing sources...");
				// create mirror as TP to query it
				Set<IUDescription> alreadyMirroredSourceIUs = new HashSet<>();
				for (IUDescription mirroredIU : iusToMirror) {
					if (mirroredIU.getId().endsWith(".source")) {
						alreadyMirroredSourceIUs.add(mirroredIU);
						alreadyMirroredSourceIUs.add(new IUDescription(mirroredIU.getId().substring(0, mirroredIU.getId().length() - ".source".length()), mirroredIU.getVersion()));
					} else if (mirroredIU.getId().endsWith(".source.feature.group")) {
						alreadyMirroredSourceIUs.add(new IUDescription(mirroredIU.getId().replace(".source.feature.group", ".feature.group"), mirroredIU.getVersion()));
						alreadyMirroredSourceIUs.add(mirroredIU);
					}
				}
				Set<IUDescription> mirroredIUsWithoutSource = new HashSet<>(iusToMirror);
				mirroredIUsWithoutSource.removeAll(alreadyMirroredSourceIUs);
				if (!mirroredIUsWithoutSource.isEmpty()) {
					List<IUDescription> sourceIusToMirror = new ArrayList<IUDescription>();
					// create TP to query it
					TargetPlatformConfigurationStub tpConfiguration = new TargetPlatformConfigurationStub();
					tpConfiguration.setEnvironments(ALL_SUPPORTED_TARGET_ENVIRONMENTS);
					for (URI metadataRepoURI : sourceDescriptor.getMetadataRepositories()) {
						tpConfiguration.addP2Repository(metadataRepoURI);
					}
					TargetPlatform sitesTP = this.p2Factory.getTargetPlatformFactory().createTargetPlatform(tpConfiguration, new MockExecutionEnvironment(), null, null);
					for (IUDescription artifactWithoutSources : mirroredIUsWithoutSource) {
						String sourceUnitId;
						if (artifactWithoutSources.getId().endsWith(".feature.jar")) {
							sourceUnitId = artifactWithoutSources.getId().replace(".feature.jar", ".source.feature.group");
						} else {
							sourceUnitId = artifactWithoutSources.getId() + ".source";
						}
						String sourceUnitVersion = artifactWithoutSources.getVersion();
						P2ResolutionResult resolvedSource = tpResolver.resolveInstallableUnit(sitesTP, sourceUnitId, "[" + sourceUnitVersion + "," + sourceUnitVersion + "]");
						if (resolvedSource.getArtifacts().size() > 0 || resolvedSource.getNonReactorUnits().size() > 0) {
							sourceIusToMirror.add(new IUDescription(sourceUnitId, sourceUnitVersion));
						}
					}
					if (!sourceIusToMirror.isEmpty()) {
						mirrorService.mirrorStandalone(sourceDescriptor, destinationDescriptor, sourceIusToMirror, createMirrorOptions(), new BuildOutputDirectory(this.project.getBuild().getOutputDirectory()));
					}
				}
			}
		} catch (Exception ex) {
			throw new MojoExecutionException("Internal error", ex);
		}
	}

	private MirrorOptions createMirrorOptions() {
		MirrorOptions options = new MirrorOptions();
		options.setFollowOnlyFilteredRequirements(false);
		options.setFollowStrictOnly(true);
		options.setIncludeFeatures(true);
		options.setIncludeNonGreedy(true);
		options.setIncludeOptional(true);
		options.setLatestVersionOnly(false);
		options.setIncludePacked(this.includePacked);
		return options;
	}

}
