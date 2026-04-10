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

package org.apache.jena.query.text.assembler;

import java.util.*;

import org.apache.jena.assembler.Assembler;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.query.text.*;
import org.apache.jena.query.text.ShaclIndexMapping.*;
import org.apache.jena.rdf.model.*;
import org.apache.jena.sparql.path.*;
import org.apache.jena.system.G;
import org.apache.lucene.analysis.Analyzer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Parses SHACL-like shape definitions from an RDF config into a {@link ShaclIndexMapping}.
 * <p>
 * Not an {@code AssemblerBase} subclass — called from {@link TextIndexLuceneAssembler}.
 * <p>
 * Uses standard Jena RDF API to read {@code sh:targetClass}, {@code sh:path},
 * {@code sh:alternativePath} — no jena-shacl dependency.
 */
public class ShaclIndexAssembler {
    private static final Logger log = LoggerFactory.getLogger(ShaclIndexAssembler.class);

    // SHACL namespace — we only read config, not run validation
    private static final String SH = "http://www.w3.org/ns/shacl#";
    private static final Property shTargetClass     = ResourceFactory.createProperty(SH, "targetClass");
    private static final Property shProperty        = ResourceFactory.createProperty(SH, "property");
    private static final Property shPath            = ResourceFactory.createProperty(SH, "path");
    private static final Property shAlternativePath = ResourceFactory.createProperty(SH, "alternativePath");
    private static final Property shInversePath    = ResourceFactory.createProperty(SH, "inversePath");

    private static final Node SH_INVERSE_PATH     = shInversePath.asNode();
    private static final Node SH_ALTERNATIVE_PATH  = shAlternativePath.asNode();
    private static final Node RDF_FIRST = org.apache.jena.vocabulary.RDF.first.asNode();
    private static final Node RDF_NIL  = org.apache.jena.vocabulary.RDF.nil.asNode();

    private ShaclIndexAssembler() {}

    /**
     * Parse an RDF list of shape resources into a {@link ShaclIndexMapping}.
     *
     * @param a the assembler context (for resolving analyzer resources)
     * @param shapesList the RDF resource that is the head of the shapes list
     * @return parsed mapping
     */
    public static ShaclIndexMapping parseShapes(Assembler a, Resource shapesList) {
        List<IndexProfile> profiles = new ArrayList<>();

        RDFList rdfList = shapesList.as(RDFList.class);
        for (RDFNode item : rdfList.asJavaList()) {
            if (!item.isResource()) {
                throw new TextIndexException("text:shapes list item is not a resource: " + item);
            }
            profiles.add(parseProfile(a, item.asResource()));
        }

        if (profiles.isEmpty()) {
            throw new TextIndexException("text:shapes list is empty");
        }

        return new ShaclIndexMapping(profiles);
    }

    private static IndexProfile parseProfile(Assembler a, Resource shape) {
        Node shapeNode = shape.asNode();

        // sh:targetClass
        Set<Node> targetClasses = new LinkedHashSet<>();
        StmtIterator tcIter = shape.listProperties(shTargetClass);
        while (tcIter.hasNext()) {
            RDFNode tc = tcIter.next().getObject();
            if (tc.isResource()) {
                targetClasses.add(tc.asNode());
            }
        }
        if (targetClasses.isEmpty()) {
            throw new TextIndexException("Shape " + shape + " has no sh:targetClass");
        }

        // idx:docIdField (optional, default "uri")
        String docIdField = getOptionalString(shape, IndexVocab.pDocIdField);

        // idx:discriminatorField (optional, default "docType")
        String discriminatorField = getOptionalString(shape, IndexVocab.pDiscriminatorField);

        // Parse fields from idx:field list or sh:property
        Map<String, FieldDef> fieldsByIRI = new LinkedHashMap<>();

        // idx:field — an RDF list of field resources
        Statement fieldStmt = shape.getProperty(IndexVocab.pField);
        if (fieldStmt != null) {
            RDFNode fieldNode = fieldStmt.getObject();
            if (fieldNode.isResource()) {
                try {
                    RDFList fieldList = fieldNode.asResource().as(RDFList.class);
                    for (RDFNode fn : fieldList.asJavaList()) {
                        if (fn.isResource()) {
                            resolveRootFieldDef(a, fn.asResource(), fieldsByIRI);
                        }
                    }
                } catch (Exception e) {
                    throw new TextIndexException("idx:field on " + shape + " is not a valid RDF list: " + e.getMessage());
                }
            }
        }

        // Also parse sh:property nodes (for shapes that use SHACL property shapes directly)
        StmtIterator propIter = shape.listProperties(shProperty);
        while (propIter.hasNext()) {
            Resource propShape = propIter.next().getObject().asResource();
            resolveRootFieldDef(a, propShape, fieldsByIRI);
        }

        List<NestedDef> nestedDefs = parseNestedDefs(a, shape, fieldsByIRI);
        List<FieldDef> fields = new ArrayList<>(fieldsByIRI.values());

        if (fields.isEmpty()) {
            throw new TextIndexException("Shape " + shape + " has no fields (idx:field, sh:property, or idx:nested/idx:property)");
        }

        // Parse direct hierarchical facet definitions: idx:facetHierarchy
        List<HierarchyDef> hierarchies = parseHierarchies(shape, fields);

        return new IndexProfile(shapeNode, targetClasses, docIdField, discriminatorField,
            fields, hierarchies, nestedDefs);
    }

