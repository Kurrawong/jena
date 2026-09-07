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
import org.apache.jena.query.text.ShaclIndexMapping.*;
import org.apache.jena.sparql.path.PathFactory;
import org.junit.Test;

/**
 * Unit tests for {@link ShaclIndexMapping}.
 */
public class TestShaclIndexMapping {

    private static final String NS = "http://example.org/";
    private static final Node BOOK_CLASS = NodeFactory.createURI(NS + "Book");
    private static final Node ARTICLE_CLASS = NodeFactory.createURI(NS + "Article");
    private static final Node TITLE_PRED = NodeFactory.createURI(NS + "title");
    private static final Node LABEL_PRED = NodeFactory.createURI(NS + "label");
    private static final Node CATEGORY_PRED = NodeFactory.createURI(NS + "category");
    private static final Node YEAR_PRED = NodeFactory.createURI(NS + "year");
    private static final Node IRRELEVANT_PRED = NodeFactory.createURI(NS + "irrelevant");

    private ShaclIndexMapping createTestMapping() {
        FieldDef titleField = new FieldDef("title", FieldType.TEXT, null,
            true, true, false, false, false, true);
        FieldDef categoryField = new FieldDef("category", FieldType.KEYWORD, null,
            true, true, true, false, true, false);
        FieldDef yearField = new FieldDef("year", FieldType.INT, null,
            true, true, false, true, false, false);

        List<FieldOccurrence> bookOccurrences = List.of(
            occurrence(titleField, TITLE_PRED),
            occurrence(titleField, LABEL_PRED),
            occurrence(categoryField, CATEGORY_PRED),
            occurrence(yearField, YEAR_PRED)
        );
        IndexProfile bookProfile = new IndexProfile(
            NodeFactory.createURI(NS + "BookShape"),
            Collections.singleton(BOOK_CLASS),
            "uri", "docType",
            Arrays.asList(titleField, categoryField, yearField),
            bookOccurrences,
            Collections.emptyList(),
            Collections.emptyList());

        FieldDef articleTitle = new FieldDef("title", FieldType.TEXT, null,
            true, true, false, false, false, true);
        IndexProfile articleProfile = new IndexProfile(
            NodeFactory.createURI(NS + "ArticleShape"),
            Collections.singleton(ARTICLE_CLASS),
            "uri", "docType",
            Collections.singletonList(articleTitle),
            Collections.singletonList(occurrence(articleTitle, TITLE_PRED)),
            Collections.emptyList(),
            Collections.emptyList());

        return new ShaclIndexMapping(Arrays.asList(bookProfile, articleProfile));
    }

    @Test
    public void testPredicateLookup() {
        ShaclIndexMapping mapping = createTestMapping();

        assertTrue(mapping.isRelevantPredicate(TITLE_PRED));
        assertTrue(mapping.isRelevantPredicate(LABEL_PRED));
        assertTrue(mapping.isRelevantPredicate(CATEGORY_PRED));
        assertTrue(mapping.isRelevantPredicate(YEAR_PRED));
        assertFalse(mapping.isRelevantPredicate(IRRELEVANT_PRED));
    }

    @Test
    public void testPredicateReturnsCorrectOccurrences() {
        ShaclIndexMapping mapping = createTestMapping();

        List<ProfileOccurrence> titleMatches = mapping.getOccurrencesForPredicate(TITLE_PRED);
        assertEquals(2, titleMatches.size());

        List<ProfileOccurrence> labelMatches = mapping.getOccurrencesForPredicate(LABEL_PRED);
        assertEquals(1, labelMatches.size());
        assertEquals("title", labelMatches.get(0).getOccurrence().getField().getFieldName());

        List<ProfileOccurrence> catMatches = mapping.getOccurrencesForPredicate(CATEGORY_PRED);
        assertEquals(1, catMatches.size());
        assertEquals("category", catMatches.get(0).getOccurrence().getField().getFieldName());
    }

    @Test
    public void testClassLookup() {
        ShaclIndexMapping mapping = createTestMapping();

        assertEquals(1, mapping.getProfilesForClass(BOOK_CLASS).size());
        assertEquals(1, mapping.getProfilesForClass(ARTICLE_CLASS).size());
        assertTrue(mapping.getProfilesForClass(IRRELEVANT_PRED).isEmpty());
    }

