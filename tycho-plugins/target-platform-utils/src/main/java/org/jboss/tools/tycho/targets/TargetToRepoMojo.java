/**
 * Copyright (c) 2012, 2014 Red Hat, Inc.
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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
import org.eclipse.tycho.DefaultArtifactKey;
import org.eclipse.tycho.artifacts.TargetPlatform;
import org.eclipse.tycho.core.resolver.shared.IncludeSourceMode;
import org.eclipse.tycho.core.resolver.shared.MavenRepositoryLocation;
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
 * @author rbioteau
 */
@Mojo(name = "mirror-target-to-repo")
public class TargetToRepoMojo extends AbstractMojo {

	@Parameter(property = "project", readonly = true)
    private MavenProject project;

	@Parameter(property = "session", readonly = true)
	private MavenSession session;

	@Requirement
	@Component
    private RepositorySystem repositorySystem;

	@Parameter
    private File sourceTargetFile;

	@Parameter
	private TargetArtifact sourceTargetArtifact;

	@Parameter(property = "mirror-target-to-repo.includeSources")
    private boolean includeSources;

	@Parameter(defaultValue = "${project.build.directory}/${project.artifactId}.target.repo")
    private File outputRepository;
    
	@Parameter(defaultValue = "JavaSE-1.7")
    private String executionEnvironment;

	@Parameter(defaultValue = "true")
    private boolean followStrictOnly;

    @Parameter(defaultValue = "false")
    private boolean followOnlyFilteredRequirements;

    @Parameter(defaultValue = "true")
    private boolean includeNonGreedy;

    @Parameter(defaultValue = "true")
    private boolean includeFeature;

    @Parameter(defaultValue = "true")
    private boolean includeOptional;

