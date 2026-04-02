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
import org.apache.jena.sparql.engine.iterator.QueryIterSlice;
import org.apache.jena.sparql.pfunction.PropFuncArg;
import org.apache.jena.sparql.pfunction.PropertyFunctionBase;
import org.apache.jena.sparql.util.IterLib;
import org.apache.jena.sparql.util.NodeFactoryExtra;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SPARQL property function for SHACL-mode text queries ({@code luc:query}).
 * <p>
 * Argument format:
 * <pre>
 * (?hit ?entity ?score ?match ?totalHits ?graph) luc:query (fieldSpec queryString cqlFilter? sortSpec? limit?)
 * </pre>
 * <p>
 * {@code ?hit} is a query-scoped blank node identifier for joining with {@code luc:match}.
 * The first string literal is the field specification: {@code "default"} searches all
 * defaultSearch fields, and a JSON array of field IRIs like
 * {@code '["urn:jena:lucene:field#title","urn:jena:lucene:field#description"]'} searches specific fields.
 */
public class ShaclTextQueryPF extends PropertyFunctionBase {
    private static final Logger log = LoggerFactory.getLogger(ShaclTextQueryPF.class);

    private ShaclTextIndexLucene textIndex = null;
    private boolean warningIssued = false;

    public ShaclTextQueryPF() {}

    @Override
    public void build(PropFuncArg argSubject, Node predicate, PropFuncArg argObject, ExecutionContext execCxt) {
        super.build(argSubject, predicate, argObject, execCxt);

        if (argSubject.isList()) {
            int size = argSubject.getArgListSize();
            if (size == 0 || size > 6) {
                throw new QueryBuildException("Subject has " + size + " elements, must be 1-6 " +
                    "(?hit ?entity ?score ?match ?totalHits ?graph): " + argSubject);
            }
        }

        if (argObject.isList()) {
            List<Node> list = argObject.getArgList();
            if (list.isEmpty()) {
                throw new QueryBuildException("Zero-length argument list");
            }
        }
    }

