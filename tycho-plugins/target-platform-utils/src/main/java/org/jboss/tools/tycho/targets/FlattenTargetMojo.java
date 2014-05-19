/**
 * Copyright (c) 2012, 2014 Red Hat, Inc.
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
 * Takes a multi-site .target file as input and flatten it to have same IUs, but from a single site.
 */
@Mojo(name = "flatten-target")
public class FlattenTargetMojo extends AbstractMojo {

	@Parameter(property = "project", readonly = true)
    private MavenProject project;
	
	@Parameter(property = "session", readonly = true)
    private MavenSession session;

    @Requirement
    @Component
    private RepositorySystem repositorySystem;

	/**
     * Location of the output file.
     */
    @Parameter(defaultValue = "${project.build.directory}/${project.artifactId}.target", required = true)
    private File outputFile;

    /**
     * Location of the initial target (as file)
     */
    @Parameter
    private File sourceTargetFile;

    /**
     * Location of initial target (as artifact)
     */
    @Parameter
    private TargetArtifact sourceTargetArtifact;

    /**
     * The single repository that the created target will reference.
     * It's typically the one where the aggregated site will be deployed.
     */
    @Parameter(required = true)
    private String targetRepositoryUrl;

    public void execute() throws MojoExecutionException {
        if (!this.outputFile.getParentFile().exists()) {
        	this.outputFile.getParentFile().mkdirs();
        }

        if ((this.sourceTargetFile != null && this.sourceTargetArtifact != null) ||
        		(this.sourceTargetFile == null && this.sourceTargetArtifact == null)) {
        	throw new MojoExecutionException("Set either 'sourceTargetFile' XOR 'sourceTargetArtifact'");
        }

        if (this.sourceTargetArtifact != null) {
            this.sourceTargetFile = this.sourceTargetArtifact.getFile(this.repositorySystem, this.session, this.project);
        }

        try {
	        DocumentBuilder docBuilder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
	        Document targetDoc = docBuilder.parse(this.sourceTargetFile);
	        NodeList locations = ((Element) ((Element)targetDoc.getElementsByTagName("target").item(0)).getElementsByTagName("locations").item(0)).getElementsByTagName("location");
	        getLog().debug("number of locations: " + locations.getLength());
	        Element targetItem = (Element) locations.item(0);
	        ((Element)targetItem.getElementsByTagName("repository").item(0)).setAttribute("location", this.targetRepositoryUrl);
	        while (locations.getLength() > 1) {
	        	Element location = (Element)locations.item(1);
	        	NodeList children = location.getChildNodes();
	        	while (children.getLength() > 0) {
	        		if ((children.item(0) instanceof Element) && ((Element)children.item(0)).getTagName().equals("repository")) {
	        			location.removeChild(children.item(0));
	        		} else {
	        			targetItem.appendChild(children.item(0));
	        		}
	        	}
	        	location.getParentNode().removeChild(location);
	        }

	        //write the content into xml file
	        Transformer transformer = TransformerFactory.newInstance().newTransformer();
	        transformer.setOutputProperty(OutputKeys.INDENT, Boolean.TRUE.toString());
	        DOMSource source = new DOMSource(targetDoc);
	        StreamResult result =  new StreamResult(outputFile);
	        transformer.transform(source, result);
        } catch (Exception ex) {
        	throw new MojoExecutionException("Error processing files", ex);
        }
    }
}
