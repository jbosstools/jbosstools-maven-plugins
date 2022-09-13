package org.jboss.tools.releng;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.jar.Manifest;

import org.apache.maven.enforcer.rule.api.EnforcerRule;
import org.apache.maven.enforcer.rule.api.EnforcerRuleException;
import org.apache.maven.enforcer.rule.api.EnforcerRuleHelper;
import org.apache.maven.plugin.logging.Log;
import org.codehaus.plexus.component.configurator.expression.ExpressionEvaluationException;

/**
 * @author <a href="mailto:nboldt@redhat.com">Nick Boldt</a>
 * Based on sample code from <a href="mailto:brianf@apache.org">Brian Fox</a>
 */
public class CoreUIDependency implements EnforcerRule {
	private static final String CACHE_PREFIX = "org.jboss.tools.releng.CoreUIDependency=";
	private File pluginId;

    private String joinString = "\n     > ";

	@Override
	public void execute( EnforcerRuleHelper helper ) throws EnforcerRuleException {
		Log log = helper.getLog();
		try {
			// get the various expressions out of the helper.
//			MavenProject project = (MavenProject) helper.evaluate( "${project}" );
//			Properties projProps = project.getProperties();

			File pluginFolder = (File)helper.evaluate("${project.basedir}");
			pluginId = pluginFolder;
			
			File manifest = new File(new File(pluginFolder, "META-INF"), "MANIFEST.MF");
			if( manifest.exists()) {
				ManifestModel model = createModel(pluginFolder, manifest, log, false);
				if( !model.isCore() || model.isTestPlugin()) {
					return;
				}

				Set<String> ui = model.getAllUIDeps();
				String[] uiDeps = ui.toArray(new String[ui.size()]);
				if( uiDeps.length > 0 ) {
					String msg = "[CoreUIDependency] " + (String)helper.evaluate("${project.artifactId}") +
						" is a Core plugin, but depends on these UI plugins directly or transitively:" +
						joinString + String.join(joinString, uiDeps);
					//log.error(msg);
					throw new EnforcerRuleException(msg);
				}
			}
		} catch ( ExpressionEvaluationException e ) {
			String msg = "Unable to lookup an expression " + e.getLocalizedMessage();
			//log.error(msg);
			throw new EnforcerRuleException(msg, e);
		}
	}

	private ManifestModel createModel(File pluginFile, File manifest, Log log, boolean verbose) {
		return createModel(pluginFile, manifest, log, null, verbose);
	}

	private ManifestModel createModel(File pluginFile, File manifest, Log log, ManifestModel parent, boolean verbose) {

		ManifestModel model = findAllDependencies(pluginFile, manifest, log);
		if( parent != null ) {
			model.setParent(parent);
		}
		//log.info("Is module core? " + model.isCore());
		if( model.isCoreOrAncestorIsCore() && !model.isTestPlugin()) {
			model.getOtherBundles();  // Prime the folders to search
			log.debug("[CoreUIDependency] Finding UI Deps");
			findUIDependencies(model, log, true);
			log.debug("[CoreUIDependency] Filling transitive Deps");
			fillTransitiveModel(pluginFile, model, log);
		}
		log.debug("[CoreUIDependency] Returning model");
		return model;
	}

	private void fillTransitiveModel(File pluginFile, ManifestModel mm, Log log) {
		//log.info("Inside fillTransitiveModel");
		//log.info("deps size is " + mm.deps.size());

		Iterator<String> it = mm.deps.iterator();
		while(it.hasNext()) {
			String dep = it.next();
			ArrayList<File> possibleLoc = mm.getOtherBundles().get(dep);
			if( possibleLoc != null ) {
				int size = possibleLoc.size();
				log.debug("[CoreUIDependency] Dependency from " + mm.bundleId + " to " + dep + " has resultCount="+size);
				if( size > 0 ) {
					File loc = possibleLoc.get(0); // Too lazy, just pretend folder names are unique here
					File manifest = new File(new File(loc, "META-INF"), "MANIFEST.MF");
					ManifestModel nested = createModel(loc, manifest, log, mm, false);
					mm.addNestedModel(nested);
				}
			} else {
				//log.info("____ Dependency from " + mm.bundleId + " to " + dep + " not found in current repository");
			}
		}
	}

	// TODO: add support for Import-Package as well as Require-Bundle

