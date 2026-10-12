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

package org.apache.jena.query.text.bench;

import java.io.File;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.apache.jena.query.*;
import org.apache.jena.query.text.TextDatasetFactory;
import org.apache.jena.sparql.core.Var;
import org.apache.jena.sparql.engine.binding.Binding;
import org.apache.jena.system.Txn;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.BenchmarkParams;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.ChainedOptionsBuilder;
import org.openjdk.jmh.runner.options.CommandLineOptions;
import org.openjdk.jmh.runner.options.OptionsBuilder;

/**
 * JMH benchmark of {@code luc:query} over a synthetic SHACL index of 500,000 entities.
 * See {@link SyntheticCorpus} for the data and {@code docs/05-testing.md} for how to run
 * it. Not part of {@code TS_Text}.
 * <p>
 * System properties, read by {@link #main} and passed on to the forked JVM:
 * <ul>
 * <li>{@code bench.dir}: where the dataset and index are built and reused. Default
 *     {@code jena-text/target/bench-luc-query}.</li>
 * <li>{@code bench.entities}: entity count. Default 500,000. Both large windows are
 *     three fifths of it.</li>
 * <li>{@code bench.matchWindow}: window of {@link #largeTextWithMatch}. Default 20,000.</li>
 * </ul>
 */
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 10, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 10, timeUnit = TimeUnit.SECONDS)
@Fork(1)
@State(Scope.Benchmark)
public class BenchLucQuery {

    static final String PROP_DIR = "bench.dir";
    static final String PROP_ENTITIES = "bench.entities";
    static final String PROP_MATCH_WINDOW = "bench.matchWindow";

    /** Heap fixed so collection behaviour does not vary between runs. */
    private static final List<String> FORK_JVM_ARGS = List.of(
        "-Xms8g", "-Xmx8g", "--add-modules", "jdk.incubator.vector", "--enable-native-access=ALL-UNNAMED");

    private static final String FIELD = "urn:jena:lucene:field#";
    private static final String PREFIXES = "PREFIX luc: <urn:jena:lucene:index#>\n";
    private static final String STATUS_FILTER =
        "{\"op\":\"=\",\"args\":[{\"property\":\"" + FIELD + "status\"},\"" + SyntheticCorpus.COMMON_STATUS + "\"]}";
    private static final String FACET_FIELDS =
        "[\"" + FIELD + "category\",\"" + FIELD + "region\",\"" + FIELD + "status\",\"" + FIELD + "subject\"]";

    static final String Q_PAGE_TEXT = PREFIXES
        + "SELECT ?entity WHERE {\n"
        + "  (?hit ?entity) luc:query (\"default\" \"default\" \"" + SyntheticCorpus.RARE_TERM + "\" \"\" \"\" 20 0) .\n"
        + "}";

    static final String Q_PAGE_TEXT_MATCH = PREFIXES
        + "SELECT ?entity ?field ?value WHERE {\n"
        + "  (?hit ?entity) luc:query (\"default\" \"default\" \"" + SyntheticCorpus.RARE_TERM + "\" \"\" \"\" 20 0) .\n"
        + "  (?hit ?field ?value) luc:match () .\n"
        + "}";

    static final String Q_PAGE_TEXT_FACET = PREFIXES
        + "SELECT ?entity ?field ?value ?count WHERE {\n"
        + "  { (?hit ?entity) luc:query (\"default\" \"default\" \"" + SyntheticCorpus.RARE_TERM + "\" \"\" \"\" 20 0) . }\n"
        + "  UNION\n"
        + "  { (?field ?value ?low ?high ?count) luc:facet (\"default\" \"default\" \"" + SyntheticCorpus.RARE_TERM
        + "\" '" + FACET_FIELDS + "' \"\" 10 0) . }\n"
        + "}";

    static String largeFilterQuery(int window) {
        return PREFIXES
            + "SELECT ?entity WHERE {\n"
            + "  (?hit ?entity) luc:query (\"default\" \"default\" \"*\" '" + STATUS_FILTER + "' \"\" " + window + " 0) .\n"
            + "}";
    }

    static String largeTextQuery(int window) {
        return PREFIXES
            + "SELECT ?entity WHERE {\n"
            + "  (?hit ?entity) luc:query (\"default\" \"default\" \"" + SyntheticCorpus.COMMON_TERM + "\" \"\" \"\" " + window + " 0) .\n"
            + "}";
    }

    static String largeTextMatchQuery(int window) {
        return PREFIXES
            + "SELECT ?entity ?field ?value WHERE {\n"
            + "  (?hit ?entity) luc:query (\"default\" \"default\" \"" + SyntheticCorpus.COMMON_TERM + "\" \"\" \"\" " + window + " 0) .\n"
            + "  (?hit ?field ?value) luc:match () .\n"
            + "}";
    }

    private Dataset dataset;
    private Query pageText;
    private Query pageTextMatch;
    private Query pageTextFacet;
    private Query largeFilter;
    private Query largeText;
    private Query largeTextMatch;

