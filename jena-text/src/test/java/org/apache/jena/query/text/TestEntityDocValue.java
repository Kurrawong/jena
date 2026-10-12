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

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.*;

import org.apache.jena.assembler.Assembler;
import org.apache.jena.atlas.lib.IRILib;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.query.*;
import org.apache.jena.rdf.model.*;
import org.apache.jena.sparql.engine.binding.Binding;
import org.apache.jena.sys.JenaSystem;
import org.apache.jena.system.Txn;
import org.apache.jena.vocabulary.RDF;
import org.apache.lucene.index.BinaryDocValues;
import org.apache.lucene.index.DocValuesType;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.FieldInfos;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.util.Bits;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The entity IRI doc value: written beside the stored copy, read in place of it, and
 * absent from indexes written before it existed, which fall back to the stored copy.
 */
public class TestEntityDocValue {

    static {
        JenaSystem.init();
        TextQuery.init();
    }

    private static final String NS = "http://example.org/";
    private static final String FIELD = "urn:jena:lucene:field#";

    private static final String PREFIXES =
        "PREFIX luc:   <urn:jena:lucene:index#>\n"
        + "PREFIX field: <" + FIELD + ">\n"
        + "PREFIX ex:    <" + NS + ">\n";

    private static String config(String directory) {
        return "@prefix idx:   <urn:jena:lucene:index#> .\n"
            + "@prefix field: <" + FIELD + "> .\n"
            + "@prefix sh:    <http://www.w3.org/ns/shacl#> .\n"
            + "@prefix text:  <http://jena.apache.org/text#> .\n"
            + "@prefix ja:    <http://jena.hpl.hp.com/2005/11/Assembler#> .\n"
            + "@prefix rdf:   <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .\n"
            + "@prefix ex:    <" + NS + "> .\n"
            + "field:body  idx:fieldName \"body\" ; idx:fieldType idx:TextField ; idx:defaultSearch true .\n"
            + "field:code  idx:fieldName \"code\" ; idx:fieldType idx:KeywordField ; idx:sortable true .\n"
            + "field:idValue idx:fieldName \"idValue\" ; idx:fieldType idx:KeywordField .\n"
            + "ex:DocShape\n"
            + "    sh:targetClass ex:Doc ;\n"
            + "    sh:property [ idx:field field:body ; sh:path ex:body ] ;\n"
            + "    sh:property [ idx:field field:code ; sh:path ex:code ] ;\n"
            + "    idx:nested [\n"
            + "        idx:joinPath ex:identifier ;\n"
            + "        idx:property [ idx:field field:idValue ; sh:path ex:idValue ] ;\n"
            + "    ] .\n"
            + "ex:dataset rdf:type text:TextDataset ;\n"
            + "    text:dataset ex:base ;\n"
            + "    text:index ex:index .\n"
            + "ex:base rdf:type ja:MemoryDataset .\n"
            + "ex:index rdf:type text:TextIndexShacl ;\n"
            + "    text:directory " + directory + " ;\n"
            + "    text:storeValues true ;\n"
            + "    text:shapes ( ex:DocShape ) .\n";
    }

    private Dataset dataset;
    private ShaclTextIndexLucene textIndex;

    @AfterEach
    public void tearDown() {
        close();
    }

    private void close() {
        if (textIndex != null) {
            textIndex.setHitReadObserver(null);
        }
        if (dataset != null) {
            dataset.close();
        }
        dataset = null;
        textIndex = null;
    }

    /** Open; with {@code legacy}, documents are written without the entity doc value. */
    private void open(String directory, boolean legacy) {
        Model config = ModelFactory.createDefaultModel();
        config.read(new StringReader(config(directory)), null, "TTL");
        dataset = (Dataset) Assembler.general().open(config.getResource(NS + "dataset"));
        textIndex = (ShaclTextIndexLucene) ((DatasetGraphText) dataset.asDatasetGraph()).getTextIndex();
        textIndex.setLegacyNoEntityDocValue(legacy);
    }

    /** "delta" ranks e1 (three times), e3 (twice), e2 (once); codes sort e2, e3, e1. */
    private void load() {
        Txn.executeWrite(dataset, () -> {
            Model m = dataset.getDefaultModel();
            Resource e1 = addDoc(m, "e1", "common delta delta delta", "C3");
            Resource ident = m.createResource();
            ident.addProperty(m.createProperty(NS + "idValue"), "10/1");
            e1.addProperty(m.createProperty(NS + "identifier"), ident);
            addDoc(m, "e2", "common alpha delta", "C1");
            addDoc(m, "e3", "common beta delta delta", "C2");
        });
    }

    private static Resource addDoc(Model m, String id, String body, String code) {
        Resource r = m.createResource(NS + id);
        r.addProperty(RDF.type, m.createResource(NS + "Doc"));
        r.addProperty(m.createProperty(NS + "body"), body);
        r.addProperty(m.createProperty(NS + "code"), code);
        return r;
    }

    private List<Node> entities(String sort) {
        String q = PREFIXES + "SELECT ?entity WHERE { (?hit ?entity) luc:query "
            + "(\"default\" \"default\" \"delta\" \"\" '" + sort + "' 10 0) . }";
        return Txn.calculateRead(dataset, () -> {
            try (QueryExecution qe = QueryExecution.dataset(dataset).query(q).build()) {
                List<Node> rows = new ArrayList<>();
                ResultSet rs = qe.execSelect();
                while (rs.hasNext()) {
                    Binding b = rs.nextBinding();
                    rows.add(b.get("entity"));
                }
                return rows;
            }
        });
    }

