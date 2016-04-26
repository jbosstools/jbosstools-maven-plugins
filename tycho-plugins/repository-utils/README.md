To use this plugin in your eclipse-repository build, add it to your site/pom.xml, and set 
properties as required to override defaults.

If you set a siteTemplateFolder value, ensure that the folder exists as site/siteTemplateFolder/ and that it contains site/siteTemplateFolder/index.html file (and optionally, a site/siteTemplateFolder/web/site.css file, too).

<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
	xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
	<modelVersion>4.0.0</modelVersion>
	<parent>
		<groupId>org.jboss.tools</groupId>
		<artifactId>modeshape</artifactId>
		<version>3.1.0-SNAPSHOT</version>
	</parent>
	<groupId>org.jboss.tools.modeshape</groupId>
	<artifactId>modeshape.site</artifactId>
	<name>modeshape.site</name>
	
	<packaging>eclipse-repository</packaging>

	<properties>
		<update.site.name>ModeShape Tools</update.site.name>
		<update.site.description>Nightly Build</update.site.description>
		<update.site.version>3.0.0.${BUILD_ALIAS}</update.site.version>
		<target.eclipse.version>4.2 (Juno)</target.eclipse.version>
	</properties>

	<build>
		<plugins>
			<plugin>
				<groupId>org.jboss.tools.tycho-plugins</groupId>
				<artifactId>repository-utils</artifactId>
				<version>0.0.1-SNAPSHOT</version>
				<executions>
					<!-- creates index.html and other artifacts -->
					<execution>
						<id>generate-facade</id>
						<phase>package</phase>
						<goals>
							<goal>generate-repository-facade</goal>
						</goals>
						<configuration>
							<!-- configuration options for the generated HTML page -->
							<symbols>
								<siteTemplateFolder>siteTemplateFolder/</siteTemplateFolder>
								<update.site.name>${update.site.name}</update.site.name>
								<update.site.description>${update.site.description}</update.site.description>
								<update.site.version>${update.site.version}</update.site.version>
								<target.eclipse.version>${target.eclipse.version}</target.eclipse.version>
							</symbols>

							<!-- this adds repository references to the update site's content.xml -->
							<associateSites>
								<site>http://download.server.org/path/to/updates/</site>
								<site>http://download.server.org/path/to/more/updates/</site>
							</associateSites>

							<!-- to include other files in the update site zip, list them here -->
							<additionalWebResources>
								<resource>../relative/path/some.file.to.include.in.update.site.zip.archive.txt</resource>
								<resource>../another.file.txt</resource>
							</additionalWebResources>

							<!-- to remove the default category created for any uncategorized features or bundles, set this to true -->
							<removeDefaultCategory>true</removeDefaultCategory>
						</configuration>
					</execution>
					<!-- fetches source zips and produces related metadata -->
					<execution>
						<id>fetch-sources</id>
						<phase>package</phase>
						<goals>
							<goal>fetch-sources-from-manifests</goal>
						</goals>
						<configuration>
							<!-- 
							<zipCacheFolder>${basedir}/cache</zipCacheFolder> 
							<outputFolder>${project.build.directory}/fullSite</outputFolder> 
							<sourcesZip>${project.build.directory}/path/to/src.zip</sourcesZip>
							<sourcesZipRootFolder>folder-at-root-of-src-zip</sourcesZipRootFolder>
							-->
							<sourceFetchMap>
								<!-- pick a plugin available in the project's update site from which we can deduce the Eclipse-SourceReference -->
								<jbosstools-base>org.jboss.tools.common</jbosstools-base>
								<jbosstools-aerogear>org.jboss.tools.aerogear.hybrid.core</jbosstools-aerogear>
								<jbosstools-arquillian>org.jboss.tools.arquillian.core</jbosstools-arquillian>
								<jbosstools-birt>org.jboss.tools.birt.core</jbosstools-birt>
								<jbosstools-central>org.jboss.tools.central</jbosstools-central>
								<jbosstools-forge>org.jboss.tools.forge.core</jbosstools-forge>
								<jbosstools-freemarker>org.jboss.ide.eclipse.freemarker</jbosstools-freemarker>
								<jbosstools-gwt>org.jboss.tools.gwt.core</jbosstools-gwt>
								<jbosstools-hibernate>org.hibernate.eclipse</jbosstools-hibernate>
								<jbosstools-javaee>org.jboss.tools.jsf</jbosstools-javaee>
								<jbosstools-jst>org.jboss.tools.jst.web</jbosstools-jst>
								<jbosstools-livereload>org.jboss.tools.livereload.core</jbosstools-livereload>
								<jbosstools-openshift>org.jboss.tools.openshift.egit.core</jbosstools-openshift>
								<jbosstools-portlet>org.jboss.tools.portlet.core</jbosstools-portlet>
								<jbosstools-server>org.jboss.ide.eclipse.as.core</jbosstools-server>
								<jbosstools-vpe>org.jboss.tools.vpe</jbosstools-vpe>
								<jbosstools-webservices>org.jboss.tools.ws.core</jbosstools-webservices>
								<!-- instead of pulling from a plugin, pull source info from a URL containing the buildinfo.json file -->
								<jbosstools-xulrunner>http://path/to/buildinfo.json</jbosstools-xulrunner>
							</sourceFetchMap>
						</configuration>
					</execution>
				</executions>
			</plugin>
		</plugins>
	</build>
</project>
