package org.jboss.tools.releng;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.Properties;

import javax.inject.Inject;
import javax.inject.Named;

import org.apache.maven.enforcer.rule.api.AbstractEnforcerRule;
import org.apache.maven.enforcer.rule.api.EnforcerRuleException;
import org.apache.maven.project.MavenProject;

/**
 * @author <a href="mailto:nboldt@redhat.com">Nick Boldt</a>
 */

@Named("foundationCoreVersionMatchesParentPom")
public class FoundationCoreVersionMatchesParentPom extends AbstractEnforcerRule {
	private String parentPomVersionBase = null;
	private String BUILD_ALIAS = null;
	private String defaultVersion = null; // found in currentVersionPropertiesFile
	private String defaultVersionBase = null; // found in currentVersionPropertiesFile, then remove .Final suffix

	/**
	 * Simple params
	 */
	private String currentVersionProperties = null;
	private String requiredQualifier = ".Final"; // or .GA

	@Inject
	private MavenProject project;

	@Override
	public void execute() throws EnforcerRuleException {

		String basedir = project.getBasedir().toString();

		Properties projProps = project.getProperties();
		Enumeration<?> e = projProps.propertyNames();
		while (e.hasMoreElements()) {
			String key = (String) e.nextElement();
			// fetch from parent pom if not passed into the rule config
			if (parentPomVersionBase == null && key.equals("parsedVersion.osgiVersion")) {
				parentPomVersionBase = projProps.getProperty(key);
			} else if (key.equals("BUILD_ALIAS")) {
				BUILD_ALIAS = projProps.getProperty(key);
			}
		}
		parentPomVersionBase = parentPomVersionBase.replaceAll(".SNAPSHOT", "");
		parentPomVersionBase = parentPomVersionBase.replaceAll(".(((AM|Alpha|Beta|CR)[0-9]+)|Final|GA)", "");
		getLog().debug("Got parentPomVersion     = " + parentPomVersionBase + "." + BUILD_ALIAS);
		getLog().debug("Got parentPomVersionBase = " + parentPomVersionBase);

		getLog().debug("Retrieved Basedir: " + basedir);
		getLog().debug("Retrieved Project: " + project);

		Properties fileProps = new Properties();
		try (InputStream currentVersionPropertiesFIS = new FileInputStream(basedir + "/" + currentVersionProperties)) {
			try {
				fileProps.load(currentVersionPropertiesFIS);

				defaultVersion = fileProps.getProperty("default.version");
				defaultVersionBase = defaultVersion.replaceAll(requiredQualifier, "");
			} catch (IOException ex) {
				ex.printStackTrace();
			} finally {
				if (currentVersionPropertiesFIS != null) {
					try {
						currentVersionPropertiesFIS.close();
					} catch (IOException ex) {
						ex.printStackTrace();
					}
				}
			}
		} catch (IOException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		}
		getLog().debug("Got default.version      = " + defaultVersion);
		getLog().debug("Got default.version base = " + defaultVersionBase);

		// want to match 4.4.3 == 4.4.3 or 4.4.3.AM2 = 4.4.3.AM2
		if (!defaultVersionBase.equals(parentPomVersionBase)
				&& !defaultVersion.equals(parentPomVersionBase + "." + BUILD_ALIAS)) {
			throw new EnforcerRuleException("\n[ERROR] Invalid value of default.version = " + defaultVersion
					+ " for parent = " + parentPomVersionBase + "." + BUILD_ALIAS + "-SNAPSHOT !"
					+ "\n\nMust set default.version = " + parentPomVersionBase + requiredQualifier + " " + "(or = "
					+ parentPomVersionBase + "." + BUILD_ALIAS + ") " + "in this file:\n\n" + basedir + "/"
					+ currentVersionProperties);
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
		return "" + parentPomVersionBase + "::" + BUILD_ALIAS + "::" + defaultVersion + "::" + currentVersionProperties;
	}

}
