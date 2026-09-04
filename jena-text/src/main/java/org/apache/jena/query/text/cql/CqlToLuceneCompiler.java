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

package org.apache.jena.query.text.cql;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.jena.atlas.json.JSON;
import org.apache.jena.atlas.json.JsonArray;
import org.apache.jena.atlas.json.JsonObject;
import org.apache.jena.query.text.LiteralFieldSupport;
import org.apache.jena.query.text.ShaclIndexMapping;
import org.apache.jena.query.text.ShaclIndexMapping.FieldDef;
import org.apache.jena.query.text.ShaclIndexMapping.FieldType;
import org.apache.jena.query.text.ShaclIndexMapping.HierarchyDef;
import org.apache.jena.query.text.TextIndexException;
import org.apache.lucene.facet.DrillDownQuery;
import org.apache.lucene.facet.FacetsConfig;
import org.apache.lucene.document.DoublePoint;
import org.apache.lucene.document.IntPoint;
import org.apache.lucene.document.LatLonShape;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.ShapeField;
import org.apache.lucene.geo.Polygon;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.*;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.util.BytesRef;

/**
 * Compiles {@link CqlExpression} trees into Lucene {@link Query} objects
 * with a pushdown/residual split.
 * <p>
 * Expressions referencing indexed fields are pushed down to Lucene;
 * non-indexed fields or unsupported operations become residual CQL
 * for post-processing.
 */
public class CqlToLuceneCompiler {

    private final ShaclIndexMapping mapping;
    private final FacetsConfig facetsConfig;
    /** The index's query analyzer, used by {@code text_query} for fields that configure
     *  no analyzer of their own — without it such a field is searched by raw term and a
     *  differently-cased or multi-word input silently matches nothing. */
    private final Analyzer defaultQueryAnalyzer;

    public record CompileResult(Query pushed, CqlExpression residual) {}

    public CqlToLuceneCompiler(ShaclIndexMapping mapping) {
        this(mapping, null, null);
    }

    public CqlToLuceneCompiler(ShaclIndexMapping mapping, FacetsConfig facetsConfig) {
        this(mapping, facetsConfig, null);
    }

    public CqlToLuceneCompiler(ShaclIndexMapping mapping, FacetsConfig facetsConfig,
                               Analyzer defaultQueryAnalyzer) {
        this.mapping = mapping;
        this.facetsConfig = facetsConfig;
        this.defaultQueryAnalyzer = defaultQueryAnalyzer;
    }

    public CompileResult compile(CqlExpression expr) {
        return compileExpr(expr);
    }

    private CompileResult compileExpr(CqlExpression expr) {
        return switch (expr) {
            case CqlExpression.CqlAnd and -> compileAnd(and);
            case CqlExpression.CqlOr or -> compileOr(or);
            case CqlExpression.CqlNot not -> compileNot(not);
            case CqlExpression.CqlComparison cmp -> compileComparison(cmp);
            case CqlExpression.CqlIn in -> compileIn(in);
            case CqlExpression.CqlBetween btw -> compileBetween(btw);
            case CqlExpression.CqlLike like -> compileLike(like);
            case CqlExpression.CqlSpatial spatial -> compileSpatial(spatial);
            case CqlExpression.CqlTextQuery tq -> compileTextQuery(tq);
        };
    }

    /**
     * Analyzer-aware text search ({@code text_query}). Tokenises the value through
     * the field's query analyzer and emits the resulting Lucene query. Child-scoped
     * fields are lifted via {@link #maybeLiftToParent} so the result surfaces parent
     * entities; same-scope fold (in {@link #compileSameScopeFold}) can combine multiple
     * text_query clauses targeting the same nested scope with sibling exact clauses.
     */
    private CompileResult compileTextQuery(CqlExpression.CqlTextQuery tq) {
        FieldDef field = findField(tq.property());
        if (field == null || !field.isIndexed()) {
            return new CompileResult(null, tq);
        }
        FieldType ft = field.getFieldType();
        if (ft != FieldType.TEXT && ft != FieldType.KEYWORD) {
            // text_query on a numeric/temporal/spatial field has no defined meaning
            return new CompileResult(null, tq);
        }
        Query q = buildAnalyzedTextQuery(field, tq.text());
        return new CompileResult(maybeLiftToParent(field, q), null);
    }

