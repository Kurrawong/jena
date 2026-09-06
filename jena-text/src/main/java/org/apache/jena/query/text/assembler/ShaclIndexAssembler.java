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
 * Parses SHACL-like shape definitions from RDF into a {@link ShaclIndexMapping}.
 */
public class ShaclIndexAssembler {
    private static final Logger log = LoggerFactory.getLogger(ShaclIndexAssembler.class);

    /** Field types an {@code idx:self} occurrence can feed. A focus node is a resource,
     *  so the numeric and temporal types have nothing to convert. */
    private static final Set<FieldType> SELF_BINDABLE_TYPES =
        Collections.unmodifiableSet(EnumSet.of(FieldType.KEYWORD, FieldType.TEXT));

    private static final String SH = "http://www.w3.org/ns/shacl#";
    private static final String SH_BLANK_NODE = SH + "BlankNode";
    private static final String SH_IRI = SH + "IRI";
    private static final String SH_LITERAL = SH + "Literal";
    private static final String SH_BLANK_NODE_OR_IRI = SH + "BlankNodeOrIRI";
    private static final String SH_BLANK_NODE_OR_LITERAL = SH + "BlankNodeOrLiteral";
    private static final String SH_IRI_OR_LITERAL = SH + "IRIOrLiteral";

    private static final Property shTargetClass     = ResourceFactory.createProperty(SH, "targetClass");
    private static final Property shProperty        = ResourceFactory.createProperty(SH, "property");
    private static final Property shPath            = ResourceFactory.createProperty(SH, "path");
    private static final Property shAlternativePath = ResourceFactory.createProperty(SH, "alternativePath");
    private static final Property shInversePath     = ResourceFactory.createProperty(SH, "inversePath");
    private static final Property shClass           = ResourceFactory.createProperty(SH, "class");
    private static final Property shNodeKind        = ResourceFactory.createProperty(SH, "nodeKind");
    private static final Property shDatatype        = ResourceFactory.createProperty(SH, "datatype");

    private static final Node SH_INVERSE_PATH = shInversePath.asNode();
    private static final Node SH_ALTERNATIVE_PATH = shAlternativePath.asNode();
    private static final Node RDF_FIRST = org.apache.jena.vocabulary.RDF.first.asNode();
    private static final Node RDF_NIL = org.apache.jena.vocabulary.RDF.nil.asNode();

    private ShaclIndexAssembler() {}

    private record CanonicalFieldSpec(FieldDef field, Node analyzerNode, Node queryAnalyzerNode,
                                      Node normalizerNode) {}

    public static ShaclIndexMapping parseShapes(Assembler a, Resource shapesList) {
        List<IndexProfile> profiles = new ArrayList<>();
        Map<String, CanonicalFieldSpec> canonicalFields = new LinkedHashMap<>();

        RDFList rdfList = shapesList.as(RDFList.class);
        for (RDFNode item : rdfList.asJavaList()) {
            if (!item.isResource()) {
                throw new TextIndexException("text:shapes list item is not a resource: " + item);
            }
            profiles.add(parseProfile(a, item.asResource(), canonicalFields));
        }

        if (profiles.isEmpty()) {
            throw new TextIndexException("text:shapes list is empty");
        }

        return new ShaclIndexMapping(profiles);
    }

    private static IndexProfile parseProfile(Assembler a, Resource shape,
                                             Map<String, CanonicalFieldSpec> canonicalFields) {
        Node shapeNode = shape.asNode();
        rejectLegacyShapeFieldList(shape);

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

        String docIdField = getOptionalString(shape, IndexVocab.pDocIdField);
        String discriminatorField = getOptionalString(shape, IndexVocab.pDiscriminatorField);

        Map<String, FieldDef> reachableFields = new LinkedHashMap<>();
        List<FieldOccurrence> rootOccurrences = parseRootOccurrences(a, shape, canonicalFields, reachableFields);
        List<NestedDef> nestedDefs = parseNestedDefs(a, shape, canonicalFields, reachableFields);
        List<FieldDef> rootFields = distinctFields(rootOccurrences);
        List<FieldDef> fields = new ArrayList<>(reachableFields.values());

        if (rootOccurrences.isEmpty() && nestedDefs.isEmpty()) {
            throw new TextIndexException("Shape " + shape + " has no field occurrences");
        }

        List<HierarchyDef> hierarchies = parseHierarchies(shape, rootFields);
        return new IndexProfile(shapeNode, targetClasses, docIdField, discriminatorField,
            fields, rootOccurrences, hierarchies, nestedDefs);
    }

    private static void rejectLegacyShapeFieldList(Resource shape) {
        if (shape.hasProperty(IndexVocab.pField)) {
            throw new TextIndexException(
                "Shape " + shape + " uses legacy idx:field. Root fields must now be declared as sh:property occurrences.");
        }
    }