    @Test
    public void testIrrelevantPredicateReturnsEmpty() {
        ShaclIndexMapping mapping = createTestMapping();

        List<ProfileOccurrence> results = mapping.getOccurrencesForPredicate(IRRELEVANT_PRED);
        assertNotNull(results);
        assertTrue(results.isEmpty());
    }

    @Test
    public void testGetFacetFieldNames() {
        ShaclIndexMapping mapping = createTestMapping();
        assertEquals(List.of("category"), mapping.getFacetFieldNames());
    }

    @Test
    public void testFieldDefDefaults() {
        FieldDef field = new FieldDef("test", null, null,
            true, true, false, false, false, false);
        assertEquals(FieldType.TEXT, field.getFieldType());
    }

    @Test
    public void testProfileDefaults() {
        FieldDef field = new FieldDef("test", FieldType.TEXT, null,
            true, true, false, false, false, false);
        IndexProfile profile = new IndexProfile(
            NodeFactory.createURI(NS + "TestShape"),
            Collections.singleton(BOOK_CLASS),
            null, null,
            Collections.singletonList(field),
            Collections.singletonList(occurrence(field, TITLE_PRED)));

        assertEquals("uri", profile.getDocIdField());
        assertEquals("docType", profile.getDiscriminatorField());
    }

    @Test
    public void testProfileCount() {
        assertEquals(2, createTestMapping().getProfiles().size());
    }

    @Test
    public void testGetDefaultSearchFieldNames() {
        List<String> defaults = createTestMapping().getDefaultSearchFieldNames();
        assertTrue(defaults.contains("title"));
    }

    @Test
    public void testFieldIRIAutoGenerated() {
        FieldDef titleField = createTestMapping().findField("urn:jena:lucene:field#title");
        assertNotNull(titleField.getFieldIRI());
        assertTrue(titleField.getFieldIRI().isURI());
        assertEquals("urn:jena:lucene:field#title", titleField.getFieldIRI().getURI());
    }

    @Test
    public void testFieldIRIExplicit() {
        Node customIRI = NodeFactory.createURI(NS + "myCustomField");
        FieldDef field = new FieldDef("test", FieldType.TEXT, null, null,
            true, true, false, false, false, false, false, customIRI);
        assertEquals(customIRI, field.getFieldIRI());
    }

    @Test
    public void testGetAllFieldNames() {
        Set<String> all = createTestMapping().getAllFieldNames();
        assertTrue(all.contains("title"));
        assertTrue(all.contains("category"));
        assertTrue(all.contains("year"));
    }

    @Test(expected = TextIndexException.class)
    public void testConflictingFieldTypes() {
        FieldDef textTitle = new FieldDef("title", FieldType.TEXT, null,
            true, true, false, false, false, true);
        FieldDef keywordTitle = new FieldDef("title", FieldType.KEYWORD, null,
            true, true, false, false, false, true);

        IndexProfile p1 = new IndexProfile(
            NodeFactory.createURI(NS + "Shape1"),
            Collections.singleton(BOOK_CLASS),
            "uri", "docType",
            Collections.singletonList(textTitle),
            Collections.singletonList(occurrence(textTitle, TITLE_PRED)),
            Collections.emptyList(),
            Collections.emptyList());

        IndexProfile p2 = new IndexProfile(
            NodeFactory.createURI(NS + "Shape2"),
            Collections.singleton(ARTICLE_CLASS),
            "uri", "docType",
            Collections.singletonList(keywordTitle),
            Collections.singletonList(occurrence(keywordTitle, TITLE_PRED)),
            Collections.emptyList(),
            Collections.emptyList());

        new ShaclIndexMapping(Arrays.asList(p1, p2));
    }

