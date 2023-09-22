package org.jboss.tools.tycho.sitegenerator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import org.junit.Before;
import org.junit.Test;

public class GitPropertiesTest {

	Properties properties = new Properties();
	
	@Before
	public void loadProperties() {
		try (InputStream resourceAsStream = getClass().getClassLoader().getResourceAsStream("projects/fetch-sources-from-manifests/target/git.properties")) {
			properties.load(resourceAsStream);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	@Test
	public void testCommitId() {
		assertNotNull(properties.get("git.commit.id").toString());
		assertEquals("b34a518cdcad80a7d750b714ce2fec18f029b489".length(),
				properties.get("git.commit.id").toString().length());
	}

	@Test
	public void testOriginUrl() {
		// could be git@github.com:jbosstools/jbosstools... or
		// git://github.com/jbosstools/jbosstools...
		// could include .git suffix or not
		String projectURL = properties.get("git.remote.origin.url").toString();
		String projectName = projectURL.replaceAll(".+/([^/]+)", "$1");
		projectName = projectName.replaceAll(".git", "");
		assertEquals("jbosstools-maven-plugins", projectName);
	}
}
