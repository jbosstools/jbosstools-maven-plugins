package org.jboss.tools.releng;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.jar.Manifest;

import javax.inject.Inject;
import javax.inject.Named;

import org.apache.maven.enforcer.rule.api.AbstractEnforcerRule;
import org.apache.maven.enforcer.rule.api.EnforcerLogger;
import org.apache.maven.enforcer.rule.api.EnforcerRuleException;
import org.apache.maven.project.MavenProject;

/**
 * @author <a href="mailto:nboldt@redhat.com">Nick Boldt</a> Based on sample
 *         code from <a href="mailto:brianf@apache.org">Brian Fox</a>
 */
@Named("coreUIDependency")
public class CoreUIDependency extends AbstractEnforcerRule {
	private static final String CACHE_PREFIX = "org.jboss.tools.releng.CoreUIDependency=";
	private File pluginId;

	private String joinString = "\n     > ";

	@Inject
	private MavenProject project;

	@Override
	public void execute() throws EnforcerRuleException {
		File pluginFolder = project.getBasedir();
		pluginId = pluginFolder;

		File manifest = new File(new File(pluginFolder, "META-INF"), "MANIFEST.MF");
		if (manifest.exists()) {
			ManifestModel model = createModel(pluginFolder, manifest);
			if (!model.isCore() || model.isTestPlugin()) {
				return;
			}

			Set<String> ui = model.getAllUIDeps();
			String[] uiDeps = ui.toArray(new String[ui.size()]);
			if (uiDeps.length > 0) {
				String msg = "[CoreUIDependency] " + project.getArtifactId()
						+ " is a Core plugin, but depends on these UI plugins directly or transitively:" + joinString
						+ String.join(joinString, uiDeps);
				throw new EnforcerRuleException(msg);
			}
		}
	}

	private ManifestModel createModel(File pluginFile, File manifest) {
		return createModel1(pluginFile, manifest, null);
	}

	private ManifestModel createModel1(File pluginFile, File manifest, ManifestModel parent) {

		ManifestModel model = findAllDependencies(pluginFile, manifest);
		if (parent != null) {
			model.setParent(parent);
		}
		if (model.isCoreOrAncestorIsCore() && !model.isTestPlugin()) {
			model.getOtherBundles(); // Prime the folders to search
			getLog().debug("[CoreUIDependency] Finding UI Deps");
			findUIDependencies(model, true);
			getLog().debug("[CoreUIDependency] Filling transitive Deps");
			fillTransitiveModel(model);
		}
		getLog().debug("[CoreUIDependency] Returning model");
		return model;
	}

	private void fillTransitiveModel(ManifestModel mm) {
		Iterator<String> it = mm.deps.iterator();
		while (it.hasNext()) {
			String dep = it.next();
			ArrayList<File> possibleLoc = mm.getOtherBundles().get(dep);
			if (possibleLoc != null) {
				int size = possibleLoc.size();
				getLog().debug("[CoreUIDependency] Dependency from " + mm.bundleId + " to " + dep + " has resultCount="
						+ size);
				if (size > 0) {
					File loc = possibleLoc.get(0); // Too lazy, just pretend folder names are unique here
					File manifest = new File(new File(loc, "META-INF"), "MANIFEST.MF");
					ManifestModel nested = createModel1(loc, manifest, mm);
					mm.addNestedModel(nested);
				}
			}
		}
	}

	// TODO: add support for Import-Package as well as Require-Bundle

