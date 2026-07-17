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

import static org.junit.Assert.*;

import java.util.*;

import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.query.text.ShaclIndexMapping.FieldDef;
import org.apache.jena.query.text.ShaclIndexMapping.FieldOccurrence;
import org.apache.jena.query.text.ShaclIndexMapping.FieldType;
import org.apache.jena.query.text.ShaclIndexMapping.IndexProfile;
import org.apache.jena.query.text.assembler.ShaclIndexAssembler;
import org.apache.jena.sparql.path.Path;
import org.apache.jena.sparql.path.PathFactory;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DocValuesType;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests that {@link ShaclTextIndexLucene#docFromMapping(Entity, IndexProfile)} produces
 * correct Lucene field types for TEXT, KEYWORD, INT, LONG, DOUBLE fields.
 */
public class TestShaclDocumentBuilding {

    private static final String NS = "http://example.org/";
    private static final Node BOOK_CLASS = NodeFactory.createURI(NS + "Book");
    private static final Node TITLE_PRED = NodeFactory.createURI(NS + "title");
    private static final Node CATEGORY_PRED = NodeFactory.createURI(NS + "category");
    private static final Node YEAR_PRED = NodeFactory.createURI(NS + "year");
    private static final Node PAGES_PRED = NodeFactory.createURI(NS + "pages");
    private static final Node RATING_PRED = NodeFactory.createURI(NS + "rating");

    private ShaclTextIndexLucene textIndex;
    private IndexProfile testProfile;

    @Before
    public void setUp() {
        FieldDef titleField = new FieldDef("title", FieldType.TEXT, null,
            true, true, false, false, false, true);

        FieldDef categoryField = new FieldDef("category", FieldType.KEYWORD, null,
            true, true, true, true, false, false);

        FieldDef yearField = new FieldDef("year", FieldType.INT, null,
            true, true, false, true, false, false);

        FieldDef pagesField = new FieldDef("pages", FieldType.LONG, null,
            true, true, false, false, false, false);

        FieldDef ratingField = new FieldDef("rating", FieldType.DOUBLE, null,
            true, true, false, true, false, false);

        List<FieldOccurrence> rootOccurrences = Arrays.asList(
            occurrence(titleField, PathFactory.pathLink(TITLE_PRED), Collections.singleton(TITLE_PRED)),
            occurrence(categoryField, PathFactory.pathLink(CATEGORY_PRED), Collections.singleton(CATEGORY_PRED)),
            occurrence(yearField, PathFactory.pathLink(YEAR_PRED), Collections.singleton(YEAR_PRED)),
            occurrence(pagesField, PathFactory.pathLink(PAGES_PRED), Collections.singleton(PAGES_PRED)),
            occurrence(ratingField, PathFactory.pathLink(RATING_PRED), Collections.singleton(RATING_PRED)));

        testProfile = new IndexProfile(
            NodeFactory.createURI(NS + "BookShape"),
            Collections.singleton(BOOK_CLASS),
            "uri", "docType",
            Arrays.asList(titleField, categoryField, yearField, pagesField, ratingField),
            rootOccurrences,
            Collections.emptyList(),
            Collections.emptyList());

        ShaclIndexMapping mapping = new ShaclIndexMapping(Collections.singletonList(testProfile));

        // Build a minimal EntityDefinition for TextIndexLucene
        EntityDefinition defn = new EntityDefinition("uri", "title");
        defn.set("title", TITLE_PRED);
        defn.set("category", CATEGORY_PRED);
        defn.setLangField("lang");

        TextIndexConfig config = new TextIndexConfig(defn);
        config.setShaclMapping(mapping);
        config.setFacetFields(Collections.singletonList("category"));

        textIndex = new ShaclTextIndexLucene(new ByteBuffersDirectory(), config);
    }

    private static FieldOccurrence occurrence(FieldDef field, Path path, Set<Node> predicates) {
        return new FieldOccurrence(
            field,
            path,
            ShaclIndexAssembler.extractPathVariants(path),
            predicates,
            null, null, null, null);
    }

    private static IndexProfile multiValuedKeywordProfile() {
        Node remarksPred = NodeFactory.createURI(NS + "remarks");
        FieldDef remarksField = new FieldDef("remarks", FieldType.KEYWORD, null,
            false, true, false, true, true, false);
        return new IndexProfile(
            NodeFactory.createURI(NS + "RemarksShape"),
            Collections.singleton(BOOK_CLASS),
            "uri", "docType",
            Collections.singletonList(remarksField),
            Collections.singletonList(occurrence(remarksField, PathFactory.pathLink(remarksPred),
                Collections.singleton(remarksPred))),
            Collections.emptyList(),
            Collections.emptyList());
    }

    @After
    public void tearDown() {
        if (textIndex != null) {
            textIndex.close();
        }
    }

    @Test
    public void testShaclModeEnabled() {
        assertTrue(textIndex.isShaclMode());
        assertNotNull(textIndex.getShaclMapping());
    }

    @Test
    public void testDocHasEntityUriField() {
        Entity entity = new Entity("http://example.org/book1", null);
        entity.put("title", "Test Book");

        Document doc = textIndex.docFromMapping(entity, testProfile);

        String uri = doc.get("uri");
        assertEquals("http://example.org/book1", uri);
    }

    @Test
    public void testDocHasDiscriminatorField() {
        Entity entity = new Entity("http://example.org/book1", null);
        entity.put("title", "Test Book");

        Document doc = textIndex.docFromMapping(entity, testProfile);

        String docType = doc.get("docType");
        assertEquals("Book", docType);
    }

    @Test
    public void testTextFieldType() {
        Entity entity = new Entity("http://example.org/book1", null);
        entity.put("title", "Test Book Title");

        Document doc = textIndex.docFromMapping(entity, testProfile);

        IndexableField titleField = doc.getField("title");
        assertNotNull("Should have title field", titleField);
        assertEquals("Test Book Title", titleField.stringValue());
    }

    @Test
    public void testKeywordFieldType() {
        Entity entity = new Entity("http://example.org/book1", null);
        entity.put("category", "Science");

        Document doc = textIndex.docFromMapping(entity, testProfile);

        // Should have StringField
        IndexableField catField = doc.getField("category");
        assertNotNull("Should have category field", catField);
        assertEquals("Science", catField.stringValue());

        // Should also have SortedSetDocValuesFacetField (facetable=true)
        // and SortedDocValuesField (sortable=true)
        IndexableField[] allCatFields = doc.getFields("category");
        assertTrue("Should have multiple category fields (string + facet + sort)",
            allCatFields.length >= 2);
    }

    @Test
    public void testIntFieldType() {
        Entity entity = new Entity("http://example.org/book1", null);
        entity.put("year", 2024);

        Document doc = textIndex.docFromMapping(entity, testProfile);

        // IntPoint for indexing
        IndexableField yearField = doc.getField("year");
        assertNotNull("Should have year field", yearField);

        // StoredField for retrieval (stored=true)
        IndexableField[] yearFields = doc.getFields("year");
        assertTrue("Should have IntPoint + StoredField + NumericDocValues (sortable)",
            yearFields.length >= 2);
    }

    @Test
    public void testLongFieldType() {
        Entity entity = new Entity("http://example.org/book1", null);
        entity.put("pages", 350L);

        Document doc = textIndex.docFromMapping(entity, testProfile);

        IndexableField[] pagesFields = doc.getFields("pages");
        assertTrue("Should have LongPoint + StoredField", pagesFields.length >= 2);
    }

    @Test
    public void testDoubleFieldType() {
        Entity entity = new Entity("http://example.org/book1", null);
        entity.put("rating", 4.5);

        Document doc = textIndex.docFromMapping(entity, testProfile);

        IndexableField[] ratingFields = doc.getFields("rating");
        assertTrue("Should have DoublePoint + StoredField + NumericDocValues",
            ratingFields.length >= 2);
    }

    @Test
    public void testMultiValuedField() {
        FieldDef multiTitleField = new FieldDef("title", FieldType.TEXT, null,
            true, true, false, false, true, true);
        List<FieldOccurrence> multiRootOccurrences = Collections.singletonList(
            occurrence(multiTitleField, PathFactory.pathLink(TITLE_PRED), Collections.singleton(TITLE_PRED)));
        IndexProfile multiValueProfile = new IndexProfile(
            NodeFactory.createURI(NS + "BookShapeMulti"),
            Collections.singleton(BOOK_CLASS),
            "uri", "docType",
            Collections.singletonList(multiTitleField),
            multiRootOccurrences,
            Collections.emptyList(),
            Collections.emptyList());

        Entity entity = new Entity("http://example.org/book1", null);
        entity.addValue("title", "First Title");
        entity.addValue("title", "Second Title");

        Document doc = textIndex.docFromMapping(entity, multiValueProfile);

        IndexableField[] titleFields = doc.getFields("title");
        assertEquals("Should have 2 title fields for multi-valued", 2, titleFields.length);
    }

    @Test
    public void testMultiValuedSortableKeywordStringsUseSortedSetDocValues() {
        IndexProfile profile = multiValuedKeywordProfile();
        Entity entity = new Entity("http://example.org/book1", null);
        entity.addValue("remarks", "First remark");
        entity.addValue("remarks", "Second remark");

        Document doc = textIndex.docFromMapping(entity, profile);

        IndexableField[] fields = doc.getFields("remarks");
        assertEquals(4, fields.length);
        assertEquals(2, Arrays.stream(fields)
            .filter(field -> field.fieldType().docValuesType() == DocValuesType.SORTED_SET)
            .count());
        textIndex.updateEntityForProfile(entity, profile);
        textIndex.commit();
    }

    @Test
    public void testMultiValuedSortableKeywordLiteralsUseSortedSetDocValues() {
        IndexProfile profile = multiValuedKeywordProfile();
        Entity entity = new Entity("http://example.org/book1", null);
        entity.addValue("remarks", NodeFactory.createLiteralString("First remark"));
        entity.addValue("remarks", NodeFactory.createLiteralString("Second remark"));

        Document doc = textIndex.docFromMapping(entity, profile);

        IndexableField[] fields = doc.getFields("remarks");
        assertEquals(2, Arrays.stream(fields)
            .filter(field -> field.fieldType().docValuesType() == DocValuesType.SORTED_SET)
            .count());
        textIndex.updateEntityForProfile(entity, profile);
        textIndex.commit();
    }

    @Test
    public void testNonMultiValuedSortableFieldOnlyIndexesFirstValue() {
        Entity entity = new Entity("http://example.org/book1", null);
        entity.addValue("category", "Science");
        entity.addValue("category", "Technology");

        Document doc = textIndex.docFromMapping(entity, testProfile);

        assertEquals("Science", doc.get("category"));
        assertEquals("Should only index one category value for non-multi-valued field", 2,
            doc.getFields("category").length);
        assertEquals(1, Arrays.stream(doc.getFields("category"))
            .filter(field -> field.fieldType().docValuesType() == DocValuesType.SORTED)
            .count());
        textIndex.updateEntityForProfile(entity, testProfile);
    }

    @Test
    public void testNullFieldSkipped() {
        Entity entity = new Entity("http://example.org/book1", null);
        // Only set title, leave category/year/pages/rating null

        entity.put("title", "Test");
        Document doc = textIndex.docFromMapping(entity, testProfile);

        // Should not have category, year, pages, or rating fields
        assertNull("category should be null", doc.get("category"));
    }

    @Test
    public void testIntFromString() {
        // Numeric fields should handle String input (from RDF literal lexical form)
        Entity entity = new Entity("http://example.org/book1", null);
        entity.put("year", "2024");

        Document doc = textIndex.docFromMapping(entity, testProfile);

        IndexableField[] yearFields = doc.getFields("year");
        assertTrue("Should parse int from string", yearFields.length >= 2);
    }
}