    @Parameter(defaultValue = "false")
    private boolean latestVersionOnly;
	
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
			if (this.sourceTargetFile == null && this.sourceTargetArtifact == null) {
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

	        List<IUDescription> initialIUs = new ArrayList<IUDescription>();
	        for (final Location loc : target.getLocations()) {
	        	if (loc instanceof InstallableUnitLocation) {
	        		for (Unit unit : ((InstallableUnitLocation)loc).getUnits()) {
	        			initialIUs.add(new IUDescription(unit.getId(), unit.getVersion()));
	        		}
	        	}
	        }	        
	        mirrorService.mirrorStandalone(sourceDescriptor, destinationDescriptor, initialIUs, createMirrorOptions(), new BuildOutputDirectory(this.project.getBuild().getOutputDirectory()));
	        
	        if (this.includeSources) {
	        	getLog().info("Computing missing sources...");
	            // create resolver
	            TargetPlatformConfigurationStub tpConfiguration = new TargetPlatformConfigurationStub();
	            tpConfiguration.setEnvironments(Collections.singletonList(TargetEnvironment.getRunningEnvironment()));
	            tpConfiguration.addTargetDefinition(target);
	            this.p2Factory = this.equinox.getService(P2ResolverFactory.class);
	            P2Resolver tpResolver = this.p2Factory.createResolver(new MavenLoggerAdapter(this.logger, getLog().isDebugEnabled()));
	            tpResolver.setEnvironments(Arrays.asList(new TargetEnvironment[] { TargetEnvironment.getRunningEnvironment() }));
	            
	            for (Location loc : target.getLocations()) {
	            	if (loc instanceof InstallableUnitLocation) {
	            		InstallableUnitLocation p2Loc = (InstallableUnitLocation) loc;
	            		for (Unit unit : p2Loc.getUnits()) {
	            			// resolve everything in TP
	            			tpResolver.addDependency(ArtifactType.TYPE_INSTALLABLE_UNIT, unit.getId(), "[" + unit.getVersion() + "," + unit.getVersion() + "]");
	            		}
	            	}
	            }
	            P2ResolutionResult result = tpResolver.resolveMetadata(tpConfiguration, this.executionEnvironment);
	            
	            Set<DefaultArtifactKey> sourcesFound = new HashSet<DefaultArtifactKey>();
	            Set<DefaultArtifactKey> regularArtifacts = new HashSet<DefaultArtifactKey>();
	        	for (Entry entry : result.getArtifacts()) {
	        		if (entry.getId().endsWith(".source")) {
	        			sourcesFound.add(new DefaultArtifactKey(entry.getType(), entry.getId().substring(0, entry.getId().length() - ".source".length()), entry.getVersion()));
	        		} else if (entry.getId().endsWith(".source.feature.group")) {
	        			sourcesFound.add(new DefaultArtifactKey(entry.getType(), entry.getId().replace(".source.feature.group", ".feature.group"), entry.getVersion()));
	        		} else {
	        			regularArtifacts.add(new DefaultArtifactKey(entry.getType(), entry.getId(), entry.getVersion()));
	        		}
	        	}
	        	Set<DefaultArtifactKey> artifactsWithoutSources = new HashSet<DefaultArtifactKey>(regularArtifacts);
	        	artifactsWithoutSources.removeAll(sourcesFound);
	        	if (!artifactsWithoutSources.isEmpty()) {
	        		TargetPlatformConfigurationStub sites = new TargetPlatformConfigurationStub();
	        		Set<IUDescription> additionalSourceUnits = new HashSet<IUDescription>();
	        		for (Location loc : target.getLocations()) {
	        			if (loc instanceof InstallableUnitLocation) {
	        				InstallableUnitLocation location = (InstallableUnitLocation)loc;
	        				for (Repository repo : location.getRepositories()) {
	        					sites.addP2Repository(new MavenRepositoryLocation(repo.getId(), repo.getLocation()));
	                		}
	        			}
	        		}
	        		TargetPlatform sitesTP = this.p2Factory.getTargetPlatformFactory().createTargetPlatform(sites, new MockExecutionEnvironment(), null, null);
	        		for (DefaultArtifactKey artifactWithoutSources : artifactsWithoutSources) {
	        		        String sourceUnitId;
	        		        if (artifactWithoutSources.getId().endsWith(".feature.jar")) {
	        		            sourceUnitId = artifactWithoutSources.getId().replace(".feature.jar", ".source.feature.group");
	        		        } else {
	        		            sourceUnitId = artifactWithoutSources.getId() + ".source";
	        		        }
	        		        String sourceUnitVersion = artifactWithoutSources.getVersion();
	        			P2ResolutionResult resolvedSource = tpResolver.resolveInstallableUnit(sitesTP, sourceUnitId, "[" + sourceUnitVersion + "," + sourceUnitVersion + "]");
	        			if (resolvedSource.getArtifacts().size() > 0 || resolvedSource.getNonReactorUnits().size() > 0) {
	        				additionalSourceUnits.add(new IUDescription(sourceUnitId, sourceUnitVersion));
	        			}
	        		}
	        		if (!additionalSourceUnits.isEmpty()) {
	        			mirrorService.mirrorStandalone(sourceDescriptor, destinationDescriptor, additionalSourceUnits, createMirrorOptions(), new BuildOutputDirectory(this.project.getBuild().getOutputDirectory()));
	        		}
	        	}
	        }
		} catch (Exception ex) {
			throw new MojoExecutionException("Internal error", ex);
		}
	}

    private MirrorOptions createMirrorOptions() {
        MirrorOptions options = new MirrorOptions();
        options.setFollowOnlyFilteredRequirements(followOnlyFilteredRequirements);
        options.setFollowStrictOnly(followStrictOnly);
        options.setIncludeFeatures(includeFeature);
        options.setIncludeNonGreedy(includeNonGreedy);
        options.setIncludeOptional(includeOptional);
        options.setLatestVersionOnly(latestVersionOnly);
        return options;
    }

}