    /**
     * Parse {@code idx:nested} declarations on a shape.
     */
    private static List<NestedDef> parseNestedDefs(Assembler a, Resource shape,
            Map<String, FieldDef> fieldsByIRI) {
        List<NestedDef> nestedDefs = new ArrayList<>();

        StmtIterator nestedIter = shape.listProperties(IndexVocab.pNested);
        while (nestedIter.hasNext()) {
            RDFNode nestedNode = nestedIter.next().getObject();
            if (!nestedNode.isResource()) {
                throw new TextIndexException("idx:nested must be a resource on " + shape);
            }

            Resource nestedRes = nestedNode.asResource();
            Path joinPath = extractJoinPath(nestedRes);
            if (joinPath == null) {
                throw new TextIndexException("idx:nested on " + shape + " is missing idx:joinPath");
            }
            String nestedName = deriveNestedName(joinPath);
            List<JoinStep> joinSteps;
            try {
                joinSteps = extractJoinSteps(joinPath);
            } catch (TextIndexException ex) {
                throw new TextIndexException("Invalid idx:joinPath on " + shape + ": " + ex.getMessage(), ex);
            }

            List<FieldDef> nestedFields = new ArrayList<>();
            Set<String> seenFieldIRIs = new LinkedHashSet<>();

            StmtIterator nestedFieldIter = nestedRes.listProperties(IndexVocab.pProperty);
            while (nestedFieldIter.hasNext()) {
                RDFNode fieldNode = nestedFieldIter.next().getObject();
                if (!fieldNode.isResource()) {
                    throw new TextIndexException("idx:property inside idx:nested must be a resource on " + shape);
                }
                FieldDef fieldDef = resolveNestedFieldDef(a, fieldNode.asResource(), fieldsByIRI, nestedName);
                if (seenFieldIRIs.add(fieldDef.getFieldIRI().getURI())) {
                    nestedFields.add(fieldDef);
                }
            }

            if (nestedFields.isEmpty()) {
                throw new TextIndexException("idx:nested on " + shape + " must reference at least one idx:property");
            }

            List<HierarchyDef> nestedHierarchies = parseHierarchies(nestedRes, nestedFields);
            nestedDefs.add(new NestedDef(nestedName, joinPath, joinSteps, extractJoinPredicates(joinSteps),
                nestedFields, nestedHierarchies));
            log.debug("Parsed nested definition: {} joinPath={} fields={}", nestedName, joinPath, nestedFields);
        }

        return nestedDefs;
    }

