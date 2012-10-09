/**
 * Copyright (c) 2012, Red Hat, Inc.
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
import java.util.Collection;
import java.util.List;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;
import org.apache.maven.repository.RepositorySystem;
import org.codehaus.plexus.component.annotations.Requirement;
import org.eclipse.sisu.equinox.EquinoxServiceFactory;
import org.eclipse.tycho.BuildOutputDirectory;
import org.eclipse.tycho.p2.resolver.TargetDefinitionFile;
import org.eclipse.tycho.p2.target.facade.TargetDefinition.InstallableUnitLocation;
import org.eclipse.tycho.p2.target.facade.TargetDefinition.Location;
import org.eclipse.tycho.p2.target.facade.TargetDefinition.Repository;
import org.eclipse.tycho.p2.target.facade.TargetDefinition.Unit;
import org.eclipse.tycho.p2.tools.DestinationRepositoryDescriptor;
import org.eclipse.tycho.p2.tools.RepositoryReferences;
import org.eclipse.tycho.p2.tools.mirroring.facade.IUDescription;
import org.eclipse.tycho.p2.tools.mirroring.facade.MirrorApplicationService;
import org.eclipse.tycho.p2.tools.mirroring.facade.MirrorOptions;

/**
 * Mirrors a target file as a p2 repo. Suitable for sharing/caching target/dependency sites.
 *
 * @author mistria
 * @goal mirror-target-to-repo
 */
public class TargetToRepoMojo extends AbstractMojo {

	 /**
     * @parameter default-value="${project}"
     * @readonly
     */
    private MavenProject project;
    /**
     * @parameter expression="${session}"
     * @readonly
     */
    private MavenSession session;
    /**
     * @component
     */
    @Requirement
    private RepositorySystem repositorySystem;

    /**
     * @parameter
     */
    private File sourceTargetFile;

    /**
     * @parameter
     */
    private TargetArtifact sourceTargetArtifact;

    /**
     * @parameter expression="${project.build.directory}/${project.artifactId}.target.repo"
     */
    private File outputRepository;

    /** @component */
    private EquinoxServiceFactory p2;

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

			final MirrorApplicationService mirrorService = p2.getService(MirrorApplicationService.class);

			TargetDefinitionFile target = TargetDefinitionFile.read(sourceTargetFile);
	        final RepositoryReferences sourceDescriptor = new RepositoryReferences();
	        for (final Location loc : target.getLocations()) {
	        	if (loc instanceof InstallableUnitLocation) {
	        		for (Repository repo : ((InstallableUnitLocation)loc).getRepositories()) {
	                    sourceDescriptor.addMetadataRepository(repo.getLocation());
	                    sourceDescriptor.addArtifactRepository(repo.getLocation());
	        		}
	        	}
	        }

	        final DestinationRepositoryDescriptor destinationDescriptor = new DestinationRepositoryDescriptor(this.outputRepository, this.sourceTargetFile.getName(), true, false, true);

	        mirrorService.mirrorStandalone(sourceDescriptor, destinationDescriptor, createIUDescriptions(target), createMirrorOptions(), new BuildOutputDirectory(this.project.getBuild().getOutputDirectory()));
		} catch (Exception ex) {
			throw new MojoExecutionException("Internal error", ex);
		}
	}

    private static Collection<IUDescription> createIUDescriptions(TargetDefinitionFile target) {
        List<IUDescription> result = new ArrayList<IUDescription>();
        for (final Location loc : target.getLocations()) {
        	if (loc instanceof InstallableUnitLocation) {
        		for (Unit unit : ((InstallableUnitLocation)loc).getUnits()) {
                    result.add(new IUDescription(unit.getId(), unit.getVersion()));
        		}
        	}
        }
        return result;
    }

    private static MirrorOptions createMirrorOptions() {
        MirrorOptions options = new MirrorOptions();
        options.setFollowOnlyFilteredRequirements(false);
        options.setFollowStrictOnly(true);
        options.setIncludeFeatures(true);
        options.setIncludeNonGreedy(true);
        options.setIncludeOptional(true);
        options.setLatestVersionOnly(false);
        return options;
    }

}
