//
// This file was generated by the JavaTM Architecture for XML Binding(JAXB) Reference Implementation, vIBM 2.2.3-11/28/2011 06:21 AM(foreman)- 
// See <a href="http://java.sun.com/xml/jaxb">http://java.sun.com/xml/jaxb</a> 
// Any modifications to this file will be lost upon recompilation of the source schema. 
// Generated on: 2013.03.18 at 06:45:00 PM EDT 
//


package com.ibm.jbatch.jsl.model;

import javax.annotation.Generated;
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlType;


/**
 * <p>Java class for PartitionMapper complex type.
 * 
 * <p>The following schema fragment specifies the expected content contained within this class.
 * 
 * <pre>
 * &lt;complexType name="PartitionMapper">
 *   &lt;complexContent>
 *     &lt;restriction base="{http://www.w3.org/2001/XMLSchema}anyType">
 *       &lt;sequence>
 *         &lt;element name="properties" type="{http://xmlns.jcp.org/xml/ns/javaee}Properties" minOccurs="0"/>
 *       &lt;/sequence>
 *       &lt;attribute name="ref" use="required" type="{http://xmlns.jcp.org/xml/ns/javaee}artifactRef" />
 *     &lt;/restriction>
 *   &lt;/complexContent>
 * &lt;/complexType>
 * </pre>
 * 
 * 
 */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "PartitionMapper", propOrder = {
    "properties"
})
@Generated(value = "com.ibm.jtc.jax.tools.xjc.Driver", date = "2013-03-18T06:45:00-04:00", comments = "JAXB RI v2.2.3-11/28/2011 06:21 AM(foreman)-")
public class PartitionMapper {

    @Generated(value = "com.ibm.jtc.jax.tools.xjc.Driver", date = "2013-03-18T06:45:00-04:00", comments = "JAXB RI v2.2.3-11/28/2011 06:21 AM(foreman)-")
    protected JSLProperties properties;
    @XmlAttribute(name = "ref", required = true)
    @Generated(value = "com.ibm.jtc.jax.tools.xjc.Driver", date = "2013-03-18T06:45:00-04:00", comments = "JAXB RI v2.2.3-11/28/2011 06:21 AM(foreman)-")
    protected String ref;

    /**
     * Gets the value of the properties property.
     * 
     * @return
     *     possible object is
     *     {@link JSLProperties }
     *     
     */
    @Generated(value = "com.ibm.jtc.jax.tools.xjc.Driver", date = "2013-03-18T06:45:00-04:00", comments = "JAXB RI v2.2.3-11/28/2011 06:21 AM(foreman)-")
    public JSLProperties getProperties() {
        return properties;
    }

    /**
     * Sets the value of the properties property.
     * 
     * @param value
     *     allowed object is
     *     {@link JSLProperties }
     *     
     */
    @Generated(value = "com.ibm.jtc.jax.tools.xjc.Driver", date = "2013-03-18T06:45:00-04:00", comments = "JAXB RI v2.2.3-11/28/2011 06:21 AM(foreman)-")
    public void setProperties(JSLProperties value) {
        this.properties = value;
    }

    /**
     * Gets the value of the ref property.
     * 
     * @return
     *     possible object is
     *     {@link String }
     *     
     */
    @Generated(value = "com.ibm.jtc.jax.tools.xjc.Driver", date = "2013-03-18T06:45:00-04:00", comments = "JAXB RI v2.2.3-11/28/2011 06:21 AM(foreman)-")
    public String getRef() {
        return ref;
    }

    /**
     * Sets the value of the ref property.
     * 
     * @param value
     *     allowed object is
     *     {@link String }
     *     
     */
    @Generated(value = "com.ibm.jtc.jax.tools.xjc.Driver", date = "2013-03-18T06:45:00-04:00", comments = "JAXB RI v2.2.3-11/28/2011 06:21 AM(foreman)-")
    public void setRef(String value) {
        this.ref = value;
    }

}
