package org.jboss.tools.releng;

import java.util.Enumeration;
import java.util.Properties;

import javax.inject.Inject;
import javax.inject.Named;

import org.apache.maven.enforcer.rule.api.AbstractEnforcerRule;
import org.apache.maven.enforcer.rule.api.EnforcerRuleException;
import org.apache.maven.project.MavenProject;

/**
 * @author <a href="mailto:nboldt@redhat.com">Nick Boldt</a> Based on sample
 *         code from <a href="mailto:brianf@apache.org">Brian Fox</a>
 */
@Named("noSnapshotsAllowed")
public class NoSnapshotsAllowed extends AbstractEnforcerRule {
	private String snapshotKey = null;
	private String buildAlias = null;

	/**
	 * Simple params
	 */
	private String buildAliasSearch = "Final|GA";
	private String SNAPSHOT = "SNAPSHOT";
	private String includePattern = "";
	private String excludePattern = "";

	@Inject
	private MavenProject project;

	@Override
	public void execute() throws EnforcerRuleException {

		String target = project.getBuild().getDirectory();
		String artifactId = project.getArtifactId();

		// defaults if not set
		if (includePattern == null || includePattern.equals("")) {
			includePattern = ".*";
		}
		if (excludePattern == null || excludePattern.equals("")) {
			excludePattern = "";
		}

		getLog().debug("Search for properties matching " + SNAPSHOT + "...");

		Properties projProps = project.getProperties();
		Enumeration<?> e = projProps.propertyNames();
		while (e.hasMoreElements()) {
			String key = (String) e.nextElement();
			// fetch from parent pom if not passed into the rule config
			if (buildAlias == null && key.equals("BUILD_ALIAS")) {
				buildAlias = projProps.getProperty(key);
				if (buildAlias.matches(buildAliasSearch)) {
					getLog().info(
							"Found buildAlias = " + buildAlias + " (for buildAliasSearch = " + buildAliasSearch + ")");
				} else {
					getLog().debug(
							"Found buildAlias = " + buildAlias + " (for buildAliasSearch = " + buildAliasSearch + ")");
				}
			} else if (key.matches(includePattern) && (excludePattern.equals("") || !key.matches(excludePattern))
					&& projProps.getProperty(key).indexOf(SNAPSHOT) > -1) {
				getLog().error("Found property " + key + " = " + projProps.getProperty(key));
				snapshotKey = key;
			}
		}

		getLog().debug("Retrieved Target Folder: " + target);
		getLog().debug("Retrieved ArtifactId: " + artifactId);
		getLog().debug("Retrieved Project: " + project);
		getLog().debug("Retrieved Project Version: " + project.getVersion());

		if (buildAlias.matches(buildAliasSearch) && snapshotKey != null) {
			throw new EnforcerRuleException("\nWhen buildAlias (" + buildAlias + ") matches /" + buildAliasSearch
					+ "/, cannot include " + SNAPSHOT + " dependencies.\n");
		}
	}

	/**
	 * If your rule is cacheable, you must return a unique id when parameters or
	 * conditions change that would cause the result to be different. Multiple
	 * cached results are stored based on their id.
	 * 
	 * The easiest way to do this is to return a hash computed from the values of
	 * your parameters.
	 * 
	 * If your rule is not cacheable, then the result here is not important, you may
	 * return anything.
	 */
	@Override
	public String getCacheId() {
		// no hash on boolean...only parameter so no hash is needed.
		return "" + snapshotKey;
	}

}