    private CompileResult compileAnd(CqlExpression.CqlAnd and) {
        List<Query> pushed = new ArrayList<>();
        List<CqlExpression> residual = new ArrayList<>();
        Set<CqlExpression> consumed = Collections.newSetFromMap(new IdentityHashMap<>());

        // Run same-scope fold first: it handles AND clauses that all target the same
        // idx:nested scope (including hierarchy-level clauses combined with non-hierarchy
        // fields), producing ONE inner BooleanQuery wrapped in ONE ToParentBlockJoinQuery
        // — required for same-child correlation (issue #65). Hierarchy DrillDownQuery
        // folding then runs on whatever the same-scope pass didn't consume, typically
        // when all clauses fit a single hierarchy path and same-scope fold of size 2+
        // could have handled it too — DrillDownQuery is slightly more efficient there.
        pushed.addAll(compileSameScopeFold(and.args(), consumed, BooleanClause.Occur.MUST));
        pushed.addAll(compileHierarchyDrillDowns(and.args(), consumed));

        for (CqlExpression child : and.args()) {
            if (consumed.contains(child)) {
                continue;
            }
            CompileResult r = compileExpr(child);
            if (r.pushed() != null) {
                pushed.add(r.pushed());
            }
            if (r.residual() != null) {
                residual.add(r.residual());
            }
        }

        Query pushedQuery = null;
        if (!pushed.isEmpty()) {
            if (pushed.size() == 1) {
                pushedQuery = pushed.get(0);
            } else {
                BooleanQuery.Builder bq = new BooleanQuery.Builder();
                for (Query q : pushed) {
                    bq.add(q, BooleanClause.Occur.MUST);
                }
                pushedQuery = bq.build();
            }
        }

        CqlExpression residualExpr = null;
        if (!residual.isEmpty()) {
            residualExpr = residual.size() == 1 ? residual.get(0) : new CqlExpression.CqlAnd(residual);
        }

        return new CompileResult(pushedQuery, residualExpr);
    }

    private List<Query> compileHierarchyDrillDowns(List<CqlExpression> args, Set<CqlExpression> consumed) {
        if (facetsConfig == null || !mapping.hasHierarchies()) {
            return Collections.emptyList();
        }

        Map<HierarchyDef, Map<Integer, CqlExpression.CqlComparison>> grouped = new HashMap<>();
        Set<HierarchyDef> ambiguous = new java.util.HashSet<>();

        for (CqlExpression child : args) {
            if (!(child instanceof CqlExpression.CqlComparison cmp) || !"=".equals(cmp.op())) {
                continue;
            }

            HierarchyDef hierarchy = mapping.findHierarchyForField(cmp.property());
            if (hierarchy == null) {
                continue;
            }

            FieldDef field = findField(cmp.property());
            if (field == null) {
                continue;
            }
            int levelIndex = hierarchy.getLevelIndex(field);
            if (levelIndex < 0) {
                continue;
            }

            Map<Integer, CqlExpression.CqlComparison> byLevel =
                grouped.computeIfAbsent(hierarchy, h -> new HashMap<>());
            CqlExpression.CqlComparison existing = byLevel.putIfAbsent(levelIndex, cmp);
            if (existing != null && !existing.equals(cmp)) {
                ambiguous.add(hierarchy);
            }
        }

        List<Query> drilldowns = new ArrayList<>();
        for (Map.Entry<HierarchyDef, Map<Integer, CqlExpression.CqlComparison>> entry : grouped.entrySet()) {
            HierarchyDef hierarchy = entry.getKey();
            if (ambiguous.contains(hierarchy)) {
                continue;
            }

            Map<Integer, CqlExpression.CqlComparison> byLevel = entry.getValue();
            int prefixDepth = 0;
            while (byLevel.containsKey(prefixDepth)) {
                prefixDepth++;
            }
            if (prefixDepth == 0) {
                continue;
            }

            String[] path = new String[prefixDepth];
            for (int i = 0; i < prefixDepth; i++) {
                CqlExpression.CqlComparison cmp = byLevel.get(i);
                path[i] = String.valueOf(cmp.value());
                consumed.add(cmp);
            }

            DrillDownQuery drillDownQuery = new DrillDownQuery(facetsConfig);
            drillDownQuery.add(hierarchy.getDimensionName(), path);
            drilldowns.add(drillDownQuery);
        }

        return drilldowns;
    }

    private CompileResult compileOr(CqlExpression.CqlOr or) {
        // OR can only be pushed if ALL children are pushable
        List<Query> pushed = new ArrayList<>();
        boolean allPushable = true;
        Set<CqlExpression> consumed = Collections.newSetFromMap(new IdentityHashMap<>());

        // Same-scope OR folding: also semantically equivalent to OR-of-independent-lifts
        // (parents match if any child satisfies any clause). Done for symmetry with AND
        // and to keep the wire query smaller (one block-join, not N).
        pushed.addAll(compileSameScopeFold(or.args(), consumed, BooleanClause.Occur.SHOULD));

        for (CqlExpression child : or.args()) {
            if (consumed.contains(child)) {
                continue;
            }
            CompileResult r = compileExpr(child);
            if (r.pushed() != null && r.residual() == null) {
                pushed.add(r.pushed());
            } else {
                allPushable = false;
                break;
            }
        }

        if (allPushable && !pushed.isEmpty()) {
            if (pushed.size() == 1) {
                return new CompileResult(pushed.get(0), null);
            }
            BooleanQuery.Builder bq = new BooleanQuery.Builder();
            for (Query q : pushed) {
                bq.add(q, BooleanClause.Occur.SHOULD);
            }
            bq.setMinimumNumberShouldMatch(1);
            return new CompileResult(bq.build(), null);
        }

        return new CompileResult(null, or);
    }

