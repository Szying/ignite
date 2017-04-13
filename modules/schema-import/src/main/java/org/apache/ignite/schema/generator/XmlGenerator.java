/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.schema.generator;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.apache.ignite.cache.QueryEntity;
import org.apache.ignite.cache.QueryIndex;
import org.apache.ignite.cache.store.jdbc.CacheJdbcPojoStoreFactory;
import org.apache.ignite.cache.store.jdbc.JdbcType;
import org.apache.ignite.cache.store.jdbc.JdbcTypeField;
import org.apache.ignite.internal.util.typedef.T2;
import org.apache.ignite.schema.model.PojoDescriptor;
import org.apache.ignite.schema.model.PojoField;
import org.apache.ignite.schema.ui.ConfirmCallable;
import org.apache.ignite.schema.ui.MessageBox;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import static org.apache.ignite.schema.ui.MessageBox.Result.CANCEL;
import static org.apache.ignite.schema.ui.MessageBox.Result.NO;
import static org.apache.ignite.schema.ui.MessageBox.Result.NO_TO_ALL;

/**
 * Generator of XML files for type metadata.
 */
public class XmlGenerator {
    /**
     * Add comment with license and generation date.
     *
     * @param doc XML document.
     */
    private static void addComment(Document doc) {
        doc.appendChild(doc.createComment("\n" +
            "  Licensed to the Apache Software Foundation (ASF) under one or more\n" +
            "  contributor license agreements.  See the NOTICE file distributed with\n" +
            "  this work for additional information regarding copyright ownership.\n" +
            "  The ASF licenses this file to You under the Apache License, Version 2.0\n" +
            "  (the \"License\"); you may not use this file except in compliance with\n" +
            "  the License.  You may obtain a copy of the License at\n\n" +
            "       http://www.apache.org/licenses/LICENSE-2.0\n\n" +
            "  Unless required by applicable law or agreed to in writing, software\n" +
            "  distributed under the License is distributed on an \"AS IS\" BASIS,\n" +
            "  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.\n" +
            "  See the License for the specific language governing permissions and\n" +
            "  limitations under the License.\n"));

        doc.appendChild(doc.createComment("\n    XML generated by Apache Ignite Schema Import utility: " +
            new SimpleDateFormat("MM/dd/yyyy").format(new Date()) + "\n"));
    }

    /**
     * Add bean to XML document.
     *
     * @param doc XML document.
     * @param parent Parent XML node.
     * @param cls Bean class.
     */
    private static Element addBean(Document doc, Node parent, Class<?> cls) {
        Element elem = doc.createElement("bean");

        elem.setAttribute("class", cls.getName());

        parent.appendChild(elem);

        return elem;
    }

    /**
     * Add element to XML document.
     *
     * @param doc XML document.
     * @param parent Parent XML node.
     * @param tagName XML tag name.
     * @param attr1 Name for first attr.
     * @param val1 Value for first attribute.
     * @param attr2 Name for second attr.
     * @param val2 Value for second attribute.
     */
    private static Element addElement(Document doc, Node parent, String tagName,
        String attr1, String val1, String attr2, String val2) {
        Element elem = doc.createElement(tagName);

        if (attr1 != null)
            elem.setAttribute(attr1, val1);

        if (attr2 != null)
            elem.setAttribute(attr2, val2);

        parent.appendChild(elem);

        return elem;
    }

    /**
     * Add element to XML document.
     *
     * @param doc XML document.
     * @param parent Parent XML node.
     * @param tagName XML tag name.
     */
    private static Element addElement(Document doc, Node parent, String tagName) {
        return addElement(doc, parent, tagName, null, null, null, null);
    }

    /**
     * Add element to XML document.
     *
     * @param doc XML document.
     * @param parent Parent XML node.
     * @param tagName XML tag name.
     */
    private static Element addElement(Document doc, Node parent, String tagName, String attrName, String attrVal) {
        return addElement(doc, parent, tagName, attrName, attrVal, null, null);
    }

    /**
     * Add &quot;property&quot; element to XML document.
     *
     * @param doc XML document.
     * @param parent Parent XML node.
     * @param name Value for &quot;name&quot; attribute
     * @param val Value for &quot;value&quot; attribute
     */
    private static Element addProperty(Document doc, Node parent, String name, String val) {
        String valAttr = val != null ? "value" : null;

        return addElement(doc, parent, "property", "name", name, valAttr, val);
    }