	private ManifestModel findAllDependencies(File pluginFile, File manifest) {
		ArrayList<String> allDeps = new ArrayList<>();
		Manifest mf = null;
		String symbolicName = null;
		try (InputStream is = new FileInputStream(manifest)) {
			mf = new Manifest(is);
			String reqs = mf.getMainAttributes().getValue("Require-Bundle");
			symbolicName = mf.getMainAttributes().getValue("Bundle-SymbolicName");
			if (symbolicName.contains(";")) {
				symbolicName = symbolicName.substring(0, symbolicName.indexOf(";"));
			}
			if (reqs != null) {
				String[] deps = reqs.split(",");
				for (int i = 0; i < deps.length; i++) {
					String[] segments = deps[i].split(";");
					if (segments != null && segments.length > 0) {
						String dep = segments[0];
						allDeps.add(dep);
					}
				}
			}
		} catch (FileNotFoundException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		ManifestModel mm = new ManifestModel(pluginFile, getLog());
		mm.setBundleId(symbolicName);
		mm.deps = allDeps;
		return mm;
	}

	private static class ManifestModel {
		private String bundleId;
		private List<String> deps;
		private List<String> uiDirectDeps;
		private HashMap<String, ManifestModel> transitiveDeps;
		private HashMap<String, ArrayList<File>> otherBundles;
		private ManifestModel parent = null;
		private File pluginFolder;
		private EnforcerLogger log;

		public ManifestModel(File pluginFolder, EnforcerLogger log) {
			deps = new ArrayList<>();
			uiDirectDeps = new ArrayList<>();
			transitiveDeps = new HashMap<>();
			otherBundles = null;
			this.pluginFolder = pluginFolder;
			this.log = log;
		}

		public void setBundleId(String bid) {
			bundleId = bid;
		}

		public boolean isCore() {
			if (bundleId.contains(".core") || bundleId.endsWith(".core")) {
				return true;
			}
			return false;
		}

		public boolean isTestPlugin() {
			return bundleId.endsWith(".test");
		}

		public boolean isCoreOrAncestorIsCore() {
			return isCore() || (parent != null && parent.isCore());
		}

		public void setUIDirectDependencies(List<String> l) {
			uiDirectDeps = l;
		}

		public void addNestedModel(ManifestModel mm2) {
			transitiveDeps.put(mm2.bundleId, mm2);
		}

		public HashMap<String, ArrayList<File>> getOtherBundles() {
			if (otherBundles != null) {
				return otherBundles;
			}
			if (parent != null) {
				return parent.getOtherBundles();
			}
			otherBundles = loadOtherBundleLocations();
			return otherBundles;
		}

		public void setParent(ManifestModel p) {
			parent = p;
		}

		public Set<String> getAllUIDeps() {
			log.debug("[CoreUIDependency] Near the end, checking who has UI deps");
			log.debug("[CoreUIDependency] Direct ui deps: " + uiDirectDeps.size());
			Set<String> ret = new HashSet<>();
			ret.addAll(uiDirectDeps);
			Iterator<String> it = transitiveDeps.keySet().iterator();
			while (it.hasNext()) {
				ManifestModel mm2 = transitiveDeps.get(it.next());
				log.debug("[CoreUIDependency] " + mm2.bundleId + " has ui deps of: " + mm2.getAllUIDeps().size());
				ret.addAll(mm2.getAllUIDeps());
			}
			return ret;
		}

		private HashMap<String, ArrayList<File>> loadOtherBundleLocations() {
			HashMap<String, ArrayList<File>> ret = new HashMap<>();
			cacheFolders(pluginFolder, ret, 3, 3);
			return ret;
		}

		private void cacheFolders(File pluginFile, HashMap<String, ArrayList<File>> map, int maxParents, int maxDepth) {
			ArrayList<File> l = map.get(pluginFile.getName());
			if (l == null) {
				l = new ArrayList<>();
				map.put(pluginFile.getName(), l);
			}
			if (!l.contains(pluginFile)) {
				if (!pluginFile.isFile() && new File(pluginFile, "pom.xml").exists()) {
					l.add(pluginFile);
				}
			}
			// Only search subfolders if this one has a pom.xml
			if (maxDepth > 0 && new File(pluginFile, "pom.xml").exists()) {
				// Search children
				File[] children = pluginFile.listFiles();
				for (int i = 0; i < children.length; i++) {
					cacheFolders(children[i], map, 0, maxDepth - 1);
				}
			}

			if (maxParents > 0 && new File(pluginFile.getParentFile(), "pom.xml").exists()) {
				// Go up one parent if parent folder has a pom.xml, repeat
				cacheFolders(pluginFile.getParentFile(), map, maxParents - 1, maxDepth);
			}
		}

	}

	private void findUIDependencies(ManifestModel mm, boolean verbose) {
		String symbolicName = mm.bundleId;
		List<String> deps = new ArrayList<>(mm.deps);
		String[] asArr = deps.toArray(new String[deps.size()]);
		if (verbose) {
			getLog().debug("[CoreUIDependency] " + symbolicName + " requires the following units: " + joinString
					+ String.join(joinString, asArr));
		}
		Iterator<String> it = deps.iterator();
		while (it.hasNext()) {
			String dep = it.next();
			if (!dep.contains(".ui.") && !dep.endsWith(".ui")) {
				it.remove();
			}
		}
		mm.setUIDirectDependencies(deps);
	}

	/**
	 * If your rule is cacheable, you must return a unique id when parameters or
	 * conditions change that would cause the result to be different. Multiple
	 * cached results are stored based on their id.
	 * 
	 * The easiest way to do this is to return a hash computed from the values of
	 * your parameters.
	 * 
	 * If your rule is not cacheable, then the result here is not important, you may
	 * return anything.
	 */
	@Override
	public String getCacheId() {
		// no hash on boolean...only parameter so no hash is needed.
		return CACHE_PREFIX + pluginId;
	}

}
