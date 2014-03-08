/*******************************************************************************
 * Copyright (c) 2013-2014 Red Hat, Inc and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    Mickael Istria (Red Hat) - initial API and implementation
 *******************************************************************************/
package org.jboss.tools.tycho.targets;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.logging.Logger;
import org.eclipse.sisu.equinox.EquinoxServiceFactory;
import org.eclipse.tycho.artifacts.TargetPlatform;
import org.eclipse.tycho.core.resolver.shared.MavenRepositoryLocation;
import org.eclipse.tycho.osgi.adapters.MavenLoggerAdapter;
import org.eclipse.tycho.p2.resolver.TargetDefinitionFile;
import org.eclipse.tycho.p2.resolver.facade.P2ResolutionResult;
import org.eclipse.tycho.p2.resolver.facade.P2Resolver;
import org.eclipse.tycho.p2.resolver.facade.P2ResolverFactory;
import org.eclipse.tycho.p2.target.facade.TargetDefinition.InstallableUnitLocation;
import org.eclipse.tycho.p2.target.facade.TargetDefinition.Location;
import org.eclipse.tycho.p2.target.facade.TargetDefinition.Repository;
import org.eclipse.tycho.p2.target.facade.TargetDefinition.Unit;
import org.eclipse.tycho.p2.target.facade.TargetPlatformConfigurationStub;
import org.osgi.framework.Version;

/**
 *
 * @goal fix-versions
 * @requireProject false
 * @author mistria
 *
 */
public class FixVersionsMojo extends AbstractMojo {

	/**
     * .target file to fix version
     *
     * @parameter expression="${targetFile}"
     */
    private File targetFile;

    /**
     * @parameter default-value="${project}"
     */
    private MavenProject project;

    /** @component */
    protected EquinoxServiceFactory equinox;

    /** @component */
    private Logger plexusLogger;


	public void execute() throws MojoExecutionException, MojoFailureException {
		if (this.targetFile == null) {
			if (this.project != null && this.project.getPackaging().equals("eclipse-target-definition")) {
				this.targetFile = new File(this.project.getBasedir(), this.project.getArtifactId() + ".target");
			}
		}
		if (this.targetFile == null) {
			throw new MojoFailureException("You need to set a <targetFile/> for packaging types that are not 'eclipse-target-definition'");
		}
		if (!this.targetFile.isFile()) {
			throw new MojoFailureException("Specified target file " + this.targetFile.getAbsolutePath() + " is not a valid file");
		}

		File outputFile = new File(targetFile.getParentFile(), targetFile.getName() + "_update_hints.txt");
		FileOutputStream fos = null;
		try {
			outputFile.createNewFile();
			fos = new FileOutputStream(outputFile);

			P2ResolverFactory resolverFactory = this.equinox.getService(P2ResolverFactory.class);
			TargetDefinitionFile targetDef;
			try {
				targetDef = TargetDefinitionFile.read(this.targetFile);
			} catch (Exception ex) {
				throw new MojoExecutionException(ex.getMessage(), ex);
			}
			for (Location location : targetDef.getLocations()) {
				if (!(location instanceof InstallableUnitLocation)) {
					getLog().warn("Location type " + location.getClass().getSimpleName() + " not supported");
					continue;
				}
				InstallableUnitLocation loc = (InstallableUnitLocation) location;
				TargetPlatformConfigurationStub tpConfig = new TargetPlatformConfigurationStub();
				for (Repository repo : loc.getRepositories()) {
					String id = repo.getId();
					if (repo.getId() == null || repo.getId().isEmpty()) {
						id = repo.getLocation().toString();
					}
					tpConfig.addP2Repository(new MavenRepositoryLocation(id, repo.getLocation()));
				}
				TargetPlatform site = resolverFactory.getTargetPlatformFactory().createTargetPlatform(
						tpConfig, new MockExecutionEnvironment(), null, null);
				P2Resolver resolver = resolverFactory.createResolver(new MavenLoggerAdapter(this.plexusLogger, true));
				for (Unit unit : loc.getUnits()) {
					getLog().info("checking " + unit.getId());
					String version = findBestMatchingVersion(site, resolver, unit);
					if (!version.equals(unit.getVersion())) {
						String message = unit.getId() + " ->" + version;
						getLog().info(message);
						fos.write(message.getBytes());
						fos.write('\n');
					}
					if (unit instanceof TargetDefinitionFile.Unit) {
						// That's deprecated, but so cool (and no other way to do it except doing parsing by hand)
						((TargetDefinitionFile.Unit)unit).setVersion(version);
					}
				}
			}
			TargetDefinitionFile.write(targetDef, new File(targetFile.getParent(), targetFile.getName() + "_fixedVersion.target"));
		} catch (FileNotFoundException ex) {
			throw new MojoExecutionException("Error while opening output file " + outputFile, ex);
		} catch (IOException ex) {
			throw new MojoExecutionException("Can't write to file " + outputFile, ex);
		} finally {
			if (fos != null) {
				try {
					fos.close();
				} catch (Exception ex) {
					throw new MojoExecutionException("IO error", ex);
				}
			}
		}
	}


