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
import org.apache.jena.query.text.ShaclIndexMapping.JoinStep;
import org.apache.jena.query.text.ShaclIndexMapping.NestedDef;
import org.apache.jena.query.text.assembler.ShaclIndexAssembler;
import org.apache.jena.sparql.path.Path;
import org.apache.jena.sparql.path.PathFactory;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.SortedNumericSelector;
import org.apache.lucene.search.SortedNumericSortField;
import org.apache.lucene.search.SortedSetSelector;
import org.apache.lucene.search.SortedSetSortField;
import org.apache.lucene.search.join.ToParentBlockJoinSortField;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.junit.Test;

/**
 * Tests for sort specification parsing and Lucene Sort construction.
 */
public class TestSortSpec {

    private static final String FP = "urn:jena:lucene:field#";

    private static FieldOccurrence occurrence(FieldDef field, Path path, Set<Node> predicates) {
        return new FieldOccurrence(
            field,
            path,
            ShaclIndexAssembler.extractPathVariants(path),
            predicates,
            null, null, null, null);
    }

    @Test
    public void testParseSingleAsc() {
        List<SortSpec> specs = SortSpecParser.parse("{\"field\":\"year\"}");
        assertEquals(1, specs.size());
        assertEquals("year", specs.get(0).field());
        assertFalse(specs.get(0).descending());
    }

    @Test
    public void testParseSingleDesc() {
        List<SortSpec> specs = SortSpecParser.parse("{\"field\":\"year\",\"order\":\"desc\"}");
        assertEquals(1, specs.size());
        assertEquals("year", specs.get(0).field());
        assertTrue(specs.get(0).descending());
    }

    @Test
    public void testParseMulti() {
        List<SortSpec> specs = SortSpecParser.parse(
            "[{\"field\":\"year\",\"order\":\"desc\"},{\"field\":\"title\"}]");
        assertEquals(2, specs.size());
        assertEquals("year", specs.get(0).field());
        assertTrue(specs.get(0).descending());
        assertEquals("title", specs.get(1).field());
        assertFalse(specs.get(1).descending());
    }

    @Test
    public void testToCanonical() {
        SortSpec asc = new SortSpec("year", false);
        assertEquals("year:asc", asc.toCanonical());

        SortSpec desc = new SortSpec("year", true);
        assertEquals("year:desc", desc.toCanonical());
    }

    @Test
    public void testIsSortSpec() {
        assertTrue(SortSpecParser.isSortSpec("{\"field\":\"year\"}"));
        assertFalse(SortSpecParser.isSortSpec("{\"op\":\"=\"}"));
    }

    // ---- Nested sort selector: parsing ----

    @Test
    public void testParseNestedSelector() {
        List<SortSpec> specs = SortSpecParser.parse(
            "{\"field\":\"identifierValue\","
            + "\"filter\":{\"field\":\"identifierType\",\"eq\":\"companyID\"},"
            + "\"order\":\"desc\",\"missing\":\"first\"}");
        assertEquals(1, specs.size());
        SortSpec spec = specs.get(0);
        assertTrue(spec.hasSelector());
        assertEquals("identifierValue", spec.field());
        assertTrue(spec.descending());
        assertEquals("identifierType", spec.filterField());
        assertEquals("companyID", spec.filterValue());
        assertEquals(SortSpec.MissingPlacement.FIRST, spec.missing());
    }

    @Test
    public void testParseFlatSpecHasNoSelector() {
        SortSpec spec = SortSpecParser.parse("{\"field\":\"year\"}").get(0);
        assertFalse(spec.hasSelector());
        assertNull(spec.filterField());
        assertNull(spec.filterValue());
        assertNull("absent 'missing' leaves Lucene's own default in place", spec.missing());
    }

    @Test
    public void testParseNonStringFilterValue() {
        SortSpec spec = SortSpecParser.parse(
            "{\"field\":\"reading\",\"filter\":{\"field\":\"sensorId\",\"eq\":42}}").get(0);
        assertEquals("42", spec.filterValue());
    }

    @Test(expected = TextIndexException.class)
    public void testParseFilterWithoutEqThrows() {
        SortSpecParser.parse("{\"field\":\"identifierValue\",\"filter\":{\"field\":\"identifierType\"}}");
    }

