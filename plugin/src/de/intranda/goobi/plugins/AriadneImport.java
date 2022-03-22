/***************************************************************
 * Copyright notice
 *
 * (c) 2016 Robert Sehr <robert.sehr@intranda.com>
 *
 * All rights reserved
 *
 * This file is part of the Goobi project. The Goobi project is free software;
 * you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * The GNU General Public License can be found at
 * http://www.gnu.org/copyleft/gpl.html.
 *
 * This script is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 *
 * This copyright notice MUST APPEAR in all copies of this file!
 ***************************************************************/

package de.intranda.goobi.plugins;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.configuration.HierarchicalConfiguration;
import org.apache.commons.configuration.XMLConfiguration;
import org.apache.commons.configuration.tree.xpath.XPathExpressionEngine;
import org.apache.commons.lang.StringUtils;
import org.goobi.production.enums.PluginType;
import org.goobi.production.plugin.interfaces.IOpacPlugin;
import org.jdom2.Attribute;
import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.JDOMException;
import org.jdom2.Namespace;
import org.jdom2.filter.Filters;
import org.jdom2.input.SAXBuilder;
import org.jdom2.input.sax.XMLReaders;
import org.jdom2.xpath.XPathFactory;

import de.sub.goobi.config.ConfigPlugins;
import de.sub.goobi.helper.HttpClientHelper;
import de.unigoettingen.sub.search.opac.ConfigOpac;
import de.unigoettingen.sub.search.opac.ConfigOpacCatalogue;
import de.unigoettingen.sub.search.opac.ConfigOpacDoctype;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.log4j.Log4j2;
import net.xeoh.plugins.base.annotations.PluginImplementation;
import ugh.dl.DigitalDocument;
import ugh.dl.DocStruct;
import ugh.dl.DocStructType;
import ugh.dl.Fileformat;
import ugh.dl.Metadata;
import ugh.dl.Prefs;
import ugh.exceptions.MetadataTypeNotAllowedException;
import ugh.exceptions.UGHException;
import ugh.fileformats.mets.MetsMods;

@PluginImplementation
@Log4j2

public class AriadneImport implements IOpacPlugin {

    @Getter
    private PluginType type = PluginType.Opac;
    @Getter
    private String title = "Ariadne";
    @Getter
    @Setter
    private String atstsl;
    @Getter
    private int hitcount = 0;

    private Prefs prefs;

    private XPathFactory xFactory = XPathFactory.instance();

    private Namespace oaiNamespace = Namespace.getNamespace("oai", "http://www.openarchives.org/OAI/2.0/");

    private String ariadneUrl;
    private ConfigOpacDoctype docType;
    private List<MetadataField> metadataList;
    private String collectionPrefix;
    private boolean generateCollection;

    public AriadneImport() {
    }

    @Override
    public Fileformat search(String inSuchfeld, String inSuchbegriff, ConfigOpacCatalogue coc, Prefs inPrefs) throws Exception {

        readConfiguration();

        prefs = inPrefs;
        String url = ariadneUrl + inSuchbegriff;
        String response = HttpClientHelper.getStringFromUrl(url);
        if (StringUtils.isNotBlank(response)) {
            Element eadRecord = getRecordFromResponse(response);
            if (eadRecord == null) {
                // nothing found
                hitcount = 0;
                return null;
            } else {
                hitcount = 1;
            }

            Fileformat ff = getFileformatFromEadRecord(eadRecord);
            return ff;
        }

        return null;
    }

    private void readConfiguration() {

        metadataList = new ArrayList<>();
        XMLConfiguration xmlConfig = ConfigPlugins.getPluginConfig("intranda_opac_ariadne");
        xmlConfig.setExpressionEngine(new XPathExpressionEngine());
        ariadneUrl = xmlConfig.getString("/ariadneUrl");

        collectionPrefix = xmlConfig.getString("/collection/@prefix");
        generateCollection = xmlConfig.getBoolean("/collection/@generate", false);

        String type = xmlConfig.getString("/doctype");

        ConfigOpac co = ConfigOpac.getInstance();
        docType = co.getDoctypeByName(type);
        if (docType == null) {
            docType = co.getAllDoctypes().get(0);
        }

        List<HierarchicalConfiguration> metadataDefinitionList = xmlConfig.configurationsAt("/metadatalist/metadata");
        for (HierarchicalConfiguration metadataDefinition : metadataDefinitionList) {
            MetadataField mf = new MetadataField();
            mf.setRulesetName(metadataDefinition.getString("./@ruleset"));
            mf.setXpath(metadataDefinition.getString("./@xpath"));
            mf.setElementName(metadataDefinition.getString("./@element", "c"));
            mf.setDoctype(metadataDefinition.getString("./@doctype", "logical"));
            mf.setXpathType(metadataDefinition.getString("./@xpathType", "element"));
            mf.setRegex(metadataDefinition.getString("./@replace", null));
            metadataList.add(mf);
        }

    }