    private static List<FieldOccurrence> parseRootOccurrences(Assembler a, Resource shape,
                                                              Map<String, CanonicalFieldSpec> canonicalFields,
                                                              Map<String, FieldDef> reachableFields) {
        List<FieldOccurrence> occurrences = new ArrayList<>();
        StmtIterator propIter = shape.listProperties(shProperty);
        while (propIter.hasNext()) {
            RDFNode propNode = propIter.next().getObject();
            if (!propNode.isResource()) {
                throw new TextIndexException("sh:property on " + shape + " must be a resource");
            }
            occurrences.add(parseOccurrence(a, propNode.asResource(), canonicalFields, reachableFields, null));
        }
        return Collections.unmodifiableList(occurrences);
    }

    private static List<NestedDef> parseNestedDefs(Assembler a, Resource shape,
                                                   Map<String, CanonicalFieldSpec> canonicalFields,
                                                   Map<String, FieldDef> reachableFields) {
        List<NestedDef> nestedDefs = new ArrayList<>();

        StmtIterator nestedIter = shape.listProperties(IndexVocab.pNested);
        while (nestedIter.hasNext()) {
            RDFNode nestedNode = nestedIter.next().getObject();
            if (!nestedNode.isResource()) {
                throw new TextIndexException("idx:nested must be a resource on " + shape);
            }

            Resource nestedRes = nestedNode.asResource();
            Path joinPath = extractJoinPath(nestedRes);

            Statement externalStmt = nestedRes.getProperty(IndexVocab.pExternalSource);
            if (externalStmt != null) {
                nestedDefs.add(parseExternalNestedDef(a, shape, nestedRes, externalStmt, joinPath,
                    canonicalFields, reachableFields));
                continue;
            }

            if (joinPath == null) {
                throw new TextIndexException(
                    "idx:nested on " + shape + " is missing idx:joinPath (or idx:externalSource)");
            }
            String nestedName = deriveNestedName(joinPath);
            List<JoinStep> joinSteps;
            try {
                joinSteps = extractJoinSteps(joinPath);
            } catch (TextIndexException ex) {
                throw new TextIndexException("Invalid idx:joinPath on " + shape + ": " + ex.getMessage(), ex);
            }

            List<FieldOccurrence> nestedOccurrences = new ArrayList<>();
            StmtIterator occurrenceIter = nestedRes.listProperties(IndexVocab.pProperty);
            while (occurrenceIter.hasNext()) {
                RDFNode occurrenceNode = occurrenceIter.next().getObject();
                if (!occurrenceNode.isResource()) {
                    throw new TextIndexException("idx:property inside idx:nested must be a resource on " + shape);
                }
                nestedOccurrences.add(parseOccurrence(
                    a, occurrenceNode.asResource(), canonicalFields, reachableFields, nestedName));
            }

            if (nestedOccurrences.isEmpty()) {
                throw new TextIndexException("idx:nested on " + shape + " must define at least one idx:property occurrence");
            }

            List<FieldDef> nestedFields = distinctFields(nestedOccurrences);
            List<HierarchyDef> nestedHierarchies = parseHierarchies(nestedRes, nestedFields);
            nestedDefs.add(new NestedDef(nestedName, joinPath, joinSteps, extractJoinPredicates(joinSteps),
                nestedOccurrences, nestedHierarchies));
            log.debug("Parsed nested definition: {} joinPath={} occurrences={}",
                nestedName, joinPath, nestedOccurrences);
        }

        return Collections.unmodifiableList(nestedDefs);
    }

    /**
     * Parse an {@code idx:nested} block whose children come from an external table
     * rather than from the graph.
     * <p>
     * Externality is not a flag — a field is external because it appears in an
     * {@code idx:column} binding, exactly as a field is nested because it appears in an
     * {@code idx:nested} block. An explicit {@code idx:external true} would be a second
     * source of truth that could contradict the bindings.
     */
    private static NestedDef parseExternalNestedDef(Assembler a, Resource shape, Resource nestedRes,
                                                    Statement externalStmt, Path joinPath,
                                                    Map<String, CanonicalFieldSpec> canonicalFields,
                                                    Map<String, FieldDef> reachableFields) {
        if (joinPath != null) {
            throw new TextIndexException(
                "idx:nested on " + shape + " has both idx:joinPath and idx:externalSource. "
                + "Children come from the graph or from rows, never both.");
        }
        if (nestedRes.hasProperty(IndexVocab.pProperty)) {
            throw new TextIndexException(
                "idx:nested on " + shape + " has both idx:externalSource and idx:property occurrences. "
                + "An external block's fields come from its idx:column bindings.");
        }
        String nestedName = getOptionalString(nestedRes, IndexVocab.pNestedName);
        if (nestedName == null || nestedName.isBlank()) {
            throw new TextIndexException(
                "idx:nested on " + shape + " with an idx:externalSource requires idx:nestedName "
                + "— there is no join path to derive a scope name from.");
        }
        if (!externalStmt.getObject().isResource()) {
            throw new TextIndexException("idx:externalSource on " + shape + " must be a resource");
        }

        ExternalSourceDef source = parseExternalSource(a, shape, externalStmt.getObject().asResource(),
            canonicalFields, reachableFields);
        List<HierarchyDef> hierarchies = parseHierarchies(nestedRes, source.getFields());
        log.debug("Parsed external nested definition: {} source={}", nestedName, source);
        return new NestedDef(nestedName, source, hierarchies);
    }