    @Test(expected = TextIndexException.class)
    public void testParseNonObjectFilterThrows() {
        SortSpecParser.parse("{\"field\":\"identifierValue\",\"filter\":\"companyID\"}");
    }

    @Test(expected = TextIndexException.class)
    public void testParseUnknownMissingPlacementThrows() {
        SortSpecParser.parse("{\"field\":\"year\",\"missing\":\"middle\"}");
    }

    @Test
    public void testSelectorToCanonicalDistinguishesFilterValue() {
        SortSpec companyId = new SortSpec("value", false, "type", "companyID", null);
        SortSpec govId = new SortSpec("value", false, "type", "govID", null);
        assertEquals("value[type=companyID]:asc", companyId.toCanonical());
        assertNotEquals("shared-execution keys must distinguish selectors",
            companyId.toCanonical(), govId.toCanonical());
        assertEquals("value[type=companyID]:asc:missing-first",
            new SortSpec("value", false, "type", "companyID",
                SortSpec.MissingPlacement.FIRST).toCanonical());
    }

    // ---- Nested sort selector: mapping validation in buildLuceneSort ----

    /**
     * Two nested blocks plus a flat field, so the selector's scope rules have something to
     * be wrong about: identifier(identifierType, identifierValue) and
     * attribution(attributionRole, agentName), with a root-scoped {@code year}.
     */
    private static ShaclTextIndexLucene selectorTestIndex() {
        Node yearPred = NodeFactory.createURI("http://example.org/year");
        Node identifierPred = NodeFactory.createURI("http://example.org/identifier");
        Node idTypePred = NodeFactory.createURI("http://example.org/propertyID");
        Node idValuePred = NodeFactory.createURI("http://example.org/value");
        Node attributionPred = NodeFactory.createURI("http://example.org/qualifiedAttribution");
        Node rolePred = NodeFactory.createURI("http://example.org/hadRole");
        Node agentPred = NodeFactory.createURI("http://example.org/agent");

        FieldDef yearField = new FieldDef("year", FieldType.INT, null,
            true, true, false, true, false, false);
        FieldDef idType = new FieldDef("identifierType", FieldType.KEYWORD, null,
            true, true, true, false, true, false);
        FieldDef idValue = new FieldDef("identifierValue", FieldType.KEYWORD, null,
            true, true, false, true, true, false);
        FieldDef idLabel = new FieldDef("identifierLabel", FieldType.KEYWORD, null,
            true, true, false, false, true, false);
        FieldDef role = new FieldDef("attributionRole", FieldType.KEYWORD, null,
            true, true, true, false, true, false);
        FieldDef agentName = new FieldDef("agentName", FieldType.KEYWORD, null,
            true, true, false, true, true, false);

        NestedDef identifierNest = new NestedDef(
            "identifier", PathFactory.pathLink(identifierPred),
            Collections.singletonList(new JoinStep(identifierPred, false)),
            Collections.singleton(identifierPred),
            Arrays.asList(occurrence(idType, PathFactory.pathLink(idTypePred), Collections.singleton(idTypePred)),
                occurrence(idValue, PathFactory.pathLink(idValuePred), Collections.singleton(idValuePred)),
                occurrence(idLabel, PathFactory.pathLink(idValuePred), Collections.singleton(idValuePred))),
            Collections.emptyList());
        NestedDef attributionNest = new NestedDef(
            "attribution", PathFactory.pathLink(attributionPred),
            Collections.singletonList(new JoinStep(attributionPred, false)),
            Collections.singleton(attributionPred),
            Arrays.asList(occurrence(role, PathFactory.pathLink(rolePred), Collections.singleton(rolePred)),
                occurrence(agentName, PathFactory.pathLink(agentPred), Collections.singleton(agentPred))),
            Collections.emptyList());

        IndexProfile profile = new IndexProfile(
            NodeFactory.createURI("http://example.org/Shape"),
            Collections.singleton(NodeFactory.createURI("http://example.org/Thing")),
            "uri", "docType",
            Arrays.asList(yearField, idType, idValue, idLabel, role, agentName),
            Collections.singletonList(
                occurrence(yearField, PathFactory.pathLink(yearPred), Collections.singleton(yearPred))),
            Collections.emptyList(),
            Arrays.asList(identifierNest, attributionNest));

        ShaclIndexMapping mapping = new ShaclIndexMapping(Collections.singletonList(profile));
        TextIndexConfig config = new TextIndexConfig(ShaclIndexAssembler.deriveEntityDefinition(mapping));
        config.setShaclMapping(mapping);
        return new ShaclTextIndexLucene(new ByteBuffersDirectory(), config);
    }