    /**
     * Detect leaf clauses in {@code args} that all target fields in the same
     * {@code idx:nested} scope, and fold each such group into ONE
     * {@code ToParentBlockJoinQuery} wrapping a single inner BooleanQuery
     * combining all the group's per-clause queries with the supplied {@code occur}.
     * <p>
     * For AND ({@code MUST}): folding is the same-child correctness fix for #65 —
     * a parent surfaces only when ONE child satisfies ALL clauses simultaneously.
     * For OR ({@code SHOULD}): semantically equivalent to independent lifts, folded
     * to keep the wire query smaller (one block-join, not N).
     * <p>
     * Group members are added to {@code consumed} so the caller's main loop skips them.
     * Returns the folded queries (already lifted to parent) to be added directly to the
     * caller's pushed list.
     * <p>
     * Scope inference handles only leaf expressions ({@code CqlComparison},
     * {@code CqlIn}, {@code CqlBetween}, {@code CqlLike}). Composite expressions
     * ({@code CqlAnd}, {@code CqlOr}, {@code CqlNot}) and clauses targeting root-scoped
     * or unknown fields are left for the existing per-clause compile path.
     */
    private List<Query> compileSameScopeFold(List<CqlExpression> args,
                                             Set<CqlExpression> consumed,
                                             BooleanClause.Occur occur) {
        // Group eligible leaves by their nested scope name.
        Map<String, List<CqlExpression>> byScope = new LinkedHashMap<>();
        for (CqlExpression child : args) {
            if (consumed.contains(child)) continue;
            String scope = inferLeafNestedScope(child);
            if (scope == null) continue;
            byScope.computeIfAbsent(scope, k -> new ArrayList<>()).add(child);
        }

        List<Query> folded = new ArrayList<>();
        for (Map.Entry<String, List<CqlExpression>> entry : byScope.entrySet()) {
            List<CqlExpression> group = entry.getValue();
            if (group.size() < 2) continue;  // single-leaf needs no folding

            List<Query> innerQueries = new ArrayList<>(group.size());
            for (CqlExpression leaf : group) {
                // Use the raw inner-query builder (NOT compileExpr) so the result is the
                // unlifted child-doc query — no block-join wrap (we wrap once at the end)
                // and no level-0 hierarchy DrillDownQuery short-circuit (which would
                // produce a parent-taxonomy query rather than a child-doc query).
                Query inner = buildInnerForLeaf(leaf);
                if (inner == null) {
                    innerQueries = null;
                    break;
                }
                innerQueries.add(inner);
            }
            if (innerQueries == null) continue;

            // Combine the inner queries and re-wrap ONCE.
            Query combined;
            if (innerQueries.size() == 1) {
                combined = innerQueries.get(0);
            } else {
                BooleanQuery.Builder bq = new BooleanQuery.Builder();
                for (Query q : innerQueries) {
                    bq.add(q, occur);
                }
                if (occur == BooleanClause.Occur.SHOULD) {
                    bq.setMinimumNumberShouldMatch(1);
                }
                combined = bq.build();
            }
            folded.add(org.apache.jena.query.text.ShaclTextIndexLucene.wrapAsParent(combined));
            for (CqlExpression member : group) {
                consumed.add(member);
            }
        }
        return folded;
    }

    /** Return the nested scope name for a leaf expression, or null if it isn't a foldable leaf. */
    private String inferLeafNestedScope(CqlExpression expr) {
        String property = switch (expr) {
            case CqlExpression.CqlComparison c -> c.property();
            case CqlExpression.CqlIn i -> i.property();
            case CqlExpression.CqlBetween b -> b.property();
            case CqlExpression.CqlLike l -> l.property();
            case CqlExpression.CqlTextQuery tq -> tq.property();
            default -> null;
        };
        if (property == null) return null;
        FieldDef field = mapping.findField(property);
        if (field == null) return null;
        ShaclIndexMapping.NestedDef scope = mapping.findNestedDefForFieldName(field.getFieldName());
        return scope != null ? scope.getNestedName() : null;
    }

