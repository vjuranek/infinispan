<?xml version="1.0" encoding="UTF-8" standalone="yes"?>

<xsl:stylesheet version="1.0"
    xmlns:pom="http://maven.apache.org/POM/4.0.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform">

   <xsl:output method="text"/>
   
   <xsl:template match="/pom:project">
       <xsl:for-each select="pom:modules/pom:module">
           <xsl:value-of select="." /><xsl:text>&#xa;</xsl:text>
       </xsl:for-each>
   </xsl:template>
   
</xsl:stylesheet>
