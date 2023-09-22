package org.jboss.tools.tycho.sitegenerator;

import static org.mockito.Mockito.mock;

import java.io.File;
import java.util.List;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;
import org.eclipse.tycho.testing.AbstractTychoMojoTestCase;

public class GenerateRepositoryFacadeTest extends AbstractTychoMojoTestCase {

	private GenerateRepositoryFacadeMojo mojo;
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

	public void testGenerate() throws Exception {
		log = mock(Log.class);

		File basedir = getBasedir("projects/generate-repository-facade");
		List<MavenProject> projects = getSortedProjects(basedir);

		MavenProject project = projects.get(0);
		MavenSession session = newMavenSession(project, projects);
		mojo = (GenerateRepositoryFacadeMojo) lookupMojoWithDefaultConfiguration(project, session,
				"generate-repository-facade");

		assertNotNull(mojo);
		setVariableValueToObject(mojo, "project", project);
		setVariableValueToObject(mojo, "session", session);

		mojo.setLog(log);
		mojo.execute();
		{
			File buildinfo = new File(basedir, "target/buildinfo/buildinfo.json");
			assertNotNull(buildinfo);
			assertTrue(buildinfo.exists());
			assertTrue(buildinfo.isFile());
		}
		{
			File buildinfo = new File(basedir, "target/repository/buildinfo.json");
			assertNotNull(buildinfo);
			assertTrue(buildinfo.exists());
			assertTrue(buildinfo.isFile());
			File index = new File(basedir, "target/repository/index.html");
			assertNotNull(index);
			assertTrue(index.exists());
			assertTrue(index.isFile());
			File css = new File(basedir, "target/repository/web/site.css");
			assertNotNull(css);
			assertTrue(css.exists());
			assertTrue(css.isFile());
		}
	}
}