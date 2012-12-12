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
					<execution>
						<id>generate-facade</id>
						<phase>package</phase>
						<goals>
							<goal>generate-repository-facade</goal>
						</goals>
						<configuration>
							<symbols>
								<siteTemplateFolder>siteTemplateFolder/</siteTemplateFolder>
								<update.site.name>${update.site.name}</update.site.name>
								<update.site.description>${update.site.description}</update.site.description>
								<update.site.version>${update.site.version}</update.site.version>
								<target.eclipse.version>${target.eclipse.version}</target.eclipse.version>
							</symbols>
						</configuration>
					</execution>
				</executions>
			</plugin>
		</plugins>
	</build>
</project>