/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 *   SPDX-License-Identifier: Apache-2.0
 */

package org.apache.jena.query.text;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.*;

import org.apache.jena.assembler.Assembler;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.query.text.assembler.TextAssembler;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.sys.JenaSystem;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.index.*;
import org.apache.lucene.util.Bits;

/**
 * Builds a SHACL-mode index from a Turtle shapes configuration and reads back the Lucene
 * documents it writes. Shared by the tests of {@code sh:node} steps and of occurrence
 * value constraints.
 */
final class ShaclTurtleFixture {

    static final String EX = "http://example.org/";
    static final String SCHEMA = "https://schema.org/";

    static final String PREFIXES = String.join("\n",
        "@prefix sh:     <http://www.w3.org/ns/shacl#> .",
        "@prefix idx:    <urn:jena:lucene:index#> .",
        "@prefix field:  <urn:jena:lucene:field#> .",
        "@prefix text:   <http://jena.apache.org/text#> .",
        "@prefix rdf:    <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .",
        "@prefix rdfs:   <http://www.w3.org/2000/01/rdf-schema#> .",
        "@prefix xsd:    <http://www.w3.org/2001/XMLSchema#> .",
        "@prefix schema: <https://schema.org/> .",
        "@prefix ex:     <http://example.org/> .",
        "");

    static {
        JenaSystem.init();
        TextAssembler.init();
        TextQuery.init();
    }

    private ShaclTurtleFixture() {}

    static Node uri(String localName) {
        return NodeFactory.createURI(EX + localName);
    }

    static Model parse(String turtle) {
        Model model = ModelFactory.createDefaultModel();
        RDFParser.fromString(PREFIXES + turtle, Lang.TURTLE).parse(model);
        return model;
    }

    /**
     * Open an in-memory index whose {@code text:shapes} list is {@code ( ex:Shape )}.
     * {@code shapesTurtle} declares {@code ex:Shape} and its canonical fields.
     */
    static ShaclTextIndexLucene openIndex(String shapesTurtle) {
        Model model = parse(shapesTurtle + "\n"
            + "ex:index a text:TextIndexShacl ; text:directory \"mem\" ; text:shapes ( ex:Shape ) .\n");
        return (ShaclTextIndexLucene) Assembler.general().open(model.createResource(EX + "index"));
    }

    /** A dataset whose changes reach {@code index} through {@link ShaclTextDocProducer}. */
    static Dataset liveDataset(ShaclTextIndexLucene index) {
        Dataset base = DatasetFactory.create();
        return TextDatasetFactory.create(base, index, true,
            new ShaclTextDocProducer(base.asDatasetGraph(), index, index.getShaclMapping()));
    }

    /** Build the entity for {@code subject} as both indexing paths do, without Lucene. */
    static Entity build(ShaclTextIndexLucene index, Graph data, Node subject) {
        ShaclIndexMapping.IndexProfile profile = index.getShaclMapping().getProfiles().get(0);
        return ShaclEntityBuilder.buildEntity(data, subject, subject.getURI(), profile);
    }

    /** The values an entity carries for {@code fieldName}, as strings, in a sorted list. */
    static List<String> values(Entity entity, String fieldName) {
        return asSortedStrings(entity.get(fieldName));
    }

    static List<String> values(Entity.NestedRecord record, String fieldName) {
        return asSortedStrings(record.get(fieldName));
    }

    private static List<String> asSortedStrings(Object value) {
        List<String> result = new ArrayList<>();
        if (value instanceof Collection<?> collection) {
            collection.forEach(v -> result.add(String.valueOf(v)));
        } else if (value != null) {
            result.add(String.valueOf(value));
        }
        Collections.sort(result);
        return result;
    }

    /** The parent document of {@code entityUri}, or null when the entity has none. */
    static Document parentDoc(ShaclTextIndexLucene index, String entityUri) {
        for (Document doc : docsOf(index, entityUri)) {
            if (doc.get(ShaclTextIndexLucene.NESTED_SCOPE_FIELD) == null) {
                return doc;
            }
        }
        return null;
    }

    /** The child documents of {@code entityUri}, in index order. */
    static List<Document> childDocs(ShaclTextIndexLucene index, String entityUri) {
        List<Document> children = new ArrayList<>();
        for (Document doc : docsOf(index, entityUri)) {
            if (doc.get(ShaclTextIndexLucene.NESTED_SCOPE_FIELD) != null) {
                children.add(doc);
            }
        }
        return children;
    }

    /**
     * Values of {@code fieldName} on {@code doc}, sorted; empty when doc is null. The
     * documents this fixture returns carry a field held only in doc values as if it were
     * stored, so a value is found wherever the index keeps it.
     */
    static List<String> stored(Document doc, String fieldName) {
        List<String> result = new ArrayList<>();
        if (doc == null) {
            return result;
        }
        result.addAll(Arrays.asList(doc.getValues(fieldName)));
        Collections.sort(result);
        return result;
    }