    /**
     * Strip the outer {@code ToParentBlockJoinQuery} wrap and return the inner child
     * query, or {@code null} if the input isn't a block-join. Reserved for any future
     * caller that needs to peel a wrap from an already-compiled query.
     */
    private static Query unwrapBlockJoin(Query q) {
        if (q instanceof org.apache.lucene.search.join.ToParentBlockJoinQuery bjq) {
            return bjq.getChildQuery();
        }
        return null;
    }

    /**
     * Build the raw child-doc query for a leaf expression, bypassing both the
     * {@code maybeLiftToParent} block-join wrap and the level-0 hierarchy
     * {@code DrillDownQuery} short-circuit. Used by the same-scope fold which needs
     * unwrapped per-clause queries to combine into a single inner BooleanQuery.
     * Returns null for non-leaf or unsupported expressions.
     */
    private Query buildInnerForLeaf(CqlExpression expr) {
        return switch (expr) {
            case CqlExpression.CqlComparison cmp -> buildInnerForComparison(cmp);
            case CqlExpression.CqlIn in -> buildInnerForIn(in);
            case CqlExpression.CqlBetween btw -> buildInnerForBetween(btw);
            case CqlExpression.CqlLike like -> buildInnerForLike(like);
            case CqlExpression.CqlTextQuery tq -> buildInnerForTextQuery(tq);
            default -> null;
        };
    }

    private Query buildInnerForTextQuery(CqlExpression.CqlTextQuery tq) {
        FieldDef field = findField(tq.property());
        if (field == null || !field.isIndexed()) return null;
        FieldType ft = field.getFieldType();
        if (ft != FieldType.TEXT && ft != FieldType.KEYWORD) return null;
        return buildAnalyzedTextQuery(field, tq.text());
    }

    private Query buildInnerForComparison(CqlExpression.CqlComparison cmp) {
        FieldDef field = findField(cmp.property());
        if (field == null || !field.isIndexed()) return null;
        return switch (cmp.op()) {
            case "=" -> buildEqualQuery(field, cmp.value());
            case "<>" -> new BooleanQuery.Builder()
                .add(new MatchAllDocsQuery(), BooleanClause.Occur.MUST)
                .add(buildEqualQuery(field, cmp.value()), BooleanClause.Occur.MUST_NOT)
                .build();
            case "<" -> buildRangeQuery(field, null, cmp.value(), false, false);
            case "<=" -> buildRangeQuery(field, null, cmp.value(), false, true);
            case ">" -> buildRangeQuery(field, cmp.value(), null, false, false);
            case ">=" -> buildRangeQuery(field, cmp.value(), null, true, false);
            default -> null;
        };
    }

    private Query buildInnerForIn(CqlExpression.CqlIn in) {
        FieldDef field = findField(in.property());
        if (field == null || !field.isIndexed()) return null;
        FieldType ft = field.getFieldType();
        if (ft == FieldType.KEYWORD || ft == FieldType.TEXT) {
            List<BytesRef> refs = new ArrayList<>();
            for (Object v : in.values()) refs.add(new BytesRef(String.valueOf(v)));
            return new TermInSetQuery(field.getFieldName(), refs);
        }
        if (in.values().isEmpty()) return new MatchNoDocsQuery();
        BooleanQuery.Builder bq = new BooleanQuery.Builder();
        for (Object v : in.values()) bq.add(buildEqualQuery(field, v), BooleanClause.Occur.SHOULD);
        bq.setMinimumNumberShouldMatch(1);
        return bq.build();
    }

    private Query buildInnerForBetween(CqlExpression.CqlBetween btw) {
        FieldDef field = findField(btw.property());
        if (field == null || !field.isIndexed()) return null;
        return buildRangeQuery(field, btw.lower(), btw.upper(), true, true);
    }

    private Query buildInnerForLike(CqlExpression.CqlLike like) {
        FieldDef field = findField(like.property());
        if (field == null || !field.isIndexed()) return null;
        FieldType ft = field.getFieldType();
        if (ft != FieldType.KEYWORD && ft != FieldType.TEXT) return null;
        String lucenePattern = like.pattern()
            .replace("*", "\\*")
            .replace("?", "\\?")
            .replace("%", "*")
            .replace("_", "?");
        return new WildcardQuery(new Term(field.getFieldName(), lucenePattern));
    }

    private CompileResult compileNot(CqlExpression.CqlNot not) {
        CompileResult inner = compileExpr(not.arg());
        if (inner.pushed() != null && inner.residual() == null) {
            BooleanQuery q = new BooleanQuery.Builder()
                .add(new MatchAllDocsQuery(), BooleanClause.Occur.MUST)
                .add(inner.pushed(), BooleanClause.Occur.MUST_NOT)
                .build();
            return new CompileResult(q, null);
        }
        return new CompileResult(null, not);
    }

