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

package org.apache.jena.query.text.assembler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.apache.jena.assembler.Assembler;
import org.apache.jena.query.text.ShaclIndexMapping;
import org.apache.jena.query.text.ShaclIndexMapping.CorrelatedHierarchy;
import org.apache.jena.query.text.ShaclIndexMapping.IndexProfile;
import org.apache.jena.query.text.ShaclTextIndexLucene;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.sys.JenaSystem;
import org.apache.jena.vocabulary.RDF;
import org.junit.Test;

/**
 * Which root hierarchies get a correlated build plan, derived from the parsed config.
 * <p>
 * Correlation applies when the level occurrences form a prefix chain — each level's
 * SHACL path extends the path of the level below it. Anything else keeps the cartesian
 * product, which is correct whenever the levels are genuinely independent.
 *
 * @see org.apache.jena.query.text.TestCorrelatedRootHierarchy for the counts this produces
 */
public class TestCorrelatedHierarchyDerivation {

    private static final String SH = "http://www.w3.org/ns/shacl#";
    private static final String EX = "http://example.org/";
    private static final String FIELD_NS = "urn:jena:lucene:field#";
    private static final String DIM = "dataTypeGrouping_dataType";

    static {
        JenaSystem.init();
        TextAssembler.init();
    }

    private Resource keywordField(Model model, String name) {
        return model.createResource(FIELD_NS + name)
            .addProperty(model.createProperty(IndexVocab.NS, "fieldName"), name)
            .addProperty(model.createProperty(IndexVocab.NS, "fieldType"), IndexVocab.KeywordField)
            .addProperty(model.createProperty(IndexVocab.NS, "facetable"), model.createTypedLiteral(true))
            .addProperty(model.createProperty(IndexVocab.NS, "multiValued"), model.createTypedLiteral(true));
    }

    private Resource occurrence(Model model, Resource field, RDFNode pathNode) {
        return model.createResource()
            .addProperty(model.createProperty(IndexVocab.NS, "field"), field)
            .addProperty(model.createProperty(SH, "path"), pathNode);
    }

    /** {@code sh:path ( a b )} — a SHACL sequence path. */
    private RDFNode sequence(Model model, String... predicates) {
        RDFNode[] links = new RDFNode[predicates.length];
        for (int i = 0; i < predicates.length; i++) {
            links[i] = model.createResource(EX + predicates[i]);
        }
        return model.createList(links);
    }

    private RDFNode inverse(Model model, String predicate) {
        return model.createResource()
            .addProperty(model.createProperty(SH, "inversePath"), model.createResource(EX + predicate));
    }

    /**
     * Build a shape whose {@code dataType} / {@code dataTypeGrouping} occurrences use the
     * supplied paths, declare a hierarchy over them, and return the parsed profile.
     */
    private IndexProfile parseProfile(Model model, RDFNode dataTypePath, RDFNode groupingPath,
                                      RDFNode... extraDataTypePaths) {
        Resource dataType = keywordField(model, "dataType");
        Resource grouping = keywordField(model, "dataTypeGrouping");

        Resource shape = model.createResource(EX + "DocumentShape")
            .addProperty(model.createProperty(SH, "targetClass"), model.createResource(EX + "Document"))
            .addProperty(model.createProperty(SH, "property"), occurrence(model, dataType, dataTypePath))
            .addProperty(model.createProperty(SH, "property"), occurrence(model, grouping, groupingPath))
            .addProperty(model.createProperty(IndexVocab.NS, "facetHierarchy"),
                model.createList(new RDFNode[] { grouping, dataType }));
        for (RDFNode extra : extraDataTypePaths) {
            shape.addProperty(model.createProperty(SH, "property"), occurrence(model, dataType, extra));
        }

        Resource indexSpec = model.createResource(EX + "index")
            .addProperty(RDF.type, TextVocab.textIndexShacl)
            .addProperty(TextVocab.pDirectory, model.createLiteral("mem"))
            .addProperty(TextVocab.pShapes, model.createList(new RDFNode[] { shape }));

        ShaclTextIndexLucene index = (ShaclTextIndexLucene) Assembler.general().open(indexSpec);
        try {
            ShaclIndexMapping mapping = index.getShaclMapping();
            assertEquals(1, mapping.getProfiles().size());
            return mapping.getProfiles().get(0);
        } finally {
            index.close();
        }
    }