    private static ExternalSourceDef parseExternalSource(Assembler a, Resource shape, Resource sourceRes,
                                                         Map<String, CanonicalFieldSpec> canonicalFields,
                                                         Map<String, FieldDef> reachableFields) {
        ExternalFormat format = parseExternalFormat(sourceRes);
        String location = getRequiredString(sourceRes, IndexVocab.pLocation,
            "idx:externalSource on " + shape + " is missing idx:location");
        boolean headerless = getOptionalBoolean(sourceRes, IndexVocab.pHeaderless, false);
        String subjectColumn = getOptionalString(sourceRes, IndexVocab.pSubjectColumn);
        int subjectColumnIndex = getOptionalInt(sourceRes, IndexVocab.pSubjectColumnIndex, -1);
        String subjectPrefix = getOptionalString(sourceRes, IndexVocab.pSubjectPrefix);
        Character delimiter = parseDelimiter(sourceRes);
        ErrorPolicy onError = parseErrorPolicy(sourceRes);

        List<ColumnBinding> columns = new ArrayList<>();
        StmtIterator columnIter = sourceRes.listProperties(IndexVocab.pColumn);
        while (columnIter.hasNext()) {
            RDFNode columnNode = columnIter.next().getObject();
            if (!columnNode.isResource()) {
                throw new TextIndexException("idx:column on " + shape + " must be a resource");
            }
            columns.add(parseColumnBinding(a, columnNode.asResource(), canonicalFields, reachableFields));
        }
        // RDF puts no order on repeated properties; sort so config order is irrelevant
        // and logs/diagnostics are reproducible.
        columns.sort(Comparator.comparingInt(ColumnBinding::getColumnIndex)
            .thenComparing(c -> c.getColumnName() != null ? c.getColumnName() : ""));

        List<String> deltaLocations = parseDeltaLocations(shape, sourceRes);
        String opColumn = getOptionalString(sourceRes, IndexVocab.pOpColumn);

        return new ExternalSourceDef(format, location, subjectColumn, subjectColumnIndex,
            subjectPrefix, delimiter, headerless, onError, columns,
            deltaLocations, opColumn);
    }

    /**
     * Delta files, applied over the base in declaration order.
     * <p>
     * Deltas are order-sensitive — a later one may delete what an earlier one added —
     * and RDF puts no order on repeated properties. So more than one must be given as
     * an RDF list, where the order is explicit, rather than as repeated statements.
     */
    private static List<String> parseDeltaLocations(Resource shape, Resource sourceRes) {
        List<Statement> statements = sourceRes.listProperties(IndexVocab.pDelta).toList();
        if (statements.isEmpty()) {
            return Collections.emptyList();
        }
        if (statements.size() > 1) {
            throw new TextIndexException(
                "idx:externalSource on " + shape + " has " + statements.size()
                + " separate idx:delta statements. Deltas apply in order and RDF does not "
                + "order repeated properties — give them as one list: "
                + "idx:delta ( \"first.csv\" \"second.csv\" ).");
        }

        RDFNode node = statements.get(0).getObject();
        if (node.isLiteral()) {
            return List.of(node.asLiteral().getString());
        }
        if (node.canAs(RDFList.class)) {
            List<String> locations = new ArrayList<>();
            for (RDFNode item : node.as(RDFList.class).asJavaList()) {
                if (!item.isLiteral()) {
                    throw new TextIndexException(
                        "idx:delta list on " + shape + " must hold string paths, got " + item);
                }
                locations.add(item.asLiteral().getString());
            }
            return locations;
        }
        throw new TextIndexException(
            "idx:delta on " + shape + " must be a string path, or a list of them");
    }

    private static ColumnBinding parseColumnBinding(Assembler a, Resource columnRes,
                                                    Map<String, CanonicalFieldSpec> canonicalFields,
                                                    Map<String, FieldDef> reachableFields) {
        if (columnRes.hasProperty(shPath) || columnRes.hasProperty(IndexVocab.pPath)) {
            throw new TextIndexException(
                "idx:column " + columnRes + " must not carry sh:path — its value comes from the "
                + "bound column, and a path would be a second, contradicting source for the field.");
        }
        if (columnRes.hasProperty(IndexVocab.pSelf)) {
            throw new TextIndexException(
                "idx:column " + columnRes + " must not carry idx:self — an external child is a "
                + "row, not a node in the graph, so there is no focus node to bind.");
        }
        Statement fieldStmt = columnRes.getProperty(IndexVocab.pField);
        if (fieldStmt == null || !fieldStmt.getObject().isResource()) {
            throw new TextIndexException("idx:column " + columnRes + " is missing idx:field");
        }
        FieldDef field = resolveCanonicalField(a, fieldStmt.getObject().asResource(), canonicalFields);
        reachableFields.putIfAbsent(field.getFieldIRI().getURI(), field);

        String columnName = getOptionalString(columnRes, IndexVocab.pColumnName);
        int columnIndex = getOptionalInt(columnRes, IndexVocab.pColumnIndex, -1);
        if ((columnName == null) == (columnIndex < 0)) {
            throw new TextIndexException(
                "idx:column " + columnRes + " (field " + field.getFieldName() + ") must set exactly one "
                + "of idx:columnName or idx:columnIndex");
        }
        return new ColumnBinding(columnName, columnIndex, field);
    }

