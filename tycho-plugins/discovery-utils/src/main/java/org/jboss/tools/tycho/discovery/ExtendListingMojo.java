package org.jboss.tools.tycho.discovery;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.Set;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.eclipse.tycho.p2.resolver.TargetDefinitionFile;
import org.eclipse.tycho.p2.resolver.TargetDefinitionFile.IULocation;
import org.eclipse.tycho.p2.target.facade.TargetDefinition.Location;
import org.eclipse.tycho.p2.target.facade.TargetDefinition.Unit;

import de.pdark.decentxml.Document;
import de.pdark.decentxml.Element;
import de.pdark.decentxml.XMLParser;

@Mojo(name="update-listing", defaultPhase=LifecyclePhase.GENERATE_RESOURCES, requiresProject=false)
public class ExtendListingMojo extends AbstractMojo {

	@Parameter(property = "project")
    private MavenProject project;
	
	@Parameter(property = "targetListingFile", defaultValue = "earlyaccess.properties")
	private File targetListingFile;
	
	@Parameter(property = "targetDefinitionFile")
	private File targetDefinitionFile;
	
	public void execute() throws MojoExecutionException, MojoFailureException {
		File pluginFile = new File(this.project.getBasedir(), "plugin.xml");
		Set<String> earlyAccessIuIds = new HashSet<String>();
		Document pluginDOM = null;
		try {
			pluginDOM = XMLParser.parse(pluginFile);
		} catch (IOException ex) {
			throw new MojoFailureException(ex.getMessage(), ex);
		}
		
		for (Element extensionElement : pluginDOM.getChild("plugin").getChildren("extension")) {
			if ("org.eclipse.mylyn.discovery.core.connectorDiscovery".equals(extensionElement.getAttributeValue("point"))) {
				for (Element connectorElement : extensionElement.getChildren("connectorDescriptor")) {
					if (connectorElement.getAttributeValue("certificationId").toLowerCase().contains("earlyaccess")) {
						for (Element iuElement : connectorElement.getChildren("iu")) {
							earlyAccessIuIds.add(iuElement.getAttributeValue("id") + ".feature.group");
						}
					}
				}
			}
		}
		
		Map<String, String> earlyAccessIUsToVersion = new HashMap<String, String>();
		TargetDefinitionFile file = TargetDefinitionFile.read(this.targetDefinitionFile);
		for (Location location : file.getLocations()) {
			if (! (location instanceof IULocation)) {
				throw new MojoExecutionException("Only p2 IUs location are supported");
			}
			IULocation iuLocation = (IULocation)location;
			for (Unit unit : iuLocation.getUnits()) {
				if (earlyAccessIuIds.contains(unit.getId())) {
					earlyAccessIUsToVersion.put(unit.getId(), unit.getVersion());
				}
			}
		}
		Properties listing = new Properties();
		for (Entry<String, String> entry : earlyAccessIUsToVersion.entrySet()) {
			String version = entry.getValue();
			String toVersionRange = "[" + version + "," + version + "]";
			String currentEntry = (String) listing.get(entry.getKey());
			if (currentEntry == null) {
				currentEntry = toVersionRange;
			} else if (!currentEntry.contains(toVersionRange)) {
				currentEntry += ";";
				currentEntry += toVersionRange;
			}
			listing.put(entry.getKey(), currentEntry);
		}
		try {
			FileOutputStream out = new FileOutputStream(this.targetListingFile);
			listing.store(out, null);
		} catch (Exception ex) {
			throw new MojoFailureException(ex.getMessage(), ex);
		}
	}

}