    /**
     * Parse {@code idx:facetHierarchy} declarations on a shape or nested block.
     * Each hierarchy is an RDF list of field resource IRIs that define the ordered levels.
     * The dimension name is derived from the field names joined by "_".
     */
    private static List<HierarchyDef> parseHierarchies(Resource owner, List<FieldDef> fields) {
        List<HierarchyDef> hierarchies = new ArrayList<>();

        StmtIterator hierIter = owner.listProperties(IndexVocab.pFacetHierarchy);
        while (hierIter.hasNext()) {
            RDFNode hierNode = hierIter.next().getObject();
            if (!hierNode.isResource()) {
                throw new TextIndexException("idx:facetHierarchy must be an RDF list on " + owner);
            }

            RDFList hierList = hierNode.asResource().as(RDFList.class);
            List<RDFNode> levelNodes = hierList.asJavaList();
            if (levelNodes.size() < 2) {
                throw new TextIndexException(
                    "idx:facetHierarchy on " + owner + " must have at least 2 levels, got " + levelNodes.size());
            }

            List<FieldDef> levels = new ArrayList<>();
            StringBuilder dimNameBuilder = new StringBuilder();
            for (RDFNode levelNode : levelNodes) {
                if (!levelNode.isURIResource()) {
                    throw new TextIndexException(
                        "idx:facetHierarchy level must be a URI resource, got " + levelNode);
                }
                String levelIRI = levelNode.asResource().getURI();
                FieldDef fd = findFieldByIRI(fields, levelIRI);
                if (fd == null) {
                    throw new TextIndexException(
                        "idx:facetHierarchy references unknown field IRI: " + levelIRI
                        + ". All hierarchy levels must be declared on the same shape or nested block.");
                }
                levels.add(fd);
                if (dimNameBuilder.length() > 0) dimNameBuilder.append("_");
                dimNameBuilder.append(fd.getFieldName());
            }

            String dimensionName = dimNameBuilder.toString();
            hierarchies.add(new HierarchyDef(dimensionName, levels));
            log.debug("Parsed hierarchy: {} levels={}", dimensionName, levels);
        }

        return hierarchies;
    }

    private static FieldDef resolveRootFieldDef(Assembler a, Resource fieldRes,
            Map<String, FieldDef> fieldsByIRI) {
        FieldDef parsed = parseFieldDef(a, fieldRes).withNestedName(null);
        String iri = parsed.getFieldIRI().getURI();
        FieldDef existing = fieldsByIRI.get(iri);
        if (existing == null) {
            fieldsByIRI.put(iri, parsed);
            return parsed;
        }
        if (existing.isNestedScoped()) {
            throw new TextIndexException(
                "Field " + iri + " cannot be used both as a root field and as an idx:nested property. "
                + "Define separate field IRIs for parent and nested scopes.");
        }
        return existing;
    }

    private static FieldDef resolveNestedFieldDef(Assembler a, Resource fieldRes,
            Map<String, FieldDef> fieldsByIRI, String nestedName) {
        FieldDef parsed = parseFieldDef(a, fieldRes).withNestedName(nestedName);
        String iri = parsed.getFieldIRI().getURI();
        FieldDef existing = fieldsByIRI.get(iri);
        if (existing == null) {
            fieldsByIRI.put(iri, parsed);
            return parsed;
        }
        if (existing.isRootScoped()) {
            throw new TextIndexException(
                "Field " + iri + " cannot be used both as a root field and as an idx:nested property. "
                + "Define separate field IRIs for parent and nested scopes.");
        }
        if (!nestedName.equals(existing.getNestedName())) {
            throw new TextIndexException(
                "Field " + iri + " cannot be reused across multiple idx:nested scopes. "
                + "Define a separate field IRI for each nested collection.");
        }
        return existing;
    }

    private static String deriveNestedName(Path joinPath) {
        return joinPath.toString();
    }

    static List<JoinStep> extractJoinSteps(Path joinPath) {
        List<JoinStep> steps = new ArrayList<>();
        collectJoinSteps(joinPath, steps);
        if (steps.isEmpty()) {
            throw new TextIndexException(
                "idx:joinPath must contain at least one predicate step: " + joinPath);
        }
        return Collections.unmodifiableList(steps);
    }

    private static void collectJoinSteps(Path joinPath, List<JoinStep> steps) {
        if (joinPath instanceof P_Link link) {
            steps.add(new JoinStep(link.getNode(), false));
            return;
        }
        if (joinPath instanceof P_Inverse inverse) {
            Path subPath = inverse.getSubPath();
            if (subPath instanceof P_Link link) {
                steps.add(new JoinStep(link.getNode(), true));
                return;
            }
            throw new TextIndexException(
                "idx:joinPath inverse steps must target a simple predicate, got: " + joinPath);
        }
        if (joinPath instanceof P_Seq seq) {
            collectJoinSteps(seq.getLeft(), steps);
            collectJoinSteps(seq.getRight(), steps);
            return;
        }
        throw new TextIndexException(
            "idx:joinPath supports only simple predicate steps, inverse predicate steps, "
            + "and sequences composed from them: " + joinPath);
    }

