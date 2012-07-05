<xsl:stylesheet version="2.0"
	xmlns:xsl="http://www.w3.org/1999/XSL/Transform" xmlns:xalan="http://xml.apache.org/xalan"
	xmlns:saxon="http://saxon.sf.net/" xmlns="http://www.w3.org/1999/xhtml"
	extension-element-prefixes="saxon">
	<xsl:output method="text" indent="yes" />
	<xsl:template match="/site">
		<xsl:for-each select="category-def">
			<xsl:sort select="@name" />
			<xsl:variable name="catg"><xsl:value-of select="@name"/></xsl:variable>
# 
<xsl:value-of select="$catg" />.category.features = \
<xsl:for-each select="//feature">
				<xsl:sort select="@id" />
				<xsl:if test="count(./category[contains(@name,$catg)])>0"><xsl:value-of select="@id" />,
</xsl:if>
			</xsl:for-each>
		</xsl:for-each>
# 
	</xsl:template>
</xsl:stylesheet>
