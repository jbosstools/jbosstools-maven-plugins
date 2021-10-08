package org.jboss.tools.tycho.sitegenerator;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;

import org.apache.commons.io.IOUtils;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.tycho.PackagingType;
import org.jboss.dmr.ModelNode;

@Mojo(name="publish-build", requiresProject=true)
public class BuildPublisherMojo extends AbstractMojo {

	@Parameter(property="project", required=true, readonly=true)
	private MavenProject project;

	@Parameter
	private boolean targetDestiniation;

	@Parameter(name="forcePublish", property="org.jboss.tools.releng.publish.force")
	private boolean forcePublish;

	@Parameter
	private boolean publishIfGitChanges;

	/**
	 * Will publish if
	 */
	@Parameter
	private boolean publishIfp2RepoChanges;

	/**
	 * In case publishIfp2RepoChanges=true, which repository to compare to
	 */
	@Parameter
	private String baselineRepo;

	public void execute() throws MojoExecutionException, MojoFailureException {
		if (!PackagingType.TYPE_ECLIPSE_REPOSITORY.equals(this.project.getPackaging())) {
			getLog().info("Ignore artifact with packaging != " + PackagingType.TYPE_ECLIPSE_REPOSITORY);
			return;
		}

		/* deprecated @since JBT 4.5.1.AM2. Use generate-repository-facade instead. */
		File fullSite = new File(this.project.getBuild().getOutputDirectory(), CreateFullSiteMojo.FULL_SITE_FOLDER_NAME);
		if (!fullSite.isDirectory()) {
			throw new MojoFailureException("Expected to find a site to publish in " + fullSite);
		}

		boolean publish = this.forcePublish;
		if (!publish && this.publishIfGitChanges) {
			URL buildInfoURL = null;
			ModelNode buildInfo = null;
			String previousCommitId = null;
			InputStream in = null;
			try {
				buildInfoURL = new URL(this.baselineRepo + "/all/repo/" + GenerateRepositoryFacadeMojo.BUILDINFO_JSON);
				in = buildInfoURL.openStream();
				buildInfo = ModelNode.fromJSONStream(in);
				previousCommitId = buildInfo.get("revision").get("HEAD").asString();

				File repoRoot = project.getBasedir();
				while (! new File(repoRoot, ".git").isDirectory()) {
					repoRoot = repoRoot.getParentFile();
				}
				if (repoRoot == null) {
					throw new FileNotFoundException("Could not find a Git repository (with a .git child folder)");
				}
				FileRepositoryBuilder builder = new FileRepositoryBuilder();
				Repository gitRepo = builder.setGitDir(new File(repoRoot, ".git"))
				  .readEnvironment() // scan environment GIT_* variables
				  .findGitDir() // scan up the file system tree
				  .build();
				Ref head = gitRepo.exactRef(Constants.HEAD);

				publish = !head.getObjectId().getName().equals(previousCommitId);
			} catch (MalformedURLException ex) {
				throw new MojoFailureException("Incorrect URL for " + GenerateRepositoryFacadeMojo.BUILDINFO_JSON, ex);
			} catch (IOException ex) {
				getLog().warn("Could not access " + buildInfoURL.toString(), ex);
			} finally {
				IOUtils.closeQuietly(in);
			}
		}
		if (!publish && this.publishIfp2RepoChanges) {
			// TODO publish = containDifferentArtifacts
		}

		if (publish) {
			// TODO RSync
		}
	}

}
