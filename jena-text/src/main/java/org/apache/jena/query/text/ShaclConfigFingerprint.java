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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

import org.apache.jena.graph.Node;
import org.apache.jena.query.text.ShaclIndexMapping.ColumnBinding;
import org.apache.jena.query.text.ShaclIndexMapping.ExternalSourceDef;
import org.apache.jena.query.text.ShaclIndexMapping.FieldDef;
import org.apache.jena.query.text.ShaclIndexMapping.FieldOccurrence;
import org.apache.jena.query.text.ShaclIndexMapping.FieldType;
import org.apache.jena.query.text.ShaclIndexMapping.HierarchyDef;
import org.apache.jena.query.text.ShaclIndexMapping.IndexProfile;
import org.apache.jena.query.text.ShaclIndexMapping.JoinStep;
import org.apache.jena.query.text.ShaclIndexMapping.NestedDef;
import org.apache.jena.query.text.ShaclIndexMapping.StepDef;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;

/**
 * A stable hash of the parts of a SHACL index configuration that determine what is
 * written to the Lucene index.
 * <p>
 * The point is to answer one question: was the index on disk built from the same index
 * configuration the server is now running? The hash is written into the Lucene index
 * when it is built (see {@link ShaclIndexStamp}) and recomputed at startup.
 *
 * <h3>What is deliberately not hashed</h3>
 *
 * <b>Filesystem locations.</b> Building an index on a large indexing machine and serving
 * it from somewhere else is a normal workflow, and the resulting index is entirely valid.
 * {@code text:directory} and {@code text:taxonomyDirectory} never reach
 * {@link ShaclIndexMapping} at all, so they are excluded by construction.
 * {@link ExternalSourceDef#getLocation()} and its delta locations <em>are</em> in the
 * mapping and are excluded explicitly — a CSV path is a pointer to data, not a
 * description of index shape.
 * <p>
 * <b>Query-time settings.</b> {@code text:maxFacetHits} changes nothing on disk, so a
 * change to it must not be reported as requiring a reindex.
 * <p>
 * <b>Analyzer parameters.</b> {@link FieldDef} stores {@link Analyzer} instances, not the
 * RDF that configured them, so only the analyzer's class name is hashed. A changed
 * stopword list on the same analyzer class hashes identically. This is a known and
 * accepted limit: a stopword list is closer to data than to schema, and the index differs
 * for the same reason it differs when the underlying triples change. Callers that report
 * a fingerprint match to a user should say so.
 * <p>
 * <b>The index-wide analyzer, when it is the default.</b> {@code text:analyzer} analyses every
 * text field with no {@code idx:analyzer} of its own, so its class is hashed, but only when
 * it is not StandardAnalyzer, which is what no {@code text:analyzer} gives. A configuration
 * that leaves it at the default keeps the fingerprint it had before the index-wide analyzer
 * was recorded, and still matches. One that sets another analyzer reports a mismatch once
 * against an index stamped before then: that index may have been analysed differently, as
 * one whose {@code text:analyzer} was a {@code text:DefinedAnalyzer} was while
 * {@code text:defineAnalyzers} was ignored.
 *
 * <h3>Determinism</h3>
 *
 * Collections that carry no meaning in their order are serialised by rendering each
 * element and sorting the rendered strings, so the fingerprint does not depend on the
 * order things appear in the configuration file. Order is preserved only where it is
 * significant: hierarchy levels, property path steps, and external-source columns.
 * <p>
 * Blank nodes are rendered without their labels, because a label is assigned by the
 * parser and differs between two parses of the same file.
 */
public class ShaclConfigFingerprint {

    /**
     * Version of the serialisation format below.
     * <p>
     * Increment this whenever the rendering changes. A reader that finds a version it
     * does not understand must report "unknown", never "mismatch" — otherwise the first
     * format change makes every deployed index look broken.
     */
    public static final int FINGERPRINT_VERSION = 2;

    private ShaclConfigFingerprint() {}

    /**
     * The canonical serialisation. Exposed for tests and for diagnostics: when two
     * fingerprints differ, diffing the two serialisations says why.
     */
    public static String serialise(ShaclIndexMapping mapping, boolean storeValues, Collection<String> facetFields) {
        return serialise(mapping, storeValues, facetFields, null);
    }