    @Test
    public void testSelectorBuildsBlockJoinSortField() {
        ShaclTextIndexLucene textIndex = selectorTestIndex();
        try {
            SortField ascending = textIndex.buildLuceneSort(List.of(
                new SortSpec(FP + "identifierValue", false, FP + "identifierType", "companyID", null)))
                .getSort()[0];
            assertTrue(ascending instanceof ToParentBlockJoinSortField);
            assertEquals("identifierValue", ascending.getField());
            assertEquals(SortField.Type.STRING, ascending.getType());
            assertFalse(ascending.getReverse());
            assertEquals("default missing placement is last",
                SortField.STRING_LAST, ascending.getMissingValue());

            // Lucene applies the reverse multiplier outside the comparator, so an absolute
            // "missing last" needs the opposite sentinel once the sort is descending.
            SortField descending = textIndex.buildLuceneSort(List.of(
                new SortSpec(FP + "identifierValue", true, FP + "identifierType", "companyID", null)))
                .getSort()[0];
            assertTrue(descending.getReverse());
            assertEquals(SortField.STRING_FIRST, descending.getMissingValue());
        } finally {
            textIndex.close();
        }
    }

    @Test
    public void testFlatSpecStillBuildsPlainSortField() {
        ShaclTextIndexLucene textIndex = selectorTestIndex();
        try {
            SortField flat = textIndex.buildLuceneSort(
                List.of(new SortSpec(FP + "year", false))).getSort()[0];
            assertFalse(flat instanceof ToParentBlockJoinSortField);
            assertNull("an absent 'missing' must not change flat sort behaviour",
                flat.getMissingValue());
        } finally {
            textIndex.close();
        }
    }

    /** {@code missing} is honoured on flat sorts too, on both the STRING and numeric paths. */
    @Test
    public void testFlatSpecHonoursExplicitMissingPlacement() {
        ShaclTextIndexLucene textIndex = selectorTestIndex();
        try {
            SortField year = textIndex.buildLuceneSort(List.of(
                new SortSpec(FP + "year", false, null, null, SortSpec.MissingPlacement.LAST)))
                .getSort()[0];
            assertEquals(Integer.MAX_VALUE, year.getMissingValue());

            SortField yearDesc = textIndex.buildLuceneSort(List.of(
                new SortSpec(FP + "year", true, null, null, SortSpec.MissingPlacement.LAST)))
                .getSort()[0];
            assertEquals(Integer.MIN_VALUE, yearDesc.getMissingValue());

            SortField value = textIndex.buildLuceneSort(List.of(
                new SortSpec(FP + "identifierValue", false, null, null,
                    SortSpec.MissingPlacement.FIRST))).getSort()[0];
            assertEquals(SortField.STRING_FIRST, value.getMissingValue());
        } finally {
            textIndex.close();
        }
    }

    @Test
    public void testSelectorOnFlatFieldThrows() {
        ShaclTextIndexLucene textIndex = selectorTestIndex();
        try {
            textIndex.buildLuceneSort(List.of(
                new SortSpec(FP + "year", false, FP + "identifierType", "companyID", null)));
            fail("a selector on a root-scoped field must be rejected");
        } catch (TextIndexException ex) {
            assertTrue(ex.getMessage(), ex.getMessage().contains("requires a nested field"));
        } finally {
            textIndex.close();
        }
    }

    @Test
    public void testSelectorAcrossDifferentNestedBlocksThrows() {
        ShaclTextIndexLucene textIndex = selectorTestIndex();
        try {
            textIndex.buildLuceneSort(List.of(
                new SortSpec(FP + "identifierValue", false, FP + "attributionRole", "owner", null)));
            fail("a discriminator from another nested block must be rejected");
        } catch (TextIndexException ex) {
            assertTrue(ex.getMessage(), ex.getMessage().contains("same idx:nested block"));
        } finally {
            textIndex.close();
        }
    }

