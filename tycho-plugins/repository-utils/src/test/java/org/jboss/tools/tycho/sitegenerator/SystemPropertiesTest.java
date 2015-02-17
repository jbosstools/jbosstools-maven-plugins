package org.jboss.tools.tycho.sitegenerator;

import static org.junit.Assert.*;

import java.net.UnknownHostException;
import java.nio.file.Paths;

import org.junit.Test;

public class SystemPropertiesTest {

	@Test
	public void testHostName() {
		java.net.InetAddress localMachine;
		try {
			localMachine = java.net.InetAddress.getLocalHost();
			System.setProperty("HOSTNAME",localMachine.getHostName());
		} catch (UnknownHostException e) {
			e.printStackTrace();
		}
		assertNotNull(System.getProperty("HOSTNAME"));
	}

	@Test
	public void testDirs() {
		assertNotNull(System.getProperty("user.dir"));
		if (System.getProperty("WORKSPACE") == null || System.getProperty("WORKSPACE").equals(""))
		{
			System.setProperty("WORKSPACE",Paths.get("").toAbsolutePath().toString());
		}
		assertNotNull(System.getProperty("WORKSPACE"));
	}
}