    /**
     * @param indexAnalyzer the index-wide {@code text:analyzer}, or null when there is none
     */
    public static String serialise(ShaclIndexMapping mapping, boolean storeValues, Collection<String> facetFields,
                                   Analyzer indexAnalyzer) {
        Objects.requireNonNull(mapping, "mapping");
        StringBuilder sb = new StringBuilder();
        sb.append("shaclIndexConfig/v").append(FINGERPRINT_VERSION).append('\n');
        sb.append("storeValues=").append(storeValues).append('\n');
        sb.append("facetFields=").append(sortedJoin(facetFields)).append('\n');
        // Not written for the default, so such a configuration keeps its fingerprint.
        if ( indexAnalyzer != null && indexAnalyzer.getClass() != StandardAnalyzer.class )
            sb.append("indexAnalyzer=").append(analyzer(indexAnalyzer)).append('\n');

        List<String> profiles = new ArrayList<>();
        for ( IndexProfile profile : mapping.getProfiles() )
            profiles.add(renderProfile(profile));
        appendSorted(sb, "profile", profiles);
        return sb.toString();
    }

    /** Hex-encoded SHA-256 of {@link #serialise}, prefixed with the digest name. */
    public static String fingerprint(ShaclIndexMapping mapping, boolean storeValues, Collection<String> facetFields) {
        return fingerprint(mapping, storeValues, facetFields, null);
    }

    /** As {@link #fingerprint(ShaclIndexMapping, boolean, Collection)}, with the index-wide analyzer. */
    public static String fingerprint(ShaclIndexMapping mapping, boolean storeValues, Collection<String> facetFields,
                                     Analyzer indexAnalyzer) {
        return sha256(serialise(mapping, storeValues, facetFields, indexAnalyzer));
    }

    // ---- Rendering.
    //
    // Every render* method returns a single line. Nested structure is escaped into that
    // line rather than indented, so that sorting a list of rendered elements is a plain
    // string sort with no risk of one element's continuation lines interleaving with
    // another's.

    private static String renderProfile(IndexProfile profile) {
        StringBuilder sb = new StringBuilder();
        // The shape node's identity is deliberately absent.
        //
        // It is frequently a blank node, and when it is named it is very often named with
        // the "@prefix : <#>" idiom, which resolves against the configuration file's own
        // location: the same shape is file:///build/config.ttl#ReportShape on an indexing
        // machine and file:///srv/config.ttl#ReportShape on a serving one. Including it
        // would report every deployment that moves a config file as a changed
        // configuration, which is the exact false positive this fingerprint exists to
        // avoid.
        //
        // Nothing is lost. A shape's name determines nothing about what is written to the
        // index; profiles are told apart below by their target classes and content.
        sb.append("targetClasses=").append(sortedNodes(profile.getTargetClasses()));
        sb.append(" docIdField=").append(str(profile.getDocIdField()));
        sb.append(" discriminatorField=").append(str(profile.getDiscriminatorField()));
        sb.append(" fields=").append(sortedRendered(profile.getFields(), ShaclConfigFingerprint::renderField));
        sb.append(" rootOccurrences=").append(sortedRendered(profile.getRootOccurrences(), ShaclConfigFingerprint::renderOccurrence));
        sb.append(" hierarchies=").append(sortedRendered(profile.getHierarchies(), ShaclConfigFingerprint::renderHierarchy));
        sb.append(" nested=").append(sortedRendered(profile.getNestedDefs(), ShaclConfigFingerprint::renderNested));
        return sb.toString();
    }

    private static String renderField(FieldDef f) {
        // Flags are always written, never omitted when false. If absence meant false, a
        // field with every flag false would render identically to no field at all.
        return "{name=" + str(f.getFieldName())
             + " iri=" + node(f.getFieldIRI())
             + " type=" + layout(f.getFieldType())
             + " analyzer=" + analyzer(f.getAnalyzer())
             + " queryAnalyzer=" + analyzer(f.getQueryAnalyzer())
             + " normalizer=" + analyzer(f.getNormalizer())
             + " stored=" + f.isStored()
             + " indexed=" + f.isIndexed()
             + " facetable=" + f.isFacetable()
             + " sortable=" + f.isSortable()
             + " multiValued=" + f.isMultiValued()
             + " defaultSearch=" + f.isDefaultSearch()
             + " storeLiteralMetadata=" + f.isStoreLiteralMetadata()
             + "}";
    }