    private static ShaclTextIndexLucene chooseTextIndex(ExecutionContext execCxt, DatasetGraph dsg) {
        // Try registry first
        Object regObj = execCxt.getContext().get(TextQuery.textIndexRegistry);
        if (regObj instanceof TextIndexRegistry registry) {
            TextIndexLucene idx = registry.getDefault();
            if (idx instanceof ShaclTextIndexLucene shaclIdx) {
                return shaclIdx;
            }
            Log.warn(ShaclTextQueryPF.class, "Text index is not a ShaclTextIndexLucene");
            return null;
        }

        // Fall back to single index
        Object obj = execCxt.getContext().get(TextQuery.textIndex);
        if (obj instanceof ShaclTextIndexLucene shaclIdx) {
            return shaclIdx;
        }
        if (obj != null) {
            Log.warn(ShaclTextQueryPF.class, "Context setting '" + TextQuery.textIndex + "' is not a ShaclTextIndexLucene");
        }
        if (dsg instanceof DatasetGraphText) {
            TextIndex ti = ((DatasetGraphText) dsg).getTextIndex();
            if (ti instanceof ShaclTextIndexLucene shaclIdx) {
                return shaclIdx;
            }
            Log.warn(ShaclTextQueryPF.class, "TextIndex is not a ShaclTextIndexLucene");
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

        // Subject shape: (?hit ?entity ?score ?match ?totalHits ?graph)
        Node hit = null, entity = null, score = null, match = null, totalHitsNode = null, graph = null;

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
                match = argSubject.getArg(3);
                if (!match.isVariable())
                    throw new QueryExecException("Match is not a variable: " + argSubject);
            }
            if (argSubject.getArgListSize() > 4) {
                totalHitsNode = argSubject.getArg(4);
                if (!totalHitsNode.isVariable())
                    throw new QueryExecException("Total hits is not a variable: " + argSubject);
            }
            if (argSubject.getArgListSize() > 5) {
                graph = argSubject.getArg(5);
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

        // Resolve text index (always uses default)
        textIndex = chooseTextIndex(execCxt, execCxt.getDataset());
        if (textIndex == null) {
            if (!warningIssued) {
                Log.warn(getClass(), "No text index - no text search performed");
                warningIssued = true;
            }
            return IterLib.result(binding, execCxt);
        }

        // Use SearchExecution for shared state with luc:facet and luc:match
        SearchExecution se = SearchExecution.getOrCreate(
            execCxt, args.searchFields, args.queryString,
            args.cqlFilter, args.sortSpecs, textIndex, null, null);

        int limit = args.limit > 0 ? args.limit : 10000;
        List<SearchHit> allHits = se.getSearchHits(limit);

        Collection<SearchHit> hits;
        if (entity != null && !Var.isVar(entity)) {
            String entityStr = TextQueryFuncs.subjectToString(entity);
            hits = new ArrayList<>();
            for (SearchHit sh : allHits) {
                if (entityStr.equals(TextQueryFuncs.subjectToString(sh.getEntityNode()))) {
                    hits.add(sh);
                }
            }
        } else {
            hits = allHits;
        }

        long totalHits = totalHitsNode != null ? se.getTotalHits() : -1;
        QueryIterator qIter = resultsToQueryIterator(binding, hit, entity, score, match,
            totalHitsNode, totalHits, graph, hits, execCxt);
        if (args.limit >= 0)
            qIter = new QueryIterSlice(qIter, 0, args.limit, execCxt);
        return qIter;
    }

    private QueryIterator resultsToQueryIterator(Binding binding,
                                                  Node hitNode, Node entityNode, Node scoreNode, Node matchNode,
                                                  Node totalHitsNode, long totalHits, Node graphNode,
                                                  Collection<SearchHit> results, ExecutionContext execCxt) {
        Var hitVar = Var.isVar(hitNode) ? Var.alloc(hitNode) : null;
        Var entityVar = (entityNode != null && Var.isVar(entityNode)) ? Var.alloc(entityNode) : null;
        Var scoreVar = (scoreNode == null) ? null : Var.alloc(scoreNode);
        Var matchVar = (matchNode == null) ? null : Var.alloc(matchNode);
        Var totalHitsVar = (totalHitsNode == null) ? null : Var.alloc(totalHitsNode);
        Node totalHitsValue = totalHitsVar != null
            ? NodeFactory.createLiteralDT(String.valueOf(totalHits), XSDDatatype.XSDinteger) : null;
        Var graphVar = (graphNode == null) ? null : Var.alloc(graphNode);

        Function<SearchHit, Binding> converter = (SearchHit sh) -> {
            BindingBuilder bmap = Binding.builder(binding);
            if (hitVar != null) bmap.add(hitVar, sh.getHitId());
            if (entityVar != null) bmap.add(entityVar, sh.getEntityNode());
            if (scoreVar != null) bmap.add(scoreVar, NodeFactoryExtra.floatToNode(sh.getScore()));
            if (matchVar != null) {
                // Bind ?match to the first field match value if available
                List<FieldMatch> fieldMatches = sh.getFieldMatches();
                if (!fieldMatches.isEmpty() && fieldMatches.get(0).getValue() != null) {
                    bmap.add(matchVar, fieldMatches.get(0).getValue());
                }
            }
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
     * Arg order: (fieldSpec queryString cqlFilter? sortSpec? limit? highlight?)
     * <ul>
     *   <li>First literal = field spec ("default", field name, or JSON array of field names)</li>
     *   <li>Next plain literal = query string</li>
     *   <li>JSON with "op" key = CQL filter</li>
     *   <li>JSON with "field" key = sort spec</li>
     *   <li>Integer = limit</li>
     *   <li>"highlight:..." = highlight options</li>
     * </ul>
     */
    private QueryArgs parseArgs(PropFuncArg argObject) {
        List<String> searchFields = new ArrayList<>();
        String queryString = null;
        CqlExpression cqlFilter = null;
        List<SortSpec> sortSpecs = null;
        int limit = -1;
        String highlight = null;

        if (argObject.isNode()) {
            Node o = argObject.getArg();
            if (!o.isLiteral()) {
                log.warn("Object to luc:query is not a literal: " + argObject);
                return null;
            }
            queryString = o.getLiteralLexicalForm();
            searchFields.add("default");
            return new QueryArgs(searchFields, queryString, cqlFilter, sortSpecs, limit, highlight);
        }

        List<Node> list = argObject.getArgList();
        if (list.isEmpty())
            throw new TextIndexException("luc:query object list can not be empty");

        int idx = 0;

        // First literal = field spec: "default" or JSON array of field IRIs
        if (idx < list.size() && list.get(idx).isLiteral()) {
            String lex = list.get(idx).getLiteralLexicalForm();
            if (lex.startsWith("[")) {
                // JSON array of field IRIs
                JsonArray arr = JSON.parseAny(lex).getAsArray();
                for (int i = 0; i < arr.size(); i++) {
                    searchFields.add(arr.get(i).getAsString().value());
                }
                idx++;
            } else if ("default".equals(lex)) {
                searchFields.add("default");
                idx++;
            }
        }

        // Query string (next non-JSON literal)
        if (idx < list.size() && list.get(idx).isLiteral()) {
            String lex = list.get(idx).getLiteralLexicalForm();
            if (!lex.startsWith("{") && !lex.startsWith("[")) {
                queryString = lex;
                idx++;
            }
        }

        if (queryString == null) {
            log.warn("No query string in luc:query arguments: " + list);
            return null;
        }

        if (searchFields.isEmpty()) {
            searchFields.add("default");
        }

        // Remaining args: CQL filter, sort spec, limit, highlight
        while (idx < list.size()) {
            Node n = list.get(idx);
            if (n.isLiteral()) {
                String lex = n.getLiteralLexicalForm();
                if (lex.startsWith("{")) {
                    if (isCqlFilter(lex)) {
                        cqlFilter = CqlParser.parse(lex);
                    } else if (SortSpecParser.isSortSpec(lex)) {
                        sortSpecs = SortSpecParser.parse(lex);
                    }
                } else if (lex.startsWith("[")) {
                    // Array sort spec
                    if (SortSpecParser.isSortSpec(lex)) {
                        sortSpecs = SortSpecParser.parse(lex);
                    }
                } else if (lex.startsWith("highlight:")) {
                    highlight = lex.substring("highlight:".length());
                } else {
                    try {
                        int v = Integer.parseInt(lex);
                        limit = (v < 0) ? -1 : v;
                    } catch (NumberFormatException e) {
                        try {
                            int v = NodeFactoryExtra.nodeToInt(n);
                            limit = (v < 0) ? -1 : v;
                        } catch (Exception ex) {
                            log.warn("Unexpected argument in luc:query: {}", lex);
                        }
                    }
                }
            }
            idx++;
        }

        return new QueryArgs(searchFields, queryString, cqlFilter, sortSpecs, limit, highlight);
    }

    /**
     * Check if a JSON string is a CQL filter (has an "op" key).
     */
    private static boolean isCqlFilter(String json) {
        return json.contains("\"op\"");
    }

    private static boolean isJsonLike(String s) {
        return s.startsWith("{") || s.startsWith("[");
    }

    private static class QueryArgs {
        final List<String> searchFields;
        final String queryString;
        final CqlExpression cqlFilter;
        final List<SortSpec> sortSpecs;
        final int limit;
        final String highlight;

        QueryArgs(List<String> searchFields, String queryString,
                  CqlExpression cqlFilter, List<SortSpec> sortSpecs,
                  int limit, String highlight) {
            this.searchFields = searchFields;
            this.queryString = queryString;
            this.cqlFilter = cqlFilter;
            this.sortSpecs = sortSpecs;
            this.limit = limit;
            this.highlight = highlight;
        }
    }
}