    @Test
    public void testSelectorOnUnsortableFieldThrows() {
        ShaclTextIndexLucene textIndex = selectorTestIndex();
        try {
            textIndex.buildLuceneSort(List.of(
                new SortSpec(FP + "identifierLabel", false, FP + "identifierType", "companyID", null)));
            fail("a non-sortable sort field must be rejected");
        } catch (TextIndexException ex) {
            assertTrue(ex.getMessage(), ex.getMessage().contains("not idx:sortable"));
        } finally {
            textIndex.close();
        }
    }

    @Test
    public void testSelectorWithUnknownFilterFieldThrows() {
        ShaclTextIndexLucene textIndex = selectorTestIndex();
        try {
            textIndex.buildLuceneSort(List.of(
                new SortSpec(FP + "identifierValue", false, FP + "noSuchField", "companyID", null)));
            fail("an unresolvable discriminator field must be rejected");
        } catch (TextIndexException ex) {
            assertTrue(ex.getMessage(), ex.getMessage().contains("Unknown sort filter field"));
        } finally {
            textIndex.close();
        }
    }

    @Test
    public void testBuildLuceneSort() {
        Node yearPred = NodeFactory.createURI("http://example.org/year");
        Node statePred = NodeFactory.createURI("http://example.org/state");
        FieldDef yearField = new FieldDef("year", FieldType.INT, null,
            true, true, false, true, false, false);
        FieldDef stateField = new FieldDef("state", FieldType.KEYWORD, null,
            true, true, true, true, false, false);

        List<FieldOccurrence> rootOccurrences = Arrays.asList(
            occurrence(yearField, PathFactory.pathLink(yearPred), Collections.singleton(yearPred)),
            occurrence(stateField, PathFactory.pathLink(statePred), Collections.singleton(statePred)));

        IndexProfile profile = new IndexProfile(
            NodeFactory.createURI("http://example.org/Shape"),
            Collections.singleton(NodeFactory.createURI("http://example.org/Thing")),
            "uri", "docType",
            Arrays.asList(yearField, stateField),
            rootOccurrences,
            Collections.emptyList(),
            Collections.emptyList());

        ShaclIndexMapping mapping = new ShaclIndexMapping(Collections.singletonList(profile));
        EntityDefinition defn = org.apache.jena.query.text.assembler.ShaclIndexAssembler.deriveEntityDefinition(mapping);

        TextIndexConfig config = new TextIndexConfig(defn);
        config.setShaclMapping(mapping);

        ByteBuffersDirectory dir = new ByteBuffersDirectory();
        ShaclTextIndexLucene textIndex = new ShaclTextIndexLucene(dir, config);

        List<SortSpec> specs = List.of(
            new SortSpec(FP + "year", true),
            new SortSpec(FP + "state", false)
        );

        Sort sort = textIndex.buildLuceneSort(specs);
        assertNotNull(sort);
        SortField[] fields = sort.getSort();
        assertEquals(2, fields.length);
        assertEquals("year", fields[0].getField());
        assertTrue(fields[0] instanceof SortedNumericSortField);
        assertEquals(SortField.Type.CUSTOM, fields[0].getType());
        assertEquals(SortField.Type.INT, ((SortedNumericSortField) fields[0]).getNumericType());
        assertEquals(SortedNumericSelector.Type.MAX, ((SortedNumericSortField) fields[0]).getSelector());
        assertTrue(fields[0].getReverse());
        assertEquals("state", fields[1].getField());
        assertEquals(SortField.Type.STRING, fields[1].getType());
        assertFalse(fields[1].getReverse());

        textIndex.close();
    }