    /** The single-valued sort key of {@code fieldName} on the entity's parent document. */
    static String sortKey(ShaclTextIndexLucene index, String entityUri, String fieldName) {
        try (DirectoryReader reader = DirectoryReader.open(index.getDirectory())) {
            for (LeafReaderContext leaf : reader.leaves()) {
                LeafReader leafReader = leaf.reader();
                StoredFields storedFields = leafReader.storedFields();
                Bits live = leafReader.getLiveDocs();
                SortedDocValues sorted = leafReader.getSortedDocValues(fieldName);
                for (int i = 0; i < leafReader.maxDoc(); i++) {
                    if (live != null && !live.get(i)) {
                        continue;
                    }
                    Document doc = storedFields.document(i);
                    if (!entityUri.equals(doc.get("uri"))
                            || doc.get(ShaclTextIndexLucene.NESTED_SCOPE_FIELD) != null) {
                        continue;
                    }
                    if (sorted == null || !sorted.advanceExact(i)) {
                        return null;
                    }
                    return sorted.lookupOrd(sorted.ordValue()).utf8ToString();
                }
            }
            return null;
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /**
     * Every document of every entity, rendered as sorted stored field values, keyed by
     * entity IRI. Two indexes holding the same documents render identically.
     */
    static Map<String, List<String>> renderAll(ShaclTextIndexLucene index) {
        Map<String, List<String>> result = new TreeMap<>();
        for (Document doc : allDocs(index)) {
            String entity = doc.get("uri");
            if (entity == null) {
                continue;
            }
            List<String> fields = new ArrayList<>();
            doc.getFields().forEach(f -> {
                if (f.stringValue() != null) {
                    fields.add(f.name() + "=" + f.stringValue());
                } else if (f.numericValue() != null) {
                    fields.add(f.name() + "=" + f.numericValue());
                }
            });
            Collections.sort(fields);
            result.computeIfAbsent(entity, k -> new ArrayList<>()).add(String.join(" ", fields));
        }
        result.values().forEach(Collections::sort);
        return result;
    }

    private static List<Document> docsOf(ShaclTextIndexLucene index, String entityUri) {
        List<Document> docs = new ArrayList<>();
        for (Document doc : allDocs(index)) {
            if (entityUri.equals(doc.get("uri"))) {
                docs.add(doc);
            }
        }
        return docs;
    }

    private static List<Document> allDocs(ShaclTextIndexLucene index) {
        List<Document> docs = new ArrayList<>();
        try {
            if (!DirectoryReader.indexExists(index.getDirectory())) {
                return docs;
            }
            try (DirectoryReader reader = DirectoryReader.open(index.getDirectory())) {
                List<ShaclIndexMapping.FieldDef> docValueFields = docValueFields(index);
                for (LeafReaderContext leaf : reader.leaves()) {
                    LeafReader leafReader = leaf.reader();
                    StoredFields storedFields = leafReader.storedFields();
                    Bits live = leafReader.getLiveDocs();
                    FieldValueReader values = new FieldValueReader(null);
                    values.startLeaf(leaf);
                    for (int i = 0; i < leafReader.maxDoc(); i++) {
                        if (live == null || live.get(i)) {
                            Document doc = storedFields.document(i);
                            addDocValues(values, docValueFields, i, doc);
                            docs.add(doc);
                        }
                    }
                }
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        return docs;
    }

    /** Every field whose value the index can hold in a doc value alone, once each by name. */
    private static List<ShaclIndexMapping.FieldDef> docValueFields(ShaclTextIndexLucene index) {
        Map<String, ShaclIndexMapping.FieldDef> byName = new TreeMap<>();
        for (ShaclIndexMapping.IndexProfile profile : index.getShaclMapping().getProfiles()) {
            List<ShaclIndexMapping.FieldDef> fields = new ArrayList<>(profile.getFields());
            profile.getNestedDefs().forEach(nested -> fields.addAll(nested.getFields()));
            for (ShaclIndexMapping.FieldDef field : fields) {
                if (field.isValueInDocValues()) {
                    byName.putIfAbsent(field.getFieldName(), field);
                }
            }
        }
        return new ArrayList<>(byName.values());
    }

    /** Add each doc-value-only value of {@code fields} on one document to it as a stored field. */
    private static void addDocValues(FieldValueReader reader, List<ShaclIndexMapping.FieldDef> fields,
            int segmentDocId, Document doc) throws IOException {
        for (ShaclIndexMapping.FieldDef field : fields) {
            if (!reader.storedNames(List.of(field)).isEmpty()) {
                continue;
            }
            for (String value : reader.values(field, segmentDocId, doc).lexicals()) {
                doc.add(new StoredField(field.getFieldName(), value));
            }
        }
    }

    /** Messages logged by {@code loggerClass} while {@code body} runs. */
    static List<String> captureLogs(Class<?> loggerClass, Runnable body) {
        org.apache.logging.log4j.core.Logger logger =
            (org.apache.logging.log4j.core.Logger) org.apache.logging.log4j.LogManager.getLogger(loggerClass);
        List<String> messages = Collections.synchronizedList(new ArrayList<>());
        org.apache.logging.log4j.core.appender.AbstractAppender appender =
            new org.apache.logging.log4j.core.appender.AbstractAppender(
                    "capture-" + System.nanoTime(), null, null, true, null) {
                @Override
                public void append(org.apache.logging.log4j.core.LogEvent event) {
                    messages.add(event.getMessage().getFormattedMessage());
                }
            };
        appender.start();
        logger.addAppender(appender);
        try {
            body.run();
        } finally {
            logger.removeAppender(appender);
            appender.stop();
        }
        return messages;
    }

    /** The messages of {@code ex} and all its causes, joined. */
    static String messageChain(Throwable ex) {
        StringBuilder sb = new StringBuilder();
        for (Throwable t = ex; t != null; t = t.getCause()) {
            sb.append(t.getMessage()).append('\n');
        }
        return sb.toString();
    }
}
