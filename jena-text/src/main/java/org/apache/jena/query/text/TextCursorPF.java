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
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.query.QueryBuildException;
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
 * SPARQL property function returning a hit's cursor ({@code luc:cursor}).
 * <p>
 * <b>Syntax:</b>
 * <pre>
 * ?hit luc:cursor ?cursor
 * </pre>
 * <p>
 * Joins with {@code luc:query} through {@code ?hit}, as {@code luc:match} does. The cursor
 * is a string literal; passed back as {@code luc:query}'s eighth argument it continues
 * with the hits after this one. Only a {@code luc:query} paged by cursor, given {@code "*"}
 * or a cursor as its eighth argument, has cursors: asking for one from any other search is
 * a query error, because its order is not total and could not be continued without
 * skipping or repeating hits.
 * <p>
 * A cursor is built from the sort values the search already holds, so it reads nothing
 * from the index. With {@code ?hit} unbound it gives every cached hit of every search paged
 * by cursor in the query.
 */
public class TextCursorPF extends PropertyFunctionBase {
    private static final Logger log = LoggerFactory.getLogger(TextCursorPF.class);

    public TextCursorPF() {}

    @Override
    public void build(PropFuncArg argSubject, Node predicate, PropFuncArg argObject, ExecutionContext execCxt) {
        super.build(argSubject, predicate, argObject, execCxt);
        if (argSubject.isList() || argObject.isList()) {
            throw new QueryBuildException("luc:cursor takes one subject and one object: ?hit luc:cursor ?cursor");
        }
    }

    @Override
    public QueryIterator exec(Binding binding,
                              PropFuncArg argSubject, Node predicate, PropFuncArg argObject,
                              ExecutionContext execCxt) {
        Node hitNode = Substitute.substitute(argSubject, binding).getArg();
        Node cursorNode = Substitute.substitute(argObject, binding).getArg();
        Var hitVar = Var.isVar(hitNode) ? Var.alloc(hitNode) : null;
        Var cursorVar = Var.isVar(cursorNode) ? Var.alloc(cursorNode) : null;

        List<Binding> bindings = new ArrayList<>();
        if (hitVar == null) {
            // ?hit bound: it names its search and its index.
            SearchExecution se = SearchExecution.forHit(execCxt, hitNode);
            SearchHit sh = se != null ? se.findHit(hitNode) : null;
            if (sh == null) {
                if (SearchExecution.all(execCxt).isEmpty()) {
                    log.warn("luc:cursor used without a preceding luc:query in the same query");
                }
                return IterLib.noResults(execCxt);
            }
            addRow(bindings, binding, null, cursorVar, cursorNode, sh, se.cursor(sh));
        } else {
            List<SearchExecution> searches = SearchExecution.all(execCxt);
            if (searches.isEmpty()) {
                log.warn("luc:cursor used without a preceding luc:query in the same query");
                return IterLib.noResults(execCxt);
            }
            // ?hit unbound: every cached hit of every search paged by cursor.
            List<SearchExecution> paged = searches.stream().filter(SearchExecution::isPagedByCursor).toList();
            if (paged.isEmpty()) {
                throw SearchExecution.notPagedByCursor();
            }
            for (SearchExecution se : paged) {
                for (SearchHit sh : se.getCachedHits()) {
                    addRow(bindings, binding, hitVar, cursorVar, cursorNode, sh, se.cursor(sh));
                }
            }
        }
        return QueryIterPlainWrapper.create(bindings.iterator(), execCxt);
    }

    /** One row binding the cursor, or keeping the binding when it equals a given cursor. */
    private static void addRow(List<Binding> bindings, Binding binding, Var hitVar, Var cursorVar,
                               Node cursorNode, SearchHit sh, String cursor) {
        Node value = NodeFactory.createLiteralString(cursor);
        if (cursorVar == null && !value.equals(cursorNode)) {
            return;
        }
        BindingBuilder builder = Binding.builder(binding);
        if (hitVar != null) builder.add(hitVar, sh.getHitId());
        if (cursorVar != null) builder.add(cursorVar, value);
        bindings.add(builder.build());
    }
}