    /**
     * Add type descriptors to XML document.
     *
     * @param doc XML document.
     * @param parent Parent XML node.
     * @param name Property name.
     * @param fields Collection of POJO fields.
     */
    private static void addJdbcFields(Document doc, Node parent, String name, Collection<PojoField> fields) {
        if (!fields.isEmpty()) {
            Element prop = addProperty(doc, parent, name, null);

            Element list = addElement(doc, prop, "list");

            for (PojoField field : fields) {
                Element item = addBean(doc, list, JdbcTypeField.class);

                Element dbType = addProperty(doc, item, "databaseFieldType", null);
                addElement(doc, dbType, "util:constant", "static-field", "java.sql.Types." + field.dbTypeName());
                addProperty(doc, item, "databaseFieldName", field.dbName());
                addProperty(doc, item, "javaFieldType", field.javaTypeName());
                addProperty(doc, item, "javaFieldName", field.javaName());
            }
        }
    }

    /**
     * Add query fields to xml document.
     *
     * @param doc XML document.
     * @param parent Parent XML node.
     * @param fields Map with fields.
     */
    private static void addQueryFields(Document doc, Node parent, Collection<PojoField> fields) {
        if (!fields.isEmpty()) {
            Element prop = addProperty(doc, parent, "fields", null);

            Element map = addElement(doc, prop, "util:map", "map-class", "java.util.LinkedHashMap");

            for (PojoField field : fields)
                addElement(doc, map, "entry", "key", field.javaName(), "value",
                    GeneratorUtils.boxPrimitiveType(field.javaTypeName()));
        }
    }

    /**
     * Add query field aliases to xml document.
     *
     * @param doc XML document.
     * @param parent Parent XML node.
     * @param fields Map with fields.
     */
    private static void addQueryFieldAliases(Document doc, Node parent, Collection<PojoField> fields) {
        Collection<PojoField> aliases = new ArrayList<>();

        for (PojoField field : fields) {
            if (!field.javaName().equalsIgnoreCase(field.dbName()))
                aliases.add(field);
        }

        if (!aliases.isEmpty()) {
            Element prop = addProperty(doc, parent, "aliases", null);

            Element map = addElement(doc, prop, "map");

            for (PojoField alias : aliases)
                addElement(doc, map, "entry", "key", alias.javaName(), "value", alias.dbName());
        }
    }

    /**
     * Add indexes to xml document.
     *
     * @param doc XML document.
     * @param parent Parent XML node.
     * @param idxs Indexes.
     */
    private static void addQueryIndexes(Document doc, Node parent, Collection<PojoField> fields,
        Collection<QueryIndex> idxs) {
        if (!idxs.isEmpty()) {
            boolean firstIdx = true;

            Element list = null;

            for (QueryIndex idx : idxs) {
                Set<Map.Entry<String, Boolean>> dbIdxFlds = idx.getFields().entrySet();

                int sz = dbIdxFlds.size();

                List<T2<String, Boolean>> idxFlds = new ArrayList<>(sz);

                for (Map.Entry<String, Boolean> idxFld : dbIdxFlds) {
                    PojoField field = GeneratorUtils.findFieldByName(fields, idxFld.getKey());

                    if (field != null)
                        idxFlds.add(new T2<>(field.javaName(), idxFld.getValue()));
                    else
                        break;
                }

                // Only if all fields present, add index description.
                if (idxFlds.size() == sz) {
                    if (firstIdx) {
                        Element prop = addProperty(doc, parent, "indexes", null);

                        list = addElement(doc, prop, "list");

                        firstIdx = false;
                    }

                    Element idxBean = addBean(doc, list, QueryIndex.class);

                    addProperty(doc, idxBean, "name", idx.getName());

                    Element idxType = addProperty(doc, idxBean, "indexType", null);
                    addElement(doc, idxType, "util:constant", "static-field", "org.apache.ignite.cache.QueryIndexType." + idx.getIndexType());

                    Element flds = addProperty(doc, idxBean, "fields", null);

                    Element fldsMap = addElement(doc, flds, "map");

                    for (T2<String, Boolean> fld : idxFlds)
                        addElement(doc, fldsMap, "entry", "key", fld.getKey(), "value", fld.getValue().toString());
                }
            }
        }
    }

    /**
     * Add element with JDBC POJO store factory to XML document.
     *
     * @param doc XML document.
     * @param parent Parent XML node.
     * @param pkg Package fo types.
     * @param pojo POJO descriptor.
     */
    private static void addJdbcPojoStoreFactory(Document doc, Node parent, String pkg, PojoDescriptor pojo,
        boolean includeKeys) {
        Element bean = addBean(doc, parent, JdbcType.class);

        addProperty(doc, bean, "databaseSchema", pojo.schema());

        addProperty(doc, bean, "databaseTable", pojo.table());

        addProperty(doc, bean, "keyType", pkg + "." + pojo.keyClassName());

        addProperty(doc, bean, "valueType", pkg + "." + pojo.valueClassName());

        addJdbcFields(doc, bean, "keyFields", pojo.keyFields());

        addJdbcFields(doc, bean, "valueFields", pojo.valueFields(includeKeys));
    }