    private Fileformat getFileformatFromEadRecord(Element eadRecord) {

        Element tektonikName = xFactory.compile("//oai:eadheader/oai:filedesc/oai:titlestmt/oai:titleproper", Filters.element(), null, oaiNamespace)
                .evaluateFirst(eadRecord);
        String tektonik = tektonikName.getValue();
        Element c = xFactory.compile("//oai:c[not(oai:c)]", Filters.element(), null, oaiNamespace).evaluateFirst(eadRecord);
        Element did = c.getChild("did", oaiNamespace);

        Element upperC = c.getParentElement();
        Element upperDid = upperC.getChild("did", oaiNamespace);

        Fileformat mm = null;
        try {
            mm = new MetsMods(prefs);
            DigitalDocument digDoc = new DigitalDocument();
            mm.setDigitalDocument(digDoc);
            DocStruct anchor = null;
            DocStruct logical = null;
            if (StringUtils.isNotBlank(docType.getRulesetChildType())) {
                anchor = digDoc.createDocStruct(prefs.getDocStrctTypeByName(docType.getRulesetType()));
                digDoc.setLogicalDocStruct(anchor);
                logical = digDoc.createDocStruct(prefs.getDocStrctTypeByName(docType.getRulesetChildType()));
                anchor.addChild(logical);
            } else {
                logical = digDoc.createDocStruct(prefs.getDocStrctTypeByName(docType.getRulesetType()));
            }
            for (MetadataField mf : metadataList) {
                String value = null;

                switch (mf.getElementName()) {
                    case "c":
                        value = compile(mf, c);
                        break;
                    case "did":
                        value = compile(mf, did);
                        break;
                    case "parentC":
                        value = compile(mf, upperC);
                        break;
                    case "parentDid":
                        value = compile(mf, upperDid);
                        break;
                    case "record":
                        value = compile(mf, eadRecord);
                        break;
                    default:
                        log.warn(mf.getElementName() + " is unknown");
                }
                if (value != null) {

                    if (StringUtils.isNotBlank(mf.getRegex())) {
                        value = value.replaceAll(mf.getRegex(), "");
                    }
                    if (mf.getDoctype().equals("anchor")) {
                        addMetadata(anchor, mf.getRulesetName(), value);
                    } else {
                        addMetadata(logical, mf.getRulesetName(), value);
                    }
                }
            }

            if (generateCollection) {
                String collectionName = collectionPrefix + tektonik + "#" + upperDid.getChildText("unittitle");
                addMetadata(logical, "singleDigCollection", collectionName);
                addMetadata(anchor, "singleDigCollection", collectionName);
            }

            DocStructType physicalType = prefs.getDocStrctTypeByName("BoundBook");
            DocStruct physical = digDoc.createDocStruct(physicalType);
            digDoc.setPhysicalDocStruct(physical);
            Metadata imagePath = new Metadata(prefs.getMetadataTypeByName("pathimagefiles"));
            imagePath.setValue("./images/");
            physical.addMetadata(imagePath);
        } catch (UGHException e) {
            log.error(e);
        }

        return mm;
    }

    private String compile(MetadataField mf, Element element) {
        String result = null;
        if (mf.getXpathType().equals("attribute")) {
            Attribute attr = xFactory.compile(mf.getXpath(), Filters.attribute(), null, oaiNamespace).evaluateFirst(element);
            if (attr != null) {
                result = attr.getValue();
            }
        } else {
            Element el = xFactory.compile(mf.getXpath(), Filters.element(), null, oaiNamespace).evaluateFirst(element);
            if (el != null) {
                result = el.getValue();
            }
        }
        // TODO Auto-generated method stub
        return result;
    }

    private Element getRecordFromResponse(String response) {
        SAXBuilder builder = new SAXBuilder(XMLReaders.NONVALIDATING);
        builder.setFeature("http://xml.org/sax/features/validation", false);
        builder.setFeature("http://apache.org/xml/features/nonvalidating/load-dtd-grammar", false);
        builder.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        try {
            Document doc = builder.build(new StringReader(response), "utf-8");
            Element oaiRootElement = doc.getRootElement();

            Element getRecordElement = oaiRootElement.getChild("GetRecord", oaiNamespace);
            if (getRecordElement == null) {
                return null;
            }

            Element record = getRecordElement.getChild("record", oaiNamespace);
            Element metadata = record.getChild("metadata", oaiNamespace);
            Element ead = metadata.getChild("ead", oaiNamespace);
            return ead;
        } catch (JDOMException | IOException e) {
            log.error(e);
        }
        return null;
    }

    @Override
    public ConfigOpacDoctype getOpacDocType() {
        return docType;
    }

    @Override
    public String createAtstsl(String value, String value2) {
        return "";
    }

    @Override
    public String getGattung() {
        return docType.getRulesetType();
    }

    private void addMetadata(DocStruct docStruct, String metadataName, String value) {
        if (StringUtils.isNotBlank(value) && docStruct != null) {
            try {
                Metadata metadata = new Metadata(prefs.getMetadataTypeByName(metadataName));
                metadata.setValue(value);
                docStruct.addMetadata(metadata);
            } catch (MetadataTypeNotAllowedException e) {
                log.error(e);
            }
        }
    }

}
