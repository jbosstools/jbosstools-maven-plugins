package org.jboss.tools.tycho.targets;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.resolver.ArtifactResolutionRequest;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.apache.maven.repository.RepositorySystem;
import org.codehaus.plexus.component.annotations.Requirement;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Creates a single platform which merges locations from all platforms
 *
 * @author mistria
 * @goal merge-targets
 */
public class MergeTargetsMojo extends AbstractMojo {


    /**
     * @parameter default-value="${project}"
     * @readonly
     */
    private MavenProject project;
    /**
     * @parameter expression="${session}"
     * @readonly
     */
    private MavenSession session;

    /**
     * @component
     */
    @Requirement
    private RepositorySystem repositorySystem;

	/**
     * Location of the output file.
     * @parameter expression="${project.build.directory}/${project.artifactId}.target"
     * @required
     */
    private File outputFile;

    /**
     * Target to transform (as a file)
     * @parameter
     */
    private List<File> sourceTargetFiles;

    /**
     * @parameter
     */
    private List<TargetArtifact> sourceTargetArtifacts;

    public void execute() throws MojoExecutionException {
    	if (this.sourceTargetFiles == null) {
    		this.sourceTargetFiles = new ArrayList<File>();
    	}
    	if (!this.outputFile.getParentFile().isDirectory()) {
    		this.outputFile.getParentFile().mkdirs();
    	}

        if (this.sourceTargetArtifacts != null) {
        	for (TargetArtifact sourceTargetArtifact : this.sourceTargetArtifacts) {
	        	this.sourceTargetFiles.add(sourceTargetArtifact.getFile(this.repositorySystem, this.session, this.project));
        	}
        }

        if (this.sourceTargetFiles.size() <= 1) {
        	throw new MojoExecutionException("Please provide at least 2 files to merge");
        }

        try {
	        DocumentBuilder docBuilder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
	        Document targetDoc = docBuilder.parse(this.sourceTargetFiles.get(0));
	        Element targetElement = (Element) ((Element)targetDoc.getElementsByTagName("target").item(0)).getElementsByTagName("locations").item(0);

	        for (int i = 1; i < this.sourceTargetFiles.size(); i++) {
	        	Document otherTargetDoc = docBuilder.parse(this.sourceTargetFiles.get(i));
	        	Element otherLocations = (Element) ((Element)otherTargetDoc.getElementsByTagName("target").item(0)).getElementsByTagName("locations").item(0);
	        	NodeList children = otherLocations.getChildNodes();
	        	for (int j = 0; j < children.getLength(); j++) {
	        		targetElement.appendChild(targetDoc.importNode(children.item(j), true));
	        	}
	        }

	        //write the content into xml file
	        Transformer transformer = TransformerFactory.newInstance().newTransformer();
	        transformer.setOutputProperty(OutputKeys.INDENT, Boolean.TRUE.toString());
	        DOMSource source = new DOMSource(targetDoc);
	        StreamResult result =  new StreamResult(this.outputFile);
	        transformer.transform(source, result);
        } catch (Exception ex) {
        	throw new MojoExecutionException("An error happened while dealing with some XML...", ex);
        }
    }
}
