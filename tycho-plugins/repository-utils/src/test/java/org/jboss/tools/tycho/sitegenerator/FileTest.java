package org.jboss.tools.tycho.sitegenerator;

import static org.junit.Assert.*;

import org.junit.Test;

public class FileTest {

	@Test
	public void testReplace() {
		assertEquals("replaced", new String("${site.contents}").replace("${site.contents}", "replaced"));
	}

}