    /** The GSWA shape: the grouping path extends the datatype path by one step. */
    @Test
    public void testPrefixChainedLevelsAreCorrelated() {
        Model model = ModelFactory.createDefaultModel();
        IndexProfile profile = parseProfile(model,
            model.createResource(EX + "hasDisplayTable"),
            sequence(model, "hasDisplayTable", "hasGrouping"));

        CorrelatedHierarchy plan = profile.getCorrelatedHierarchy(DIM);
        assertNotNull("prefix-chained levels must be correlated", plan);
        assertEquals(2, plan.getDepth());
        assertEquals("dataType", plan.getLevel(1).getFieldName());
    }

    /** Independent paths off the entity — a genuine cartesian hierarchy, left alone. */
    @Test
    public void testIndependentLevelsStayCartesian() {
        Model model = ModelFactory.createDefaultModel();
        IndexProfile profile = parseProfile(model,
            model.createResource(EX + "hasDisplayTable"),
            model.createResource(EX + "hasGrouping"));

        assertNull("independent paths have no node at which the levels meet",
            profile.getCorrelatedHierarchy(DIM));
    }

    /** The chain must run the right way: the outer level extends the inner one. */
    @Test
    public void testReversedChainStaysCartesian() {
        Model model = ModelFactory.createDefaultModel();
        IndexProfile profile = parseProfile(model,
            sequence(model, "hasGrouping", "hasDisplayTable"),
            model.createResource(EX + "hasGrouping"));

        assertNull(profile.getCorrelatedHierarchy(DIM));
    }

    /**
     * A level reached by two different paths (fan-in) has no single meeting node, so
     * correlation cannot be derived and the cartesian product is kept.
     */
    @Test
    public void testFanInLevelStaysCartesian() {
        Model model = ModelFactory.createDefaultModel();
        IndexProfile profile = parseProfile(model,
            model.createResource(EX + "hasDisplayTable"),
            sequence(model, "hasDisplayTable", "hasGrouping"),
            model.createResource(EX + "hasLegacyDisplayTable"));

        assertNull("a fan-in level cannot be correlated",
            profile.getCorrelatedHierarchy(DIM));
    }

    /** Inverse steps chain like any other step. */
    @Test
    public void testInverseStepInChainIsCorrelated() {
        Model model = ModelFactory.createDefaultModel();
        Model seqModel = model;
        RDFNode groupingPath = seqModel.createList(new RDFNode[] {
            seqModel.createResource(EX + "hasDisplayTable"),
            inverse(seqModel, "groups")
        });

        IndexProfile profile = parseProfile(model,
            model.createResource(EX + "hasDisplayTable"),
            groupingPath);

        assertNotNull("an inverse ascent step is still a chain",
            profile.getCorrelatedHierarchy(DIM));
    }

    /** Three prefix-chained levels correlate the whole way up. */
    @Test
    public void testThreeLevelChainIsCorrelated() {
        Model model = ModelFactory.createDefaultModel();

        Resource dataType = keywordField(model, "dataType");
        Resource grouping = keywordField(model, "dataTypeGrouping");
        Resource theme = keywordField(model, "dataTypeTheme");

        Resource shape = model.createResource(EX + "DocumentShape")
            .addProperty(model.createProperty(SH, "targetClass"), model.createResource(EX + "Document"))
            .addProperty(model.createProperty(SH, "property"),
                occurrence(model, dataType, model.createResource(EX + "hasDisplayTable")))
            .addProperty(model.createProperty(SH, "property"),
                occurrence(model, grouping, sequence(model, "hasDisplayTable", "hasGrouping")))
            .addProperty(model.createProperty(SH, "property"),
                occurrence(model, theme, sequence(model, "hasDisplayTable", "hasGrouping", "hasTheme")))
            .addProperty(model.createProperty(IndexVocab.NS, "facetHierarchy"),
                model.createList(new RDFNode[] { theme, grouping, dataType }));

        Resource indexSpec = model.createResource(EX + "index")
            .addProperty(RDF.type, TextVocab.textIndexShacl)
            .addProperty(TextVocab.pDirectory, model.createLiteral("mem"))
            .addProperty(TextVocab.pShapes, model.createList(new RDFNode[] { shape }));

        ShaclTextIndexLucene index = (ShaclTextIndexLucene) Assembler.general().open(indexSpec);
        try {
            IndexProfile profile = index.getShaclMapping().getProfiles().get(0);
            CorrelatedHierarchy plan = profile.getCorrelatedHierarchy(
                "dataTypeTheme_dataTypeGrouping_dataType");
            assertNotNull(plan);
            assertEquals(3, plan.getDepth());
        } finally {
            index.close();
        }
    }
}
