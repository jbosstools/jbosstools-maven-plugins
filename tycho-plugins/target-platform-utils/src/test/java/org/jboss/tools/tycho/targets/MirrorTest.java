/**
 * Copyright (c) 2017 Red Hat, Inc.
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

import org.apache.maven.it.VerificationException;
import org.apache.maven.it.Verifier;
import org.codehaus.plexus.PlexusTestCase;
import org.junit.Assert;
import org.junit.Test;

public class MirrorTest extends PlexusTestCase {

	private File mirrorTarget(String targetFile) throws VerificationException {
		File testDir = new File(getBasedir(), "target/runs/test-" + targetFile);
		testDir.mkdirs();
		Verifier verifier = new Verifier(testDir.getAbsolutePath());
		verifier.setAutoclean(false);
		verifier.getSystemProperties().setProperty("targetDefinition",
				getBasedir() + "/src/test/resources/" + targetFile + ".target");
		verifier.executeGoal(
				"org.jboss.tools.tycho-plugins:target-platform-utils:1.0.1-SNAPSHOT:mirror-target-to-repo");
		verifier.verify(true);
		File pluginFolder = new File(testDir, "${project.basedir}/target/" + targetFile + ".target.repo/plugins");
		return pluginFolder;
	}

	@Test
	public void testSpecifiedOlderVersionSlicer() throws Exception {
		File pluginFolder = mirrorTarget("olderVersionSlicer");
		Assert.assertFalse(new File(pluginFolder, "org.apache.commons.codec_1.9.0.v20170208-1614.jar").exists());
		Assert.assertTrue(new File(pluginFolder, "org.apache.commons.codec_1.6.0.v201305230611.jar").exists());
	}

	@Test
	public void test2VersionSlicer() throws Exception {
		File pluginFolder = mirrorTarget("twoVersionsSlicer");
		Assert.assertTrue(new File(pluginFolder, "org.apache.commons.codec_1.6.0.v201305230611.jar").exists());
		Assert.assertTrue(new File(pluginFolder, "org.apache.commons.codec_1.9.0.v20170208-1614.jar").exists());
	}

	@Test
	public void test2VersionsPlanner() throws Exception {
		File pluginFolder = mirrorTarget("twoVersionsPlanner");
		Assert.assertTrue(new File(pluginFolder, "org.apache.commons.codec_1.6.0.v201305230611.jar").exists());
		Assert.assertTrue(new File(pluginFolder, "org.apache.commons.codec_1.9.0.v20170208-1614.jar").exists());
	}
}
