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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.function.Function;

import org.apache.jena.atlas.io.IndentedLineBuffer;
import org.apache.jena.atlas.iterator.Iter;
import org.apache.jena.atlas.json.JSON;
import org.apache.jena.atlas.json.JsonArray;
import org.apache.jena.atlas.logging.Log;
import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.query.QueryBuildException;
import org.apache.jena.query.QueryExecException;
import org.apache.jena.query.text.cql.CqlExpression;
import org.apache.jena.query.text.cql.CqlParser;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.core.Substitute;
import org.apache.jena.sparql.core.Var;
import org.apache.jena.sparql.engine.ExecutionContext;
import org.apache.jena.sparql.engine.QueryIterator;
import org.apache.jena.sparql.engine.binding.Binding;
import org.apache.jena.sparql.engine.binding.BindingBuilder;
import org.apache.jena.sparql.engine.iterator.QueryIterPlainWrapper;
import org.apache.jena.sparql.pfunction.PropFuncArg;
import org.apache.jena.sparql.pfunction.PropertyFunctionBase;
import org.apache.jena.sparql.util.IterLib;
import org.apache.jena.sparql.util.NodeFactoryExtra;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SPARQL property function for SHACL-mode text queries ({@code luc:query}).
 * <p>
 * Supported argument format:
 * <pre>
 * (?hit ?entity ?score ?totalHits ?graph) luc:query (indexSelector fieldSpec queryString cqlFilter sortSpec limit offset)
 * </pre>
 * <p>
 * {@code ?hit} is a query-scoped blank node identifier for joining with {@code luc:match}.
 * The second string literal is the field specification: {@code "default"} searches all
 * defaultSearch fields, and a JSON array of field IRIs like
 * {@code '["urn:jena:lucene:field#title","urn:jena:lucene:field#description"]'} searches specific fields.
 * Use the empty string literal ({@code ""}) when {@code cqlFilter} or {@code sortSpec} is intentionally absent.
 */
public class ShaclTextQueryPF extends PropertyFunctionBase {
    private static final Logger log = LoggerFactory.getLogger(ShaclTextQueryPF.class);
    private static final int QUERY_ARG_ARITY = 7;

    private ShaclTextIndexLucene textIndex = null;
    private boolean warningIssued = false;

    public ShaclTextQueryPF() {}

    @Override
    public void build(PropFuncArg argSubject, Node predicate, PropFuncArg argObject, ExecutionContext execCxt) {
        super.build(argSubject, predicate, argObject, execCxt);

        if (argSubject.isList()) {
            int size = argSubject.getArgListSize();
            if (size == 0 || size > 5) {
                throw new QueryBuildException("Subject has " + size + " elements, must be 1-5 " +
                    "(?hit ?entity ?score ?totalHits ?graph): " + argSubject);
            }
        }

        if (!argObject.isList()) {
            throw new QueryBuildException("luc:query expects exactly " + QUERY_ARG_ARITY
                + " object arguments: (indexSelector fieldSpec queryString cqlFilter sortSpec limit offset)");
        }
        int size = argObject.getArgListSize();
        if (size != QUERY_ARG_ARITY) {
            throw new QueryBuildException("luc:query expects exactly " + QUERY_ARG_ARITY
                + " object arguments (indexSelector fieldSpec queryString cqlFilter sortSpec limit offset), got " + size);
        }
    }

    private record ResolvedTextIndex(String identity, ShaclTextIndexLucene index) {}

    private static ResolvedTextIndex chooseTextIndex(ExecutionContext execCxt, DatasetGraph dsg, String selector) {
        // Try registry first
        Object regObj = execCxt.getContext().get(TextQuery.textIndexRegistry);
        if (regObj instanceof TextIndexRegistry registry) {
            TextIndexRegistry.ResolvedIndex resolved = registry.resolve(selector);
            TextIndexLucene idx = resolved.index();
            if (idx instanceof ShaclTextIndexLucene shaclIdx) {
                return new ResolvedTextIndex(resolved.canonicalKey(), shaclIdx);
            }
            throw new TextIndexException("Selected text index is not SHACL-enabled: " + selector);
        }

        // Fall back to single index
        if (!TextIndexRegistry.DEFAULT_ID.equals(selector)) {
            throw new TextIndexException("Single-index datasets only support index selector \"" +
                TextIndexRegistry.DEFAULT_ID + "\", got: " + selector);
        }
        Object obj = execCxt.getContext().get(TextQuery.textIndex);
        if (obj instanceof ShaclTextIndexLucene shaclIdx) {
            return new ResolvedTextIndex(TextIndexRegistry.DEFAULT_ID, shaclIdx);
        }
        if (obj != null) {
            throw new TextIndexException("Configured text index is not SHACL-enabled");
        }
        if (dsg instanceof DatasetGraphText) {
            TextIndex ti = ((DatasetGraphText) dsg).getTextIndex();
            if (ti instanceof ShaclTextIndexLucene shaclIdx) {
                return new ResolvedTextIndex(TextIndexRegistry.DEFAULT_ID, shaclIdx);
            }
            throw new TextIndexException("Dataset text index is not SHACL-enabled");
        }
        Log.warn(ShaclTextQueryPF.class, "Failed to find the text index");
        return null;
    }

