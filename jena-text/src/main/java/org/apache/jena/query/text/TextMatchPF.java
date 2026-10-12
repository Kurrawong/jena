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
 * {@link SearchExecution} stored in the {@link ExecutionContext}. A bound {@code ?hit}
 * names its search, so it is found with one lookup. A field IRI in the field slot
 * keeps only matches on that field, and a constant in the value slot only that value,
 * as when {@code ?field} and {@code ?value} are shared with an earlier {@code luc:fields}.
 * <p>
 * Match details are computed on the first call, for every hit {@code luc:query} has
 * returned, against the searcher the search ran on.
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
            }
            if (argSubject.getArgListSize() > 2) {
                valueNode = argSubject.getArg(2);
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

        Var hitVar = Var.isVar(hitNode) ? Var.alloc(hitNode) : null;
        Var fieldVar = fieldNode != null && Var.isVar(fieldNode) ? Var.alloc(fieldNode) : null;
        Node fieldFilter = fieldNode != null && fieldVar == null ? fieldNode : null;
        Var valueVar = valueNode != null && Var.isVar(valueNode) ? Var.alloc(valueNode) : null;
        Node valueFilter = valueNode != null && valueVar == null ? valueNode : null;
        Var snippetVar = snippetNode != null ? Var.alloc(snippetNode) : null;

        List<Binding> bindings = new ArrayList<>();

        if (hitVar == null) {
            // ?hit bound: it names its search and its rank.
            SearchExecution se = SearchExecution.forHit(execCxt, hitNode);
            SearchHit sh = se != null ? se.findHit(hitNode) : null;
            if (sh == null) {
                if (SearchExecution.all(execCxt).isEmpty()) {
                    log.warn("luc:match used without a preceding luc:query in the same query");
                }
                return IterLib.noResults(execCxt);
            }
            addRows(bindings, binding, null, fieldVar, fieldFilter, valueVar, valueFilter, snippetVar, sh,
                se.fieldMatches(sh));
        } else {
            List<SearchExecution> searches = SearchExecution.all(execCxt);
            if (searches.isEmpty()) {
                log.warn("luc:match used without a preceding luc:query in the same query");
                return IterLib.noResults(execCxt);
            }
            // ?hit unbound: every hit of every search in the query.
            for (SearchExecution se : searches) {
                for (SearchHit sh : se.hitsWithFieldMatches()) {
                    addRows(bindings, binding, hitVar, fieldVar, fieldFilter, valueVar, valueFilter, snippetVar,
                        sh, sh.getFieldMatches());
                }
            }
        }

        return QueryIterPlainWrapper.create(bindings.iterator(), execCxt);
    }

    private static void addRows(List<Binding> bindings, Binding binding, Var hitVar, Var fieldVar,
                                Node fieldFilter, Var valueVar, Node valueFilter, Var snippetVar,
                                SearchHit sh, List<FieldMatch> fieldMatches) {
        if (fieldMatches.isEmpty()) {
            // No field matches: a row with only ?hit, so a join on ?hit still succeeds.
            // Not when a field or value is named: there is nothing on it to return.
            if (hitVar != null && fieldFilter == null && valueFilter == null) {
                BindingBuilder builder = Binding.builder(binding);
                builder.add(hitVar, sh.getHitId());
                bindings.add(builder.build());
            }
            return;
        }
        for (FieldMatch fm : fieldMatches) {
            if (fieldFilter != null && !fieldFilter.equals(fm.getFieldIRI())) {
                continue;
            }
            if (valueFilter != null && !valueFilter.equals(fm.getValue())) {
                continue;
            }
            BindingBuilder builder = Binding.builder(binding);
            if (hitVar != null) builder.add(hitVar, sh.getHitId());
            if (fieldVar != null && fm.getFieldIRI() != null) builder.add(fieldVar, fm.getFieldIRI());
            if (valueVar != null && fm.getValue() != null) builder.add(valueVar, fm.getValue());
            if (snippetVar != null && fm.getSnippet() != null) builder.add(snippetVar, fm.getSnippet());
            bindings.add(builder.build());
        }
    }
}