    @Setup(Level.Trial)
    public void setup(BenchmarkParams params) throws Exception {
        SyntheticCorpus corpus = corpus();
        corpus.ensureBuilt();
        dataset = TextDatasetFactory.create(corpus.assemblerFile().toString());

        // Both large hit sets are three fifths of the entities: 300,000 at the default size.
        int largeWindow = corpus.entities() * 3 / 5;
        int matchWindow = Integer.getInteger(PROP_MATCH_WINDOW, 20_000);
        pageText = QueryFactory.create(Q_PAGE_TEXT);
        pageTextMatch = QueryFactory.create(Q_PAGE_TEXT_MATCH);
        pageTextFacet = QueryFactory.create(Q_PAGE_TEXT_FACET);
        largeFilter = QueryFactory.create(largeFilterQuery(largeWindow));
        largeText = QueryFactory.create(largeTextQuery(largeWindow));
        largeTextMatch = QueryFactory.create(largeTextMatchQuery(matchWindow));

        // Run this benchmark's query once and check its row count, so a broken query
        // cannot be timed as a fast one.
        String name = params.getBenchmark();
        String method = name.substring(name.lastIndexOf('.') + 1);
        Query query = switch (method) {
            case "pageText" -> pageText;
            case "pageTextWithMatch" -> pageTextMatch;
            case "pageTextWithFacet" -> pageTextFacet;
            case "largeFilterOnly" -> largeFilter;
            case "largeTextNoMatch" -> largeText;
            case "largeTextWithMatch" -> largeTextMatch;
            default -> throw new IllegalStateException("No sanity check for " + method);
        };
        long t0 = System.nanoTime();
        long rows = run(query, null);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        SyntheticCorpus.log(method + ": " + rows + " rows on the first run, " + ms + " ms");
        long minimum = switch (method) {
            case "pageText" -> 20;
            case "pageTextWithMatch" -> 20;
            case "pageTextWithFacet" -> 20 + 10 + 8 + 3 + 10;
            case "largeFilterOnly", "largeTextNoMatch" -> largeWindow;
            case "largeTextWithMatch" -> matchWindow;
            default -> 0;
        };
        // luc:match returns one row per matched field, so those two have a lower bound.
        boolean exact = !method.equals("pageTextWithMatch") && !method.equals("largeTextWithMatch");
        if (exact ? rows != minimum : rows < minimum)
            throw new IllegalStateException(method + " returned " + rows + " rows, expected "
                + (exact ? "" : "at least ") + minimum);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        if (dataset != null)
            dataset.close();
    }

    /** Page of 20, text query, binds {@code ?entity}. */
    @Benchmark
    public long pageText(Blackhole bh) {
        return run(pageText, bh);
    }

    /** Page of 20, text query, joined to {@code luc:match}. */
    @Benchmark
    public long pageTextWithMatch(Blackhole bh) {
        return run(pageTextMatch, bh);
    }

    /** Page of 20 and four facets over the same search, sharing one execution. */
    @Benchmark
    public long pageTextWithFacet(Blackhole bh) {
        return run(pageTextFacet, bh);
    }

    /** 300,000 hits from a CQL filter with query string {@code *}, binds {@code ?entity}. */
    @Benchmark
    public long largeFilterOnly(Blackhole bh) {
        return run(largeFilter, bh);
    }

    /** 300,000 hits from a text query, without {@code luc:match}. */
    @Benchmark
    public long largeTextNoMatch(Blackhole bh) {
        return run(largeText, bh);
    }

    /**
     * Text query joined to {@code luc:match}, over a window of {@code bench.matchWindow}
     * hits (default 20,000). At 300,000 hits it does not finish in a usable time on the
     * baseline code, so the window is smaller by default.
     */
    @Benchmark
    public long largeTextWithMatch(Blackhole bh) {
        return run(largeTextMatch, bh);
    }

    private long run(Query query, Blackhole bh) {
        return Txn.calculateRead(dataset, () -> {
            long rows = 0;
            try (QueryExecution qe = QueryExecution.dataset(dataset).query(query).build()) {
                ResultSet rs = qe.execSelect();
                List<Var> vars = Var.varList(rs.getResultVars());
                while (rs.hasNext()) {
                    Binding b = rs.nextBinding();
                    for (Var v : vars) {
                        if (bh != null)
                            bh.consume(b.get(v));
                    }
                    rows++;
                }
            }
            return rows;
        });
    }

    static SyntheticCorpus corpus() {
        int entities = Integer.getInteger(PROP_ENTITIES, 500_000);
        String dir = System.getProperty(PROP_DIR);
        Path path = dir != null ? Path.of(dir) : defaultDir();
        return new SyntheticCorpus(path.toAbsolutePath(), entities);
    }

    /** {@code target/bench-luc-query} next to the {@code test-classes} this class was loaded from. */
    private static Path defaultDir() {
        try {
            File classes = new File(BenchLucQuery.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return classes.toPath().getParent().resolve("bench-luc-query");
        } catch (URISyntaxException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /**
     * Builds the index if absent, then runs JMH. Arguments are JMH's own; with no
     * benchmark regex every scenario in this class runs. {@code -l} builds the index and
     * lists the scenarios without running them.
     */
    public static void main(String... args) throws Exception {
        SyntheticCorpus corpus = corpus();
        corpus.ensureBuilt();

        CommandLineOptions cli = new CommandLineOptions(args);
        ChainedOptionsBuilder builder = new OptionsBuilder().parent(cli);
        if (cli.getIncludes().isEmpty())
            builder.include(BenchLucQuery.class.getName() + "\\.");
        List<String> forwarded = new ArrayList<>(FORK_JVM_ARGS);
        forwarded.add("-D" + PROP_DIR + "=" + corpus.assemblerFile().getParent());
        for (String prop : List.of(PROP_ENTITIES, PROP_MATCH_WINDOW)) {
            String value = System.getProperty(prop);
            if (value != null)
                forwarded.add("-D" + prop + "=" + value);
        }
        builder.jvmArgsAppend(forwarded.toArray(new String[0]));
        Runner runner = new Runner(builder.build());
        if (cli.shouldList()) {
            runner.list();
            return;
        }
        runner.run();
    }
}