    @Test(expected = TextIndexException.class)
    public void testRootHierarchyCannotReferenceNestedOnlyField() {
        FieldDef titleField = new FieldDef("title", FieldType.TEXT, null,
            true, true, false, false, false, true,
            NodeFactory.createURI(NS + "titleField"));
        FieldDef nestedOnlyField = new FieldDef("identifierType", FieldType.KEYWORD, null,
            true, true, true, false, false, false,
            NodeFactory.createURI(NS + "identifierTypeField"));

        FieldOccurrence rootTitle = occurrence(titleField, TITLE_PRED);
        FieldOccurrence nestedOccurrence = new FieldOccurrence(
            nestedOnlyField,
            PathFactory.pathLink(CATEGORY_PRED),
            List.of(List.of(new JoinStep(CATEGORY_PRED, false))),
            Collections.singleton(CATEGORY_PRED),
            null,
            null,
            null,
            "nested");
        NestedDef nestedDef = new NestedDef(
            "nested",
            PathFactory.pathLink(LABEL_PRED),
            List.of(new JoinStep(LABEL_PRED, false)),
            Collections.singleton(LABEL_PRED),
            Collections.singletonList(nestedOccurrence),
            Collections.emptyList());
        HierarchyDef invalidHierarchy = new HierarchyDef(
            "title_identifierType",
            Arrays.asList(titleField, nestedOnlyField));

        IndexProfile profile = new IndexProfile(
            NodeFactory.createURI(NS + "Shape"),
            Collections.singleton(BOOK_CLASS),
            "uri", "docType",
            Arrays.asList(titleField, nestedOnlyField),
            Collections.singletonList(rootTitle),
            Collections.singletonList(invalidHierarchy),
            Collections.singletonList(nestedDef));

        new ShaclIndexMapping(Collections.singletonList(profile));
    }

    private static FieldOccurrence occurrence(FieldDef field, Node predicate) {
        return new FieldOccurrence(
            field,
            PathFactory.pathLink(predicate),
            List.of(List.of(new JoinStep(predicate, false))),
            Collections.singleton(predicate),
            null,
            null,
            null,
            null);
    }

    // ------------------------------------------------------------------
    // One idx:fieldName may not be claimed by two field IRIs (#141)
    // ------------------------------------------------------------------

    private static IndexProfile profileWith(String shape, FieldDef field) {
        return new IndexProfile(
            NodeFactory.createURI(NS + shape),
            Collections.singleton(BOOK_CLASS),
            "uri", "docType",
            Collections.singletonList(field),
            Collections.singletonList(occurrence(field, TITLE_PRED)),
            Collections.emptyList(),
            Collections.emptyList());
    }

    @Test
    public void testTwoFieldIrisSharingAFieldNameIsRejected() {
        // Both write to the Lucene field "author", and a lookup by name returns whichever
        // shape parsed first, so the second definition is silently ignored. Same type, so
        // the old conflicting-type check did not catch it.
        FieldDef first = new FieldDef("author", FieldType.KEYWORD, null,
            true, true, false, false, false, false,
            NodeFactory.createURI("urn:jena:lucene:field#authorName"));
        FieldDef second = new FieldDef("author", FieldType.KEYWORD, null,
            true, true, false, false, false, false,
            NodeFactory.createURI("urn:jena:lucene:field#reviewerName"));

        TextIndexException e = assertThrows(TextIndexException.class,
            () -> new ShaclIndexMapping(Arrays.asList(
                profileWith("BookShape", first), profileWith("ArticleShape", second))));
        assertTrue("Message should name the field: " + e.getMessage(),
            e.getMessage().contains("author"));
        assertTrue("Message should name both IRIs: " + e.getMessage(),
            e.getMessage().contains("authorName") && e.getMessage().contains("reviewerName"));
    }

    @Test
    public void testOneFieldSharedAcrossProfilesIsStillAllowed() {
        // The ordinary case: one field definition bound in several shapes. Same IRI, so
        // there is no ambiguity to reject.
        FieldDef shared = new FieldDef("author", FieldType.KEYWORD, null,
            true, true, false, false, false, false,
            NodeFactory.createURI("urn:jena:lucene:field#authorName"));

        ShaclIndexMapping mapping = new ShaclIndexMapping(Arrays.asList(
            profileWith("BookShape", shared), profileWith("ArticleShape", shared)));
        assertEquals(2, mapping.getProfiles().size());
    }

    @Test
    public void testConflictingTypesForOneNameStillRejected() {
        FieldDef asKeyword = new FieldDef("author", FieldType.KEYWORD, null,
            true, true, false, false, false, false);
        FieldDef asInt = new FieldDef("author", FieldType.INT, null,
            true, true, false, false, false, false);

        assertThrows(TextIndexException.class,
            () -> new ShaclIndexMapping(Arrays.asList(
                profileWith("BookShape", asKeyword), profileWith("ArticleShape", asInt))));
    }
}