    private CompileResult compileComparison(CqlExpression.CqlComparison cmp) {
        Query entityIriQuery = compileEntityIriComparison(cmp);
        if (entityIriQuery != null) {
            return new CompileResult(entityIriQuery, null);
        }

        FieldDef field = findField(cmp.property());
        if (field == null || !field.isIndexed()) {
            return new CompileResult(null, cmp);
        }

        if ("=".equals(cmp.op())) {
            Query hierarchyQuery = compileSingleHierarchyEquality(cmp, field);
            if (hierarchyQuery != null) {
                return new CompileResult(hierarchyQuery, null);
            }
        }

        String op = cmp.op();
        Object value = cmp.value();
        FieldType ft = field.getFieldType();

        Query q = switch (op) {
            case "=" -> buildEqualQuery(field, value);
            case "<>" -> {
                Query eq = buildEqualQuery(field, value);
                yield new BooleanQuery.Builder()
                    .add(new MatchAllDocsQuery(), BooleanClause.Occur.MUST)
                    .add(eq, BooleanClause.Occur.MUST_NOT)
                    .build();
            }
            case "<" -> buildRangeQuery(field, null, value, false, false);
            case "<=" -> buildRangeQuery(field, null, value, false, true);
            case ">" -> buildRangeQuery(field, value, null, false, false);
            case ">=" -> buildRangeQuery(field, value, null, true, false);
            default -> null;
        };

        if (q == null) {
            return new CompileResult(null, cmp);
        }
        return new CompileResult(maybeLiftToParent(field, q), null);
    }

    private Query compileSingleHierarchyEquality(CqlExpression.CqlComparison cmp, FieldDef field) {
        if (facetsConfig == null || !mapping.hasHierarchies()) {
            return null;
        }

        HierarchyDef hierarchy = mapping.findHierarchyForField(cmp.property());
        if (hierarchy == null) {
            return null;
        }

        int levelIndex = hierarchy.getLevelIndex(field);
        if (levelIndex != 0) {
            return null;
        }

        // A nested-scoped field stays on the block-join path even at level 0.
        //
        // The taxonomy drill-down matches exactly the same parents — the hierarchy facet
        // paths of a nested scope are written onto the parent document — but it carries no
        // child query, so nothing downstream can say WHICH child matched and luc:nestedMatch
        // has nothing to project. Level 1+ of the same hierarchy already lifts through
        // maybeLiftToParent, so the short-circuit made a lone level-0 equality the one
        // filter whose compilation depended on a field happening to be declared as a
        // hierarchy level. Root-scoped hierarchy fields keep the drill-down: they have no
        // children, so there is nothing to lose.
        if (mapping.findNestedDefForFieldName(field.getFieldName()) != null) {
            return null;
        }

        DrillDownQuery drillDownQuery = new DrillDownQuery(facetsConfig);
        drillDownQuery.add(hierarchy.getDimensionName(), String.valueOf(cmp.value()));
        return drillDownQuery;
    }

    private CompileResult compileIn(CqlExpression.CqlIn in) {
        Query entityIriQuery = compileEntityIriIn(in);
        if (entityIriQuery != null) {
            return new CompileResult(entityIriQuery, null);
        }

        FieldDef field = findField(in.property());
        if (field == null || !field.isIndexed()) {
            return new CompileResult(null, in);
        }

        FieldType ft = field.getFieldType();
        String fieldName = field.getFieldName();

        if (ft == FieldType.KEYWORD || ft == FieldType.TEXT) {
            List<BytesRef> refs = new ArrayList<>();
            for (Object v : in.values()) {
                // KEYWORD with a normalizer: normalize each value so it matches the indexed term.
                refs.add(keywordBytes(field, String.valueOf(v)));
            }
            return new CompileResult(maybeLiftToParent(field, new TermInSetQuery(fieldName, refs)), null);
        }

        // Numeric IN: OR of exact queries
        if (in.values().isEmpty()) {
            return new CompileResult(maybeLiftToParent(field, new MatchNoDocsQuery()), null);
        }

        BooleanQuery.Builder bq = new BooleanQuery.Builder();
        for (Object v : in.values()) {
            Query eq = buildEqualQuery(field, v);
            bq.add(eq, BooleanClause.Occur.SHOULD);
        }
        bq.setMinimumNumberShouldMatch(1);
        return new CompileResult(maybeLiftToParent(field, bq.build()), null);
    }

    private CompileResult compileBetween(CqlExpression.CqlBetween btw) {
        FieldDef field = findField(btw.property());
        if (field == null || !field.isIndexed()) {
            return new CompileResult(null, btw);
        }

        Query q = buildRangeQuery(field, btw.lower(), btw.upper(), true, true);
        if (q == null) {
            return new CompileResult(null, btw);
        }
        return new CompileResult(maybeLiftToParent(field, q), null);
    }

