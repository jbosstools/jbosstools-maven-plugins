package org.jboss.tools.tycho.sitegenerator;

import static org.junit.Assert.*;

import java.io.IOException;
import java.util.Properties;

import org.junit.Test;

public class GitPropertiesTest {

    Properties properties = new Properties();

	@Test
	public void testCommitId() throws IOException {
		properties.load(getClass().getClassLoader().getResourceAsStream("git.properties"));
	  	assertNotNull(properties.get("git.commit.id").toString());
	  	assertEquals("b34a518cdcad80a7d750b714ce2fec18f029b489".length(),properties.get("git.commit.id").toString().length());
	}

	@Test
	public void testOriginUrl() throws IOException {
		properties.load(getClass().getClassLoader().getResourceAsStream("git.properties"));
	  	assertNotNull(properties.get("git.remote.origin.url").toString());
	  	assertEquals("git@github.com:jbosstools/jbosstools-maven-plugins.git", properties.get("git.remote.origin.url").toString());
	}
}