    private static Set<Node> extractJoinPredicates(List<JoinStep> joinSteps) {
        Set<Node> predicates = new LinkedHashSet<>();
        for (JoinStep joinStep : joinSteps) {
            predicates.add(joinStep.getPredicate());
        }
        return Collections.unmodifiableSet(predicates);
    }

    private static FieldDef findFieldByIRI(List<FieldDef> fields, String iri) {
        for (FieldDef fd : fields) {
            if (fd.getFieldIRI().getURI().equals(iri)) {
                return fd;
            }
        }
        return null;
    }

    private static FieldDef parseFieldDef(Assembler a, Resource fieldRes) {
        // idx:fieldName (required)
        String fieldName = getRequiredString(fieldRes, IndexVocab.pFieldName,
            "Field " + fieldRes + " missing idx:fieldName");

        // idx:fieldType (optional, default TEXT)
        FieldType fieldType = FieldType.TEXT;
        Statement ftStmt = fieldRes.getProperty(IndexVocab.pFieldType);
        if (ftStmt != null) {
            fieldType = parseFieldType(ftStmt.getObject());
        }

        // idx:analyzer (optional — used for indexing; also used for querying if idx:queryAnalyzer is not set)
        Analyzer analyzer = null;
        Statement analyzerStmt = fieldRes.getProperty(IndexVocab.pAnalyzer);
        if (analyzerStmt != null && analyzerStmt.getObject().isResource()) {
            analyzer = (Analyzer) a.open(analyzerStmt.getObject().asResource());
        }

        // idx:queryAnalyzer (optional — overrides idx:analyzer at query time)
        Analyzer queryAnalyzer = null;
        Statement queryAnalyzerStmt = fieldRes.getProperty(IndexVocab.pQueryAnalyzer);
        if (queryAnalyzerStmt != null && queryAnalyzerStmt.getObject().isResource()) {
            queryAnalyzer = (Analyzer) a.open(queryAnalyzerStmt.getObject().asResource());
        }

        // Boolean flags with defaults
        boolean stored = getOptionalBoolean(fieldRes, IndexVocab.pStored, true);
        boolean indexed = getOptionalBoolean(fieldRes, IndexVocab.pIndexed, true);
        boolean facetable = getOptionalBoolean(fieldRes, IndexVocab.pFacetable, false);
        boolean sortable = getOptionalBoolean(fieldRes, IndexVocab.pSortable, false);
        boolean multiValued = getOptionalBoolean(fieldRes, IndexVocab.pMultiValued, false);
        boolean defaultSearch = getOptionalBoolean(fieldRes, IndexVocab.pDefaultSearch, false);
        boolean storeLiteralMetadata = getOptionalBoolean(fieldRes, IndexVocab.pStoreLiteralMetadata, false);

        // Parse path: from sh:path or idx:path
        Path path = extractPath(fieldRes);
        if (path == null) {
            throw new TextIndexException("Field " + fieldRes + " (" + fieldName + ") has no path/predicate");
        }

        // Extract leaf predicates for change listener compatibility
        Set<Node> predicates = extractLeafPredicates(path);

        // Use the field resource's URI as the field IRI if it's a named resource
        Node fieldIRI = fieldRes.isURIResource() ? fieldRes.asNode() : null;

        log.debug("Parsed field: {} type={} path={} facetable={}", fieldName, fieldType, path, facetable);
        return new FieldDef(fieldName, fieldType, analyzer, queryAnalyzer, stored, indexed,
                           facetable, sortable, multiValued, defaultSearch, storeLiteralMetadata,
                           predicates, path, fieldIRI);
    }

