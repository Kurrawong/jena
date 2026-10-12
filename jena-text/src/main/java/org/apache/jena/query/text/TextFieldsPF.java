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

import java.util.*;

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
 * SPARQL property function returning field values for each hit ({@code luc:fields}).
 * <p>
 * <b>Syntax:</b>
 * <pre>
 * (?hit ?field ?value) luc:fields ()
 * </pre>
 * <p>
 * Behaves like a triple pattern with {@code ?hit} as subject: one row per value. A
 * variable in the field slot ranges over every returnable root field (stored, on the
 * entity's own document); a field IRI selects that field. A constant in the value slot
 * keeps only that value, so {@code ?field} and {@code ?value} shared with
 * {@code luc:match} keep the pairs both produce, whichever is evaluated first.
 * <p>
 * Joins with {@code luc:query} through {@code ?hit}, which names its search. The first
 * call for a field reads it for every hit {@code luc:query} has returned, in one
 * doc-ID-ordered pass against the searcher the search ran on, and caches the values;
 * later calls are lookups. Fields whose doc value holds the value are read from it with
 * no stored-field access.
 * <p>
 * A field IRI that is not configured, a field with {@code idx:stored false} and a field
 * inside an {@code idx:nested} block each raise a query error naming the field. When the
 * dataset has several indexes, a field IRI configured in one of them gives no rows for the
 * hits of the others. They are
 * raised on the first call: the index is known only once {@code luc:query} has run.
 */
public class TextFieldsPF extends PropertyFunctionBase {
    private static final Logger log = LoggerFactory.getLogger(TextFieldsPF.class);

    /** Resolved field slots, per index mapping; one instance serves one query pattern. */
    private final Map<ShaclIndexMapping, Map<Node, List<ShaclIndexMapping.FieldDef>>> resolved =
        new IdentityHashMap<>();

    public TextFieldsPF() {}

    @Override
    public void build(PropFuncArg argSubject, Node predicate, PropFuncArg argObject, ExecutionContext execCxt) {
        super.build(argSubject, predicate, argObject, execCxt);
        if (!argSubject.isList() || argSubject.getArgListSize() != 3) {
            throw new QueryBuildException("luc:fields subject must be (?hit ?field ?value): " + argSubject);
        }
        if (!argObject.isList() || argObject.getArgListSize() != 0) {
            throw new QueryBuildException("luc:fields object must be (): " + argObject);
        }
    }

    @Override
    public QueryIterator exec(Binding binding,
                              PropFuncArg argSubject, Node predicate, PropFuncArg argObject,
                              ExecutionContext execCxt) {
        argSubject = Substitute.substitute(argSubject, binding);
        Node hitNode = argSubject.getArg(0);
        Node fieldNode = argSubject.getArg(1);
        Node valueNode = argSubject.getArg(2);

        Var hitVar = Var.isVar(hitNode) ? Var.alloc(hitNode) : null;
        Var fieldVar = Var.isVar(fieldNode) ? Var.alloc(fieldNode) : null;
        Var valueVar = Var.isVar(valueNode) ? Var.alloc(valueNode) : null;
        Node fieldFilter = fieldVar == null ? fieldNode : null;
        Node valueFilter = valueVar == null ? valueNode : null;

        if (fieldFilter != null && !fieldFilter.isURI()) {
            // Not a field IRI, for example a literal bound by an earlier pattern.
            return IterLib.noResults(execCxt);
        }

        List<Binding> bindings = new ArrayList<>();
        if (hitVar == null) {
            // ?hit bound: it names its search and its rank.
            SearchExecution se = SearchExecution.forHit(execCxt, hitNode);
            SearchHit sh = se != null ? se.findHit(hitNode) : null;
            if (sh == null) {
                if (SearchExecution.all(execCxt).isEmpty()) {
                    log.warn("luc:fields used without a preceding luc:query in the same query");
                }
                return IterLib.noResults(execCxt);
            }
            List<ShaclIndexMapping.FieldDef> fields = fields(execCxt, se, fieldFilter);
            addRows(bindings, binding, null, fieldVar, valueVar, valueFilter, sh, se.fieldValues(sh, fields));
        } else {
            List<SearchExecution> searches = SearchExecution.all(execCxt);
            if (searches.isEmpty()) {
                log.warn("luc:fields used without a preceding luc:query in the same query");
                return IterLib.noResults(execCxt);
            }
            // ?hit unbound: every cached hit of every search in the query.
            for (SearchExecution se : searches) {
                List<ShaclIndexMapping.FieldDef> fields = fields(execCxt, se, fieldFilter);
                for (SearchHit sh : se.hitsWithFieldValues(fields)) {
                    addRows(bindings, binding, hitVar, fieldVar, valueVar, valueFilter, sh,
                        se.fieldValues(sh, fields));
                }
            }
        }
        return QueryIterPlainWrapper.create(bindings.iterator(), execCxt);
    }

    private static void addRows(List<Binding> bindings, Binding binding, Var hitVar, Var fieldVar,
                                Var valueVar, Node valueFilter, SearchHit sh,
                                Map<ShaclIndexMapping.FieldDef, List<Node>> values) {
        for (Map.Entry<ShaclIndexMapping.FieldDef, List<Node>> entry : values.entrySet()) {
            Node fieldIRI = entry.getKey().getFieldIRI();
            for (Node value : entry.getValue()) {
                if (valueFilter != null && !valueFilter.equals(value)) {
                    continue;
                }
                BindingBuilder builder = Binding.builder(binding);
                if (hitVar != null) builder.add(hitVar, sh.getHitId());
                if (fieldVar != null) builder.add(fieldVar, fieldIRI);
                if (valueVar != null) builder.add(valueVar, value);
                bindings.add(builder.build());
            }
        }
    }

    /**
     * The fields a slot names in the search's index: one for an IRI, every returnable root
     * field for a variable. An IRI configured only in another index searched by the same
     * query names no field here, so that index's hits give no rows.
     */
    private List<ShaclIndexMapping.FieldDef> fields(ExecutionContext execCxt, SearchExecution se, Node fieldIRI) {
        ShaclIndexMapping mapping = se.getTextIndex().getShaclMapping();
        Node key = fieldIRI != null ? fieldIRI : Var.ANON;
        return resolved.computeIfAbsent(mapping, m -> new HashMap<>())
            .computeIfAbsent(key, k -> fieldIRI == null ? returnable(mapping)
                : configuredInOtherIndex(execCxt, mapping, fieldIRI) ? List.of()
                : List.of(field(mapping, fieldIRI)));
    }

    /**
     * Whether {@code fieldIRI}, absent from {@code mapping}, is configured in another
     * index the query can search: one in the dataset's index registry, or one a search in
     * this query ran against.
     */
    private static boolean configuredInOtherIndex(ExecutionContext execCxt, ShaclIndexMapping mapping,
                                                  Node fieldIRI) {
        String iri = fieldIRI.getURI();
        if (mapping.findField(iri) != null) {
            return false;
        }
        List<TextIndex> indexes = new ArrayList<>();
        if (execCxt.getContext().get(TextQuery.textIndexRegistry) instanceof TextIndexRegistry registry) {
            indexes.addAll(registry.all());
        }
        for (SearchExecution other : SearchExecution.all(execCxt)) {
            indexes.add(other.getTextIndex());
        }
        for (TextIndex index : indexes) {
            if (index instanceof ShaclTextIndexLucene shacl && shacl.getShaclMapping().findField(iri) != null) {
                return true;
            }
        }
        return false;
    }

    private static List<ShaclIndexMapping.FieldDef> returnable(ShaclIndexMapping mapping) {
        List<ShaclIndexMapping.FieldDef> fields = new ArrayList<>();
        for (ShaclIndexMapping.FieldDef field : mapping.getRootFields()) {
            // A geometry's stored value has no term to return.
            if (field.isStored() && field.getFieldType() != ShaclIndexMapping.FieldType.LATLON) {
                fields.add(field);
            }
        }
        return List.copyOf(fields);
    }

    private static ShaclIndexMapping.FieldDef field(ShaclIndexMapping mapping, Node fieldIRI) {
        String iri = fieldIRI.getURI();
        ShaclIndexMapping.FieldDef field = mapping.findField(iri);
        if (field == null) {
            throw new QueryExecException("luc:fields: <" + iri + "> is not a configured field");
        }
        if (!field.isStored()) {
            throw new QueryExecException("luc:fields: field <" + iri + "> has idx:stored false, "
                + "so its values cannot be returned");
        }
        if (!mapping.getRootFields().contains(field)) {
            throw new QueryExecException("luc:fields: field <" + iri + "> is inside an idx:nested block; "
                + "its values are on child documents. luc:nestedMatch returns them");
        }
        return field;
    }
}