    @Override
    public QueryIterator exec(Binding binding,
                              PropFuncArg argSubject, Node predicate, PropFuncArg argObject,
                              ExecutionContext execCxt) {
        if (log.isTraceEnabled()) {
            IndentedLineBuffer subjBuff = new IndentedLineBuffer();
            argSubject.output(subjBuff, null);
            IndentedLineBuffer objBuff = new IndentedLineBuffer();
            argObject.output(objBuff, null);
            log.trace("exec: {} luc:query {}", subjBuff, objBuff);
        }

        argSubject = Substitute.substitute(argSubject, binding);
        argObject = Substitute.substitute(argObject, binding);

        // Subject shape: (?hit ?entity ?score ?totalHits ?graph)
        Node hit = null, entity = null, score = null, totalHitsNode = null, graph = null;

        if (argSubject.isList()) {
            hit = argSubject.getArg(0);
            if (argSubject.getArgListSize() > 1) {
                entity = argSubject.getArg(1);
            }
            if (argSubject.getArgListSize() > 2) {
                score = argSubject.getArg(2);
                if (!score.isVariable())
                    throw new QueryExecException("Score is not a variable: " + argSubject);
            }
            if (argSubject.getArgListSize() > 3) {
                totalHitsNode = argSubject.getArg(3);
                if (!totalHitsNode.isVariable())
                    throw new QueryExecException("Total hits is not a variable: " + argSubject);
            }
            if (argSubject.getArgListSize() > 4) {
                graph = argSubject.getArg(4);
                if (!graph.isVariable())
                    throw new QueryExecException("Graph is not a variable: " + argSubject);
            }
        } else {
            hit = argSubject.getArg();
        }

        if (hit != null && hit.isLiteral())
            return IterLib.noResults(execCxt);

        QueryArgs args = parseArgs(argObject);
        if (args == null)
            return IterLib.noResults(execCxt);

        ResolvedTextIndex resolvedTextIndex = chooseTextIndex(execCxt, execCxt.getDataset(), args.indexSelector);
        if (resolvedTextIndex == null) {
            if (!warningIssued) {
                Log.warn(getClass(), "No text index - no text search performed");
                warningIssued = true;
            }
            return IterLib.result(binding, execCxt);
        }
        textIndex = resolvedTextIndex.index();

        // Use SearchExecution for shared state with luc:facet and luc:match
        SearchExecution se = SearchExecution.getOrCreate(
            execCxt, resolvedTextIndex.identity(), args.searchFields, args.queryString,
            args.cqlFilter, args.sortSpecs, textIndex, null, null);

        int limit = args.limit > 0 ? args.limit : 10000;
        int offset = args.offset;
        List<SearchHit> pageHits = se.getSearchHits(offset, limit);

        Collection<SearchHit> hits;
        if (entity != null && !Var.isVar(entity)) {
            String entityStr = TextQueryFuncs.subjectToString(entity);
            hits = new ArrayList<>();
            for (SearchHit sh : pageHits) {
                if (entityStr.equals(TextQueryFuncs.subjectToString(sh.getEntityNode()))) {
                    hits.add(sh);
                }
            }
        } else {
            hits = pageHits;
        }

        long totalHits = totalHitsNode != null ? se.getTotalHits() : -1;
        QueryIterator qIter = resultsToQueryIterator(binding, hit, entity, score,
            totalHitsNode, totalHits, graph, hits, execCxt);
        return qIter;
    }

    private QueryIterator resultsToQueryIterator(Binding binding,
                                                  Node hitNode, Node entityNode, Node scoreNode,
                                                  Node totalHitsNode, long totalHits, Node graphNode,
                                                  Collection<SearchHit> results, ExecutionContext execCxt) {
        Var hitVar = Var.isVar(hitNode) ? Var.alloc(hitNode) : null;
        Var entityVar = (entityNode != null && Var.isVar(entityNode)) ? Var.alloc(entityNode) : null;
        Var scoreVar = (scoreNode == null) ? null : Var.alloc(scoreNode);
        Var totalHitsVar = (totalHitsNode == null) ? null : Var.alloc(totalHitsNode);
        Node totalHitsValue = totalHitsVar != null
            ? NodeFactory.createLiteralDT(String.valueOf(totalHits), XSDDatatype.XSDinteger) : null;
        Var graphVar = (graphNode == null) ? null : Var.alloc(graphNode);

        Function<SearchHit, Binding> converter = (SearchHit sh) -> {
            BindingBuilder bmap = Binding.builder(binding);
            if (hitVar != null) bmap.add(hitVar, sh.getHitId());
            if (entityVar != null) bmap.add(entityVar, sh.getEntityNode());
            if (scoreVar != null) bmap.add(scoreVar, NodeFactoryExtra.floatToNode(sh.getScore()));
            if (totalHitsVar != null) bmap.add(totalHitsVar, totalHitsValue);
            if (graphVar != null && sh.getGraph() != null) bmap.add(graphVar, sh.getGraph());
            return bmap.build();
        };

        Iterator<Binding> bIter = Iter.map(results.iterator(), converter);
        return QueryIterPlainWrapper.create(bIter, execCxt);
    }