    /**
     * Extract a {@link Path} from sh:path or idx:path on a field/property-shape resource.
     * <p>
     * Supports the SHACL property path subset:
     * <ul>
     *   <li>{@code sh:path <uri>} — predicate path</li>
     *   <li>{@code sh:path [ sh:alternativePath (<uri1> <uri2> ...) ]} — alternative path</li>
     *   <li>{@code sh:path [ sh:inversePath <uri> ]} — inverse path</li>
     *   <li>{@code sh:path ( <uri1> <uri2> )} — sequence path</li>
     *   <li>Nesting of the above types</li>
     *   <li>{@code idx:path <uri>} — single predicate (convenience)</li>
     * </ul>
     */
    static Path extractPath(Resource fieldRes) {
        // Try sh:path first
        Statement pathStmt = fieldRes.getProperty(shPath);
        if (pathStmt != null) {
            Node pathNode = pathStmt.getObject().asNode();
            Graph graph = fieldRes.getModel().getGraph();
            return parseShaclPath(graph, pathNode);
        }

        // Also try idx:path as a convenience (single predicate only)
        Statement idxPathStmt = fieldRes.getProperty(IndexVocab.pPath);
        if (idxPathStmt != null) {
            RDFNode pathNode = idxPathStmt.getObject();
            if (pathNode.isURIResource()) {
                return PathFactory.pathLink(pathNode.asNode());
            }
        }

        return null;
    }

    static Path extractJoinPath(Resource nestedRes) {
        Statement pathStmt = nestedRes.getProperty(IndexVocab.pJoinPath);
        if (pathStmt == null) {
            return null;
        }
        Node pathNode = pathStmt.getObject().asNode();
        Graph graph = nestedRes.getModel().getGraph();
        return parseShaclPath(graph, pathNode);
    }

    /**
     * Parse a SHACL property path from an RDF graph into a jena-arq {@link Path} object.
     * Supports predicate, alternative, inverse, and sequence paths (and nesting of these).
     * Does not support zeroOrMore, oneOrMore, or zeroOrOne paths.
     * <p>
     * Logic adapted from {@code org.apache.jena.shacl.engine.ShaclPaths.path()} to avoid
     * a jena-shacl dependency.
     */
    static Path parseShaclPath(Graph graph, Node node) {
        // Predicate path: a URI node
        if (node.isURI() && !RDF_NIL.equals(node)) {
            return PathFactory.pathLink(node);
        }

        // Sequence path: an RDF list ( p1 p2 ... )
        if (isList(graph, node)) {
            List<Node> nodes = G.rdfList(graph, node);
            if (nodes.isEmpty()) {
                throw new TextIndexException("Empty list for path sequence");
            }
            Path path = null;
            for (Node n : nodes) {
                Path p = parseShaclPath(graph, n);
                if (path == null) {
                    path = p;
                } else {
                    path = PathFactory.pathSeq(path, p);
                }
            }
            return path;
        }

        // Blank node: inverse or alternative path
        if (node.isBlank()) {
            // sh:inversePath
            if (G.hasProperty(graph, node, SH_INVERSE_PATH)) {
                Node x = G.getOneSP(graph, node, SH_INVERSE_PATH);
                Path p = parseShaclPath(graph, x);
                return PathFactory.pathInverse(p);
            }

            // sh:alternativePath
            if (G.hasProperty(graph, node, SH_ALTERNATIVE_PATH)) {
                Node x = G.getOneSP(graph, node, SH_ALTERNATIVE_PATH);
                List<Node> nodes = G.rdfList(graph, x);
                Path path = null;
                for (Node n : nodes) {
                    Path p = parseShaclPath(graph, n);
                    if (path == null) {
                        path = p;
                    } else {
                        path = PathFactory.pathAlt(path, p);
                    }
                }
                return path;
            }
        }

        throw new TextIndexException("Unsupported SHACL path node: " + node
            + ". Only predicate, alternative, inverse, and sequence paths are supported.");
    }

    /** Check if a node is the head of an RDF list. */
    private static boolean isList(Graph graph, Node node) {
        return RDF_NIL.equals(node) || G.contains(graph, node, RDF_FIRST, null);
    }

    /**
     * Extract all leaf predicate URIs from a {@link Path} for change listener compatibility.
     * For simple paths, returns the single predicate. For complex paths, collects all
     * predicate URIs that appear anywhere in the path structure.
     */
    static Set<Node> extractLeafPredicates(Path path) {
        Set<Node> predicates = new LinkedHashSet<>();
        collectPredicates(path, predicates);
        return predicates;
    }

