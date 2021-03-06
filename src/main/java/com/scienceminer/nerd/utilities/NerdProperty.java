package com.scienceminer.nerd.utilities;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.StringWriter;

/**
 * Class containing the key, value and type of a NERD property.
 *
 */
public class NerdProperty {

    private static final Logger LOGGER = LoggerFactory.getLogger(NerdProperty.class);

    /**
     * Possible types for Nerd properties.
     */
    public enum TYPE {
        STRING("string"), BOOLEAN("boolean"), INTEGER("integer"), FILE("file"), PASSWORD("password"), UNKNOWN("unknown");

        private String value;

        TYPE(String value) {
            this.value = value;
        }

        @Override
        public String toString() {
            return this.value;
        }
    }

    /**
     * The key of the property.
     */
    String key;

    /**
     * The value of the property.
     */
    String value;

    /**
     * The type of the property
     */
    TYPE type;

    /**
     * Constructor.
     *
     * @param pKey   the key
     * @param pValue the value
     */
    public NerdProperty(String pKey, String pValue) {
        setKey(pKey);
        setValue(pValue);
        type = inferType(pKey, pValue);
    }

    public NerdProperty() {}

    /**
     * @return the key
     */
    public String getKey() {
        return key;
    }

    /**
     * @param pKey the key to set
     */
    public void setKey(String pKey) {
        key = StringUtils.isBlank(pKey) ? StringUtils.EMPTY : pKey;
    }

    /**
     * @return the value
     */
    public String getValue() {
        return value;
    }

    /**
     * @param pValue the value to set
     */
    public void setValue(String pValue) {
        value = StringUtils.isBlank(pValue) ? StringUtils.EMPTY : pValue;
    }

    /**
     * @return the type
     */
    public TYPE getType() {
        return type;
    }

    /**
     * @param pType the type to set
     */
    public void setType(TYPE pType) {
        this.type = pType;
    }

    @Override
    public int hashCode() {
        HashCodeBuilder hcb = new HashCodeBuilder();
        hcb.append(key);
        return hcb.toHashCode();
    }

    @Override
    public boolean equals(Object arg0) {
        NerdProperty prop = (NerdProperty) arg0;
        EqualsBuilder eqb = new EqualsBuilder();
        eqb.append(key, prop.getKey());
        eqb.append(value, prop.getValue());
        eqb.append(type, prop.getType());
        return eqb.build();
    }

    public static NerdProperty fromJson(String json) {
        ObjectMapper mapper = new ObjectMapper();
        mapper.enable(DeserializationFeature.READ_ENUMS_USING_TO_STRING);

        try {
            return mapper.readValue(json, NerdProperty.class);
        } catch (IOException e) {
            LOGGER.error("Cannot read json data, \n" + json, e);
        }

        return null;
    }

    public String toJson() {
        StringWriter sw = new StringWriter();

        try {
            ObjectMapper mapper = new ObjectMapper();
            mapper.enable(SerializationFeature.WRITE_ENUMS_USING_TO_STRING);
            mapper.writeValue(sw, this);
        } catch (IOException e) {
            LOGGER.error("Cannot write json data.", e);
        }

        return sw.toString();
    }

    @Override
    public String toString() {
        StringBuilder str = new StringBuilder();
        str.append(XmlUtils.startTag("property"));
        str.append(XmlUtils.fullTag("key", getKey()));
        str.append(XmlUtils.fullTag("value", getValue()));
        str.append(XmlUtils.fullTag("type", getType().toString()));
        str.append(XmlUtils.endTag("property"));
        return str.toString();
    }

    /**
     * Return whether the type is String, boolean, integer or file.
     *
     * @param pKey   The key of the parameter.
     * @param pValue The value of the parameter.
     * @return One type of {@link NerdProperty.TYPE}, empty string if pValue
     * is null or empty.
     */
    protected static TYPE inferType(String pKey, String pValue) {
        TYPE type = TYPE.UNKNOWN;
        if (NerdPropertyKeys.PROP_NERD_SERVICE_ADMIN_PW
                .equalsIgnoreCase(pKey)) {
            type = TYPE.PASSWORD;
        } else if (StringUtils.isNotBlank(pValue)) {
            pValue = pValue.trim();
            if (isBoolean(pValue))
                type = TYPE.BOOLEAN;
            else if (isInteger(pValue))
                type = TYPE.INTEGER;
            else if (isFile(pValue))
                type = TYPE.FILE;
            else
                type = TYPE.STRING;
        }

        return type;
    }

    private static boolean isBoolean(String pValue) {
        return StringUtils.equalsIgnoreCase(pValue, "TRUE")
                || StringUtils.equalsIgnoreCase(pValue, "FALSE");
    }

    private static boolean isInteger(String pValue) {
        try {
            Integer.parseInt(pValue);
        } catch (NumberFormatException nfe) {
            return false;
        }
        return true;
    }

    private static boolean isFile(String pValue) {
        File file = new File(pValue);
        return file.exists();
    }

}
