package org.jboss.tools.releng;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.Properties;

import org.apache.maven.enforcer.rule.api.EnforcerRule;
import org.apache.maven.enforcer.rule.api.EnforcerRuleException;
import org.apache.maven.enforcer.rule.api.EnforcerRuleHelper;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.component.configurator.expression.ExpressionEvaluationException;

/**
 * @author <a href="mailto:nboldt@redhat.com">Nick Boldt</a>
 */
public class FoundationCoreVersionMatchesParentPom
    implements EnforcerRule
{
    private String parentPomVersionBase = null;
    private String BUILD_ALIAS = null;
    private String defaultVersion = null; // found in currentVersionPropertiesFile
    private String defaultVersionBase = null; // found in currentVersionPropertiesFile, then remove .Final suffix
   
    /**
     * Simple params
     */
    private String currentVersionProperties = null;
    private String requiredQualifier = ".Final"; // or .GA

    public void execute( EnforcerRuleHelper helper )
        throws EnforcerRuleException
    {
        Log log = helper.getLog();

        try
        {
            MavenProject project = (MavenProject) helper.evaluate( "${project}" );
            String basedir = (String) helper.evaluate( "${project.basedir}" ).toString();
            
            Properties projProps = project.getProperties();
            Enumeration<?> e = projProps.propertyNames();
            while (e.hasMoreElements()) {
                String key = (String) e.nextElement();
                // fetch from parent pom if not passed into the rule config
                // log.info(key + " = " + projProps.getProperty(key));
                if (parentPomVersionBase == null && key.equals("parsedVersion.osgiVersion"))
                {
                	parentPomVersionBase = projProps.getProperty(key);
//                    log.info("Found parentPomVersion = " + parentPomVersionBase + " (to match for default.version = " + defaultVersion + ")");
                } else if (key.equals("BUILD_ALIAS"))
                {
                	BUILD_ALIAS = projProps.getProperty(key);
                }
            }
            parentPomVersionBase = parentPomVersionBase.replaceAll(".SNAPSHOT", "");
       		log.debug("Got parentPomVersion     = " + parentPomVersionBase + "." + BUILD_ALIAS);
    		log.debug("Got parentPomVersionBase = " + parentPomVersionBase);
            
            log.debug( "Retrieved Basedir: " + basedir );
            log.debug( "Retrieved Project: " + project );

            Properties fileProps = new Properties();
        	InputStream currentVersionPropertiesFIS = null;
        	try {
        		currentVersionPropertiesFIS = new FileInputStream(basedir +"/" + currentVersionProperties);
        		fileProps.load(currentVersionPropertiesFIS);

        		defaultVersion = fileProps.getProperty("default.version");
        		defaultVersionBase = defaultVersion.replaceAll(requiredQualifier, "");
        	} catch (IOException ex) {
        		ex.printStackTrace();
        	} finally {
        		if (currentVersionPropertiesFIS != null) {
        			try {
        				currentVersionPropertiesFIS.close();
        			} catch (IOException ex) {
        				ex.printStackTrace();
        			}
        		}
        	}
       		log.debug("Got default.version      = " + defaultVersion);
    		log.debug("Got default.version base = " + defaultVersionBase);
 

    		// want to match 4.4.3 == 4.4.3 or 4.4.3.AM2 = 4.4.3.AM2
            if (!defaultVersionBase.equals(parentPomVersionBase) && !defaultVersion.equals(parentPomVersionBase + "." + BUILD_ALIAS))
            {
                throw new EnforcerRuleException( "\n[ERROR] Invalid value of default.version = " + defaultVersion + 
                		" for parent = " + parentPomVersionBase + "." + BUILD_ALIAS + "-SNAPSHOT !" +
                		"\n\nMust set default.version = " + parentPomVersionBase + requiredQualifier + " " +
            			"(or = " + parentPomVersionBase + "." + BUILD_ALIAS + ") " + 
            			"in this file:\n\n" + basedir +"/" + currentVersionProperties);
            }
        }
        catch ( ExpressionEvaluationException e )
        {
            throw new EnforcerRuleException( "Unable to lookup an expression " + e.getLocalizedMessage(), e );
        }
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
    public String getCacheId()
    {
        //no hash on boolean...only parameter so no hash is needed.
        return ""+parentPomVersionBase+"::"+BUILD_ALIAS+"::"+defaultVersion+"::"+currentVersionProperties;
    }

    /**
     * This tells the system if the results are cacheable at all. Keep in mind that during
     * forked builds and other things, a given rule may be executed more than once for the same
     * project. This means that even things that change from project to project may still 
     * be cacheable in certain instances.
     */
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
    public boolean isResultValid( EnforcerRule arg0 )
    {
        return false;
    }
}