    private CompileResult compileLike(CqlExpression.CqlLike like) {
        FieldDef field = findField(like.property());
        if (field == null || !field.isIndexed()) {
            return new CompileResult(null, like);
        }

        FieldType ft = field.getFieldType();
        if (ft != FieldType.KEYWORD && ft != FieldType.TEXT) {
            return new CompileResult(null, like);
        }

        // Convert CQL LIKE pattern (% and _) to Lucene WildcardQuery (* and ?)
        String lucenePattern = like.pattern()
            .replace("*", "\\*")  // escape literal *
            .replace("?", "\\?")  // escape literal ?
            .replace("%", "*")    // CQL % → Lucene *
            .replace("_", "?");   // CQL _ → Lucene ?

        return new CompileResult(
            maybeLiftToParent(field, new WildcardQuery(new Term(field.getFieldName(), lucenePattern))), null);
    }

    /**
     * If {@code field} is owned by an {@code idx:nested} scope, lift the inner query
     * to the parent doc level via {@code ToParentBlockJoinQuery}. Root-scoped fields
     * pass through unchanged.
     * <p>
     * Note: this lifts each clause independently, so a multi-clause AND on the same
     * nested scope produces N independent lifts and can still cross-correlate across
     * different children. PR-B introduces scope-aware AND/OR folding to deliver
     * same-child correctness. For lone child-scope clauses this is exact.
     */
    private Query maybeLiftToParent(FieldDef field, Query inner) {
        if (inner == null) return null;
        ShaclIndexMapping.NestedDef scope = mapping.findNestedDefForFieldName(field.getFieldName());
        if (scope == null) {
            return inner;
        }
        return org.apache.jena.query.text.ShaclTextIndexLucene.wrapAsParent(inner);
    }

    private CompileResult compileSpatial(CqlExpression.CqlSpatial spatial) {
        // Only s_intersects is supported for now
        if (!"s_intersects".equals(spatial.op())) {
            return new CompileResult(null, spatial);
        }

        FieldDef field = findField(spatial.property());
        if (field == null || field.getFieldType() != FieldType.LATLON) {
            return new CompileResult(null, spatial);
        }

        String fieldName = field.getFieldName();
        String geomJson = String.valueOf(spatial.geometry());

        try {
            JsonObject geomObj = JSON.parse(geomJson);

            if (geomObj.hasKey("bbox")) {
                JsonArray bbox = geomObj.get("bbox").getAsArray();
                if (bbox.size() != 4) {
                    throw new TextIndexException("bbox must have exactly 4 values [swLon, swLat, neLon, neLat], got " + bbox.size());
                }
                double swLon = bbox.get(0).getAsNumber().value().doubleValue();
                double swLat = bbox.get(1).getAsNumber().value().doubleValue();
                double neLon = bbox.get(2).getAsNumber().value().doubleValue();
                double neLat = bbox.get(3).getAsNumber().value().doubleValue();

                Query q = LatLonShape.newBoxQuery(fieldName, ShapeField.QueryRelation.INTERSECTS,
                    swLat, neLat, swLon, neLon);
                return new CompileResult(q, null);
            }

            if (geomObj.hasKey("type") && "Polygon".equals(geomObj.get("type").getAsString().value())) {
                Polygon poly = geoJsonPolygonToLucene(geomObj.get("coordinates").getAsArray());
                Query q = LatLonShape.newGeometryQuery(fieldName, ShapeField.QueryRelation.INTERSECTS, poly);
                return new CompileResult(q, null);
            }

            return new CompileResult(null, spatial);
        } catch (TextIndexException e) {
            throw e;
        } catch (Exception e) {
            throw new TextIndexException("Failed to parse spatial geometry JSON: " + e.getMessage(), e);
        }
    }

    /**
     * Convert GeoJSON {@code Polygon} coordinates to a Lucene polygon.
     * <p>
     * Ring 0 is the exterior shell; rings 1..n are interior rings (holes) and are
     * passed to Lucene rather than dropped, so a query polygon with a hole does not
     * match entities sitting inside that hole. This mirrors what
     * {@code ShaclTextIndexLucene.jtsPolygonToLucene} already does for indexed polygons.
     * <p>
     * GeoJSON coordinate order is [lon, lat].
     */
    private static Polygon geoJsonPolygonToLucene(JsonArray coordinates) {
        Polygon[] holes = new Polygon[Math.max(0, coordinates.size() - 1)];
        for (int h = 1; h < coordinates.size(); h++) {
            holes[h - 1] = geoJsonRingToLucene(coordinates.get(h).getAsArray());
        }
        JsonArray shell = coordinates.get(0).getAsArray();
        double[][] latLon = geoJsonRingCoordinates(shell);
        return new Polygon(latLon[0], latLon[1], holes);
    }