    /**
     * A field type, naming what it writes where that changed with no change to the
     * configuration. A TemporalField writes a span per value for the temporal operators,
     * so an index built before then reports a mismatch instead of matching while it cannot
     * answer them.
     */
    private static String layout(FieldType type) {
        return type == FieldType.TEMPORAL ? "TEMPORAL/span" : type.name();
    }

    private static String renderOccurrence(FieldOccurrence o) {
        return "{field=" + str(o.getField() == null ? null : o.getField().getFieldName())
             + " path=" + path(o.getPath())
             + " variants=" + renderPathVariants(o.getPathVariants())
             + " predicates=" + sortedNodes(o.getPredicates())
             + " requiredClass=" + node(o.getRequiredClass())
             + " nodeKind=" + (o.getNodeKindConstraint() == null ? "null" : o.getNodeKindConstraint().name())
             + " datatype=" + node(o.getDatatype())
             + " constraints=" + condition(o.getValueCondition())
             + " steps=" + renderSteps(o.getSteps())
             + " localPath=" + path(o.getLocalPath())
             + " nestedName=" + str(o.getNestedName())
             + period(o.getPeriodPaths())
             + "}";
    }

    /**
     * A PeriodField occurrence's start and end paths, named in order: they are not covered
     * by the sorted variants and predicates, which are the same when the two are swapped.
     * Nothing for any other occurrence, so those fingerprints are unchanged.
     */
    private static String period(FieldOccurrence.PeriodPaths paths) {
        return paths == null ? "" : " periodStart=" + path(paths.start()) + " periodEnd=" + path(paths.end());
    }

