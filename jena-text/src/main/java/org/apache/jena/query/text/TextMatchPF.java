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
import java.util.List;

import org.apache.jena.graph.Node;
import org.apache.jena.query.QueryBuildException;
import org.apache.jena.query.QueryExecException;
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
import org.apache.jena.sparql.util.Symbol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SPARQL property function for per-hit field match details ({@code luc:match}).
 * <p>
 * Joins with {@code luc:query} via a shared {@code ?hit} blank node identifier.
 * Returns one row per matched field for each hit.
 * <p>
 * <b>Syntax:</b>
 * <pre>
 * (?hit ?field ?value ?snippet) luc:match ()
 * </pre>
 * <p>
 * The object argument list is empty — all state comes from the shared
 * {@link SearchExecution} stored in the {@link ExecutionContext}.
 */
public class TextMatchPF extends PropertyFunctionBase {
    private static final Logger log = LoggerFactory.getLogger(TextMatchPF.class);

    public TextMatchPF() {}

    @Override
    public void build(PropFuncArg argSubject, Node predicate, PropFuncArg argObject, ExecutionContext execCxt) {
        super.build(argSubject, predicate, argObject, execCxt);

        if (argSubject.isList()) {
            int size = argSubject.getArgListSize();
            if (size < 1 || size > 4) {
                throw new QueryBuildException("Subject must have 1-4 elements " +
                    "(?hit ?field ?value ?snippet): " + argSubject);
            }
        }
    }

    @Override
    public QueryIterator exec(Binding binding,
                              PropFuncArg argSubject, Node predicate, PropFuncArg argObject,
                              ExecutionContext execCxt) {

        argSubject = Substitute.substitute(argSubject, binding);

        // Parse subject: (?hit ?field ?value ?snippet)
        Node hitNode = null, fieldNode = null, valueNode = null, snippetNode = null;

        if (argSubject.isList()) {
            hitNode = argSubject.getArg(0);
            if (argSubject.getArgListSize() > 1) {
                fieldNode = argSubject.getArg(1);
                if (!fieldNode.isVariable())
                    throw new QueryExecException("Field must be a variable: " + argSubject);
            }
            if (argSubject.getArgListSize() > 2) {
                valueNode = argSubject.getArg(2);
                if (!valueNode.isVariable())
                    throw new QueryExecException("Value must be a variable: " + argSubject);
            }
            if (argSubject.getArgListSize() > 3) {
                snippetNode = argSubject.getArg(3);
                if (!snippetNode.isVariable())
                    throw new QueryExecException("Snippet must be a variable: " + argSubject);
            }
        } else {
            hitNode = argSubject.getArg();
        }

        if (hitNode == null) {
            return IterLib.noResults(execCxt);
        }

        // Find the SearchExecution in the execution context
        // We scan context symbols for any searchExecution entry
        SearchExecution se = findSearchExecution(execCxt);
        if (se == null) {
            log.warn("luc:match used without a preceding luc:query in the same query");
            return IterLib.noResults(execCxt);
        }

        // Get search hits (already computed and cached by luc:query)
        List<SearchHit> allHits = se.getSearchHits(0, 10000);

        // Build result bindings
        Var hitVar = Var.isVar(hitNode) ? Var.alloc(hitNode) : null;
        Var fieldVar = fieldNode != null ? Var.alloc(fieldNode) : null;
        Var valueVar = valueNode != null ? Var.alloc(valueNode) : null;
        Var snippetVar = snippetNode != null ? Var.alloc(snippetNode) : null;

        List<Binding> bindings = new ArrayList<>();

        for (SearchHit sh : allHits) {
            // If ?hit is already bound, filter to matching hits
            if (hitVar == null) {
                // ?hit is bound — check if it matches this hit's ID
                if (!hitNode.equals(sh.getHitId())) continue;
            }

            List<FieldMatch> fieldMatches = sh.getFieldMatches();
            if (fieldMatches.isEmpty()) {
                // No field matches — emit a row with unbound field/value/snippet
                // only if ?hit is a variable (to ensure join works)
                if (hitVar != null) {
                    BindingBuilder builder = Binding.builder(binding);
                    builder.add(hitVar, sh.getHitId());
                    bindings.add(builder.build());
                }
            } else {
                for (FieldMatch fm : fieldMatches) {
                    BindingBuilder builder = Binding.builder(binding);
                    if (hitVar != null) builder.add(hitVar, sh.getHitId());
                    if (fieldVar != null && fm.getFieldIRI() != null) builder.add(fieldVar, fm.getFieldIRI());
                    if (valueVar != null && fm.getValue() != null) builder.add(valueVar, fm.getValue());
                    if (snippetVar != null && fm.getSnippet() != null) builder.add(snippetVar, fm.getSnippet());
                    bindings.add(builder.build());
                }
            }
        }

        return QueryIterPlainWrapper.create(bindings.iterator(), execCxt);
    }

    /**
     * Find a SearchExecution in the execution context.
     * Scans for any symbol matching the searchExecution pattern.
     */
    private SearchExecution findSearchExecution(ExecutionContext execCxt) {
        // Look for any SearchExecution stored by luc:query or luc:facet
        String prefix = TextQuery.NS + "searchExecution/";
        // The context doesn't have a list-all-keys method, so we check
        // a well-known symbol pattern. SearchExecution stores under
        // "urn:jena:lucene:index#searchExecution/<key>"
        // We need to find it. Use the context's internal map.
        org.apache.jena.sparql.util.Context ctx = execCxt.getContext();
        for (Symbol sym : ctx.keys()) {
            if (sym.getSymbol().startsWith(prefix)) {
                Object obj = ctx.get(sym);
                if (obj instanceof SearchExecution) {
                    return (SearchExecution) obj;
                }
            }
        }
        return null;
    }
}
