package org.jboss.tools.releng;

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
 * Based on sample code from <a href="mailto:brianf@apache.org">Brian Fox</a>
 */
public class NoSnapshotsAllowed
    implements EnforcerRule
{
    private String snapshotKey = null;
    private String buildAlias = null;

    /**
     * Simple params
     */
    private String buildAliasSearch = "Final|GA";
    private String SNAPSHOT = "SNAPSHOT";
    private String includePattern = "";
    private String excludePattern = "";

    public void execute( EnforcerRuleHelper helper )
        throws EnforcerRuleException
    {
        Log log = helper.getLog();

        try
        {
            // get the various expressions out of the helper.
            MavenProject project = (MavenProject) helper.evaluate( "${project}" );
            
//            MavenSession session = (MavenSession) helper.evaluate( "${session}" );
            String target = (String) helper.evaluate( "${project.build.directory}" );
            String artifactId = (String) helper.evaluate( "${project.artifactId}" );
 
            // defaults if not set
            if (includePattern == null || includePattern.equals("")) { includePattern = ".*"; } 
            if (excludePattern == null || excludePattern.equals("")) { excludePattern = ""; } 
            
            log.debug("Search for properties matching " + SNAPSHOT + "...");
            
            Properties projProps = project.getProperties();
            Enumeration<?> e = projProps.propertyNames();
            while (e.hasMoreElements()) {
                String key = (String) e.nextElement();
                // fetch from parent pom if not passed into the rule config
                if (buildAlias == null && key.equals("BUILD_ALIAS"))
                {
                    buildAlias = projProps.getProperty(key);
                    if (buildAlias.matches(buildAliasSearch))
                    {
                    	log.info("Found buildAlias = " + buildAlias + " (for buildAliasSearch = " + buildAliasSearch + ")");
                    } else{
                    	log.debug("Found buildAlias = " + buildAlias + " (for buildAliasSearch = " + buildAliasSearch + ")");
                    }
                } else if (key.matches(includePattern) && (excludePattern.equals("") || !key.matches(excludePattern)) && projProps.getProperty(key).indexOf(SNAPSHOT)>-1)
                {
                    log.error("Found property "+ key + " = " + projProps.getProperty(key));
                	snapshotKey = key;
//                } else {
//                    log.debug("Property: "+ key + " = " + projProps.getProperty(key));
                }
            }
            	
//            // retrieve any component out of the session directly
//            ArtifactResolver resolver = (ArtifactResolver) helper.getComponent( ArtifactResolver.class );
//            RuntimeInformation rti = (RuntimeInformation) helper.getComponent( RuntimeInformation.class );
//            log.debug( "Retrieved Session: " + session );
//            log.debug( "Retrieved Resolver: " + resolver );
//            log.debug( "Retrieved RuntimeInfo: " + rti );

            log.debug( "Retrieved Target Folder: " + target );
            log.debug( "Retrieved ArtifactId: " +artifactId );
            log.debug( "Retrieved Project: " + project );
            log.debug( "Retrieved Project Version: " + project.getVersion());

            if ( buildAlias.matches(buildAliasSearch) && snapshotKey != null)
            {
                throw new EnforcerRuleException( "\nWhen buildAlias (" + buildAlias + 
                		") matches /" + buildAliasSearch + "/, cannot include " + SNAPSHOT + " dependencies.\n");
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
        return ""+snapshotKey;
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