    private static ExternalFormat parseExternalFormat(Resource sourceRes) {
        Node format = getOptionalResourceNode(sourceRes, IndexVocab.pFormat);
        if (format == null) {
            throw new TextIndexException("idx:externalSource " + sourceRes + " is missing idx:format");
        }
        String uri = format.getURI();
        if (IndexVocab.CsvFile.getURI().equals(uri)) return ExternalFormat.CSV;
        if (IndexVocab.TsvFile.getURI().equals(uri)) return ExternalFormat.TSV;
        throw new TextIndexException("Unknown idx:format: " + uri
            + ". Supported: idx:CsvFile, idx:TsvFile.");
    }

    private static ErrorPolicy parseErrorPolicy(Resource sourceRes) {
        String onError = getOptionalString(sourceRes, IndexVocab.pOnError);
        if (onError == null) {
            return ErrorPolicy.SKIP;
        }
        return switch (onError.toLowerCase(Locale.ROOT)) {
            case "skip" -> ErrorPolicy.SKIP;
            case "fail" -> ErrorPolicy.FAIL;
            default -> throw new TextIndexException(
                "idx:onError must be \"skip\" or \"fail\", got \"" + onError + "\"");
        };
    }

    private static Character parseDelimiter(Resource sourceRes) {
        String delimiter = getOptionalString(sourceRes, IndexVocab.pDelimiter);
        if (delimiter == null) {
            return null;
        }
        if (delimiter.length() != 1) {
            throw new TextIndexException(
                "idx:delimiter must be a single character, got \"" + delimiter + "\"");
        }
        return delimiter.charAt(0);
    }

    private static List<HierarchyDef> parseHierarchies(Resource owner, List<FieldDef> fields) {
        List<HierarchyDef> hierarchies = new ArrayList<>();

        StmtIterator hierIter = owner.listProperties(IndexVocab.pFacetHierarchy);
        while (hierIter.hasNext()) {
            RDFNode hierNode = hierIter.next().getObject();
            if (!hierNode.isResource()) {
                throw new TextIndexException("idx:facetHierarchy must be an RDF list on " + owner);
            }

            // Two forms. A bare RDF list, or a resource carrying idx:levels — which may be
            // a URI, giving the hierarchy an IRI to be addressed by. The taxonomy
            // dimension name stays derived from the level names either way, because it is
            // the on-disk key and renaming it would invalidate existing indexes.
            Resource hierRes = hierNode.asResource();
            Node dimensionIRI = null;
            if (hierRes.hasProperty(IndexVocab.pLevels)) {
                RDFNode levelsNode = hierRes.getProperty(IndexVocab.pLevels).getObject();
                if (!levelsNode.isResource()) {
                    throw new TextIndexException("idx:levels must be an RDF list on " + hierRes);
                }
                if (hierRes.isURIResource()) {
                    dimensionIRI = hierRes.asNode();
                }
                hierRes = levelsNode.asResource();
            }

            RDFList hierList = hierRes.as(RDFList.class);
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
                FieldDef field = findFieldByIRI(fields, levelIRI);
                if (field == null) {
                    throw new TextIndexException(
                        "idx:facetHierarchy references unknown canonical field IRI: " + levelIRI
                        + ". Hierarchy levels must reference fields populated in the same scope.");
                }
                levels.add(field);
                if (dimNameBuilder.length() > 0) {
                    dimNameBuilder.append("_");
                }
                dimNameBuilder.append(field.getFieldName());
            }

            String dimensionName = dimNameBuilder.toString();
            hierarchies.add(new HierarchyDef(dimensionName, levels, dimensionIRI));
            log.debug("Parsed hierarchy: {} levels={}", dimensionName, levels);
        }