    private static void collectPredicates(Path path, Set<Node> predicates) {
        if (path instanceof P_Link pLink) {
            predicates.add(pLink.getNode());
        } else if (path instanceof P_Inverse pInv) {
            collectPredicates(pInv.getSubPath(), predicates);
        } else if (path instanceof P_Seq pSeq) {
            collectPredicates(pSeq.getLeft(), predicates);
            collectPredicates(pSeq.getRight(), predicates);
        } else if (path instanceof P_Alt pAlt) {
            collectPredicates(pAlt.getLeft(), predicates);
            collectPredicates(pAlt.getRight(), predicates);
        }
    }

    /**
     * Build an {@link EntityDefinition} from a {@link ShaclIndexMapping} for backward
     * compatibility with existing query methods ({@code text:query}, {@code text:facet}).
     */
    public static EntityDefinition deriveEntityDefinition(ShaclIndexMapping mapping) {
        IndexProfile firstProfile = mapping.getProfiles().get(0);
        String entityField = firstProfile.getDocIdField();

        // Find the first defaultSearch field
        String primaryField = null;
        for (IndexProfile profile : mapping.getProfiles()) {
            for (FieldDef field : profile.getFields()) {
                if (field.isDefaultSearch()) {
                    primaryField = field.getFieldName();
                    break;
                }
            }
            if (primaryField != null) break;
        }
        // Fallback: use first TEXT field
        if (primaryField == null) {
            for (FieldDef field : firstProfile.getFields()) {
                if (field.getFieldType() == FieldType.TEXT) {
                    primaryField = field.getFieldName();
                    break;
                }
            }
        }
        // Last resort: first field
        if (primaryField == null) {
            primaryField = firstProfile.getFields().get(0).getFieldName();
        }

        EntityDefinition defn = new EntityDefinition(entityField, primaryField);
        defn.setLangField("lang");
        defn.setUidField("uid");

        // Register all fields and their predicates
        for (IndexProfile profile : mapping.getProfiles()) {
            for (FieldDef field : profile.getFields()) {
                for (Node pred : field.getPredicates()) {
                    defn.set(field.getFieldName(), pred);
                }
                if (field.getAnalyzer() != null) {
                    defn.setAnalyzer(field.getFieldName(), field.getAnalyzer());
                }
                if (field.getQueryAnalyzer() != null) {
                    defn.setQueryAnalyzer(field.getFieldName(), field.getQueryAnalyzer());
                }
            }
        }

        return defn;
    }

    private static FieldType parseFieldType(RDFNode node) {
        if (!node.isResource()) {
            throw new TextIndexException("idx:fieldType must be a resource, got: " + node);
        }
        String uri = node.asResource().getURI();
        if (IndexVocab.TextField.getURI().equals(uri)) return FieldType.TEXT;
        if (IndexVocab.KeywordField.getURI().equals(uri)) return FieldType.KEYWORD;
        if (IndexVocab.IntField.getURI().equals(uri)) return FieldType.INT;
        if (IndexVocab.LongField.getURI().equals(uri)) return FieldType.LONG;
        if (IndexVocab.DoubleField.getURI().equals(uri)) return FieldType.DOUBLE;
        if (IndexVocab.DateField.getURI().equals(uri)) return FieldType.DATE;
        if (IndexVocab.DateTimeField.getURI().equals(uri)) return FieldType.DATETIME;
        if (IndexVocab.LatLonField.getURI().equals(uri)) return FieldType.LATLON;
        throw new TextIndexException("Unknown idx:fieldType: " + uri);
    }

    private static String getRequiredString(Resource r, Property p, String errorMsg) {
        Statement s = r.getProperty(p);
        if (s == null) throw new TextIndexException(errorMsg);
        return s.getObject().asLiteral().getString();
    }

    private static String getOptionalString(Resource r, Property p) {
        Statement s = r.getProperty(p);
        if (s == null) return null;
        return s.getObject().asLiteral().getString();
    }

    private static boolean getOptionalBoolean(Resource r, Property p, boolean defaultValue) {
        Statement s = r.getProperty(p);
        if (s == null) return defaultValue;
        return s.getObject().asLiteral().getBoolean();
    }
}