    private static Node ex(String local) {
        return NodeFactory.createURI(NS + local);
    }

    private static final String CODE_ASC = "[{\"field\":\"" + FIELD + "code\",\"order\":\"asc\"}]";

    private void assertSameIris() {
        assertEquals(List.of(ex("e1"), ex("e3"), ex("e2")), entities(""), "relevance");
        assertEquals(List.of(ex("e2"), ex("e3"), ex("e1")), entities(CODE_ASC), "sort");
    }

    private DocValuesType entityDocValuesType() {
        IndexSearcher searcher = textIndex.acquireSearcher();
        try {
            FieldInfo info = FieldInfos.getMergedFieldInfos(searcher.getIndexReader())
                .fieldInfo(ShaclTextIndexLucene.entityDocValueField(textIndex.getDocDef().getEntityField()));
            return info == null ? DocValuesType.NONE : info.getDocValuesType();
        } finally {
            textIndex.releaseSearcher(searcher);
        }
    }

    /** Counts stored-field and doc-value reads of the entity field. */
    private static final class Counter implements HitReadObserver {
        private final String entityField;
        int stored;
        int docValues;

        Counter(String entityField) {
            this.entityField = entityField;
        }

        @Override
        public synchronized void storedFieldsRead(int docId, Collection<String> fieldNames) {
            if (fieldNames.contains(entityField)) {
                stored++;
            }
        }

        @Override
        public synchronized void docValuesRead(int docId, String fieldName) {
            if (entityField.equals(fieldName)) {
                docValues++;
            }
        }
    }

    private Counter count() {
        Counter counter = new Counter(textIndex.getDocDef().getEntityField());
        textIndex.setHitReadObserver(counter);
        return counter;
    }

    @Test
    public void newIndexWritesABinaryDocValueAndReadsIt() {
        open("\"mem\"", false);
        load();
        assertEquals(DocValuesType.BINARY, entityDocValuesType());
        Counter counter = count();
        assertSameIris();
        assertEquals(0, counter.stored, "no stored read of the entity field");
        assertEquals(6, counter.docValues);
    }

    @Test
    public void indexWithoutTheDocValueFallsBackToTheStoredCopy() {
        open("\"mem\"", true);
        load();
        assertEquals(DocValuesType.NONE, entityDocValuesType());
        Counter counter = count();
        assertSameIris();
        assertEquals(6, counter.stored);
        assertEquals(0, counter.docValues);
    }

    /**
     * One entry per live document, sorted: the local name of its stored entity field, then
     * that of its entity doc value, or "-" when it has none.
     */
    private List<String> entityDocValuesByDocument() throws IOException {
        String entityField = textIndex.getDocDef().getEntityField();
        String docValueField = ShaclTextIndexLucene.entityDocValueField(entityField);
        IndexSearcher searcher = textIndex.acquireSearcher();
        try {
            List<String> docs = new ArrayList<>();
            for (LeafReaderContext leaf : searcher.getIndexReader().leaves()) {
                LeafReader reader = leaf.reader();
                BinaryDocValues values = reader.getBinaryDocValues(docValueField);
                Bits live = reader.getLiveDocs();
                for (int i = 0; i < reader.maxDoc(); i++) {
                    if (live != null && !live.get(i)) {
                        continue;
                    }
                    String entity = reader.storedFields().document(i).get(entityField);
                    String docValue = values != null && values.advanceExact(i)
                        ? values.binaryValue().utf8ToString().substring(NS.length()) : "-";
                    docs.add(entity.substring(NS.length()) + "=" + docValue);
                }
            }
            Collections.sort(docs);
            return docs;
        } finally {
            textIndex.releaseSearcher(searcher);
        }
    }

    @Test
    public void onlyParentDocumentsCarryTheDocValue() throws IOException {
        open("\"mem\"", false);
        load();
        // e1 has one nested child, which carries the entity field but not the doc value.
        assertEquals(List.of("e1=-", "e1=e1", "e2=e2", "e3=e3"), entityDocValuesByDocument());
    }

    @Test
    public void entityUpdateAndDeleteReplaceTheWholeBlock() throws IOException {
        open("\"mem\"", false);
        load();
        Txn.executeWrite(dataset, () -> {
            Model m = dataset.getDefaultModel();
            Resource e1 = m.createResource(NS + "e1");
            e1.removeAll(m.createProperty(NS + "code"));
            e1.addProperty(m.createProperty(NS + "code"), "C0");
            m.removeAll(m.createResource(NS + "e2"), null, null);
        });
        assertEquals(List.of("e1=-", "e1=e1", "e3=e3"), entityDocValuesByDocument());
        Counter counter = count();
        assertEquals(List.of(ex("e1"), ex("e3")), entities(CODE_ASC));
        assertEquals(0, counter.stored);
    }

    @Test
    public void segmentWithoutTheDocValueFallsBackToTheStoredCopy(@TempDir Path dir) {
        String directory = "<" + IRILib.filenameToIRI(dir.toAbsolutePath().toString()) + ">";
        // Written before the doc value existed.
        open(directory, true);
        load();
        close();

        // A new document gets the doc value; the old segment has none.
        open(directory, false);
        Txn.executeWrite(dataset, () -> addDoc(dataset.getDefaultModel(), "e4", "common delta", "C4"));
        assertEquals(DocValuesType.BINARY, entityDocValuesType());
        Counter counter = count();
        assertEquals(List.of(ex("e2"), ex("e3"), ex("e1"), ex("e4")), entities(CODE_ASC));
        assertEquals(3, counter.stored, "e1 to e3 from the stored copy");
        assertEquals(1, counter.docValues, "e4 from the doc value");
    }
}