	private ManifestModel findAllDependencies(File pluginFile, File manifest, Log log) {
		ArrayList<String> allDeps = new ArrayList<String>();
		Manifest mf = null;
		String symbolicName = null;
		try {
			mf = new Manifest(new FileInputStream(manifest));
			String reqs = mf.getMainAttributes().getValue("Require-Bundle");
			symbolicName = mf.getMainAttributes().getValue("Bundle-SymbolicName");
			if( symbolicName.contains(";")) {
				symbolicName = symbolicName.substring(0, symbolicName.indexOf(";"));
			}
			if (reqs != null) {
				String[] deps = reqs.split(",");
				for( int i = 0; i < deps.length; i++ ) {
					String[] segments = deps[i].split(";");
					if( segments != null && segments.length > 0) {
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
		ManifestModel mm = new ManifestModel(pluginFile, log);
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
		private Log log;
		public ManifestModel(File pluginFolder, Log log) {
			deps = new ArrayList<String>();
			uiDirectDeps = new ArrayList<String>();
			transitiveDeps = new HashMap<String, ManifestModel>();
			otherBundles = null;
			this.pluginFolder = pluginFolder;
			this.log = log;
		}
		public void setBundleId(String bid) {
			bundleId = bid;
		}

		public boolean isCore() {
			if( bundleId.contains(".core") || bundleId.endsWith(".core")) {
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
			if( otherBundles != null ) {
				return otherBundles;
			}
			if( parent != null ) {
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
			Set<String> ret = new HashSet<String>();
			ret.addAll(uiDirectDeps);
			Iterator<String> it = transitiveDeps.keySet().iterator();
			while(it.hasNext()) {
				ManifestModel mm2 = transitiveDeps.get(it.next());
				log.debug("[CoreUIDependency] " + mm2.bundleId + " has ui deps of: " + mm2.getAllUIDeps().size());
				ret.addAll(mm2.getAllUIDeps());
			}
			return ret;
		}
		private HashMap<String, ArrayList<File>> loadOtherBundleLocations() {
			//log.info("[LD] Loading other bundle locations 2");
			
			HashMap<String, ArrayList<File>> ret = new HashMap<String, ArrayList<File>>();
			cacheFolders(pluginFolder, ret, 3, 3, log);
			
//			Iterator<String> ks = ret.keySet().iterator();
//			while(ks.hasNext()) {
//				log.info("[LD] " + ks.next());
//			}
			
			return ret;
		}
		private void cacheFolders(File pluginFile, HashMap<String, ArrayList<File>> map, 
				int maxParents, int maxDepth, Log log) {
			//log.info("[LD] - Caching from " + pluginFile.getAbsolutePath());
			ArrayList<File> l = map.get(pluginFile.getName());
			if( l == null ) {
				l = new ArrayList<File>();
				map.put(pluginFile.getName(), l);
			} 
			if( !l.contains(pluginFile)) {
				//log.info("Add " + pluginFile.getName() + " to cache? ");
				if( !pluginFile.isFile() && new File(pluginFile, "pom.xml").exists()) {
					//log.info("[LD] - yes");
					l.add(pluginFile);
				}
			}
			// Only search subfolders if this one has a pom.xml
			//log.info("[LD] - search children?");
			if( maxDepth > 0 && new File(pluginFile, "pom.xml").exists()) {
				//log.info("[LD] - search children? yes");
				// Search children
				File[] children = pluginFile.listFiles();
				for( int i = 0; i < children.length; i++ ) {
					cacheFolders(children[i], map, 0, maxDepth-1, log);
				}
			}
			
			//log.info("[LD] - search parents?");
			if( maxParents > 0 && new File(pluginFile.getParentFile(), "pom.xml").exists()) {
				//log.info("[LD] - search parents? yes");
				// Go up one parent if parent folder has a pom.xml, repeat
				cacheFolders(pluginFile.getParentFile(), map, maxParents-1, maxDepth, log);
			}
		}

	}
	
	private void findUIDependencies(ManifestModel mm, Log log, boolean verbose) {
		String symbolicName = mm.bundleId;
		List<String> deps = new ArrayList<String>(mm.deps);
		String[] asArr = deps.toArray(new String[deps.size()]);
		if( verbose ) {
			log.debug("[CoreUIDependency] " + symbolicName + " requires the following units: " + joinString + String.join(joinString, asArr));
		}
		Iterator<String> it = deps.iterator();
		while(it.hasNext()) {
			String dep = it.next();
			if( !dep.contains(".ui.") && !dep.endsWith(".ui")) {
				it.remove();
			}
		}
		mm.setUIDirectDependencies(deps);
	}
	

	/**
	 * If your rule is cacheable, you must return a unique id when parameters or conditions
	 * change that would cause the result to be different. Multiple cached results are stored
	 * based on their id.
	 * 
	 * The easiest way to do this is to return a hash computed from the values of your parameters.
	 * 
	 * If your rule is not cacheable, then the result here is not important, you may return anything.
	 */
	@Override
	public String getCacheId()
	{
		//no hash on boolean...only parameter so no hash is needed.
		return CACHE_PREFIX+pluginId;
	}

	/**
	 * This tells the system if the results are cacheable at all. Keep in mind that during
	 * forked builds and other things, a given rule may be executed more than once for the same
	 * project. This means that even things that change from project to project may still 
	 * be cacheable in certain instances.
	 */
	@Override
	public boolean isCacheable()
	{
		return false;
	}

	/**
	 * If the rule is cacheable and the same id is found in the cache, the stored results
	 * are passed to this method to allow double checking of the results. Most of the time 
	 * this can be done by generating unique ids, but sometimes the results of objects returned
	 * by the helper need to be queried. You may for example, store certain objects in your rule
	 * and then query them later.
	 */
	@Override
	public boolean isResultValid( EnforcerRule arg0 )
	{
		return false;
	}
}
