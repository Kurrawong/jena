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

import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;

import org.apache.jena.query.text.cql.TestCqlParser;
import org.apache.jena.query.text.cql.TestCqlToLuceneCompiler;

import org.apache.jena.query.text.assembler.*;
import org.apache.jena.query.text.changes.TestDatasetMonitor;

@Suite
@SelectClasses({

    TestBuildTextDataset.class
    , TestDatasetMonitor.class

    , TestDatasetWithLuceneTextIndex.class
    , TestDatasetWithLuceneMultilingualTextIndex.class
    , TestDatasetWithLuceneTextIndexWithLangField.class
    , TestDatasetWithLuceneGraphTextIndex.class
    , TestDatasetWithLuceneTextIndexDeletionSupport.class
    , TestDatasetWithLuceneStoredLiterals.class

    , TestTextNonTxn.class
    , TestTextTxn.class
    , TestTextNonTxnTDB1.class
    , TestTextTxnTDB.class

    , TestEntityMapAssembler.class
    , TestTextDatasetAssembler.class
    , TestTextIndexLuceneAssembler.class
    , TestDatasetWithSimpleAnalyzer.class
    , TestDatasetWithStandardAnalyzer.class
    , TestDatasetWithKeywordAnalyzer.class
    , TestDatasetWithLowerCaseKeywordAnalyzer.class
//    , TestLuceneWithMultipleThreads.class
    , TestDatasetWithLocalizedAnalyzer.class
    , TestDatasetWithConfigurableAnalyzer.class
    , TestDatasetWithAnalyzingQueryParser.class
    , TestDatasetWithComplexPhraseQueryParser.class
    , TestDatasetWithSurroundQueryParser.class
    , TestGenericAnalyzerAssembler.class
    , TestTextGraphIndexExtra.class
    , TestTextGraphIndexExtra2.class
    , TestTextHighlighting.class
    , TestTextDefineAnalyzers.class
    , TestTextMultilingualEnhancements.class
    , TestTextMultipleProplistNotWorking.class

    , TestPropListsAssembler.class
    , TestTextPropLists.class
    , TestTextPropLists02.class
    , TestTextMultilingualEnhancements02.class

    , TestNativeFacetCounts.class
    , TestVectorSearch.class
    , TestVectorAssembler.class
    , TestVectorDimensionMismatch.class
    , TestTextFacetPF.class
    , TestSearchExecution.class

    // SHACL entity-per-document tests
    , TestShaclIndexMapping.class
    , TestShaclConfigFingerprint.class
    , TestShaclIndexStamp.class
    , TestShaclDocumentBuilding.class
    , TestShaclTextDocProducer.class
    , TestShaclNoPFDuringIndexing.class
    , TestShaclAssembler.class
    , TestShaclEntityPerDocument.class
    , TestPerFieldQueryAnalyzer.class
    , TestShaclPathSupport.class
    , TestTextQueryPFFilters.class
    , TestShaclBulkIndexer.class
    , TestShaclBulkIndexerMultiIndex.class
    , TestFacetCachingInvalidation.class
    , TestBlockJoinIndexModel.class

    // External content indexing: nested children built from a CSV/tabular source
    , TestExternalContentIndexing.class
    , TestGswaMeasurementCsv.class
    , org.apache.jena.query.text.external.TestCsvRowSource.class
    , org.apache.jena.query.text.external.TestExternalDeltaSource.class
    , org.apache.jena.query.text.external.TestSortingRowSource.class
    , org.apache.jena.query.text.assembler.TestExternalSourceAssembler.class
    , org.apache.jena.query.text.assembler.TestTaxonomyDirectoryAssembler.class

    // CQL and multi-index tests
    , TestCqlParser.class
    , TestCqlToLuceneCompiler.class
    , TestSortSpec.class
    // Nested sort selector: order by a child value where the co-located discriminator = X
    , TestNestedSortSelector.class
    , TestTextIndexRegistry.class

    // Spatial filtering
    , TestSpatialFiltering.class

    // Multi-valued field support
    , TestShaclLucQueryRawValueOnMultiValuedField.class

    // KEYWORD raw-byte sort + verbatim exact-match baseline (keyword-normalizer proposal)
    , TestKeywordRawSortAndExactMatch.class
    // idx:normalizer feature: case-insensitive KEYWORD sort + exact match
    , TestKeywordNormalizer.class
    // idx:normalizer on a multi-valued KEYWORD field (SortedSet path)
    , TestKeywordNormalizerMultiValued.class
    // twin-field pattern: same predicate -> TEXT search field + normalized KEYWORD sort field
    , TestKeywordNormalizerTwinField.class

    // luc:match property function
    , TestTextMatchPF.class

    // luc:nestedMatch property function
    , TestNestedMatchProjection.class

    // Shared field occurrences / fan-in
    , TestSharedFieldOccurrences.class

    // Hierarchical facets
    , TestHierarchicalFacets.class
    , TestHierarchicalFacetsSparql.class
    , TestNestedHierarchicalFacets.class
    // idx:self — an occurrence bound to the focus node itself
    , TestSelfBoundOccurrences.class
    , org.apache.jena.query.text.assembler.TestSelfOccurrenceAssembler.class
    , TestCorrelatedNestedAttribution.class
    , TestTypeaheadFieldConfigurations.class
    , TestNestedJoinPathSupport.class

    // Offset paging
    , TestOffsetPaging.class

    // Range facets
    , TestRangeFacetCounts.class

    // TEMPORAL fields: epoch filtering, sort, and literal reconstruction on read
    , TestDateLiteralRoundTrip.class

    // Classic Lucene query syntax reaching the queryString argument
    , TestLuceneQuerySyntax.class

    // Demo data validation
    , TestDemoMiningScenarios.class
})

public class TS_Text
{}