	private String findBestMatchingVersion(TargetPlatform site, P2Resolver resolver, Unit unit) throws MojoFailureException, MojoExecutionException {
		String version = unit.getVersion();
		P2ResolutionResult currentIUResult = resolver.resolveInstallableUnit(site, unit.getId(), toQueryExactVersion(version));
		if (currentIUResult.getArtifacts().isEmpty() && currentIUResult.getNonReactorUnits().isEmpty()) {
			currentIUResult = resolver.resolveInstallableUnit(site, unit.getId(), toQueryIgnoreQualifier(version));
			if (currentIUResult.getArtifacts().isEmpty() && currentIUResult.getNonReactorUnits().isEmpty()) {
				currentIUResult = resolver.resolveInstallableUnit(site, unit.getId(), toQueryIgnoreMicro(version));
				if (currentIUResult.getArtifacts().isEmpty() && currentIUResult.getNonReactorUnits().isEmpty()) {
					currentIUResult = resolver.resolveInstallableUnit(site, unit.getId(), toQueryIgnoreMinor(version));
					if (currentIUResult.getArtifacts().isEmpty() && currentIUResult.getNonReactorUnits().isEmpty()) {
						currentIUResult = resolver.resolveInstallableUnit(site, unit.getId(), "0.0.0");
					}
				}
			}
		}
		if (currentIUResult.getArtifacts().size() > 0) {
			return currentIUResult.getArtifacts().iterator().next().getVersion();
		} else if (currentIUResult.getNonReactorUnits().size() > 0) {
			Object foundItem = currentIUResult.getNonReactorUnits().iterator().next();
			// foundItem is most probably a p2 internal InstallableUnit (type not accessible from Tycho). Let's do some introspection
			try {
				Method getVersionMethod = foundItem.getClass().getMethod("getVersion");
				Object foundVersion = getVersionMethod.invoke(foundItem);
				return foundVersion.toString();
			} catch (Exception ex) {
				throw new MojoExecutionException("Unsupported search result " + foundItem.getClass(), ex);
			}
		} else {
			throw new MojoFailureException("Could not find any IU for " + unit.getId());
		}
	}

	private String toQueryExactVersion(String version) {
		return "[" + version + "," + version + "]";
	}


	private String toQueryIgnoreMinor(String version) {
		Version osgiVersion = new Version(version);
		StringBuilder res = new StringBuilder();
		res.append("[");
		res.append(osgiVersion.getMajor()); res.append(".0.0");
		res.append(",");
		res.append(osgiVersion.getMajor() + 1); res.append(".0.0");
		res.append(")");
		return res.toString();
	}

	private String toQueryIgnoreMicro(String version) {
		Version osgiVersion = new Version(version);
		StringBuilder res = new StringBuilder();
		res.append("[");
		res.append(osgiVersion.getMajor()); res.append("."); res.append(osgiVersion.getMinor()); res.append(".0");
		res.append(",");
		res.append(osgiVersion.getMajor()); res.append("."); res.append(osgiVersion.getMinor() + 1); res.append(".0");
		res.append(")");
		return res.toString();
	}


	private String toQueryIgnoreQualifier(String version) {
		Version osgiVersion = new Version(version);
		StringBuilder res = new StringBuilder();
		res.append("[");
		res.append(osgiVersion.getMajor()); res.append("."); res.append(osgiVersion.getMinor()); res.append("."); res.append(osgiVersion.getMicro());
		res.append(",");
		res.append(osgiVersion.getMajor()); res.append("."); res.append(osgiVersion.getMinor()); res.append("."); res.append(osgiVersion.getMicro() + 1);
		res.append(")");
		return res.toString();
	}


}
