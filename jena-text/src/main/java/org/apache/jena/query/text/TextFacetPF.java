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
import java.util.Map;

import org.apache.jena.atlas.json.JSON;
import org.apache.jena.atlas.json.JsonArray;
import org.apache.jena.atlas.json.JsonObject;
import org.apache.jena.atlas.json.JsonValue;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SPARQL property function for facet counts ({@code luc:facet}).
 * <p>
 * <b>Syntax:</b>
 * <pre>
 * (?field ?value ?low ?high ?count) luc:facet (...)
 * </pre>
 * <p>
 * The first string literal is the field specification for text query scoping
 * (same as luc:query). CQL filters are JSON objects with an {@code "op"} key.
 */
public class TextFacetPF extends PropertyFunctionBase {
    private static final Logger log = LoggerFactory.getLogger(TextFacetPF.class);

    private ShaclTextIndexLucene textIndex = null;
    private boolean warningIssued = false;

    public TextFacetPF() {}

    @Override
    public void build(PropFuncArg argSubject, Node predicate, PropFuncArg argObject, ExecutionContext execCxt) {
        super.build(argSubject, predicate, argObject, execCxt);

        if (argSubject.isList()) {
            int size = argSubject.getArgListSize();
            if (size != 5) {
                throw new QueryBuildException("Subject must have exactly 5 variables: (field value low high count)");
            }
        } else {
            throw new QueryBuildException("Subject must be a 5-element variable list: (field value low high count)");
        }

        if (argObject.isList()) {
            List<Node> list = argObject.getArgList();
            if (list.isEmpty()) {
                throw new QueryBuildException("Object list must contain at least a query string and facet fields");
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
            Log.warn(TextFacetPF.class, "Text index is not a ShaclTextIndexLucene - faceting not supported");
            return null;
        }

        // Fall back to single index
        Object obj = execCxt.getContext().get(TextQuery.textIndex);
        if (obj instanceof ShaclTextIndexLucene shaclIdx) {
            return shaclIdx;
        }
        if (obj != null) {
            Log.warn(TextFacetPF.class, "Context setting '" + TextQuery.textIndex + "' is not a ShaclTextIndexLucene");
        }
        if (dsg instanceof DatasetGraphText) {
            TextIndex ti = ((DatasetGraphText) dsg).getTextIndex();
            if (ti instanceof ShaclTextIndexLucene shaclIdx) {
                return shaclIdx;
            }
            Log.warn(TextFacetPF.class, "TextIndex is not a ShaclTextIndexLucene - faceting not supported");
        }
        Log.warn(TextFacetPF.class, "Failed to find the text index");
        return null;
    }

    @Override
    public QueryIterator exec(Binding binding,
                              PropFuncArg argSubject, Node predicate, PropFuncArg argObject,
                              ExecutionContext execCxt) {

        argSubject = Substitute.substitute(argSubject, binding);
        argObject = Substitute.substitute(argObject, binding);

        // Parse subject variables: (?field ?value ?low ?high ?count)
        SubjectVars subjectVars = parseSubjectVars(argSubject);

        // Parse object arguments
        FacetArgs args = parseObjectArgs(argObject);
        if (args == null || args.facetRequest.isEmpty()) {
            return IterLib.noResults(execCxt);
        }

        // Resolve text index (always uses default)
        textIndex = chooseTextIndex(execCxt, execCxt.getDataset());
        if (textIndex == null) {
            if (!warningIssued) {
                Log.warn(getClass(), "No text index - no facet counts available");
                warningIssued = true;
            }
            return IterLib.noResults(execCxt);
        }

        if (!textIndex.isFacetingEnabled()) {
            Log.warn(getClass(), "Faceting is not enabled on this text index. Configure facet fields in the index definition.");
            return IterLib.noResults(execCxt);
        }

        FacetArgs validatedArgs = validateFacetArgs(args);

        // Get facet counts via SearchExecution for shared state
        Map<String, List<FacetValue>> facetCounts;
        try {
            log.debug("TextFacetPF: searchFields={} cqlFilter={} queryString='{}' facetFields={}",
                validatedArgs.searchFields, validatedArgs.cqlFilter, validatedArgs.queryString, validatedArgs.facetRequest);

            SearchExecution se = SearchExecution.getOrCreate(
                execCxt, validatedArgs.searchFields, validatedArgs.queryString,
                validatedArgs.cqlFilter, null, textIndex, null, null);
            facetCounts = se.getFacetCounts(validatedArgs.facetRequest, validatedArgs.maxValues, validatedArgs.minCount);
        } catch (Exception e) {
            log.error("Error getting facet counts: {}", e.getMessage());
            return IterLib.noResults(execCxt);
        }

        return generateBindings(binding, subjectVars, facetCounts, execCxt);
    }

    private QueryIterator generateBindings(Binding binding, SubjectVars subjectVars,
            Map<String, List<FacetValue>> facetCounts, ExecutionContext execCxt) {

        Var fieldVar = Var.isVar(subjectVars.fieldNode) ? Var.alloc(subjectVars.fieldNode) : null;
        Var valueVar = subjectVars.valueNode != null ? Var.alloc(subjectVars.valueNode) : null;
        Var lowVar = subjectVars.lowNode != null ? Var.alloc(subjectVars.lowNode) : null;
        Var highVar = subjectVars.highNode != null ? Var.alloc(subjectVars.highNode) : null;
        Var countVar = subjectVars.countNode != null ? Var.alloc(subjectVars.countNode) : null;

        ShaclIndexMapping mapping = textIndex.getShaclMapping();
        List<Binding> bindings = new ArrayList<>();

        for (Map.Entry<String, List<FacetValue>> entry : facetCounts.entrySet()) {
            String field = entry.getKey();
            ShaclIndexMapping.FieldDef fd = mapping.findFieldByName(field);
            for (FacetValue fv : entry.getValue()) {
                BindingBuilder builder = Binding.builder(binding);
                if (fieldVar != null) {
                    builder.add(fieldVar, fd != null ? fd.getFieldIRI()
                        : NodeFactory.createLiteralString(field));
                }
                if (valueVar != null && fv.getKind() == FacetValue.Kind.VALUE) {
                    builder.add(valueVar, facetValueNode(fd, fv.getValue()));
                }
                if (lowVar != null && fv.getKind() == FacetValue.Kind.RANGE && fv.getLow() != null) {
                    builder.add(lowVar, facetValueNode(fd, fv.getLow()));
                }
                if (highVar != null && fv.getKind() == FacetValue.Kind.RANGE && fv.getHigh() != null) {
                    builder.add(highVar, facetValueNode(fd, fv.getHigh()));
                }
                if (countVar != null) {
                    builder.add(countVar, NodeFactory.createLiteralDT(
                        String.valueOf(fv.getCount()), XSDDatatype.XSDlong));
                }
                bindings.add(builder.build());
            }
        }

        return QueryIterPlainWrapper.create(bindings.iterator(), execCxt);
    }

    private static Node facetValueNode(ShaclIndexMapping.FieldDef fd, String value) {
        if (fd == null) return NodeFactory.createLiteralString(value);
        return switch (fd.getFieldType()) {
            case KEYWORD -> looksLikeUri(value)
                ? NodeFactory.createURI(value)
                : NodeFactory.createLiteralString(value);
            case TEXT    -> NodeFactory.createLiteralString(value);
            case INT     -> NodeFactory.createLiteralDT(value, XSDDatatype.XSDinteger);
            case LONG    -> NodeFactory.createLiteralDT(value, XSDDatatype.XSDlong);
            case DOUBLE  -> NodeFactory.createLiteralDT(value, XSDDatatype.XSDdouble);
            case LATLON  -> NodeFactory.createLiteralString(value);
        };
    }

    private static boolean looksLikeUri(String value) {
        return value.contains("://") || value.startsWith("urn:");
    }

    /**
     * Parse the object argument list.
     * <p>
     * Arg order: (fieldSpec queryString facetFields cqlFilter? maxValues? minCount?)
     */
    private FacetArgs parseObjectArgs(PropFuncArg argObject) {
        List<String> searchFields = new ArrayList<>();
        String queryString = null;
        List<String> facetFields = new ArrayList<>();
        List<FacetRequest.RangeFacetSpec> rangeFields = new ArrayList<>();
        CqlExpression cqlFilter = null;
        int maxValues = 10;
        int minCount = 0;
        boolean maxValuesSet = false;

        if (argObject.isNode()) {
            log.warn("luc:facet requires at least a query string and facet fields");
            return null;
        }

        List<Node> list = argObject.getArgList();
        int idx = 0;

        // 1. First literal = field spec: "default" or JSON array of field IRIs
        if (idx < list.size() && list.get(idx).isLiteral()) {
            String lex = list.get(idx).getLiteralLexicalForm();
            if ("default".equals(lex)) {
                searchFields.add("default");
                idx++;
            }
        }

        // 2. Query string (first non-JSON, non-integer literal)
        if (idx < list.size() && list.get(idx).isLiteral()) {
            String lex = list.get(idx).getLiteralLexicalForm();
            if (!lex.startsWith("[") && !lex.startsWith("{") && !isInteger(lex)) {
                queryString = lex;
                idx++;
            }
        }

        if (searchFields.isEmpty()) {
            searchFields.add("default");
        }

        // 3. Parse remaining: JSON arrays (facet fields), JSON objects (CQL), and integers
        while (idx < list.size()) {
            Node n = list.get(idx);
            if (n.isLiteral()) {
                String lex = n.getLiteralLexicalForm();
                if (lex.startsWith("[")) {
                    parseFacetRequestArray(lex, facetFields, rangeFields);
                } else if (lex.startsWith("{")) {
                    // JSON object: CQL filter (has "op" key)
                    if (lex.contains("\"op\"")) {
                        cqlFilter = CqlParser.parse(lex);
                    }
                } else if (isInteger(lex)) {
                    if (!maxValuesSet) {
                        maxValues = Integer.parseInt(lex);
                        maxValuesSet = true;
                    } else {
                        minCount = Integer.parseInt(lex);
                    }
                } else {
                    if (queryString == null) {
                        queryString = lex;
                    } else {
                        log.warn("Unexpected argument in luc:facet: {}", lex);
                    }
                }
            }
            idx++;
        }

        return new FacetArgs(searchFields, queryString, new FacetRequest(facetFields, rangeFields), cqlFilter, maxValues, minCount);
    }

    private void parseFacetRequestArray(String json, List<String> facetFields, List<FacetRequest.RangeFacetSpec> rangeFields) {
        JsonArray arr = JSON.parseAny(json).getAsArray();
        for (int i = 0; i < arr.size(); i++) {
            JsonValue item = arr.get(i);
            if (item.isString()) {
                facetFields.add(item.getAsString().value());
                continue;
            }
            if (!item.isObject()) {
                throw new QueryExecException("Facet request entries must be strings or range objects");
            }
            JsonObject obj = item.getAsObject();
            if (!obj.hasKey("field") || !obj.hasKey("ranges")) {
                throw new QueryExecException("Range facet objects must contain 'field' and 'ranges'");
            }
            String field = obj.get("field").getAsString().value();
            JsonArray ranges = obj.get("ranges").getAsArray();
            List<String> boundaries = new ArrayList<>(ranges.size());
            for (int j = 0; j < ranges.size(); j++) {
                JsonValue rangeValue = ranges.get(j);
                if (rangeValue.isNull()) {
                    boundaries.add(null);
                } else if (rangeValue.isNumber()) {
                    boundaries.add(rangeValue.getAsNumber().value().toString());
                } else {
                    throw new QueryExecException("Range boundaries must be numeric or null");
                }
            }
            rangeFields.add(new FacetRequest.RangeFacetSpec(field, boundaries));
        }
    }

    private FacetArgs validateFacetArgs(FacetArgs args) {
        FacetRequest request = args.facetRequest;
        List<String> validatedFlatFields = new ArrayList<>();
        for (String field : request.getFlatFields()) {
            if ("*".equals(field)) {
                validatedFlatFields.add(field);
                continue;
            }
            ShaclIndexMapping.FieldDef fd = textIndex.getShaclMapping().findField(field);
            if (fd != null && isNumericField(fd)) {
                throw new QueryExecException("Numeric facet field <" + field + "> requires a range object");
            }
            validatedFlatFields.add(field);
        }

        List<FacetRequest.RangeFacetSpec> validatedRanges = new ArrayList<>();
        for (FacetRequest.RangeFacetSpec spec : request.getRangeFields()) {
            ShaclIndexMapping.FieldDef fd = textIndex.getShaclMapping().findField(spec.fieldIri());
            if (fd == null) {
                throw new QueryExecException("Unknown range facet field <" + spec.fieldIri() + ">");
            }
            if (!isNumericField(fd)) {
                throw new QueryExecException("Range object field <" + spec.fieldIri() + "> is not numeric; use a string facet target instead");
            }
            if (!fd.isFacetable()) {
                throw new QueryExecException("Range facet field <" + spec.fieldIri() + "> is not facetable");
            }
            validateBoundaries(fd, spec);
            validatedRanges.add(spec);
        }

        return new FacetArgs(args.searchFields, args.queryString, new FacetRequest(validatedFlatFields, validatedRanges),
            args.cqlFilter, args.maxValues, args.minCount);
    }

    private void validateBoundaries(ShaclIndexMapping.FieldDef fd, FacetRequest.RangeFacetSpec spec) {
        List<String> boundaries = spec.boundaries();
        if (boundaries.size() < 2) {
            throw new QueryExecException("Range facet field <" + spec.fieldIri() + "> must have at least two boundaries");
        }
        for (int i = 0; i < boundaries.size(); i++) {
            String boundary = boundaries.get(i);
            if (boundary == null && i != 0 && i != boundaries.size() - 1) {
                throw new QueryExecException("Null range boundaries are allowed only at the start or end");
            }
            if (boundary == null) {
                continue;
            }
            switch (fd.getFieldType()) {
                case INT -> Integer.parseInt(boundary);
                case LONG -> Long.parseLong(boundary);
                case DOUBLE -> {
                    double v = Double.parseDouble(boundary);
                    if (Double.isNaN(v) || Double.isInfinite(v)) {
                        throw new QueryExecException("DOUBLE range boundaries must be finite");
                    }
                }
                default -> throw new QueryExecException("Range object field <" + spec.fieldIri() + "> is not numeric; use a string facet target instead");
            }
            if (i > 0 && boundaries.get(i - 1) != null) {
                if (compareNumericLexical(fd, boundaries.get(i - 1), boundary) >= 0) {
                    throw new QueryExecException("Range boundaries for <" + spec.fieldIri() + "> must be strictly increasing");
                }
            }
        }
    }

    private int compareNumericLexical(ShaclIndexMapping.FieldDef fd, String left, String right) {
        return switch (fd.getFieldType()) {
            case INT -> Integer.compare(Integer.parseInt(left), Integer.parseInt(right));
            case LONG -> Long.compare(Long.parseLong(left), Long.parseLong(right));
            case DOUBLE -> Double.compare(Double.parseDouble(left), Double.parseDouble(right));
            default -> throw new IllegalArgumentException("Field is not numeric: " + fd.getFieldName());
        };
    }

    private static boolean isNumericField(ShaclIndexMapping.FieldDef fd) {
        return switch (fd.getFieldType()) {
            case INT, LONG, DOUBLE -> true;
            default -> false;
        };
    }

    private SubjectVars parseSubjectVars(PropFuncArg argSubject) {
        List<Node> subjList = argSubject.getArgList();
        if (subjList.size() != 5) {
            throw new QueryExecException("Subject must have exactly 5 variables: (field value low high count)");
        }
        Node fieldNode = requireVariable(subjList.get(0), "Field", argSubject);
        return new SubjectVars(
            fieldNode,
            requireVariable(subjList.get(1), "Value", argSubject),
            requireVariable(subjList.get(2), "Low", argSubject),
            requireVariable(subjList.get(3), "High", argSubject),
            requireVariable(subjList.get(4), "Count", argSubject));
    }

    private Node requireVariable(Node node, String label, PropFuncArg argSubject) {
        if (!node.isVariable()) {
            throw new QueryExecException(label + " must be a variable: " + argSubject);
        }
        return node;
    }

    private static boolean isInteger(String s) {
        try {
            Integer.parseInt(s);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static class FacetArgs {
        final List<String> searchFields;
        final String queryString;
        final FacetRequest facetRequest;
        final CqlExpression cqlFilter;
        final int maxValues;
        final int minCount;

        FacetArgs(List<String> searchFields, String queryString, FacetRequest facetRequest,
                  CqlExpression cqlFilter, int maxValues, int minCount) {
            this.searchFields = searchFields;
            this.queryString = queryString;
            this.facetRequest = facetRequest;
            this.cqlFilter = cqlFilter;
            this.maxValues = maxValues;
            this.minCount = minCount;
        }
    }

    private static class SubjectVars {
        final Node fieldNode;
        final Node valueNode;
        final Node lowNode;
        final Node highNode;
        final Node countNode;

        SubjectVars(Node fieldNode, Node valueNode, Node lowNode, Node highNode, Node countNode) {
            this.fieldNode = fieldNode;
            this.valueNode = valueNode;
            this.lowNode = lowNode;
            this.highNode = highNode;
            this.countNode = countNode;
        }
    }
}