    @Test
    public void testBuildLuceneSortNull() {
        Node yearPred = NodeFactory.createURI("http://example.org/year");
        FieldDef yearField = new FieldDef("year", FieldType.INT, null,
            true, true, false, true, false, false);

        List<FieldOccurrence> rootOccurrences = Collections.singletonList(
            occurrence(yearField, PathFactory.pathLink(yearPred), Collections.singleton(yearPred)));

        IndexProfile profile = new IndexProfile(
            NodeFactory.createURI("http://example.org/Shape"),
            Collections.singleton(NodeFactory.createURI("http://example.org/Thing")),
            "uri", "docType",
            Collections.singletonList(yearField),
            rootOccurrences,
            Collections.emptyList(),
            Collections.emptyList());

        ShaclIndexMapping mapping = new ShaclIndexMapping(Collections.singletonList(profile));
        EntityDefinition defn = org.apache.jena.query.text.assembler.ShaclIndexAssembler.deriveEntityDefinition(mapping);

        TextIndexConfig config = new TextIndexConfig(defn);
        config.setShaclMapping(mapping);

        ByteBuffersDirectory dir = new ByteBuffersDirectory();
        ShaclTextIndexLucene textIndex = new ShaclTextIndexLucene(dir, config);

        assertNull(textIndex.buildLuceneSort(null));
        assertNull(textIndex.buildLuceneSort(Collections.emptyList()));

        textIndex.close();
    }

    @Test
    public void testBuildLuceneSortAscendingUsesMinSelector() {
        Node yearPred = NodeFactory.createURI("http://example.org/year");
        FieldDef yearField = new FieldDef("year", FieldType.INT, null,
            true, true, false, true, true, false);

        List<FieldOccurrence> rootOccurrences = Collections.singletonList(
            occurrence(yearField, PathFactory.pathLink(yearPred), Collections.singleton(yearPred)));

        IndexProfile profile = new IndexProfile(
            NodeFactory.createURI("http://example.org/Shape"),
            Collections.singleton(NodeFactory.createURI("http://example.org/Thing")),
            "uri", "docType",
            Collections.singletonList(yearField),
            rootOccurrences,
            Collections.emptyList(),
            Collections.emptyList());

        ShaclIndexMapping mapping = new ShaclIndexMapping(Collections.singletonList(profile));
        EntityDefinition defn = org.apache.jena.query.text.assembler.ShaclIndexAssembler.deriveEntityDefinition(mapping);

        TextIndexConfig config = new TextIndexConfig(defn);
        config.setShaclMapping(mapping);

        ByteBuffersDirectory dir = new ByteBuffersDirectory();
        ShaclTextIndexLucene textIndex = new ShaclTextIndexLucene(dir, config);

        try {
            Sort sort = textIndex.buildLuceneSort(List.of(new SortSpec(FP + "year", false)));
            SortField field = sort.getSort()[0];
            assertTrue(field instanceof SortedNumericSortField);
            assertEquals(SortedNumericSelector.Type.MIN, ((SortedNumericSortField) field).getSelector());
        } finally {
            textIndex.close();
        }
    }

    @Test
    public void testMultiValuedKeywordSortUsesMinAndMaxSelectors() {
        Node remarksPred = NodeFactory.createURI("http://example.org/remarks");
        FieldDef remarksField = new FieldDef("remarks", FieldType.KEYWORD, null,
            false, true, false, true, true, false);

        List<FieldOccurrence> rootOccurrences = Collections.singletonList(
            occurrence(remarksField, PathFactory.pathLink(remarksPred), Collections.singleton(remarksPred)));
        IndexProfile profile = new IndexProfile(
            NodeFactory.createURI("http://example.org/Shape"),
            Collections.singleton(NodeFactory.createURI("http://example.org/Thing")),
            "uri", "docType",
            Collections.singletonList(remarksField),
            rootOccurrences,
            Collections.emptyList(),
            Collections.emptyList());
        ShaclIndexMapping mapping = new ShaclIndexMapping(Collections.singletonList(profile));
        EntityDefinition defn = ShaclIndexAssembler.deriveEntityDefinition(mapping);
        TextIndexConfig config = new TextIndexConfig(defn);
        config.setShaclMapping(mapping);
        ShaclTextIndexLucene textIndex = new ShaclTextIndexLucene(new ByteBuffersDirectory(), config);

        try {
            SortField ascending = textIndex.buildLuceneSort(
                List.of(new SortSpec(FP + "remarks", false))).getSort()[0];
            assertTrue(ascending instanceof SortedSetSortField);
            assertEquals(SortedSetSelector.Type.MIN,
                ((SortedSetSortField) ascending).getSelector());
            assertFalse(ascending.getReverse());

            SortField descending = textIndex.buildLuceneSort(
                List.of(new SortSpec(FP + "remarks", true))).getSort()[0];
            assertTrue(descending instanceof SortedSetSortField);
            assertEquals(SortedSetSelector.Type.MAX,
                ((SortedSetSortField) descending).getSelector());
            assertTrue(descending.getReverse());
        } finally {
            textIndex.close();
        }
    }

