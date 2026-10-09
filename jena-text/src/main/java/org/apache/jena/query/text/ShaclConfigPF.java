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

import org.apache.jena.graph.Graph;
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

/**
 * SPARQL property function returning a SHACL index's configuration ({@code luc:config}).
 * <p>
 * <b>Syntax:</b>
 * <pre>
 * (?s ?p ?o) luc:config (indexSelector)
 * </pre>
 * <p>
 * Each subject slot is a variable or a constant, and the call matches like one triple
 * pattern over the configuration the index was assembled from. The index must set
 * {@code text:exposeConfig true}; otherwise the call is a query error.
 */
public class ShaclConfigPF extends PropertyFunctionBase {

    public ShaclConfigPF() {}

    @Override
    public void build(PropFuncArg argSubject, Node predicate, PropFuncArg argObject, ExecutionContext execCxt) {
        super.build(argSubject, predicate, argObject, execCxt);
        if (!argSubject.isList() || argSubject.getArgListSize() != 3) {
            throw new QueryBuildException("luc:config subject must be a 3-element list: (s p o)");
        }
        if (!argObject.isList() || argObject.getArgListSize() != 1) {
            throw new QueryBuildException("luc:config expects exactly 1 object argument: (indexSelector)");
        }
    }

    @Override
    public QueryIterator exec(Binding binding,
                              PropFuncArg argSubject, Node predicate, PropFuncArg argObject,
                              ExecutionContext execCxt) {
        argSubject = Substitute.substitute(argSubject, binding);
        argObject = Substitute.substitute(argObject, binding);

        Node selectorNode = argObject.getArg(0);
        if (!selectorNode.isLiteral()) {
            throw new QueryExecException("luc:config: the index selector must be a string literal, got " + selectorNode);
        }
        String selector = selectorNode.getLiteralLexicalForm();

        ShaclIndexSelection.Resolved resolved;
        try {
            resolved = ShaclIndexSelection.resolve(execCxt, execCxt.getDataset(), selector);
        } catch (TextIndexException e) {
            throw new QueryExecException("luc:config: " + e.getMessage(), e);
        }
        if (resolved == null) {
            throw new QueryExecException("luc:config: the dataset has no text index");
        }
        Graph config = resolved.index().getExposedConfig();
        if (config == null) {
            throw new QueryExecException("luc:config is not enabled on text index \"" + selector
                + "\". Set text:exposeConfig true on the index to allow it.");
        }

        Node s = argSubject.getArg(0);
        Node p = argSubject.getArg(1);
        Node o = argSubject.getArg(2);
        List<Binding> rows = new ArrayList<>();
        config.find(pattern(s), pattern(p), pattern(o)).forEach(t -> {
            BindingBuilder row = Binding.builder(binding);
            if (bind(row, s, t.getSubject()) && bind(row, p, t.getPredicate()) && bind(row, o, t.getObject())) {
                rows.add(row.build());
            }
        });
        return QueryIterPlainWrapper.create(rows.iterator(), execCxt);
    }

    private static Node pattern(Node slot) {
        return Var.isVar(slot) ? Node.ANY : slot;
    }

    /** False when a variable already bound in this row, by an earlier slot, disagrees. */
    private static boolean bind(BindingBuilder row, Node slot, Node value) {
        if (!Var.isVar(slot)) {
            return true;
        }
        Var var = Var.alloc(slot);
        if (row.contains(var)) {
            return row.get(var).equals(value);
        }
        row.add(var, value);
        return true;
    }
}
