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

import static org.apache.jena.query.text.assembler.IndexVocab.*;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.sparql.graph.GraphFactory;
import org.apache.jena.sparql.graph.GraphReadOnly;
import org.apache.lucene.index.FilterLeafReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.MultiTerms;
import org.apache.lucene.index.PointValues;
import org.apache.lucene.index.SegmentReader;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.search.IndexSearcher;

/**
 * The figures {@code luc:stats} returns, read from one {@link IndexSearcher}.
 * <p>
 * Field figures come from Lucene's index statistics, which count documents that are
 * deleted but not yet merged away. {@code luc:deletedDocumentCount} on the index says how
 * many there are. A field inside an {@code idx:nested} block is held on child documents,
 * so its {@code luc:documentCount} counts those, not entities.
 */
final class ShaclIndexStats {

    private ShaclIndexStats() {}

    static Graph compute(IndexSearcher searcher, ShaclIndexMapping mapping, Node index) throws IOException {
        IndexReader reader = searcher.getIndexReader();
        Graph g = GraphFactory.createDefaultGraph();

        add(g, index, pEntityCount, searcher.count(ShaclTextIndexLucene.PARENT_DOC_FILTER));
        add(g, index, pDocumentCount, reader.numDocs());
        add(g, index, pDeletedDocumentCount, reader.numDeletedDocs());
        add(g, index, pSegmentCount, reader.leaves().size());
        add(g, index, pSizeInBytes, sizeInBytes(reader));

        Set<Node> seen = new HashSet<>();
        for (ShaclIndexMapping.IndexProfile profile : mapping.getProfiles()) {
            for (ShaclIndexMapping.FieldDef field : profile.getFields()) {
                if (seen.add(field.getFieldIRI())) {
                    addField(g, reader, field);
                }
            }
        }
        return new GraphReadOnly(g);
    }

    private static void addField(Graph g, IndexReader reader, ShaclIndexMapping.FieldDef field) throws IOException {
        Node s = field.getFieldIRI();
        String name = field.getFieldName();
        switch (field.getFieldType()) {
            case TEXT, KEYWORD -> {
                add(g, s, pDocumentCount, reader.getDocCount(name));
                add(g, s, pTermCount, distinctTerms(reader, name));
                add(g, s, pTokenCount, reader.getSumTotalTermFreq(name));
            }
            case INT, LONG, DOUBLE, LATLON -> addPoints(g, reader, s, name);
            case TEMPORAL -> addPoints(g, reader, s, LiteralFieldSupport.epochField(name));
        }
    }

    /** A {@code LatLonField} geometry is indexed as several triangles, each a point. */
    private static void addPoints(Graph g, IndexReader reader, Node s, String luceneField) throws IOException {
        add(g, s, pDocumentCount, PointValues.getDocCount(reader, luceneField));
        add(g, s, pPointCount, PointValues.size(reader, luceneField));
    }

    /**
     * Lucene records the term count per segment only, and a term present in several
     * segments would be counted once for each, so more than one segment means walking the
     * merged term dictionary. The result is cached per searcher by the caller.
     */
    private static long distinctTerms(IndexReader reader, String field) throws IOException {
        Terms terms = MultiTerms.getTerms(reader, field);
        if (terms == null) {
            return 0;
        }
        long size = terms.size();
        if (size >= 0) {
            return size;
        }
        long count = 0;
        TermsEnum te = terms.iterator();
        while (te.next() != null) {
            count++;
        }
        return count;
    }

    /** Files of the main index's live segments. The taxonomy index is not included. */
    private static long sizeInBytes(IndexReader reader) throws IOException {
        long total = 0;
        for (LeafReaderContext leaf : reader.leaves()) {
            LeafReader r = FilterLeafReader.unwrap(leaf.reader());
            if (r instanceof SegmentReader segment) {
                total += segment.getSegmentInfo().sizeInBytes();
            }
        }
        return total;
    }

    private static void add(Graph g, Node s, Property p, long value) {
        g.add(s, p.asNode(), NodeFactory.createLiteralDT(Long.toString(value), XSDDatatype.XSDlong));
    }
}