    /**
     * Parse object arguments.
     * <p>
     * Arg order: (indexSelector fieldSpec queryString cqlFilter sortSpec limit offset)
     */
    private QueryArgs parseArgs(PropFuncArg argObject) {
        if (!argObject.isList()) {
            throw new QueryExecException("luc:query expects exactly " + QUERY_ARG_ARITY
                + " object arguments (indexSelector fieldSpec queryString cqlFilter sortSpec limit offset)");
        }
        List<Node> list = argObject.getArgList();
        if (list.size() != QUERY_ARG_ARITY) {
            throw new QueryExecException("luc:query expects exactly " + QUERY_ARG_ARITY
                + " object arguments (indexSelector fieldSpec queryString cqlFilter sortSpec limit offset), got " + list.size());
        }

        String indexSelector = requireLiteralString(list.get(0), "indexSelector");
        List<String> searchFields = new ArrayList<>();
        parseFieldSpec(requireLiteralString(list.get(1), "fieldSpec"), searchFields);
        String queryString = requireLiteralString(list.get(2), "queryString");
        CqlExpression cqlFilter = parseCqlFilter(requireLiteralString(list.get(3), "cqlFilter"));
        List<SortSpec> sortSpecs = parseSortSpec(requireLiteralString(list.get(4), "sortSpec"));
        int limit = parseLimit(list.get(5));
        int offset = parseOffset(list.get(6));

        long windowSize = (long) offset + limit;
        if (windowSize > Integer.MAX_VALUE) {
            throw new QueryExecException("luc:query offset + limit exceeds maximum (" +
                Integer.MAX_VALUE + "): offset=" + offset + ", limit=" + limit);
        }

        return new QueryArgs(indexSelector, searchFields, queryString, cqlFilter, sortSpecs, limit, offset);
    }

    private static String requireLiteralString(Node node, String label) {
        if (!node.isLiteral()) {
            throw new QueryExecException("luc:query " + label + " must be a literal, got: " + node);
        }
        return node.getLiteralLexicalForm();
    }

    private static void parseFieldSpec(String fieldSpec, List<String> searchFields) {
        if ("default".equals(fieldSpec)) {
            searchFields.add("default");
            return;
        }
        if (!fieldSpec.startsWith("[")) {
            throw new QueryExecException("luc:query fieldSpec must be \"default\" or a JSON array of field IRIs");
        }
        JsonArray arr = JSON.parseAny(fieldSpec).getAsArray();
        for (int i = 0; i < arr.size(); i++) {
            searchFields.add(arr.get(i).getAsString().value());
        }
    }

    private static CqlExpression parseCqlFilter(String filterLex) {
        if (filterLex.isEmpty()) {
            return null;
        }
        return CqlParser.parse(filterLex);
    }

    private static List<SortSpec> parseSortSpec(String sortLex) {
        if (sortLex.isEmpty()) {
            return null;
        }
        return SortSpecParser.parse(sortLex);
    }

    private static int parseLimit(Node node) {
        try {
            int value = Integer.parseInt(requireLiteralString(node, "limit"));
            return Math.max(value, -1);
        } catch (NumberFormatException ex) {
            throw new QueryExecException("luc:query limit must be an integer literal, got: " + node);
        }
    }

    private static int parseOffset(Node node) {
        try {
            int value = Integer.parseInt(requireLiteralString(node, "offset"));
            if (value < 0) {
                throw new QueryExecException("luc:query offset must be non-negative, got: " + value);
            }
            return value;
        } catch (NumberFormatException ex) {
            throw new QueryExecException("luc:query offset must be an integer literal, got: " + node);
        }
    }

    private static class QueryArgs {
        final String indexSelector;
        final List<String> searchFields;
        final String queryString;
        final CqlExpression cqlFilter;
        final List<SortSpec> sortSpecs;
        final int limit;
        final int offset;

        QueryArgs(String indexSelector, List<String> searchFields, String queryString,
                  CqlExpression cqlFilter, List<SortSpec> sortSpecs,
                  int limit, int offset) {
            this.indexSelector = indexSelector;
            this.searchFields = searchFields;
            this.queryString = queryString;
            this.cqlFilter = cqlFilter;
            this.sortSpecs = sortSpecs;
            this.limit = limit;
            this.offset = offset;
        }
    }
}
