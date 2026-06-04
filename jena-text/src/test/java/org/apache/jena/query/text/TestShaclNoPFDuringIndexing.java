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
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.query.ReadWrite;
import org.apache.jena.query.text.ShaclIndexMapping.*;
import org.apache.jena.query.text.assembler.ShaclIndexAssembler;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.sparql.engine.ExecutionContext;
import org.apache.jena.sparql.engine.QueryIterator;
import org.apache.jena.sparql.engine.binding.Binding;
import org.apache.jena.sparql.pfunction.PropFuncArg;
import org.apache.jena.sparql.pfunction.PropertyFunctionBase;
import org.apache.jena.sparql.pfunction.PropertyFunctionRegistry;
import org.apache.jena.sparql.path.PathFactory;
import org.apache.jena.sparql.util.IterLib;
import org.apache.jena.vocabulary.RDF;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Verifies that SPARQL property functions are not invoked when the SHACL indexer
 * evaluates field paths. Predicates registered as PFs (e.g. geo:sfWithin) must be
 * treated as plain triple-store IRIs at index time, not dispatched to the PF runtime.
 */
public class TestShaclNoPFDuringIndexing {

    private static final String NS   = "http://example.org/";
    private static final String PF_URI = NS + "testPropertyFunction";

    private static final Node THING_CLASS = NodeFactory.createURI(NS + "Thing");
    private static final Node NAME_PRED   = NodeFactory.createURI(NS + "name");
    private static final Node PF_PRED     = NodeFactory.createURI(PF_URI);

    private final AtomicInteger pfCallCount = new AtomicInteger(0);

    private Dataset dataset;
    private ShaclTextIndexLucene textIndex;

    @Before
    public void setUp() {
        pfCallCount.set(0);

        PropertyFunctionRegistry.get().put(PF_URI, uri -> new PropertyFunctionBase() {
            @Override
            public QueryIterator exec(Binding binding, PropFuncArg subjectArg, Node predicate,
                                      PropFuncArg objectArg, ExecutionContext execCtx) {
                pfCallCount.incrementAndGet();
                return IterLib.noResults(execCtx);
            }
        });

        FieldDef nameField = new FieldDef("name", FieldType.TEXT, null,
            true, true, false, false, false, true);
        FieldDef pfField   = new FieldDef("pfField", FieldType.KEYWORD, null,
            true, true, false, false, false, false);

        List<FieldOccurrence> rootOccurrences = Arrays.asList(
            occurrence(nameField, NAME_PRED),
            occurrence(pfField, PF_PRED));

        IndexProfile profile = new IndexProfile(
            NodeFactory.createURI(NS + "ThingShape"),
            Collections.singleton(THING_CLASS),
            "uri", "docType",
            Arrays.asList(nameField, pfField),
            rootOccurrences,
            Collections.emptyList(),
            Collections.emptyList());

        ShaclIndexMapping mapping = new ShaclIndexMapping(Collections.singletonList(profile));
        EntityDefinition defn = ShaclIndexAssembler.deriveEntityDefinition(mapping);

        TextIndexConfig config = new TextIndexConfig(defn);
        config.setShaclMapping(mapping);
        config.setValueStored(true);

        ByteBuffersDirectory dir = new ByteBuffersDirectory();
        textIndex = new ShaclTextIndexLucene(dir, config);

        Dataset baseDs = DatasetFactory.create();
        ShaclTextDocProducer producer = new ShaclTextDocProducer(
            baseDs.asDatasetGraph(), textIndex, mapping);

        dataset = TextDatasetFactory.create(baseDs, textIndex, true, producer);
    }

    @After
    public void tearDown() throws Exception {
        if (dataset != null) {
            dataset.close();
        }
        PropertyFunctionRegistry.get().remove(PF_URI);
    }

    @Test
    public void testPropertyFunctionNotInvokedDuringIndexing() {
        dataset.begin(ReadWrite.WRITE);
        try {
            Model model = dataset.getDefaultModel();
            Resource thing = ResourceFactory.createResource(NS + "thing1");
            model.add(thing, RDF.type, ResourceFactory.createResource(NS + "Thing"));
            model.add(thing, ResourceFactory.createProperty(NS + "name"), "Widget");
            dataset.commit();
        } finally {
            dataset.end();
        }

        assertEquals("Property function must not be invoked during SHACL indexing", 0, pfCallCount.get());
    }

    @Test
    public void testPropertyFunctionNotInvokedOnTypeChange() {
        dataset.begin(ReadWrite.WRITE);
        try {
            Model model = dataset.getDefaultModel();
            Resource thing = ResourceFactory.createResource(NS + "thing2");
            model.add(thing, RDF.type, ResourceFactory.createResource(NS + "Thing"));
            dataset.commit();
        } finally {
            dataset.end();
        }

        assertEquals("Property function must not be invoked on rdf:type change", 0, pfCallCount.get());
    }

    private static FieldOccurrence occurrence(FieldDef field, Node predicate) {
        return new FieldOccurrence(
            field,
            PathFactory.pathLink(predicate),
            List.of(List.of(new JoinStep(predicate, false))),
            Collections.singleton(predicate),
            null, null, null, null);
    }
}