    /** Convert a single GeoJSON linear ring to a Lucene polygon with no holes. */
    private static Polygon geoJsonRingToLucene(JsonArray ring) {
        double[][] latLon = geoJsonRingCoordinates(ring);
        return new Polygon(latLon[0], latLon[1]);
    }

    /** Split a GeoJSON linear ring into parallel lat and lon arrays. */
    private static double[][] geoJsonRingCoordinates(JsonArray ring) {
        double[] lats = new double[ring.size()];
        double[] lons = new double[ring.size()];
        for (int i = 0; i < ring.size(); i++) {
            JsonArray coord = ring.get(i).getAsArray();
            lons[i] = coord.get(0).getAsNumber().value().doubleValue();
            lats[i] = coord.get(1).getAsNumber().value().doubleValue();
        }
        return new double[][] { lats, lons };
    }

    /**
     * Build the analyzer-aware text query that the {@code text_query} operator
     * compiles to. Tokenises {@code value} through the field's configured query
     * analyzer (falling back to the field's index analyzer, then to the index-wide
     * query analyzer, if no query-side one is set)
     * and emits a {@link TermQuery} (single token) or {@link org.apache.lucene.search.PhraseQuery}
     * (multi-token, positional). Empty token streams produce {@link MatchNoDocsQuery}
     * rather than matching everything.
     */
    private Query buildAnalyzedTextQuery(FieldDef field, String value) {
        String fieldName = field.getFieldName();
        org.apache.lucene.analysis.Analyzer analyzer = field.getQueryAnalyzer() != null
            ? field.getQueryAnalyzer()
            : field.getAnalyzer() != null ? field.getAnalyzer() : defaultQueryAnalyzer;
        if (analyzer == null) {
            // Nothing to analyse with (no index available to ask) — treat as a raw term.
            return new TermQuery(new Term(fieldName, value));
        }
        List<String> tokens = new ArrayList<>();
        try (org.apache.lucene.analysis.TokenStream ts = analyzer.tokenStream(fieldName, value)) {
            org.apache.lucene.analysis.tokenattributes.CharTermAttribute term =
                ts.addAttribute(org.apache.lucene.analysis.tokenattributes.CharTermAttribute.class);
            ts.reset();
            while (ts.incrementToken()) {
                tokens.add(term.toString());
            }
            ts.end();
        } catch (java.io.IOException e) {
            return new TermQuery(new Term(fieldName, value));
        }
        if (tokens.isEmpty()) {
            return new MatchNoDocsQuery();
        }
        if (tokens.size() == 1) {
            return new TermQuery(new Term(fieldName, tokens.get(0)));
        }
        org.apache.lucene.search.PhraseQuery.Builder pq = new org.apache.lucene.search.PhraseQuery.Builder();
        for (String t : tokens) {
            pq.add(new Term(fieldName, t));
        }
        return pq.build();
    }

    /** Normalized bytes for a comparison value: applies the field's normalizer (KEYWORD only)
     *  so query-time terms match index-time terms; raw bytes when there is no normalizer. */
    private static BytesRef keywordBytes(FieldDef field, String value) {
        Analyzer norm = field.getNormalizer();
        return norm != null ? norm.normalize(field.getFieldName(), value) : new BytesRef(value);
    }

    /** String form of {@link #keywordBytes} for building a {@link Term}. */
    private static String keywordTerm(FieldDef field, Object value) {
        return keywordBytes(field, String.valueOf(value)).utf8ToString();
    }

    private Query buildEqualQuery(FieldDef field, Object value) {
        String fieldName = field.getFieldName();
        FieldType ft = field.getFieldType();
        return switch (ft) {
            // KEYWORD and TEXT go to a TermQuery — exact-term semantics. A KEYWORD field
            // with a normalizer normalizes the comparison value first (TEXT never has one).
            // Analyzer-aware text matching uses the explicit text_query operator instead.
            case KEYWORD, TEXT -> new TermQuery(new Term(fieldName, keywordTerm(field, value)));
            case INT -> IntPoint.newExactQuery(fieldName, toInt(value));
            case LONG -> LongPoint.newExactQuery(fieldName, toLong(value));
            case DOUBLE -> DoublePoint.newExactQuery(fieldName, toDouble(value));
            case TEMPORAL -> LongPoint.newExactQuery(
                LiteralFieldSupport.epochField(fieldName),
                toEpochMillis(ft, value));
            case LATLON -> throw new TextIndexException("Equality queries not supported on LATLON field '" + fieldName + "'");
        };
    }