    /**
     * Add element with query entity to XML document.
     *
     * @param doc XML document.
     * @param parent Parent XML node.
     * @param pkg Package fo types.
     * @param pojo POJO descriptor.
     * @param generateAliases {@code true} if aliases should be generated for query fields.
     */
    private static void addQueryEntity(Document doc, Node parent, String pkg, PojoDescriptor pojo, boolean generateAliases) {
        Element bean = addBean(doc, parent, QueryEntity.class);

        addProperty(doc, bean, "keyType", pkg + "." + pojo.keyClassName());

        addProperty(doc, bean, "valueType", pkg + "." + pojo.valueClassName());

        Collection<PojoField> fields = pojo.valueFields(true);

        addQueryFields(doc, bean, fields);

        if (generateAliases)
            addQueryFieldAliases(doc, bean, fields);

        addQueryIndexes(doc, bean, fields, pojo.indexes());
    }

    /**
     * Transform metadata into xml.
     *
     * @param pkg Package fo types.
     * @param pojo POJO descriptor.
     * @param includeKeys {@code true} if key fields should be included into value class.
     * @param generateAliases {@code true} if aliases should be generated for query fields.
     * @param out File to output result.
     * @param askOverwrite Callback to ask user to confirm file overwrite.
     */
    public static void generate(String pkg, PojoDescriptor pojo, boolean includeKeys, boolean generateAliases, File out,
        ConfirmCallable askOverwrite) {
        generate(pkg, Collections.singleton(pojo), includeKeys, generateAliases, out, askOverwrite);
    }

    /**
     * Transform metadata into xml.
     *
     * @param pkg Package fo types.
     * @param pojos POJO descriptors.
     * @param includeKeys {@code true} if key fields should be included into value class.
     * @param generateAliases {@code true} if aliases should be generated for query fields.
     * @param out File to output result.
     * @param askOverwrite Callback to ask user to confirm file overwrite.
     */
    public static void generate(String pkg, Collection<PojoDescriptor> pojos, boolean includeKeys,
        boolean generateAliases, File out, ConfirmCallable askOverwrite) {

        File outFolder = out.getParentFile();

        if (outFolder == null)
            throw new IllegalStateException("Invalid output file: " + out);

        if (!outFolder.exists() && !outFolder.mkdirs())
            throw new IllegalStateException("Failed to create output folder for XML file: " + outFolder);

        try {
            if (out.exists()) {
                MessageBox.Result choice = askOverwrite.confirm(out.getName());

                if (CANCEL == choice)
                    throw new IllegalStateException("XML generation was canceled!");

                if (NO == choice || NO_TO_ALL == choice)
                    return;
            }

            DocumentBuilderFactory docFactory = DocumentBuilderFactory.newInstance();

            DocumentBuilder docBuilder = docFactory.newDocumentBuilder();

            Document doc = docBuilder.newDocument();
            doc.setXmlStandalone(true);

            addComment(doc);

            Element beans = addElement(doc, doc, "beans");
            beans.setAttribute("xmlns", "http://www.springframework.org/schema/beans");
            beans.setAttribute("xmlns:xsi", "http://www.w3.org/2001/XMLSchema-instance");
            beans.setAttribute("xmlns:util", "http://www.springframework.org/schema/util");
            beans.setAttribute("xsi:schemaLocation",
                "http://www.springframework.org/schema/beans " +
                "http://www.springframework.org/schema/beans/spring-beans.xsd " +
                "http://www.springframework.org/schema/util " +
                "http://www.springframework.org/schema/util/spring-util.xsd");

            Element factoryBean = addBean(doc, beans, CacheJdbcPojoStoreFactory.class);
            Element typesElem = addProperty(doc, factoryBean, "types", null);
            Element typesItemsElem = addElement(doc, typesElem, "list");

            for (PojoDescriptor pojo : pojos)
                addJdbcPojoStoreFactory(doc, typesItemsElem, pkg, pojo, includeKeys);

            for (PojoDescriptor pojo : pojos)
                addQueryEntity(doc, beans, pkg, pojo, generateAliases);

            TransformerFactory transformerFactory = TransformerFactory.newInstance();

            Transformer transformer = transformerFactory.newTransformer();

            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "4");

            ByteArrayOutputStream baos = new ByteArrayOutputStream(65536);

            transformer.transform(new DOMSource(doc), new StreamResult(baos));

            // Custom pretty-print of generated XML.
            Files.write(out.toPath(), baos.toString()
                .replaceAll("><", ">\n<")
                .replaceFirst("<!--", "\n<!--")
                .replaceFirst("-->", "-->\n")
                .replaceAll("\" xmlns", "\"\n       xmlns")
                .replaceAll("\" xsi", "\"\n       xsi")
                .replaceAll(" http://www.springframework", "\n                           http://www.springframework")
                .getBytes());
        }
        catch (ParserConfigurationException | TransformerException | IOException e) {
            throw new IllegalStateException(e);
        }
    }
}