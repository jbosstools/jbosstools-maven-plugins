package org.jboss.tools.tycho.targets;

public class TargetArtifact {

	private String groupId;
	private String artifactId;
	private String version;

	public String getGroupId() {
		return this.groupId;
	}
	public String getArtifactId() {
		return this.artifactId;
	}
	public String getVersion() {
		return this.version;
	}

	public boolean isCorrectlySet() {
		return this.groupId != null && this.artifactId != null && this.version != null;
	}

	@Override
	public String toString() {
		return this.groupId + ":" + this.artifactId + ":" + this.version;
	}
}