    @Test
    public void testMultiValuedKeywordSortOrdering() {
        Node remarksPred = NodeFactory.createURI("http://example.org/remarks");
        FieldDef remarksField = new FieldDef("remarks", FieldType.KEYWORD, null,
            false, true, false, true, true, false);

        List<FieldOccurrence> rootOccurrences = Collections.singletonList(
            occurrence(remarksField, PathFactory.pathLink(remarksPred), Collections.singleton(remarksPred)));
        IndexProfile profile = new IndexProfile(
            NodeFactory.createURI("http://example.org/Shape"),
            Collections.singleton(NodeFactory.createURI("http://example.org/Thing")),
            "uri", "docType",
            Collections.singletonList(remarksField),
            rootOccurrences,
            Collections.emptyList(),
            Collections.emptyList());
        ShaclIndexMapping mapping = new ShaclIndexMapping(Collections.singletonList(profile));
        EntityDefinition defn = ShaclIndexAssembler.deriveEntityDefinition(mapping);
        TextIndexConfig config = new TextIndexConfig(defn);
        config.setShaclMapping(mapping);
        ShaclTextIndexLucene textIndex = new ShaclTextIndexLucene(new ByteBuffersDirectory(), config);

        try {
            Entity first = new Entity("http://example.org/first", null);
            first.addValue("remarks", "alpha");
            first.addValue("remarks", "yankee");
            textIndex.updateEntityForProfile(first, profile);

            Entity second = new Entity("http://example.org/second", null);
            second.addValue("remarks", NodeFactory.createLiteralString("bravo"));
            second.addValue("remarks", NodeFactory.createLiteralString("zulu"));
            textIndex.updateEntityForProfile(second, profile);
            textIndex.commit();

            List<SearchHit> ascending = textIndex.searchWithHitIds(
                Collections.emptyList(), null, null,
                List.of(new SortSpec(FP + "remarks", false)), null, null, 10);
            assertEquals(List.of("http://example.org/first", "http://example.org/second"),
                ascending.stream().map(hit -> hit.getEntityNode().getURI()).toList());

            List<SearchHit> descending = textIndex.searchWithHitIds(
                Collections.emptyList(), null, null,
                List.of(new SortSpec(FP + "remarks", true)), null, null, 10);
            assertEquals(List.of("http://example.org/second", "http://example.org/first"),
                descending.stream().map(hit -> hit.getEntityNode().getURI()).toList());
        } finally {
            textIndex.close();
        }
    }

    @Test(expected = TextIndexException.class)
    public void testBuildLuceneSortTextFieldThrows() {
        Node titlePred = NodeFactory.createURI("http://example.org/title");
        FieldDef titleField = new FieldDef("title", FieldType.TEXT, null,
            true, true, false, false, false, true);

        List<FieldOccurrence> rootOccurrences = Collections.singletonList(
            occurrence(titleField, PathFactory.pathLink(titlePred), Collections.singleton(titlePred)));

        IndexProfile profile = new IndexProfile(
            NodeFactory.createURI("http://example.org/Shape"),
            Collections.singleton(NodeFactory.createURI("http://example.org/Thing")),
            "uri", "docType",
            Collections.singletonList(titleField),
            rootOccurrences,
            Collections.emptyList(),
            Collections.emptyList());

        ShaclIndexMapping mapping = new ShaclIndexMapping(Collections.singletonList(profile));
        EntityDefinition defn = org.apache.jena.query.text.assembler.ShaclIndexAssembler.deriveEntityDefinition(mapping);

        TextIndexConfig config = new TextIndexConfig(defn);
        config.setShaclMapping(mapping);

        ByteBuffersDirectory dir = new ByteBuffersDirectory();
        ShaclTextIndexLucene textIndex = new ShaclTextIndexLucene(dir, config);

        try {
            textIndex.buildLuceneSort(List.of(new SortSpec(FP + "title", false)));
        } finally {
            textIndex.close();
        }
    }
}