        return Collections.unmodifiableList(hierarchies);
    }

    private static FieldOccurrence parseOccurrence(Assembler a, Resource occurrenceRes,
                                                   Map<String, CanonicalFieldSpec> canonicalFields,
                                                   Map<String, FieldDef> reachableFields,
                                                   String nestedName) {
        rejectCanonicalFieldPropertiesOnOccurrence(occurrenceRes);

        Statement fieldStmt = occurrenceRes.getProperty(IndexVocab.pField);
        if (fieldStmt == null || !fieldStmt.getObject().isResource()) {
            throw new TextIndexException("Field occurrence " + occurrenceRes + " is missing idx:field");
        }

        FieldDef field = resolveCanonicalField(a, fieldStmt.getObject().asResource(), canonicalFields);
        reachableFields.putIfAbsent(field.getFieldIRI().getURI(), field);

        Path path = extractOccurrencePath(occurrenceRes);
        boolean self = isSelfBound(occurrenceRes);
        if (self && path != null) {
            throw new TextIndexException("Field occurrence " + occurrenceRes
                + " has both idx:self and sh:path. A field is fed by the focus node or by a "
                + "path from it, never both.");
        }
        if (!self && path == null) {
            throw new TextIndexException("Field occurrence " + occurrenceRes
                + " is missing sh:path (or idx:self)");
        }

        Node requiredClass = getOptionalResourceNode(occurrenceRes, shClass);
        NodeKindConstraint nodeKindConstraint = getOptionalNodeKindConstraint(occurrenceRes);
        Node datatype = getOptionalResourceNode(occurrenceRes, shDatatype);

        if (self) {
            if (!SELF_BINDABLE_TYPES.contains(field.getFieldType())) {
                throw new TextIndexException("Field occurrence " + occurrenceRes
                    + " binds the focus node with idx:self into field "
                    + field.getFieldIRI().getURI() + ", which has type " + field.getFieldType()
                    + ". A focus node is a resource, so a self-bound field must be "
                    + SELF_BINDABLE_TYPES + ".");
            }
            return FieldOccurrence.self(field, requiredClass, nodeKindConstraint, datatype, nestedName);
        }

        List<List<JoinStep>> pathVariants = extractPathVariants(path);
        Set<Node> predicates = extractLeafPredicates(path);

        return new FieldOccurrence(field, path, pathVariants, predicates,
            requiredClass, nodeKindConstraint, datatype, nestedName);
    }

    /**
     * {@code idx:self} is a marker, so it is only ever written {@code true}. An explicit
     * {@code false} is rejected rather than read as "no self binding": the author meant
     * something by writing it, and silently indexing nothing is the worst reading.
     */
    private static boolean isSelfBound(Resource occurrenceRes) {
        Statement stmt = occurrenceRes.getProperty(IndexVocab.pSelf);
        if (stmt == null) {
            return false;
        }
        if (!stmt.getObject().isLiteral()) {
            throw new TextIndexException("idx:self on " + occurrenceRes + " must be true");
        }
        if (!stmt.getObject().asLiteral().getBoolean()) {
            throw new TextIndexException("idx:self on " + occurrenceRes
                + " is false. Omit idx:self and give the occurrence an sh:path instead.");
        }
        return true;
    }

    private static void rejectCanonicalFieldPropertiesOnOccurrence(Resource occurrenceRes) {
        List<Property> forbidden = List.of(
            IndexVocab.pFieldName,
            IndexVocab.pFieldType,
            IndexVocab.pAnalyzer,
            IndexVocab.pQueryAnalyzer,
            IndexVocab.pStored,
            IndexVocab.pIndexed,
            IndexVocab.pFacetable,
            IndexVocab.pSortable,
            IndexVocab.pMultiValued,
            IndexVocab.pDefaultSearch,
            IndexVocab.pStoreLiteralMetadata,
            IndexVocab.pJoinPath,
            IndexVocab.pNested,
            IndexVocab.pPath
        );
        for (Property property : forbidden) {
            if (occurrenceRes.hasProperty(property)) {
                throw new TextIndexException(
                    "Field occurrence " + occurrenceRes + " mixes occurrence data with canonical field metadata via " + property);
            }
        }
    }

    private static FieldDef resolveCanonicalField(Assembler a, Resource fieldRes,
                                                  Map<String, CanonicalFieldSpec> canonicalFields) {
        String directKey = fieldRes.isURIResource() ? fieldRes.getURI() : null;
        if (directKey != null) {
            CanonicalFieldSpec existing = canonicalFields.get(directKey);
            if (existing != null) {
                return existing.field();
            }
        }

        CanonicalFieldSpec parsed = parseCanonicalField(a, fieldRes);
        String iri = parsed.field().getFieldIRI().getURI();
        CanonicalFieldSpec existing = canonicalFields.get(iri);
        if (existing == null) {
            canonicalFields.put(iri, parsed);
            return parsed.field();
        }
        validateSameCanonicalField(existing, parsed);
        return existing.field();
    }

    private static void validateSameCanonicalField(CanonicalFieldSpec existing, CanonicalFieldSpec parsed) {
        FieldDef existingField = existing.field();
        FieldDef parsedField = parsed.field();
        if (!Objects.equals(existingField.getFieldName(), parsedField.getFieldName())
                || existingField.getFieldType() != parsedField.getFieldType()
                || existingField.isStored() != parsedField.isStored()
                || existingField.isIndexed() != parsedField.isIndexed()
                || existingField.isFacetable() != parsedField.isFacetable()
                || existingField.isSortable() != parsedField.isSortable()
                || existingField.isMultiValued() != parsedField.isMultiValued()
                || existingField.isDefaultSearch() != parsedField.isDefaultSearch()
                || existingField.isStoreLiteralMetadata() != parsedField.isStoreLiteralMetadata()
                || !Objects.equals(existing.analyzerNode(), parsed.analyzerNode())
                || !Objects.equals(existing.queryAnalyzerNode(), parsed.queryAnalyzerNode())
                || !Objects.equals(existing.normalizerNode(), parsed.normalizerNode())) {
            throw new TextIndexException(
                "Canonical field " + existingField.getFieldIRI().getURI()
                + " is defined inconsistently across occurrences");
        }
    }

    private static CanonicalFieldSpec parseCanonicalField(Assembler a, Resource fieldRes) {
        rejectOccurrencePropertiesOnCanonicalField(fieldRes);

        String fieldName = getRequiredString(fieldRes, IndexVocab.pFieldName,
            "Canonical field " + fieldRes + " is missing idx:fieldName");

        FieldType fieldType = FieldType.TEXT;
        Statement ftStmt = fieldRes.getProperty(IndexVocab.pFieldType);
        if (ftStmt != null) {
            fieldType = parseFieldType(ftStmt.getObject());
        }

        Analyzer analyzer = null;
        Statement analyzerStmt = fieldRes.getProperty(IndexVocab.pAnalyzer);
        if (analyzerStmt != null && analyzerStmt.getObject().isResource()) {
            analyzer = (Analyzer) a.open(analyzerStmt.getObject().asResource());
        }
        Node analyzerNode = analyzerStmt != null && analyzerStmt.getObject().isResource()
            ? analyzerStmt.getObject().asNode()
            : null;

        Analyzer queryAnalyzer = null;
        Statement queryAnalyzerStmt = fieldRes.getProperty(IndexVocab.pQueryAnalyzer);
        if (queryAnalyzerStmt != null && queryAnalyzerStmt.getObject().isResource()) {
            queryAnalyzer = (Analyzer) a.open(queryAnalyzerStmt.getObject().asResource());
        }
        Node queryAnalyzerNode = queryAnalyzerStmt != null && queryAnalyzerStmt.getObject().isResource()
            ? queryAnalyzerStmt.getObject().asNode()
            : null;

        // idx:normalizer — an analyzer applied to a KEYWORD field's indexed term + sort key.
        // Only valid on KEYWORD fields; a config error on anything else (fail fast).
        Analyzer normalizer = null;
        Node normalizerNode = null;
        Statement normalizerStmt = fieldRes.getProperty(IndexVocab.pNormalizer);
        if (normalizerStmt != null) {
            if (fieldType != FieldType.KEYWORD) {
                throw new TextIndexException(
                    "idx:normalizer is only valid on KEYWORD fields; field '" + fieldName
                    + "' has type " + fieldType + ".");
            }
            if (!normalizerStmt.getObject().isResource()) {
                throw new TextIndexException(
                    "idx:normalizer on field '" + fieldName + "' must reference an analyzer resource.");
            }
            normalizer = (Analyzer) a.open(normalizerStmt.getObject().asResource());
            normalizerNode = normalizerStmt.getObject().asNode();
        }

        boolean stored = getOptionalBoolean(fieldRes, IndexVocab.pStored, true);
        boolean indexed = getOptionalBoolean(fieldRes, IndexVocab.pIndexed, true);
        boolean facetable = getOptionalBoolean(fieldRes, IndexVocab.pFacetable, false);
        boolean sortable = getOptionalBoolean(fieldRes, IndexVocab.pSortable, false);
        boolean multiValued = getOptionalBoolean(fieldRes, IndexVocab.pMultiValued, false);
        boolean defaultSearch = getOptionalBoolean(fieldRes, IndexVocab.pDefaultSearch, false);
        boolean storeLiteralMetadata = getOptionalBoolean(fieldRes, IndexVocab.pStoreLiteralMetadata, false);

        Node fieldIRI = fieldRes.isURIResource() ? fieldRes.asNode() : null;
        FieldDef fieldDef = new FieldDef(fieldName, fieldType, analyzer, queryAnalyzer,
            stored, indexed, facetable, sortable, multiValued, defaultSearch,
            storeLiteralMetadata, fieldIRI, normalizer);
        log.debug("Parsed canonical field: {} type={} facetable={}", fieldDef.getFieldName(),
            fieldDef.getFieldType(), fieldDef.isFacetable());
        return new CanonicalFieldSpec(fieldDef, analyzerNode, queryAnalyzerNode, normalizerNode);
    }

    private static void rejectOccurrencePropertiesOnCanonicalField(Resource fieldRes) {
        if (fieldRes.hasProperty(shPath)
                || fieldRes.hasProperty(IndexVocab.pPath)
                || fieldRes.hasProperty(shClass)
                || fieldRes.hasProperty(shNodeKind)
                || fieldRes.hasProperty(shDatatype)
                || fieldRes.hasProperty(IndexVocab.pField)) {
            throw new TextIndexException(
                "Canonical field " + fieldRes + " must not carry occurrence properties such as sh:path or idx:field");
        }
    }

    private static String deriveNestedName(Path joinPath) {
        return joinPath.toString();
    }

    static List<JoinStep> extractJoinSteps(Path joinPath) {
        List<JoinStep> steps = new ArrayList<>();
        collectJoinSteps(joinPath, steps);
        if (steps.isEmpty()) {
            throw new TextIndexException("idx:joinPath must contain at least one predicate step: " + joinPath);
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

    public static List<List<JoinStep>> extractPathVariants(Path path) {
        List<List<JoinStep>> variants = collectPathVariants(path);
        if (variants.isEmpty()) {
            throw new TextIndexException("sh:path must contain at least one predicate step: " + path);
        }
        return Collections.unmodifiableList(variants);
    }

    private static List<List<JoinStep>> collectPathVariants(Path path) {
        if (path instanceof P_Link link) {
            return List.of(List.of(new JoinStep(link.getNode(), false)));
        }
        if (path instanceof P_Inverse inverse) {
            return invertVariants(collectPathVariants(inverse.getSubPath()));
        }
        if (path instanceof P_Seq seq) {
            return combineVariants(collectPathVariants(seq.getLeft()), collectPathVariants(seq.getRight()));
        }
        if (path instanceof P_Alt alt) {
            List<List<JoinStep>> variants = new ArrayList<>();
            variants.addAll(collectPathVariants(alt.getLeft()));
            variants.addAll(collectPathVariants(alt.getRight()));
            return variants;
        }
        throw new TextIndexException(
            "sh:path supports only simple predicate steps, inverse predicate steps, "
            + "sequences, and alternatives composed from them: " + path);
    }

    private static List<List<JoinStep>> invertVariants(List<List<JoinStep>> variants) {
        List<List<JoinStep>> inverted = new ArrayList<>();
        for (List<JoinStep> variant : variants) {
            List<JoinStep> copy = new ArrayList<>(variant.size());
            for (int i = variant.size() - 1; i >= 0; i--) {
                JoinStep step = variant.get(i);
                copy.add(new JoinStep(step.getPredicate(), !step.isInverse()));
            }
            inverted.add(Collections.unmodifiableList(copy));
        }
        return Collections.unmodifiableList(inverted);
    }

    private static List<List<JoinStep>> combineVariants(List<List<JoinStep>> left, List<List<JoinStep>> right) {
        List<List<JoinStep>> combined = new ArrayList<>();
        for (List<JoinStep> lhs : left) {
            for (List<JoinStep> rhs : right) {
                List<JoinStep> seq = new ArrayList<>(lhs.size() + rhs.size());
                seq.addAll(lhs);
                seq.addAll(rhs);
                combined.add(Collections.unmodifiableList(seq));
            }
        }
        return Collections.unmodifiableList(combined);
    }

    private static Set<Node> extractJoinPredicates(List<JoinStep> joinSteps) {
        Set<Node> predicates = new LinkedHashSet<>();
        for (JoinStep step : joinSteps) {
            predicates.add(step.getPredicate());
        }
        return Collections.unmodifiableSet(predicates);
    }

    private static FieldDef findFieldByIRI(List<FieldDef> fields, String iri) {
        for (FieldDef field : fields) {
            if (field.getFieldIRI().getURI().equals(iri)) {
                return field;
            }
        }
        return null;
    }

    private static List<FieldDef> distinctFields(List<FieldOccurrence> occurrences) {
        Map<String, FieldDef> fieldsByIri = new LinkedHashMap<>();
        for (FieldOccurrence occurrence : occurrences) {
            FieldDef field = occurrence.getField();
            fieldsByIri.putIfAbsent(field.getFieldIRI().getURI(), field);
        }
        return new ArrayList<>(fieldsByIri.values());
    }

    static Path extractOccurrencePath(Resource occurrenceRes) {
        Statement pathStmt = occurrenceRes.getProperty(shPath);
        if (pathStmt == null) {
            if (occurrenceRes.hasProperty(IndexVocab.pPath)) {
                throw new TextIndexException(
                    "Field occurrence " + occurrenceRes + " uses legacy idx:path. Use sh:path instead.");
            }
            return null;
        }
        Node pathNode = pathStmt.getObject().asNode();
        Graph graph = occurrenceRes.getModel().getGraph();
        return parseShaclPath(graph, pathNode);
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

    static Path parseShaclPath(Graph graph, Node node) {
        if (node.isURI() && !RDF_NIL.equals(node)) {
            return PathFactory.pathLink(node);
        }

        if (isList(graph, node)) {
            List<Node> nodes = G.rdfList(graph, node);
            if (nodes.isEmpty()) {
                throw new TextIndexException("Empty list for path sequence");
            }
            Path path = null;
            for (Node n : nodes) {
                Path next = parseShaclPath(graph, n);
                path = path == null ? next : PathFactory.pathSeq(path, next);
            }
            return path;
        }

        if (node.isBlank()) {
            if (G.hasProperty(graph, node, SH_INVERSE_PATH)) {
                Node subPath = G.getOneSP(graph, node, SH_INVERSE_PATH);
                return PathFactory.pathInverse(parseShaclPath(graph, subPath));
            }

            if (G.hasProperty(graph, node, SH_ALTERNATIVE_PATH)) {
                Node pathList = G.getOneSP(graph, node, SH_ALTERNATIVE_PATH);
                List<Node> nodes = G.rdfList(graph, pathList);
                Path path = null;
                for (Node n : nodes) {
                    Path next = parseShaclPath(graph, n);
                    path = path == null ? next : PathFactory.pathAlt(path, next);
                }
                return path;
            }
        }

        throw new TextIndexException("Unsupported SHACL path node: " + node
            + ". Only predicate, alternative, inverse, and sequence paths are supported.");
    }

    private static boolean isList(Graph graph, Node node) {
        return RDF_NIL.equals(node) || G.contains(graph, node, RDF_FIRST, null);
    }

    static Set<Node> extractLeafPredicates(Path path) {
        Set<Node> predicates = new LinkedHashSet<>();
        collectPredicates(path, predicates);
        return Collections.unmodifiableSet(predicates);
    }

    private static void collectPredicates(Path path, Set<Node> predicates) {
        if (path instanceof P_Link link) {
            predicates.add(link.getNode());
        } else if (path instanceof P_Inverse inverse) {
            collectPredicates(inverse.getSubPath(), predicates);
        } else if (path instanceof P_Seq seq) {
            collectPredicates(seq.getLeft(), predicates);
            collectPredicates(seq.getRight(), predicates);
        } else if (path instanceof P_Alt alt) {
            collectPredicates(alt.getLeft(), predicates);
            collectPredicates(alt.getRight(), predicates);
        }
    }

    public static EntityDefinition deriveEntityDefinition(ShaclIndexMapping mapping) {
        IndexProfile firstProfile = mapping.getProfiles().get(0);
        String entityField = firstProfile.getDocIdField();

        String primaryField = null;
        for (IndexProfile profile : mapping.getProfiles()) {
            for (FieldDef field : profile.getFields()) {
                if (field.isDefaultSearch()) {
                    primaryField = field.getFieldName();
                    break;
                }
            }
            if (primaryField != null) {
                break;
            }
        }
        if (primaryField == null) {
            for (FieldDef field : firstProfile.getFields()) {
                if (field.getFieldType() == FieldType.TEXT) {
                    primaryField = field.getFieldName();
                    break;
                }
            }
        }
        if (primaryField == null) {
            primaryField = firstProfile.getFields().get(0).getFieldName();
        }

        EntityDefinition defn = new EntityDefinition(entityField, primaryField);
        defn.setLangField("lang");
        defn.setUidField("uid");

        for (IndexProfile profile : mapping.getProfiles()) {
            for (FieldOccurrence occurrence : profile.getRootOccurrences()) {
                registerOccurrence(defn, occurrence);
            }
            for (NestedDef nestedDef : profile.getNestedDefs()) {
                for (FieldOccurrence occurrence : nestedDef.getOccurrences()) {
                    registerOccurrence(defn, occurrence);
                }
            }
            for (FieldDef field : profile.getFields()) {
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

    private static void registerOccurrence(EntityDefinition defn, FieldOccurrence occurrence) {
        for (Node predicate : occurrence.getPredicates()) {
            defn.set(occurrence.getField().getFieldName(), predicate);
        }
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
        if (IndexVocab.TemporalField.getURI().equals(uri)) return FieldType.TEMPORAL;
        // Deprecated aliases — both still map to FieldType.TEMPORAL for backwards compat.
        if (IndexVocab.DateField.getURI().equals(uri)) return FieldType.TEMPORAL;
        if (IndexVocab.DateTimeField.getURI().equals(uri)) return FieldType.TEMPORAL;
        if (IndexVocab.LatLonField.getURI().equals(uri)) return FieldType.LATLON;
        throw new TextIndexException("Unknown idx:fieldType: " + uri);
    }

    private static String getRequiredString(Resource resource, Property property, String errorMsg) {
        Statement stmt = resource.getProperty(property);
        if (stmt == null) {
            throw new TextIndexException(errorMsg);
        }
        return stmt.getObject().asLiteral().getString();
    }

    private static String getOptionalString(Resource resource, Property property) {
        Statement stmt = resource.getProperty(property);
        if (stmt == null) {
            return null;
        }
        return stmt.getObject().asLiteral().getString();
    }

    private static boolean getOptionalBoolean(Resource resource, Property property, boolean defaultValue) {
        Statement stmt = resource.getProperty(property);
        if (stmt == null) {
            return defaultValue;
        }
        return stmt.getObject().asLiteral().getBoolean();
    }

    private static int getOptionalInt(Resource resource, Property property, int defaultValue) {
        Statement stmt = resource.getProperty(property);
        if (stmt == null) {
            return defaultValue;
        }
        return stmt.getObject().asLiteral().getInt();
    }

    private static Node getOptionalResourceNode(Resource resource, Property property) {
        Statement stmt = resource.getProperty(property);
        if (stmt == null) {
            return null;
        }
        if (!stmt.getObject().isResource()) {
            throw new TextIndexException(property + " on " + resource + " must be a resource");
        }
        return stmt.getObject().asNode();
    }

    private static NodeKindConstraint getOptionalNodeKindConstraint(Resource occurrenceRes) {
        Node nodeKind = getOptionalResourceNode(occurrenceRes, shNodeKind);
        if (nodeKind == null) {
            return null;
        }
        String uri = nodeKind.getURI();
        if (SH_BLANK_NODE.equals(uri)) return NodeKindConstraint.BLANK_NODE;
        if (SH_IRI.equals(uri)) return NodeKindConstraint.IRI;
        if (SH_LITERAL.equals(uri)) return NodeKindConstraint.LITERAL;
        if (SH_BLANK_NODE_OR_IRI.equals(uri)) return NodeKindConstraint.BLANK_NODE_OR_IRI;
        if (SH_BLANK_NODE_OR_LITERAL.equals(uri)) return NodeKindConstraint.BLANK_NODE_OR_LITERAL;
        if (SH_IRI_OR_LITERAL.equals(uri)) return NodeKindConstraint.IRI_OR_LITERAL;
        throw new TextIndexException("Unsupported sh:nodeKind on " + occurrenceRes + ": " + nodeKind);
    }
}
