package de.intranda.goobi.plugins;

import lombok.Data;

@Data
public class MetadataField {

    private String rulesetName;
    private String xpath;
    private String elementName;
    private String doctype;
    private String xpathType;

    private String regex;

}
