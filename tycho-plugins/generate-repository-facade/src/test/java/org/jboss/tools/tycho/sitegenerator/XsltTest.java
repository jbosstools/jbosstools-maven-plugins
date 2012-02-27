package org.jboss.tools.tycho.sitegenerator;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

import javax.xml.transform.Result;
import javax.xml.transform.Source;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;

import org.junit.Test;

public class XsltTest {

	@Test
	public void testSiteProperties() throws Exception {
        InputStream siteXsl = GenerateRepositoryFacadeMojo.class.getResourceAsStream("/xslt/site.properties.xsl");
        Source xsltSource = new StreamSource(siteXsl);
        Transformer transformer = TransformerFactory.newInstance().newTransformer(xsltSource);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Result res = new StreamResult(out);
        transformer.transform(new StreamSource(new ByteArrayInputStream("<site/>".getBytes())), res);
        siteXsl.close();
        out.close();
	}

}
