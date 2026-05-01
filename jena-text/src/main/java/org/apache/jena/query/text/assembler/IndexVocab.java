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

    // Shape-level properties
    public static final Property pField             = Vocab.property(NS, "field");
    public static final Property pDocIdField        = Vocab.property(NS, "docIdField");
    public static final Property pDiscriminatorField = Vocab.property(NS, "discriminatorField");
    public static final Property pNested            = Vocab.property(NS, "nested");

    // Field-level properties
    public static final Property pFieldName     = Vocab.property(NS, "fieldName");
    public static final Property pFieldType     = Vocab.property(NS, "fieldType");
    public static final Property pAnalyzer      = Vocab.property(NS, "analyzer");
    public static final Property pQueryAnalyzer = Vocab.property(NS, "queryAnalyzer");
    public static final Property pStored        = Vocab.property(NS, "stored");
    public static final Property pIndexed       = Vocab.property(NS, "indexed");
    public static final Property pFacetable     = Vocab.property(NS, "facetable");
    public static final Property pSortable      = Vocab.property(NS, "sortable");
    public static final Property pMultiValued   = Vocab.property(NS, "multiValued");
    public static final Property pDefaultSearch = Vocab.property(NS, "defaultSearch");
    public static final Property pStoreLiteralMetadata = Vocab.property(NS, "storeLiteralMetadata");
    public static final Property pPath          = Vocab.property(NS, "path");
    public static final Property pJoinPath      = Vocab.property(NS, "joinPath");
    public static final Property pProperty      = Vocab.property(NS, "property");

    // Hierarchical facet properties
    public static final Property pFacetHierarchy = Vocab.property(NS, "facetHierarchy");
}
