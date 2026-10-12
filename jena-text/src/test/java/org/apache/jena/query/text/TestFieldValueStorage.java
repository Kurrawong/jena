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
import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.query.*;
import org.apache.jena.rdf.model.*;
import org.apache.jena.sparql.engine.binding.Binding;
import org.apache.jena.sys.JenaSystem;
import org.apache.jena.system.Txn;
import org.apache.jena.vocabulary.RDF;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DocValuesType;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.FieldInfos;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.search.IndexSearcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Where field values are kept, and that every field returns its original term.
 * <p>
 * A field's doc value holds its original value when it is a {@code KEYWORD} with
 * {@code idx:sortable}, no {@code idx:normalizer} and no {@code idx:storeLiteralMetadata}.
 * Such a field is written as a doc value only; every other stored field keeps a stored copy.
 */
public class TestFieldValueStorage {

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

    private static String config(String directory, boolean codeSortable) {
        return "@prefix idx:   <urn:jena:lucene:index#> .\n"
            + "@prefix field: <" + FIELD + "> .\n"
            + "@prefix sh:    <http://www.w3.org/ns/shacl#> .\n"
            + "@prefix text:  <http://jena.apache.org/text#> .\n"
            + "@prefix ja:    <http://jena.hpl.hp.com/2005/11/Assembler#> .\n"
            + "@prefix rdf:   <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .\n"
            + "@prefix ex:    <" + NS + "> .\n"
            + "field:title     idx:fieldName \"title\" ; idx:fieldType idx:TextField ; idx:defaultSearch true .\n"
            + "field:code      idx:fieldName \"code\" ; idx:fieldType idx:KeywordField ; idx:sortable "
            + codeSortable + " .\n"
            + "field:link      idx:fieldName \"link\" ; idx:fieldType idx:KeywordField ; idx:sortable true .\n"
            + "field:tags      idx:fieldName \"tags\" ; idx:fieldType idx:KeywordField ; idx:sortable true ;"
            + " idx:multiValued true .\n"
            + "field:links     idx:fieldName \"links\" ; idx:fieldType idx:KeywordField ; idx:sortable true ;"
            + " idx:multiValued true .\n"
            + "field:hidden    idx:fieldName \"hidden\" ; idx:fieldType idx:KeywordField ; idx:sortable true ;"
            + " idx:stored false .\n"
            + "field:norm      idx:fieldName \"norm\" ; idx:fieldType idx:KeywordField ; idx:sortable true ;"
            + " idx:normalizer [ a text:LowerCaseKeywordAnalyzer ] .\n"
            + "field:facetOnly idx:fieldName \"facetOnly\" ; idx:fieldType idx:KeywordField ; idx:facetable true .\n"
            + "field:langKw    idx:fieldName \"langKw\" ; idx:fieldType idx:KeywordField ; idx:sortable true ;"
            + " idx:storeLiteralMetadata true .\n"
            + "field:num       idx:fieldName \"num\" ; idx:fieldType idx:IntField ; idx:sortable true .\n"
            + "field:when      idx:fieldName \"when\" ; idx:fieldType idx:TemporalField ; idx:sortable true ;"
            + " idx:storeLiteralMetadata true .\n"
            + "field:idType    idx:fieldName \"idType\" ; idx:fieldType idx:KeywordField .\n"
            + "field:idValue   idx:fieldName \"idValue\" ; idx:fieldType idx:KeywordField ; idx:sortable true .\n"
            + "ex:DocShape\n"
            + "    sh:targetClass ex:Doc ;\n"
            + "    sh:property [ idx:field field:title ; sh:path ex:title ] ;\n"
            + "    sh:property [ idx:field field:code ; sh:path ex:code ] ;\n"
            + "    sh:property [ idx:field field:link ; sh:path ex:link ] ;\n"
            + "    sh:property [ idx:field field:tags ; sh:path ex:tag ] ;\n"
            + "    sh:property [ idx:field field:links ; sh:path ex:links ] ;\n"
            + "    sh:property [ idx:field field:hidden ; sh:path ex:hidden ] ;\n"
            + "    sh:property [ idx:field field:norm ; sh:path ex:norm ] ;\n"
            + "    sh:property [ idx:field field:facetOnly ; sh:path ex:facetOnly ] ;\n"
            + "    sh:property [ idx:field field:langKw ; sh:path ex:langKw ] ;\n"
            + "    sh:property [ idx:field field:num ; sh:path ex:num ] ;\n"
            + "    sh:property [ idx:field field:when ; sh:path ex:when ] ;\n"
            + "    idx:nested [\n"
            + "        idx:joinPath ex:identifier ;\n"
            + "        idx:property [ idx:field field:idType ; sh:path ex:idType ] ;\n"
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

    private static final Node LINK = NodeFactory.createURI(NS + "target/1");
    private static final Node LINK_A = NodeFactory.createURI(NS + "target/a");
    private static final Node LINK_B = NodeFactory.createURI(NS + "target/b");

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

    private void open(String directory, boolean codeSortable) {
        Model config = ModelFactory.createDefaultModel();
        config.read(new StringReader(config(directory, codeSortable)), null, "TTL");
        dataset = (Dataset) Assembler.general().open(config.getResource(NS + "dataset"));
        textIndex = (ShaclTextIndexLucene) ((DatasetGraphText) dataset.asDatasetGraph()).getTextIndex();
    }

    private void load() {
        Txn.executeWrite(dataset, () -> {
            Model m = dataset.getDefaultModel();
            Resource e1 = doc(m, "e1", "alpha one", "C1");
            e1.addProperty(m.createProperty(NS + "link"), m.createResource(LINK.getURI()));
            e1.addProperty(m.createProperty(NS + "tag"), "red");
            e1.addProperty(m.createProperty(NS + "tag"), "blue");
            e1.addProperty(m.createProperty(NS + "links"), m.createResource(LINK_B.getURI()));
            e1.addProperty(m.createProperty(NS + "links"), m.createResource(LINK_A.getURI()));
            e1.addProperty(m.createProperty(NS + "hidden"), "H1");
            e1.addProperty(m.createProperty(NS + "norm"), "MiXed Case");
            e1.addProperty(m.createProperty(NS + "facetOnly"), "Fac One");
            e1.addProperty(m.createProperty(NS + "langKw"), m.createLiteral("chat", "fr"));
            e1.addProperty(m.createProperty(NS + "num"), m.createTypedLiteral("007", XSDDatatype.XSDinteger));
            e1.addProperty(m.createProperty(NS + "when"),
                m.createTypedLiteral("2020-01-01T10:00:00+05:00", XSDDatatype.XSDdateTime));
            Resource ident = m.createResource();
            ident.addProperty(m.createProperty(NS + "idType"), "doi");
            ident.addProperty(m.createProperty(NS + "idValue"), "10/1");
            e1.addProperty(m.createProperty(NS + "identifier"), ident);

            Resource e2 = doc(m, "e2", "beta two", "C2");
            e2.addProperty(m.createProperty(NS + "tag"), "red");
        });
    }

    private static Resource doc(Model m, String id, String title, String code) {
        Resource r = m.createResource(NS + id);
        r.addProperty(RDF.type, m.createResource(NS + "Doc"));
        r.addProperty(m.createProperty(NS + "title"), title);
        r.addProperty(m.createProperty(NS + "code"), code);
        return r;
    }

    private void openMemoryAndLoad() {
        open("\"mem\"", true);
        load();
    }

    private List<Binding> select(String where) {
        String q = PREFIXES + "SELECT * WHERE {\n" + where + "\n}";
        return Txn.calculateRead(dataset, () -> {
            try (QueryExecution qe = QueryExecution.dataset(dataset).query(q).build()) {
                List<Binding> rows = new ArrayList<>();
                ResultSet rs = qe.execSelect();
                while (rs.hasNext()) {
                    rows.add(rs.nextBinding());
                }
                return rows;
            }
        });
    }

    private static final String E1_ONLY = "(\"default\" \"default\" \"alpha\" \"\" \"\" 10 0)";

    /** The values {@code luc:fields} returns for e1 on one field. */
    private Set<Node> valuesOfE1(String fieldLocal) {
        Set<Node> values = new HashSet<>();
        for (Binding b : select("(?hit) luc:query " + E1_ONLY + " .\n"
                + "(?hit field:" + fieldLocal + " ?v) luc:fields () .")) {
            values.add(b.get("v"));
        }
        return values;
    }

    private static Node str(String s) {
        return NodeFactory.createLiteralString(s);
    }

    /** Records every stored-field read. */
    private static final class Counter implements HitReadObserver {
        final List<Collection<String>> reads = new ArrayList<>();

        @Override
        public synchronized void storedFieldsRead(int docId, Collection<String> fieldNames) {
            reads.add(new ArrayList<>(fieldNames));
        }

        boolean anyRead(String fieldName) {
            for (Collection<String> fields : reads) {
                if (fields.contains(fieldName)) {
                    return true;
                }
            }
            return false;
        }
    }

    private Counter count() {
        Counter counter = new Counter();
        textIndex.setHitReadObserver(counter);
        return counter;
    }

    // ---- Eligibility -----------------------------------------------------------------

    @Test
    public void eligibilityIsDecidedPerField() {
        openMemoryAndLoad();
        ShaclIndexMapping mapping = textIndex.getShaclMapping();
        for (String eligible : List.of("code", "link", "tags", "links", "hidden", "idValue")) {
            assertTrue(mapping.findField(FIELD + eligible).isValueInDocValues(), eligible);
        }
        for (String other : List.of("title", "norm", "facetOnly", "langKw", "num", "when", "idType")) {
            assertFalse(mapping.findField(FIELD + other).isValueInDocValues(), other);
        }
    }

    // ---- What is written -------------------------------------------------------------

    /** The stored fields of the parent document of {@code entity}, and of its children. */
    private List<Document> storedDocs(String entity) throws IOException {
        IndexSearcher searcher = textIndex.acquireSearcher();
        try {
            IndexReader reader = searcher.getIndexReader();
            StoredFields stored = reader.storedFields();
            String entityField = textIndex.getDocDef().getEntityField();
            List<Document> docs = new ArrayList<>();
            for (int i = 0; i < reader.maxDoc(); i++) {
                Document doc = stored.document(i);
                if (entity.equals(doc.get(entityField))) {
                    docs.add(doc);
                }
            }
            return docs;
        } finally {
            textIndex.releaseSearcher(searcher);
        }
    }

    private DocValuesType docValuesType(String fieldName) {
        IndexSearcher searcher = textIndex.acquireSearcher();
        try {
            FieldInfo info = FieldInfos.getMergedFieldInfos(searcher.getIndexReader()).fieldInfo(fieldName);
            return info == null ? null : info.getDocValuesType();
        } finally {
            textIndex.releaseSearcher(searcher);
        }
    }

    @Test
    public void eligibleStoredFieldWritesTheDocValueOnly() throws IOException {
        openMemoryAndLoad();
        Set<String> stored = new HashSet<>();
        for (Document doc : storedDocs(NS + "e1")) {
            doc.getFields().forEach(f -> stored.add(f.name()));
        }
        for (String eligible : List.of("code", "link", "tags", "links", "hidden", "idValue")) {
            assertFalse(stored.contains(eligible), eligible + " has a stored copy");
        }
        for (String other : List.of("title", "norm", "facetOnly", "langKw", "num", "when", "idType")) {
            assertTrue(stored.contains(other), other + " has no stored copy");
        }
        assertEquals(DocValuesType.SORTED, docValuesType("code"));
        assertEquals(DocValuesType.SORTED_SET, docValuesType("tags"));
        assertEquals(DocValuesType.SORTED, docValuesType("hidden"));
        assertEquals(DocValuesType.SORTED, docValuesType("idValue"));
    }

    @Test
    public void unstoredEligibleFieldIsNotReturnable() {
        openMemoryAndLoad();
        QueryExecException ex = assertThrows(QueryExecException.class, () -> valuesOfE1("hidden"));
        assertTrue(ex.getMessage().contains(FIELD + "hidden"), ex.getMessage());
    }

    // ---- What is read back -----------------------------------------------------------

    private void assertEligibleTerms() {
        assertEquals(Set.of(str("C1")), valuesOfE1("code"));
        assertEquals(Set.of(LINK), valuesOfE1("link"));
        assertEquals(Set.of(str("red"), str("blue")), valuesOfE1("tags"));
        assertEquals(Set.of(LINK_A, LINK_B), valuesOfE1("links"));
    }

    @Test
    public void eligibleFieldsReturnTheOriginalTermsFromDocValues() {
        openMemoryAndLoad();
        Counter counter = count();
        assertEligibleTerms();
        assertEquals(List.of(), counter.reads, "eligible fields are read from doc values");
    }

    @Test
    public void otherFieldsReturnTheOriginalTermsFromStoredFields() {
        openMemoryAndLoad();
        assertEquals(Set.of(str("MiXed Case")), valuesOfE1("norm"));
        assertEquals(Set.of(str("Fac One")), valuesOfE1("facetOnly"));
        assertEquals(Set.of(NodeFactory.createLiteralLang("chat", "fr")), valuesOfE1("langKw"));
        assertEquals(Set.of(NodeFactory.createLiteralDT("007", XSDDatatype.XSDinteger)), valuesOfE1("num"));
        assertEquals(Set.of(NodeFactory.createLiteralDT("2020-01-01T10:00:00+05:00", XSDDatatype.XSDdateTime)),
            valuesOfE1("when"));
    }

    @Test
    public void indexWithStoredCopiesOfEligibleFieldsStillReturnsThem() throws IOException {
        open("\"mem\"", true);
        textIndex.setLegacyStoredCopies(true);
        load();
        Set<String> stored = new HashSet<>();
        for (Document doc : storedDocs(NS + "e1")) {
            doc.getFields().forEach(f -> stored.add(f.name()));
        }
        assertTrue(stored.contains("code"), "written as before the storage rule");
        assertEligibleTerms();
    }

    @Test
    public void segmentWithoutTheDocValueFallsBackToStoredFields(@TempDir Path dir) {
        String directory = "<" + IRILib.filenameToIRI(dir.toAbsolutePath().toString()) + ">";
        // Written while code was not sortable: a stored copy and no doc value.
        open(directory, false);
        load();
        close();

        open(directory, true);
        assertTrue(textIndex.getShaclMapping().findField(FIELD + "code").isValueInDocValues());
        assertEquals(Set.of(str("C1")), valuesOfE1("code"));
    }

    // ---- luc:match and luc:nestedMatch -----------------------------------------------

    private static String tagQuery(String tag) {
        return "(?hit ?entity) luc:query (\"default\" '[\"" + FIELD + "tags\"]' \"" + tag + "\" \"\" \"\" 10 0) .\n"
            + "(?hit ?field ?value) luc:match () .";
    }

    @Test
    public void lucMatchSelectsTheMatchedValueOfAMultiValuedDocValueField() {
        openMemoryAndLoad();
        Counter counter = count();
        // In byte order "blue" comes first; the query matched "red".
        Map<String, String> red = new TreeMap<>();
        for (Binding b : select(tagQuery("red"))) {
            red.put(b.get("entity").getURI(), b.get("value").getLiteralLexicalForm());
        }
        assertEquals(Map.of(NS + "e1", "red", NS + "e2", "red"), red);

        List<Binding> blue = select(tagQuery("blue"));
        assertEquals(1, blue.size());
        assertEquals("blue", blue.get(0).get("value").getLiteralLexicalForm());
        assertFalse(counter.anyRead("tags"), "tags is read from its doc value");
    }

    @Test
    public void lucNestedMatchReadsEligibleChildFieldsFromDocValues() {
        openMemoryAndLoad();
        Counter counter = count();
        String doi = "{\"op\":\"=\",\"args\":[{\"property\":\"" + FIELD + "idType\"},\"doi\"]}";
        Set<String> rows = new TreeSet<>();
        for (Binding b : select(
                "(?hit ?entity) luc:query (\"default\" \"default\" \"\" '" + doi + "' \"\" 10 0) .\n"
                + "(?hit ?record ?field ?value) luc:nestedMatch () .")) {
            rows.add(b.get("field").getURI() + "=" + b.get("value").getLiteralLexicalForm());
        }
        assertEquals(Set.of(FIELD + "idType=doi", FIELD + "idValue=10/1"), rows);
        assertFalse(counter.anyRead("idValue"), "idValue is read from its doc value");
    }
}
