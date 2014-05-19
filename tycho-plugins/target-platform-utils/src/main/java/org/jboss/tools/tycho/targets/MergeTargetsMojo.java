/**
 * Copyright (c) 2013, 2014 Red Hat, Inc.
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
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
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
 */
@Mojo(name = "merge-targets")
public class MergeTargetsMojo extends AbstractMojo {


	@Parameter(property = "project", readonly = true)
    private MavenProject project;

	@Parameter(property = "session", readonly = true)
    private MavenSession session;

    @Requirement
    @Component
    private RepositorySystem repositorySystem;

    @Parameter(defaultValue = "${project.build.directory}/${project.artifactId}.target", required = true)
    private File outputFile;

    @Parameter(defaultValue = "${project.artifactId}-${project.version}")
    private String targetName;

    /**
     * File-based Target definitions to merge
     */
    @Parameter
    private List<File> sourceTargetFiles;

    /**
     * GAV-based Target definitions to merge
     */
    @Parameter
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
	        Element targetElement = (Element) targetDoc.getElementsByTagName("target").item(0);
	        Element locationsElement = (Element) targetElement.getElementsByTagName("locations").item(0);

	        /* Rename the target element to the name of the output file */
	        targetElement.setAttribute("name", targetName);

	        for (int i = 1; i < this.sourceTargetFiles.size(); i++) {
	        	Document otherTargetDoc = docBuilder.parse(this.sourceTargetFiles.get(i));
	        	Element otherLocations = (Element) ((Element)otherTargetDoc.getElementsByTagName("target").item(0)).getElementsByTagName("locations").item(0);
	        	NodeList children = otherLocations.getChildNodes();
	        	for (int j = 0; j < children.getLength(); j++) {
	        		locationsElement.appendChild(targetDoc.importNode(children.item(j), true));
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
