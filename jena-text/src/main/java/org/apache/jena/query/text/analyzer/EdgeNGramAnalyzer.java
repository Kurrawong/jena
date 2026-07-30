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

package org.apache.jena.query.text.analyzer;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.core.KeywordTokenizer;
import org.apache.lucene.analysis.core.LowerCaseFilter;
import org.apache.lucene.analysis.ngram.EdgeNGramTokenFilter;
import org.apache.lucene.analysis.standard.StandardTokenizer;

/**
 * Analyzer for prefix/typeahead indexing. Lowercases the value and emits edge
 * n-grams from {@code minGram} to {@code maxGram} characters. Two modes decide
 * what the n-grams are prefixes <em>of</em>:
 * <dl>
 *   <dt>whole-value (default, {@code tokenized = false})</dt>
 *   <dd>The entire value is one token, so only prefixes of the whole string match.
 *       {@code "RPT-MIA-2023-001"} is reachable by typing it from the start, and
 *       punctuation is never split on. Pair with {@link LowerCaseKeywordAnalyzer}
 *       as the query analyzer.</dd>
 *   <dt>per-word ({@code tokenized = true})</dt>
 *   <dd>The value is split into words first, so any word can be prefix-matched:
 *       {@code "Sarah"} and {@code "Sarah Jo"} both reach {@code "Dr Sarah Jones"}.
 *       This is what names, titles and other prose-like short values need. Pair with
 *       a word-tokenizing query analyzer — {@code text:StandardAnalyzer} — so that
 *       multi-word input tokenizes the same way and matches as a phrase.</dd>
 * </dl>
 * In per-word mode the original token is preserved alongside the n-grams, so words
 * longer than {@code maxGram} stay searchable in full.
 * <p>
 * The query analyzer matters: n-gramming the <em>query</em> as well as the index
 * matches far too much. When a field configures this analyzer with no explicit
 * {@code idx:queryAnalyzer}, the matching one for its mode is supplied by default.
 */
public class EdgeNGramAnalyzer extends Analyzer {

    private final int minGram;
    private final int maxGram;
    private final boolean tokenized;

    public EdgeNGramAnalyzer() {
        this(1, 20);
    }

    public EdgeNGramAnalyzer(int minGram, int maxGram) {
        this(minGram, maxGram, false);
    }

    public EdgeNGramAnalyzer(int minGram, int maxGram, boolean tokenized) {
        this.minGram = minGram;
        this.maxGram = maxGram;
        this.tokenized = tokenized;
    }

    /** True when values are split into words before n-gramming. */
    public boolean isTokenized() {
        return tokenized;
    }

    @Override
    protected TokenStreamComponents createComponents(String fieldName) {
        Tokenizer source = tokenized ? new StandardTokenizer() : new KeywordTokenizer();
        TokenStream stream = new LowerCaseFilter(source);
        stream = new EdgeNGramTokenFilter(stream, minGram, maxGram, tokenized);
        return new TokenStreamComponents(source, stream);
    }

    @Override
    protected TokenStream normalize(String fieldName, TokenStream in) {
        return new LowerCaseFilter(in);
    }
}