    /** Step order is significant: each step starts from the nodes the previous one kept. */
    private static String renderSteps(List<StepDef> steps) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for ( StepDef step : steps ) {
            if ( !first )
                sb.append(',');
            first = false;
            sb.append("{path=").append(path(step.getPath()))
              .append(" filter=").append(condition(step.getFilter()))
              .append('}');
        }
        return sb.append(']').toString();
    }

    /** A SHACL condition, by its canonical rendering, which carries no blank node labels. */
    private static String condition(ShaclShapeCondition c) {
        return c == null ? "null" : c.getCanonical();
    }

    /** Level order is significant — (continent country state) is not (state country continent). */
    private static String renderHierarchy(HierarchyDef h) {
        StringBuilder sb = new StringBuilder("{dimension=").append(str(h.getDimensionName())).append(" levels=[");
        boolean first = true;
        for ( FieldDef level : h.getLevels() ) {
            if ( !first )
                sb.append(',');
            first = false;
            sb.append(str(level.getFieldName()));
        }
        return sb.append("]}").toString();
    }

    private static String renderNested(NestedDef n) {
        return "{name=" + str(n.getNestedName())
             + " joinPath=" + path(n.getJoinPath())
             + " joinSteps=" + renderJoinSteps(n.getJoinSteps())
             + " joinPredicates=" + sortedNodes(n.getJoinPredicates())
             + " fields=" + sortedRendered(n.getFields(), ShaclConfigFingerprint::renderField)
             + " occurrences=" + sortedRendered(n.getOccurrences(), ShaclConfigFingerprint::renderOccurrence)
             + " hierarchies=" + sortedRendered(n.getHierarchies(), ShaclConfigFingerprint::renderHierarchy)
             + " external=" + renderExternal(n.getExternalSource())
             + "}";
    }

    private static String renderExternal(ExternalSourceDef e) {
        if ( e == null )
            return "null";
        // getLocation() and getDeltaLocations() are deliberately absent: they are paths to
        // data, and the same configuration is expected to point at different paths on an
        // indexing machine and on a serving one. Whether deltas are in use at all is
        // structural, so the count is kept.
        return "{format=" + e.getFormat()
             + " subjectColumn=" + str(e.getSubjectColumn())
             + " subjectColumnIndex=" + e.getSubjectColumnIndex()
             + " subjectPrefix=" + str(e.getSubjectPrefix())
             + " delimiter=" + (e.getDelimiter() == null ? "null" : e.getDelimiter().toString())
             + " headerless=" + e.isHeaderless()
             + " onError=" + e.getOnError()
             + " opColumn=" + str(e.getOpColumn())
             + " deltaCount=" + e.getDeltaLocations().size()
             + " columns=" + renderColumns(e.getColumns())
             + " fields=" + sortedRendered(e.getFields(), ShaclConfigFingerprint::renderField)
             + "}";
    }

    /** Column order is significant: these are positions in a CSV row. */
    private static String renderColumns(List<ColumnBinding> columns) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for ( ColumnBinding c : columns ) {
            if ( !first )
                sb.append(',');
            first = false;
            sb.append("{name=").append(str(c.getColumnName()))
              .append(" index=").append(c.getColumnIndex())
              .append(" field=").append(str(c.getField() == null ? null : c.getField().getFieldName()))
              .append('}');
        }
        return sb.append(']').toString();
    }

    /** Step order within a variant is significant; the set of variants is not ordered. */
    private static String renderPathVariants(List<List<JoinStep>> variants) {
        if ( variants == null )
            return "null";
        List<String> rendered = new ArrayList<>();
        for ( List<JoinStep> variant : variants )
            rendered.add(renderJoinSteps(variant));
        rendered.sort(null);
        return "[" + String.join(",", rendered) + "]";
    }

    private static String renderJoinSteps(List<JoinStep> steps) {
        if ( steps == null )
            return "null";
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for ( JoinStep s : steps ) {
            if ( !first )
                sb.append(',');
            first = false;
            sb.append(node(s.getPredicate())).append(s.isInverse() ? "^" : "");
        }
        return sb.append(']').toString();
    }

    // ---- Primitives.

    /**
     * A blank node contributes its kind but not its label: labels are assigned by the
     * parser and differ between two parses of the same file, which would make the
     * fingerprint unstable.
     */
    private static String node(Node n) {
        if ( n == null )
            return "null";
        if ( n.isBlank() )
            return "_:";
        if ( n.isURI() )
            return "<" + n.getURI() + ">";
        if ( n.isLiteral() ) {
            String dt = n.getLiteralDatatypeURI();
            String lang = n.getLiteralLanguage();
            String lex = "\"" + n.getLiteralLexicalForm() + "\"";
            if ( lang != null && !lang.isEmpty() )
                return lex + "@" + lang;
            return dt == null ? lex : lex + "^^<" + dt + ">";
        }
        return n.toString();
    }

    /**
     * Property paths are rendered with Jena's own {@code toString}, which writes IRIs in
     * full rather than against a prefix map, so it does not vary with the prefixes a
     * configuration file happens to declare. {@code TestShaclConfigFingerprint} pins this.
     */
    private static String path(Object p) {
        return p == null ? "null" : p.toString();
    }

    private static String analyzer(Analyzer a) {
        return a == null ? "null" : a.getClass().getName();
    }

    private static String str(String s) {
        return s == null ? "null" : "\"" + s + "\"";
    }

    private static String sortedNodes(Collection<Node> nodes) {
        if ( nodes == null )
            return "null";
        List<String> rendered = new ArrayList<>();
        for ( Node n : nodes )
            rendered.add(node(n));
        rendered.sort(null);
        return "[" + String.join(",", rendered) + "]";
    }

    private static <T> String sortedRendered(Collection<T> items, java.util.function.Function<T, String> render) {
        if ( items == null )
            return "null";
        List<String> rendered = new ArrayList<>();
        for ( T item : items )
            rendered.add(render.apply(item));
        rendered.sort(null);
        return "[" + String.join(",", rendered) + "]";
    }

    private static String sortedJoin(Collection<String> values) {
        if ( values == null )
            return "null";
        List<String> copy = new ArrayList<>(values);
        copy.sort(null);
        return "[" + String.join(",", copy) + "]";
    }

    private static void appendSorted(StringBuilder sb, String label, List<String> lines) {
        lines.sort(null);
        for ( String line : lines )
            sb.append(label).append(' ').append(line).append('\n');
    }

    private static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder("sha256:");
            for ( byte b : digest )
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required of every Java platform.
            throw new TextIndexException("SHA-256 not available", e);
        }
    }
}
