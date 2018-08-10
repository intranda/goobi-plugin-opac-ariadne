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

import org.apache.commons.lang.StringUtils;
import org.goobi.production.enums.PluginType;
import org.goobi.production.plugin.interfaces.IOpacPlugin;
import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.JDOMException;
import org.jdom2.Namespace;
import org.jdom2.filter.Filters;
import org.jdom2.input.SAXBuilder;
import org.jdom2.input.sax.XMLReaders;
import org.jdom2.xpath.XPathFactory;

import de.sub.goobi.helper.HttpClientHelper;
import de.unigoettingen.sub.search.opac.ConfigOpac;
import de.unigoettingen.sub.search.opac.ConfigOpacCatalogue;
import de.unigoettingen.sub.search.opac.ConfigOpacDoctype;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.log4j.Log4j;
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
@Log4j

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

    public static void main(String[] args) {
        AriadneImport ai = new AriadneImport();
        Prefs prefs = new Prefs();
        try {
            prefs.loadPrefs("/home/robert/greifswald.xml");
            ai.search("", "obj-5164280", null, prefs);
        } catch (Exception e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }

    public AriadneImport() {
    }

    @Override
    public Fileformat search(String inSuchfeld, String inSuchbegriff, ConfigOpacCatalogue coc, Prefs inPrefs) throws Exception {
        prefs = inPrefs;
        String url =
                "https://ariadne-portal.uni-greifswald.de/?page_id=463&verb=GetRecord&metadataPrefix=goobi_ead&identifier=ariadne-portal.uni-greifswald.de:"
                        + inSuchbegriff;
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

    private Fileformat getFileformatFromEadRecord(Element eadRecord) {

        Element tektonikName = xFactory.compile("//oai:eadheader/oai:filedesc/oai:titlestmt/oai:titleproper", Filters.element(), null, oaiNamespace)
                .evaluateFirst(eadRecord);
        String tektonik = tektonikName.getValue();
        Element c = xFactory.compile("//oai:c[not(oai:c)]", Filters.element(), null, oaiNamespace).evaluateFirst(eadRecord);
        Element did = c.getChild("did", oaiNamespace);
        Fileformat mm = null;
        try {
            mm = new MetsMods(prefs);
            DigitalDocument digDoc = new DigitalDocument();
            mm.setDigitalDocument(digDoc);
            DocStruct volumeRun = digDoc.createDocStruct(prefs.getDocStrctTypeByName("VolumeRun"));
            digDoc.setLogicalDocStruct(volumeRun);
            DocStruct recordDocStruct = digDoc.createDocStruct(prefs.getDocStrctTypeByName("Record"));
            volumeRun.addChild(recordDocStruct);
            addMetadata(recordDocStruct, "CatalogIDDigital", c.getAttributeValue("id"));
            for (Element element : did.getChildren()) {
                if (element.getName().equals("unitid") && element.getAttribute("type") == null) {
                    addMetadata(recordDocStruct, "shelfmarksource", element.getValue());
                } else if (element.getName().equals("unittitle")) {
                    addMetadata(recordDocStruct, "TitleDocMain", element.getValue());
                } else if (element.getName().equals("unitdate")) {
                    addMetadata(recordDocStruct, "Dating", element.getAttributeValue("normal"));
                    addMetadata(recordDocStruct, "Period", element.getValue());
                } else if (element.getName().equals("abstract")) {
                    addMetadata(recordDocStruct, "Contains", element.getValue());
                } else if (element.getName().equals("unitid") && element.getAttributeValue("type") == "Sortierung") {
                    addMetadata(recordDocStruct, "CurrentNo", element.getValue());
                    addMetadata(recordDocStruct, "CurrentNoSorting", element.getValue());
                } else if (element.getName().equals("accessrestrict")) {
                    addMetadata(recordDocStruct, "AccessLicense", element.getChild("p", oaiNamespace).getChild("a", oaiNamespace).getValue());
                }
            }
            //            <unitid type="Altsignatur">Ia 2</unitid>
            //            <physdesc>
            //            <genreform normal="Akten">Sachakten</genreform>
            //            </physdesc>

            // TODO singleDigCollection Archive#100 UAG-HGW# + Tektonik + parent c  #07.02. - Vorlesungen, Index, Fleisslisten

            Element upperC = c.getParentElement();
            Element upperDid = upperC.getChild("did", oaiNamespace);
            if (upperDid != null) {
                String collectionName = "Archive#100 UAG-HGW#" + tektonik + "#" + upperDid.getChildText("unittitle");
                addMetadata(recordDocStruct, "singleDigCollection", collectionName);
                addMetadata(volumeRun, "singleDigCollection", collectionName);
            }
            while (upperC.getAttributeValue("level").equals("class")) {
                String currentId = upperC.getAttributeValue("id");
                String currentTitle = upperC.getChild("did", oaiNamespace).getChildText("unittitle");
                upperC = upperC.getParentElement();
                addMetadata(volumeRun, "TitleDocSub1", currentTitle);
                addMetadata(volumeRun, "Resource", currentId);
            }

            if (upperC.getAttributeValue("level").equals("collection")) {
                Element anchorMetadata = upperC.getChild("did", oaiNamespace);
                for (Element element : anchorMetadata.getChildren()) {
                    if (element.getName().equals("unitid")) {
                        addMetadata(volumeRun, "CatalogIDDigital", element.getValue().replaceAll("\\W", ""));
                        addMetadata(volumeRun, "Resource", element.getValue());
                    } else if (element.getName().equals("unittitle")) {
                        addMetadata(volumeRun, "TitleDocMain", element.getValue());
                    } else if (element.getName().equals("unitdate")) {
                        addMetadata(volumeRun, "Dating", element.getValue());
                    } else if (element.getName().equals("origination") && element.getAttribute("label") == null) {
                        addMetadata(volumeRun, "Contains", element.getValue());
                    } else if (element.getName().equals("origination") && element.getAttribute("label") != null) {
                        addMetadata(volumeRun, "ContainsToo", element.getValue());
                    }
                }
            }

            Element location = xFactory.compile("//oai:archdesc/oai:did/oai:repository/oai:corpname", Filters.element(), null, oaiNamespace)
                    .evaluateFirst(eadRecord);
            addMetadata(recordDocStruct, "PhysicalLocation", location != null ? location.getValue() : "Universitätsbibliothek Greifswald");
            DocStructType physicalType = prefs.getDocStrctTypeByName("BoundBook");
            DocStruct physical = digDoc.createDocStruct(physicalType);
            digDoc.setPhysicalDocStruct(physical);
            Metadata imagePath = new Metadata(prefs.getMetadataTypeByName("pathimagefiles"));
            imagePath.setValue("./images/");
            physical.addMetadata(imagePath);
        } catch (UGHException e) {
            log.error(e);
        }

        /*

        <DocStrctType anchor="true">
        <Name>VolumeRun</Name>
        <language name="de">BandSerie</language>
        <language name="en">VolumeRun</language>
        <language name="es">Serie de volúmenes</language>
        <allowedchildtype>Record</allowedchildtype>
        <allowedchildtype>Volume</allowedchildtype>
        <metadata num="1o">CatalogIDSource</metadata>
        <metadata num="*">TitleDocSub1</metadata>
        <metadata num="1o">CatalogIDDigital</metadata>
        <metadata num="*" DefaultDisplay="true">singleDigCollection</metadata>
        <metadata num="*">Creator</metadata>
        <metadata num="1o">Contains</metadata>
        <metadata num="*">ContainsToo</metadata>
        <metadata num="*">_urn</metadata>
        <metadata num="+" DefaultDisplay="true">TitleDocMain</metadata>
        <metadata num="*" DefaultDisplay="true">AccessLicense</metadata>
        <metadata num="*" DefaultDisplay="true">Resource</metadata>
        <metadata num="*">_ucc_id</metadata>
        <metadata num="*">OtherOldPrints</metadata>
        <metadata num="*">Dedicatee</metadata>
        <metadata num="*">Contributor</metadata>
        <metadata num="1o" DefaultDisplay="true">PublicationStart</metadata>
        <metadata num="1o" DefaultDisplay="true">PublicationEnd</metadata>
        <metadata num="*" DefaultDisplay="true">Dating</metadata>
        <metadata num="1o">PublicationRun</metadata>
        <metadata num="1o">PublicationYear</metadata>
         */

        /*

        <DocStrctType topStruct="true">
        <Name>Record</Name>
        <metadata num="*">Creator</metadata>
        <metadata num="*">ContainsToo</metadata>
        <metadata num="*">TitleDocSub1</metadata>
        <metadata num="1o" DefaultDisplay="true">CatalogIDSource</metadata>
        <metadata num="*" DefaultDisplay="true">singleDigCollection</metadata>
        <metadata num="*">_urn</metadata>
        <metadata num="*">Dedicatee</metadata>
        <metadata num="*">OtherOldPrints</metadata>
        <metadata num="*">Contributor</metadata>
        <metadata num="*" DefaultDisplay="true">Resource</metadata>
        <metadata num="*">_ucc_id</metadata>
        <metadata num="1o">copyrightimageset</metadata>
        <metadata num="1o">FormatSourcePrint</metadata>
        <metadata num="1o">SizeSourcePrint</metadata>
        <metadata num="*" DefaultDisplay="true">DocLanguage</metadata>
        <metadata num="*">PhysicalLocation</metadata>
        <metadata num="*">Format</metadata>

         */
        // VolumeRun, Record

        return mm;
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
        ConfigOpac co = ConfigOpac.getInstance();
        ConfigOpacDoctype cod = co.getDoctypeByName("volumerun");
        if (cod == null) {
            cod = co.getAllDoctypes().get(0);
        }
        return cod;
    }

    @Override
    public String createAtstsl(String value, String value2) {
        // TODO Auto-generated method stub
        return "";
    }

    @Override
    public String getGattung() {
        // TODO Auto-generated method stub
        return "VolumeRun";
    }

    private void addMetadata(DocStruct docStruct, String metadataName, String value) {
        if (StringUtils.isNotBlank(value)) {
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
