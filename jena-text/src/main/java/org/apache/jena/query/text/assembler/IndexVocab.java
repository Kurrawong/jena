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

import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.system.Vocab;

/**
 * Vocabulary constants for the {@code idx:} namespace used in SHACL-driven index profiles.
 * <p>
 * Namespace: {@code urn:jena:lucene:index#}
 */
public class IndexVocab {
    public static final String NS = "urn:jena:lucene:index#";

    // Property function URIs
    public static final String pfQuery = NS + "query";
    public static final String pfFacet = NS + "facet";
    public static final String pfMatch = NS + "match";
    /**
     * Deliberately not {@code NS + "nested"}: that IRI is already the {@link #pNested}
     * configuration predicate, and registering a property function on it would intercept
     * any query pattern reading an {@code idx:nested} block out of a config graph.
     */
    public static final String pfNestedMatch = NS + "nestedMatch";

    // Types
    public static final Resource IndexProfile   = Vocab.resource(NS, "IndexProfile");
    public static final Resource Field          = Vocab.resource(NS, "Field");

    // Field type resources
    public static final Resource TextField      = Vocab.resource(NS, "TextField");
    public static final Resource KeywordField   = Vocab.resource(NS, "KeywordField");
    public static final Resource IntField       = Vocab.resource(NS, "IntField");
    public static final Resource LongField      = Vocab.resource(NS, "LongField");
    public static final Resource DoubleField    = Vocab.resource(NS, "DoubleField");
    public static final Resource TemporalField  = Vocab.resource(NS, "TemporalField");
    /** @deprecated alias for {@link #TemporalField}; both resolve to {@code FieldType.TEMPORAL}. */
    @Deprecated public static final Resource DateField      = Vocab.resource(NS, "DateField");
    /** @deprecated alias for {@link #TemporalField}; both resolve to {@code FieldType.TEMPORAL}. */
    @Deprecated public static final Resource DateTimeField  = Vocab.resource(NS, "DateTimeField");
    public static final Resource LatLonField    = Vocab.resource(NS, "LatLonField");
    public static final Resource VectorField    = Vocab.resource(NS, "VectorField");

    // Vector similarity functions (idx:similarity)
    public static final Resource Cosine               = Vocab.resource(NS, "Cosine");
    public static final Resource DotProduct           = Vocab.resource(NS, "DotProduct");
    public static final Resource Euclidean            = Vocab.resource(NS, "Euclidean");
    public static final Resource MaximumInnerProduct  = Vocab.resource(NS, "MaximumInnerProduct");

    // External source formats (idx:format)
    public static final Resource CsvFile        = Vocab.resource(NS, "CsvFile");
    public static final Resource TsvFile        = Vocab.resource(NS, "TsvFile");

    // Shape-level properties
    public static final Property pField             = Vocab.property(NS, "field");
    public static final Property pDocIdField        = Vocab.property(NS, "docIdField");
    public static final Property pDiscriminatorField = Vocab.property(NS, "discriminatorField");
    public static final Property pNested            = Vocab.property(NS, "nested");
    /**
     * Attaches a VECTOR field to a shape. Separate from the {@code sh:property} occurrence
     * mechanism because a vector field has no {@code sh:path}: it is derived from the
     * values of the fields named in its {@code idx:embeddingSource}, not extracted from
     * the graph.
     */
    public static final Property pVectorField       = Vocab.property(NS, "vectorField");

    // Field-level properties
    public static final Property pFieldName     = Vocab.property(NS, "fieldName");
    public static final Property pFieldType     = Vocab.property(NS, "fieldType");
    public static final Property pAnalyzer      = Vocab.property(NS, "analyzer");
    public static final Property pQueryAnalyzer = Vocab.property(NS, "queryAnalyzer");
    public static final Property pNormalizer    = Vocab.property(NS, "normalizer");
    public static final Property pStored        = Vocab.property(NS, "stored");
    public static final Property pIndexed       = Vocab.property(NS, "indexed");
    public static final Property pFacetable     = Vocab.property(NS, "facetable");
    public static final Property pSortable      = Vocab.property(NS, "sortable");
    public static final Property pMultiValued   = Vocab.property(NS, "multiValued");
    public static final Property pDefaultSearch = Vocab.property(NS, "defaultSearch");
    public static final Property pStoreLiteralMetadata = Vocab.property(NS, "storeLiteralMetadata");
    public static final Property pPath          = Vocab.property(NS, "path");
    public static final Property pSelf          = Vocab.property(NS, "self");
    public static final Property pJoinPath      = Vocab.property(NS, "joinPath");
    public static final Property pProperty      = Vocab.property(NS, "property");

    // Vector field properties (on an idx:VectorField)
    /** Component count of the vector. Fixed at index time by Lucene. */
    public static final Property pDimension       = Vocab.property(NS, "dimension");
    /** Vector similarity function; defaults to {@link #Cosine}. */
    public static final Property pSimilarity      = Vocab.property(NS, "similarity");
    /** RDF list of field IRIs whose values are verbalised and embedded. */
    public static final Property pEmbeddingSource = Vocab.property(NS, "embeddingSource");

    // Index-level embedding configuration (idx:embedding block)
    public static final Property pEmbedding     = Vocab.property(NS, "embedding");
    /** Provider name, matched against EmbeddingProviderFactory.name(), e.g. "jlama". */
    public static final Property pProvider      = Vocab.property(NS, "provider");
    /** Model identifier, interpreted by the provider. */
    public static final Property pModel         = Vocab.property(NS, "model");
    /** Local directory holding or caching model files. */
    public static final Property pModelPath     = Vocab.property(NS, "modelPath");
    /** Repeatable provider-specific option. */
    public static final Property pOption        = Vocab.property(NS, "option");
    public static final Property pOptionName    = Vocab.property(NS, "optionName");
    public static final Property pOptionValue   = Vocab.property(NS, "optionValue");
    /** Neighbours a KNN search retrieves. One value per index; defaults to 100. */
    public static final Property pKnnTopK       = Vocab.property(NS, "knnTopK");

    // Hierarchical facet properties
    public static final Property pFacetHierarchy = Vocab.property(NS, "facetHierarchy");

    // External source properties (inside an idx:nested block)
    public static final Property pNestedName        = Vocab.property(NS, "nestedName");
    public static final Property pExternalSource    = Vocab.property(NS, "externalSource");
    public static final Property pFormat            = Vocab.property(NS, "format");
    public static final Property pLocation          = Vocab.property(NS, "location");
    public static final Property pSubjectColumn     = Vocab.property(NS, "subjectColumn");
    public static final Property pSubjectColumnIndex = Vocab.property(NS, "subjectColumnIndex");
    public static final Property pSubjectPrefix     = Vocab.property(NS, "subjectPrefix");
    public static final Property pDelimiter         = Vocab.property(NS, "delimiter");
    public static final Property pHeaderless        = Vocab.property(NS, "headerless");
    public static final Property pOnError           = Vocab.property(NS, "onError");
    public static final Property pColumn            = Vocab.property(NS, "column");
    public static final Property pColumnName        = Vocab.property(NS, "columnName");
    public static final Property pColumnIndex       = Vocab.property(NS, "columnIndex");
    /** Delta file applied over the base at build time. Repeatable; applied in order. */
    public static final Property pDelta             = Vocab.property(NS, "delta");
    /** Column holding ADD/DELETE in a delta file. Defaults to "op". */
    public static final Property pOpColumn          = Vocab.property(NS, "opColumn");
}
