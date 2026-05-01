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
import org.apache.jena.query.*;
import org.apache.jena.query.text.ShaclIndexMapping.FieldDef;
import org.apache.jena.query.text.ShaclIndexMapping.FieldOccurrence;
import org.apache.jena.query.text.ShaclIndexMapping.FieldType;
import org.apache.jena.query.text.ShaclIndexMapping.IndexProfile;
import org.apache.jena.query.text.assembler.ShaclIndexAssembler;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.path.Path;
import org.apache.jena.sparql.path.PathFactory;
import org.apache.jena.vocabulary.RDF;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for {@link ShaclBulkIndexer} when the dataset has multiple SHACL indexes
 * registered via {@link TextIndexRegistry} (issue #68).
 * <p>
 * Mirrors what {@code shacltextindexer} does on the CLI: walks the registry,
 * runs a {@code ShaclBulkIndexer} per registered SHACL index. Each index must
 * receive its own documents.
 */
public class TestShaclBulkIndexerMultiIndex {

    private static final String NS = "http://example.org/";
    private static final Node BOOK_CLASS = NodeFactory.createURI(NS + "Book");
    private static final Node ARTICLE_CLASS = NodeFactory.createURI(NS + "Article");
    private static final Node TITLE_PRED = NodeFactory.createURI(NS + "title");
    private static final Node CATEGORY_PRED = NodeFactory.createURI(NS + "category");
    private static final Node TOPIC_PRED = NodeFactory.createURI(NS + "topic");

    private Dataset baseDataset;
    private ShaclTextIndexLucene bookIndex;
    private ShaclTextIndexLucene articleIndex;
    private TextIndexRegistry registry;

    @Before
    public void setUp() {
        baseDataset = DatasetFactory.create();
        bookIndex = makeShaclIndex("BookShape", BOOK_CLASS,
            occurrence(textField("title"), TITLE_PRED),
            occurrence(keywordField("category"), CATEGORY_PRED));
        articleIndex = makeShaclIndex("ArticleShape", ARTICLE_CLASS,
            occurrence(textField("title"), TITLE_PRED),
            occurrence(keywordField("topic"), TOPIC_PRED));

        registry = new TextIndexRegistry();
        registry.register("books", bookIndex);
        registry.register("articles", articleIndex);
    }

    @After
    public void tearDown() {
        if (registry != null) registry.close();
        if (baseDataset != null) baseDataset.close();
    }

    @Test
    public void testBulkIndexerProcessesEachRegisteredShaclIndex() {
        // Load mixed data: books and articles in the same TDB (no live wrapper).
        Model model = baseDataset.getDefaultModel();
        addBook(model, "book1", "Machine Learning Basics", "Technology");
        addBook(model, "book2", "Quantum Physics", "Science");
        addArticle(model, "art1", "Deep Learning Article", "AI");
        addArticle(model, "art2", "Cosmology Update", "Physics");

        DatasetGraph dsg = baseDataset.asDatasetGraph();

        // Mirror what shacltextindexer.exec() does post-PR-72: walk the registry and
        // run ShaclBulkIndexer per registered SHACL index.
        long total = 0;
        for (Map.Entry<String, TextIndexLucene> entry : registry.allWithIds().entrySet()) {
            assertTrue("Each registered index must be a SHACL Lucene index for this test",
                entry.getValue() instanceof ShaclTextIndexLucene);
            ShaclTextIndexLucene shaclIdx = (ShaclTextIndexLucene) entry.getValue();
            ShaclBulkIndexer indexer = new ShaclBulkIndexer(
                dsg, shaclIdx, shaclIdx.getShaclMapping());
            indexer.index();
            total += indexer.getEntityCount();
        }
        assertEquals("Total entities across both indexes must equal book + article count",
            4, total);

        // Each index must contain only its own docs.
        List<TextHit> bookHits = bookIndex.query(TITLE_PRED, "machine OR quantum", null, null);
        Set<String> bookUris = uris(bookHits);
        assertTrue("Book index should contain book1", bookUris.contains(NS + "book1"));
        assertTrue("Book index should contain book2", bookUris.contains(NS + "book2"));
        assertFalse("Book index must NOT contain articles", bookUris.contains(NS + "art1"));

        List<TextHit> articleHits = articleIndex.query(TITLE_PRED, "deep OR cosmology", null, null);
        Set<String> articleUris = uris(articleHits);
        assertTrue("Article index should contain art1", articleUris.contains(NS + "art1"));
        assertTrue("Article index should contain art2", articleUris.contains(NS + "art2"));
        assertFalse("Article index must NOT contain books", articleUris.contains(NS + "book1"));
    }

    // --- Helpers ---

    private static FieldDef textField(String name) {
        return new FieldDef(name, FieldType.TEXT, null,
            true, true, false, false, false, true);
    }

    private static FieldDef keywordField(String name) {
        return new FieldDef(name, FieldType.KEYWORD, null,
            true, true, true, false, true, false);
    }

    private static FieldOccurrence occurrence(FieldDef field, Node predicate) {
        Path path = PathFactory.pathLink(predicate);
        return new FieldOccurrence(
            field, path,
            ShaclIndexAssembler.extractPathVariants(path),
            Collections.singleton(predicate),
            null, null, null, null);
    }

    private static ShaclTextIndexLucene makeShaclIndex(String shapeLocalName, Node targetClass,
                                                       FieldOccurrence... occurrences) {
        List<FieldDef> fields = new ArrayList<>();
        for (FieldOccurrence occ : occurrences) fields.add(occ.getField());
        IndexProfile profile = new IndexProfile(
            NodeFactory.createURI(NS + shapeLocalName),
            Collections.singleton(targetClass),
            "uri", "docType",
            fields,
            Arrays.asList(occurrences),
            Collections.emptyList(),
            Collections.emptyList());
        ShaclIndexMapping mapping = new ShaclIndexMapping(Collections.singletonList(profile));
        EntityDefinition defn = ShaclIndexAssembler.deriveEntityDefinition(mapping);
        TextIndexConfig config = new TextIndexConfig(defn);
        config.setShaclMapping(mapping);
        config.setFacetFields(mapping.getFacetFieldNames());
        config.setValueStored(true);
        return new ShaclTextIndexLucene(new ByteBuffersDirectory(), config);
    }

    private void addBook(Model model, String id, String title, String category) {
        Resource r = ResourceFactory.createResource(NS + id);
        model.add(r, RDF.type, ResourceFactory.createResource(NS + "Book"));
        model.add(r, ResourceFactory.createProperty(NS + "title"), title);
        model.add(r, ResourceFactory.createProperty(NS + "category"), category);
    }

    private void addArticle(Model model, String id, String title, String topic) {
        Resource r = ResourceFactory.createResource(NS + id);
        model.add(r, RDF.type, ResourceFactory.createResource(NS + "Article"));
        model.add(r, ResourceFactory.createProperty(NS + "title"), title);
        model.add(r, ResourceFactory.createProperty(NS + "topic"), topic);
    }

    private static Set<String> uris(List<TextHit> hits) {
        Set<String> s = new HashSet<>();
        for (TextHit h : hits) {
            if (h.getNode().isURI()) s.add(h.getNode().getURI());
        }
        return s;
    }
}
