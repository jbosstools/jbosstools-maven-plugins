/**
 * Copyright (c) 2013, 2014 Red Hat, Inc.
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

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.resolver.ArtifactResolutionRequest;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.apache.maven.repository.RepositorySystem;

public class TargetArtifact {

	private String groupId;
	private String artifactId;
	private String version;
	private String classifier;

	public String getGroupId() {
		return this.groupId;
	}
	public String getArtifactId() {
		return this.artifactId;
	}
	public String getVersion() {
		return this.version;
	}
    public String getClassifier() {
        return this.classifier;
    }

	public boolean isCorrectlySet() {
		return this.groupId != null && this.artifactId != null && this.version != null;
	}

	@Override
	public String toString() {
		return this.groupId + ":" + this.artifactId + ":" + this.classifier + ":" + this.version;
	}

	public File getFile(RepositorySystem repositorySystem, MavenSession session, MavenProject project) throws MojoExecutionException {
		if (!isCorrectlySet()) {
    		throw new MojoExecutionException("'sourceTargetArtifact' must define groupId, artifactId and version");
    	}
		Artifact artifact = null;
		if (this.classifier == null) {
			artifact = repositorySystem.createArtifact(this.groupId, this.artifactId, this.version, "target");
		} else {
			artifact = repositorySystem.createArtifactWithClassifier(this.groupId, this.artifactId, this.version, "target", this.classifier);
		}
        ArtifactResolutionRequest request = new ArtifactResolutionRequest();
        request.setArtifact(artifact);
        request.setLocalRepository(session.getLocalRepository());
        request.setRemoteRepositories(project.getRemoteArtifactRepositories());
        repositorySystem.resolve(request);

        if (!artifact.isResolved()) {
            throw new MojoExecutionException("Could not resolve target platform specification artifact " + artifact);
        }

        return artifact.getFile();
	}
}