    private Query buildRangeQuery(FieldDef field, Object lower, Object upper,
                                  boolean lowerInclusive, boolean upperInclusive) {
        String fieldName = field.getFieldName();
        FieldType ft = field.getFieldType();
        return switch (ft) {
            case INT -> {
                int lo = lower != null ? (lowerInclusive ? toInt(lower) : Math.addExact(toInt(lower), 1)) : Integer.MIN_VALUE;
                int hi = upper != null ? (upperInclusive ? toInt(upper) : Math.addExact(toInt(upper), -1)) : Integer.MAX_VALUE;
                yield IntPoint.newRangeQuery(fieldName, lo, hi);
            }
            case LONG -> {
                long lo = lower != null ? (lowerInclusive ? toLong(lower) : Math.addExact(toLong(lower), 1L)) : Long.MIN_VALUE;
                long hi = upper != null ? (upperInclusive ? toLong(upper) : Math.addExact(toLong(upper), -1L)) : Long.MAX_VALUE;
                yield LongPoint.newRangeQuery(fieldName, lo, hi);
            }
            case DOUBLE -> {
                double lo = lower != null
                    ? (lowerInclusive ? toDouble(lower) : Math.nextUp(toDouble(lower)))
                    : Double.NEGATIVE_INFINITY;
                double hi = upper != null
                    ? (upperInclusive ? toDouble(upper) : Math.nextDown(toDouble(upper)))
                    : Double.POSITIVE_INFINITY;
                yield DoublePoint.newRangeQuery(fieldName, lo, hi);
            }
            case TEMPORAL -> {
                long lo = lower != null
                    ? (lowerInclusive ? toEpochMillis(ft, lower) : Math.addExact(toEpochMillis(ft, lower), 1L))
                    : Long.MIN_VALUE;
                long hi = upper != null
                    ? (upperInclusive ? toEpochMillis(ft, upper) : Math.addExact(toEpochMillis(ft, upper), -1L))
                    : Long.MAX_VALUE;
                yield LongPoint.newRangeQuery(LiteralFieldSupport.epochField(fieldName), lo, hi);
            }
            case KEYWORD, TEXT -> null; // Range queries on keywords not supported
            case LATLON -> null; // Range queries not applicable to spatial fields
        };
    }

    private FieldDef findField(String fieldIRI) {
        return mapping.findField(fieldIRI);
    }

    /**
     * Reserved-property handler for {@code urn:jena:lucene:index#entityIri}.
     * Translates {@code =} and {@code <>} into a TermQuery against the doc-id field.
     * Returns null for unsupported operators (range, etc) so they fall through to residual.
     */
    private Query compileEntityIriComparison(CqlExpression.CqlComparison cmp) {
        String docIdField = mapping.findDocIdFieldForEntityIriProperty(cmp.property());
        if (docIdField == null) {
            return null;
        }
        Query term = new TermQuery(new Term(docIdField, String.valueOf(cmp.value())));
        return switch (cmp.op()) {
            case "=" -> term;
            case "<>" -> new BooleanQuery.Builder()
                .add(new MatchAllDocsQuery(), BooleanClause.Occur.MUST)
                .add(term, BooleanClause.Occur.MUST_NOT)
                .build();
            default -> null;
        };
    }

    /**
     * Reserved-property handler for {@code urn:jena:lucene:index#entityIri}.
     * Translates {@code in [...]} into a TermInSetQuery against the doc-id field.
     */
    private Query compileEntityIriIn(CqlExpression.CqlIn in) {
        String docIdField = mapping.findDocIdFieldForEntityIriProperty(in.property());
        if (docIdField == null) {
            return null;
        }
        if (in.values().isEmpty()) {
            return new MatchNoDocsQuery();
        }
        List<BytesRef> refs = new ArrayList<>(in.values().size());
        for (Object v : in.values()) {
            refs.add(new BytesRef(String.valueOf(v)));
        }
        return new TermInSetQuery(docIdField, refs);
    }

    private static int toInt(Object v) {
        if (v instanceof Number n) return n.intValue();
        return Integer.parseInt(String.valueOf(v));
    }

    private static long toLong(Object v) {
        if (v instanceof Number n) return n.longValue();
        return Long.parseLong(String.valueOf(v));
    }

    private static double toDouble(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        return Double.parseDouble(String.valueOf(v));
    }

    private static long toEpochMillis(FieldType fieldType, Object value) {
        Long epoch = LiteralFieldSupport.toEpochMillis(fieldType, value);
        if (epoch == null) {
            throw new TextIndexException("Could not normalize temporal value '" + value + "' for " + fieldType);
        }
        return epoch;
    }
}
