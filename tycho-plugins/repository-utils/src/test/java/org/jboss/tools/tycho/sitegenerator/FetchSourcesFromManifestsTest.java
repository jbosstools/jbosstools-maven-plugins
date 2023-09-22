package org.jboss.tools.tycho.sitegenerator;

import static org.mockito.Mockito.mock;

import java.io.File;
import java.util.List;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;
import org.eclipse.tycho.testing.AbstractTychoMojoTestCase;

public class FetchSourcesFromManifestsTest extends AbstractTychoMojoTestCase {

	private FetchSourcesFromManifests mojo;
	private Log log;

	@Override
	protected void setUp() throws Exception {
		// required
		super.setUp();
	}

	@Override
	protected void tearDown() throws Exception {
		// required
		super.tearDown();
	}

	public void testFetch() throws Exception {
		log = mock(Log.class);

		File basedir = getBasedir("projects/fetch-sources-from-manifests");
		List<MavenProject> projects = getSortedProjects(basedir);

		MavenProject project = projects.get(0);
		MavenSession session = newMavenSession(project, projects);
		mojo = (FetchSourcesFromManifests) lookupMojoWithDefaultConfiguration(project, session,
				"fetch-sources-from-manifests");

		assertNotNull(mojo);
		setVariableValueToObject(mojo, "project", project);
		mojo.setLog(log);
		mojo.execute();

		File result = new File(basedir, "target/jbosstools-src.zip");
		assertNotNull(result);
		assertTrue(result.exists());
		assertTrue(result.isFile());

	}
}