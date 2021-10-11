package org.jboss.tools.tycho.sitegenerator;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Map.Entry;
import java.util.Properties;

import org.apache.commons.io.FileUtils;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.archiver.zip.ZipArchiver;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.tycho.PackagingType;

/* deprecated @since JBT 4.5.1.AM2. Use generate-repository-facade instead. */
@Mojo(name="create-full-site", defaultPhase=LifecyclePhase.DEPLOY, requiresProject=true)
public class CreateFullSiteMojo extends AbstractMojo {

	public static final String FULL_SITE_FOLDER_NAME = "fullSite";

	@Parameter(property = "project", required = true, readonly = true)
	private MavenProject project;

	public void execute() throws MojoExecutionException, MojoFailureException {
		if (! PackagingType.TYPE_ECLIPSE_REPOSITORY.equals(project.getPackaging())) {
			getLog().debug("Skipped for projects which have packaging != " + PackagingType.TYPE_ECLIPSE_REPOSITORY);
			return;
		}
		File fullSite = new File(project.getBuild().getDirectory(), FULL_SITE_FOLDER_NAME);
		fullSite.mkdirs();
		File all = new File(fullSite, "all");
		all.mkdir();
		File repo = new File(all, "repo");
		repo.mkdir();
		File logs = new File(fullSite, "logs");
		logs.mkdir();
		File repository = new File(project.getBuild().getDirectory(), "repository");
		try {
			FileUtils.copyDirectory(repository, new File(fullSite, "all/repo"));
		} catch (IOException e) {
			throw new MojoFailureException("Could not copy p2 repository", e);
		}

		try {
			createRevisionFile(logs);
		} catch (FileNotFoundException ex) {
			getLog().error("Could not generate revision file. No Git repository found.");
		} catch (IOException ex) {
			throw new MojoFailureException("Could not generate revision file", ex);
		}

		try {
			createBuildProperties(logs);
		} catch (IOException ex) {
			throw new MojoFailureException("Could not generate properties file", ex);
		}

		ZipArchiver archiver = new ZipArchiver();
		archiver.setDestFile(new File(all, "repository.zip"));
		archiver.addDirectory(repository);
		try {
			archiver.createArchive();
		} catch (IOException ex) {
			throw new MojoFailureException("Could not create zip for p2 repository", ex);
		}

	}

	/**
	 * @param logs
	 * @throws IOException
	 * @throws FileNotFoundException
	 */
	private void createRevisionFile(File logs) throws IOException, FileNotFoundException {
		File repoRoot = logs;
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
		Properties revProperties = new Properties();
		Ref head = gitRepo.exactRef(Constants.HEAD);
		revProperties.put(Constants.HEAD, head.getObjectId().getName());
		for (Entry<String, Ref> entry : gitRepo.getAllRefs().entrySet()) {
			if (entry.getKey().startsWith(Constants.R_REMOTES) && entry.getValue().getObjectId().getName().equals(head.getObjectId().getName())) {
				int lastSlashIndex = entry.getKey().lastIndexOf('/');
				String remoteName = entry.getKey().substring(Constants.R_REMOTES.length(), lastSlashIndex);
				String remoteUrl = gitRepo.getConfig().getString("remote", remoteName, "url");
				String branchName = entry.getKey().substring(lastSlashIndex + 1);
				revProperties.put(remoteUrl + ":" + branchName,
						entry.getValue().getObjectId().getName());
			}
		}
		File gitRevisionFile = new File(logs, "GIT_REVISION.txt");
		gitRevisionFile.createNewFile();
		FileOutputStream gitRevisionOut = new FileOutputStream(gitRevisionFile);
		revProperties.store(gitRevisionOut, null);
		gitRevisionOut.close();
	}

	private void createBuildProperties(File logFolder) throws IOException {
		StringBuilder content = new StringBuilder();
		addPropertyLine(content, "BUILD_ALIAS");
		addPropertyLine(content, "JOB_NAME");
		addPropertyLine(content, "BUILD_NUMBER");
		addPropertyLine(content, "BUILD_TIMESTAMP");
		addPropertyLine(content, "HUDSON_SLAVE");
		addPropertyLine(content, "RELEASE");
		addPropertyLine(content, "ZIPSUFFIX");
		addPropertyLine(content, "TARGET_PLATFORM_VERSION");
		addPropertyLine(content, "TARGET_PLATFORM_VERSION_MAXIMUM");
		addPropertyLine(content, "os.name");
		addPropertyLine(content, "os.version");
		addPropertyLine(content, "os.arch");
		addPropertyLine(content, "java.vendor");
		addPropertyLine(content, "java.version");

		File targetFile = new File(logFolder, "build.properties");
		targetFile.createNewFile();
		FileUtils.writeStringToFile(targetFile, content.toString());
	}

	private void addPropertyLine(StringBuilder target, String propertyName) {
		target.append(propertyName);
		target.append('=');
		target.append(System.getProperty(propertyName));
		target.append(System.lineSeparator());
	}

}
