package org.jboss.tools.tycho.sitegenerator;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashSet;
import java.util.List;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.Set;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
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
import org.json.JSONArray;
import org.json.JSONObject;

@Mojo(name="create-full-site", defaultPhase=LifecyclePhase.DEPLOY, requiresProject=true)
public class CreateFullSiteMojo extends AbstractMojo {

	public static final String FULL_SITE_FOLDER_NAME = "fullSite";
	private static final String UPSTREAM_ELEMENT = "upstream";
	public static final String BUILDINFO_JSON = "buildInfo.json";
	@Parameter(property = "project", required = true, readonly = true)
	private MavenProject project;
	
	public void execute() throws MojoExecutionException, MojoFailureException {
		if (! PackagingType.TYPE_ECLIPSE_REPOSITORY.equals(project.getPackaging())) {
			throw new MojoExecutionException("expected packaging=" + PackagingType.TYPE_ECLIPSE_REPOSITORY);
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
		
		
		ZipArchiver archiver = new ZipArchiver();
		archiver.setDestFile(new File(all, "repository.zip"));
		archiver.addDirectory(repository);
		try {
			archiver.createArchive();
		} catch (IOException ex) {
			throw new MojoFailureException("Could not create zip for p2 repository", ex);
		}
		
		JSONObject jsonProperties = new JSONObject();
		jsonProperties.put("timestamp", System.currentTimeMillis()); // TODO get it from build metadata
		
		try {
			jsonProperties.put("revision", createRevisionFile(logs));
		} catch (Exception ex) {
			throw new MojoFailureException("Could not generate revision file", ex);
		}
		
		try {
			jsonProperties.put("properties", createBuildProperties(logs));
		} catch (Exception ex) {
			throw new MojoFailureException("Could not generate properties file", ex);
		}
		
		try {
			jsonProperties.put(UPSTREAM_ELEMENT, aggregateUpstreamMetadata());
		} catch (Exception ex) {
			throw new MojoExecutionException("Could not get upstream metadata");
		}

		File jsonFile = new File(repo, BUILDINFO_JSON);
		try {
			FileUtils.writeStringToFile(jsonFile, jsonProperties.toString(4));
		} catch (Exception ex) {
			throw new MojoFailureException("Could not generate properties file", ex);
		}
	}

	/**
	 * @param logs
	 * @throws IOException
	 * @throws FileNotFoundException
	 */
	private JSONObject createRevisionFile(File logs) throws IOException, FileNotFoundException {
		JSONObject res = new JSONObject();
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
		Ref head = gitRepo.getRef(Constants.HEAD);
		res.put("HEAD", head.getObjectId().getName());
		JSONArray knownReferences = new JSONArray();
		revProperties.put(Constants.HEAD, head.getObjectId().getName());
		for (Entry<String, Ref> entry : gitRepo.getAllRefs().entrySet()) {
			if (entry.getKey().startsWith(Constants.R_REMOTES) && entry.getValue().getObjectId().getName().equals(head.getObjectId().getName())) {
				JSONObject reference = new JSONObject();
				int lastSlashIndex = entry.getKey().lastIndexOf('/');
				String remoteName = entry.getKey().substring(Constants.R_REMOTES.length(), lastSlashIndex);
				String remoteUrl = gitRepo.getConfig().getString("remote", remoteName, "url");
				String branchName = entry.getKey().substring(lastSlashIndex + 1);
				reference.put("name", remoteName);
				reference.put("url", remoteUrl);
				reference.put("ref", branchName);
				knownReferences.put(reference);
				revProperties.put(remoteUrl + ":" + branchName,
						entry.getValue().getObjectId().getName());
			}
		}
		res.put("knownReferences", knownReferences);
		File gitRevisionFile = new File(logs, "GIT_REVISION.txt");
		gitRevisionFile.createNewFile();
		FileOutputStream gitRevisionOut = new FileOutputStream(gitRevisionFile);
		revProperties.store(gitRevisionOut, null);
		gitRevisionOut.close();
		return res;
	}
	
	private JSONObject createBuildProperties(File logFolder) throws IOException {
		JSONObject res = new JSONObject();
		StringBuilder content = new StringBuilder();
		addPropertyLine(content, res, "BUILD_ALIAS");
		addPropertyLine(content, res, "JOB_NAME");
		addPropertyLine(content, res, "BUILD_NUMBER");
		addPropertyLine(content, res, "BUILD_ID");
		addPropertyLine(content, res, "HUDSON_SLAVE");
		addPropertyLine(content, res, "RELEASE"); 
		addPropertyLine(content, res, "ZIPSUFFIX");
		addPropertyLine(content, res, "TARGET_PLATFORM_VERSION");
		addPropertyLine(content, res, "TARGET_PLATFORM_VERSION_MAXIMUM");
		addPropertyLine(content, res, "os.name");
		addPropertyLine(content, res, "os.version");
		addPropertyLine(content, res, "os.arch");
		addPropertyLine(content, res, "java.vendor");
		addPropertyLine(content, res, "java.version");
		
		
		File targetFile = new File(logFolder, "build.properties");
		targetFile.createNewFile();
		FileUtils.writeStringToFile(targetFile, content.toString());
		return res;
	}
	
	private void addPropertyLine(StringBuilder target, JSONObject json, String propertyName) {
		json.put(propertyName, System.getProperty(propertyName));
		target.append(propertyName);
		target.append('=');
		target.append(System.getProperty(propertyName));
		target.append(System.lineSeparator());
	}
	
	private JSONObject aggregateUpstreamMetadata() {
		List<?> repos = this.project.getRepositories();
		JSONObject res = new JSONObject();
		for (Object item : repos) {
			org.apache.maven.model.Repository repo = (org.apache.maven.model.Repository)item;
			if ("p2".equals(repo.getLayout())) {
				String supposedBuildInfoURL = repo.getUrl();
				if (!supposedBuildInfoURL.endsWith("/")) {
					supposedBuildInfoURL += "/";
				}
				supposedBuildInfoURL += BUILDINFO_JSON;
				URL upstreamBuildInfoURL = null;
				try {
					upstreamBuildInfoURL = new URL(supposedBuildInfoURL);
					String content = IOUtils.toString(upstreamBuildInfoURL.openStream());
					JSONObject obj = new JSONObject(content);
					obj.remove(UPSTREAM_ELEMENT); // remove upstream of upstream as it would make a HUGE file
					res.put(repo.getUrl(), obj);
				} catch (MalformedURLException ex) {
					// Only log those
					getLog().error("Incorrect URL " + upstreamBuildInfoURL);
				} catch (IOException ex) {
					getLog().error("Could not read build info at " + upstreamBuildInfoURL);
				}
			}
		}
		return res;
	}

}
