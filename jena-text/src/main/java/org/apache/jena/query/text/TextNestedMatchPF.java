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
 * SPARQL property function projecting the nested child records that satisfied the
 * filter ({@code luc:nestedMatch}).
 * <p>
 * Joins with {@code luc:query} via the shared {@code ?hit} identifier, exactly as
 * {@code luc:match} does. Where {@code luc:match} answers "which fields of the text
 * query matched on the entity", this answers "which {@code idx:nested} child documents
 * satisfied the CQL filter, and what do they contain".
 * <p>
 * <b>Syntax:</b>
 * <pre>
 * (?hit ?record ?field ?value) luc:nestedMatch ()
 * </pre>
 * <p>
 * One row per (child record, field, value). {@code ?record} is a query-scoped blank node
 * shared by every row of one child, so a consumer recovers a whole record with
 * {@code GROUP BY ?record}. That grouping is the reason this is not a variant of
 * {@code luc:match}: when two children of the same entity match, a flat stream of
 * (field, value) rows cannot say which value belongs with which key.
 * <p>
 * The object argument list is empty — all state comes from the shared
 * {@link SearchExecution} stored in the {@link ExecutionContext}. A bound {@code ?hit}
 * names its search, so it is found with one lookup. A field IRI in the field slot keeps
 * only that field's values.
 * <p>
 * The child records are found on the first call, for every hit {@code luc:query} has
 * returned, against the searcher the search ran on.
 */
public class TextNestedMatchPF extends PropertyFunctionBase {
    private static final Logger log = LoggerFactory.getLogger(TextNestedMatchPF.class);

    public TextNestedMatchPF() {}

    @Override
    public void build(PropFuncArg argSubject, Node predicate, PropFuncArg argObject, ExecutionContext execCxt) {
        super.build(argSubject, predicate, argObject, execCxt);

        if (argSubject.isList()) {
            int size = argSubject.getArgListSize();
            if (size < 1 || size > 4) {
                throw new QueryBuildException("Subject must have 1-4 elements " +
                    "(?hit ?record ?field ?value): " + argSubject);
            }
        }
    }

    @Override
    public QueryIterator exec(Binding binding,
                              PropFuncArg argSubject, Node predicate, PropFuncArg argObject,
                              ExecutionContext execCxt) {

        argSubject = Substitute.substitute(argSubject, binding);

        Node hitNode = null, recordNode = null, fieldNode = null, valueNode = null;

        if (argSubject.isList()) {
            hitNode = argSubject.getArg(0);
            if (argSubject.getArgListSize() > 1) {
                recordNode = requireVariable(argSubject.getArg(1), "Record", argSubject);
            }
            if (argSubject.getArgListSize() > 2) {
                fieldNode = argSubject.getArg(2);
            }
            if (argSubject.getArgListSize() > 3) {
                valueNode = requireVariable(argSubject.getArg(3), "Value", argSubject);
            }
        } else {
            hitNode = argSubject.getArg();
        }

        if (hitNode == null) {
            return IterLib.noResults(execCxt);
        }

        Var hitVar = Var.isVar(hitNode) ? Var.alloc(hitNode) : null;
        Var recordVar = recordNode != null ? Var.alloc(recordNode) : null;
        Var fieldVar = fieldNode != null && Var.isVar(fieldNode) ? Var.alloc(fieldNode) : null;
        Node fieldFilter = fieldNode != null && fieldVar == null ? fieldNode : null;
        Var valueVar = valueNode != null ? Var.alloc(valueNode) : null;

        List<Binding> bindings = new ArrayList<>();

        if (hitVar == null) {
            // ?hit bound: it names its search and its rank.
            SearchExecution se = SearchExecution.forHit(execCxt, hitNode);
            SearchHit sh = se != null ? se.findHit(hitNode) : null;
            if (sh == null) {
                if (SearchExecution.all(execCxt).isEmpty()) {
                    log.warn("luc:nestedMatch used without a preceding luc:query in the same query");
                }
                return IterLib.noResults(execCxt);
            }
            addRows(bindings, binding, null, recordVar, fieldVar, fieldFilter, valueVar, sh, se.nestedMatches(sh));
        } else {
            List<SearchExecution> searches = SearchExecution.all(execCxt);
            if (searches.isEmpty()) {
                log.warn("luc:nestedMatch used without a preceding luc:query in the same query");
                return IterLib.noResults(execCxt);
            }
            for (SearchExecution se : searches) {
                for (SearchHit sh : se.hitsWithNestedMatches()) {
                    addRows(bindings, binding, hitVar, recordVar, fieldVar, fieldFilter, valueVar,
                        sh, sh.getNestedMatches());
                }
            }
        }

        return QueryIterPlainWrapper.create(bindings.iterator(), execCxt);
    }

    private static void addRows(List<Binding> bindings, Binding binding, Var hitVar, Var recordVar,
                                Var fieldVar, Node fieldFilter, Var valueVar,
                                SearchHit sh, List<NestedMatch> records) {
        for (NestedMatch record : records) {
            for (FieldMatch fm : record.getFieldMatches()) {
                if (fieldFilter != null && !fieldFilter.equals(fm.getFieldIRI())) {
                    continue;
                }
                BindingBuilder builder = Binding.builder(binding);
                if (hitVar != null) builder.add(hitVar, sh.getHitId());
                if (recordVar != null) builder.add(recordVar, record.getRecordId());
                if (fieldVar != null && fm.getFieldIRI() != null) builder.add(fieldVar, fm.getFieldIRI());
                if (valueVar != null && fm.getValue() != null) builder.add(valueVar, fm.getValue());
                bindings.add(builder.build());
            }
        }
    }

    private static Node requireVariable(Node node, String label, PropFuncArg argSubject) {
        if (!node.isVariable()) {
            throw new QueryExecException(label + " must be a variable: " + argSubject);
        }
        return node;
    }
}
