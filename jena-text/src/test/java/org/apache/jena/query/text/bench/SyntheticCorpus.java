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

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.apache.jena.atlas.lib.IRILib;
import org.apache.jena.query.text.cmd.shacltextindexer;
import org.apache.jena.riot.Lang;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.tdb2.DatabaseMgr;
import org.apache.jena.tdb2.loader.DataLoader;
import org.apache.jena.tdb2.loader.LoaderFactory;
import org.apache.jena.tdb2.loader.base.LoaderOps;
import org.apache.jena.tdb2.sys.TDBInternal;

/**
 * Generates the synthetic dataset for {@link BenchLucQuery}, loads it into TDB2 and
 * builds its SHACL Lucene index with {@code shacltextindexer}, the same path a
 * production bulk load takes.
 * <p>
 * Every entity is a {@code bench:Record} with these values:
 * <ul>
 * <li>{@code rdfs:label}, {@code bench:recordId}: identifier-like strings.</li>
 * <li>{@code bench:description}, {@code bench:abstract}: about 300 characters each, drawn
 *     from a 5,000-word vocabulary with a Zipf distribution.</li>
 * <li>{@code bench:category} (30 values), {@code bench:region} (8),
 *     {@code bench:status} (3), two {@code bench:subject} (200), {@code bench:year}.</li>
 * <li>Two {@code schema:identifier} children, each with {@code schema:propertyID} and
 *     {@code schema:value}.</li>
 * </ul>
 * Hit counts are fixed by the entity number, not by chance:
 * <ul>
 * <li>{@link #COMMON_TERM} is in the description of entities with {@code i % 5} in 1..3:
 *     300,000 of 500,000.</li>
 * <li>{@link #RARE_TERM} is in the description of entities with {@code i % 47 == 7}:
 *     10,638 of 500,000. 47 is coprime with 5, so these entities have every status.</li>
 * <li>{@code bench:status} is {@link #COMMON_STATUS} for {@code i % 5} in 0..2:
 *     300,000 of 500,000.</li>
 * </ul>
 * The vocabulary never contains either marker term.
 */
public final class SyntheticCorpus {

    public static final String NS = "http://example.org/bench/";
    public static final String COMMON_TERM = "survey";
    public static final String RARE_TERM = "basalt";
    public static final String COMMON_STATUS = "active";

    private static final String SCHEMA = "https://schema.org/";
    private static final String RDF_TYPE = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type";
    private static final String RDFS_LABEL = "http://www.w3.org/2000/01/rdf-schema#label";
    private static final String XSD_INT = "http://www.w3.org/2001/XMLSchema#int";

    private static final String[] REGIONS = {
        "north", "south", "east", "west", "central", "coastal", "inland", "offshore" };
    private static final String[] OTHER_STATUS = { "retired", "draft" };
    private static final String[] ID_SCHEMES = { "doi", "handle", "isbn" };
    private static final String[] SYLLABLES = {
        "ka", "lo", "mi", "ne", "ru", "ta", "pe", "vi", "go", "da", "fe", "ho", "ji", "lu",
        "mo", "ni", "po", "re", "ti", "wa", "ze", "qu", "ar", "en", "il", "or", "um", "yo" };

    private static final int VOCABULARY_SIZE = 5_000;
    private static final int TEXT_LENGTH = 300;
    private static final long SEED = 20261012L;

    /** Marker file written after a complete build; its absence means rebuild. */
    private static final String COMPLETE_MARKER = "BUILD-COMPLETE";

    private final Path dir;
    private final int entities;

    public SyntheticCorpus(Path dir, int entities) {
        this.dir = dir;
        this.entities = entities;
    }

    public int entities() {
        return entities;
    }

    public Path assemblerFile() {
        return dir.resolve("config.ttl");
    }

    public Path luceneDir() {
        return dir.resolve("lucene");
    }

    public Path tdbDir() {
        return dir.resolve("tdb2");
    }

    public static String entityIri(int i) {
        return NS + "record/" + i;
    }

    /**
     * Builds the dataset and index unless a complete build for the same entity count is
     * already in {@code dir}. A partial build is deleted and redone.
     */
    public void ensureBuilt() throws IOException {
        Path marker = dir.resolve(COMPLETE_MARKER);
        if (Files.exists(marker)) {
            String built = Files.readString(marker, StandardCharsets.UTF_8).trim();
            if (built.startsWith("entities=" + entities + "\n")) {
                log("Reusing index in " + dir + " (" + built.replace('\n', ' ') + ")");
                return;
            }
            throw new IllegalStateException("Benchmark directory " + dir + " holds a different build ("
                + built.lines().findFirst().orElse("") + "), not entities=" + entities
                + ". Delete it or use another bench.dir.");
        }
        if (Files.exists(dir)) {
            log("Deleting incomplete build in " + dir);
            deleteRecursively(dir);
        }
        Files.createDirectories(dir);
        writeAssembler();

        long t0 = System.nanoTime();
        Path data = dir.resolve("data.nt.gz");
        long triples = generate(data);
        long t1 = System.nanoTime();
        log(String.format("Generated %,d triples in %.1f s", triples, (t1 - t0) / 1e9));

        load(data);
        long t2 = System.nanoTime();
        log(String.format("Loaded TDB2 in %.1f s", (t2 - t1) / 1e9));

        shacltextindexer.testMain("--desc=" + assemblerFile());
        long t3 = System.nanoTime();
        log(String.format("Built Lucene index in %.1f s", (t3 - t2) / 1e9));

        // Release the TDB2 lock: the benchmark opens the dataset in a forked JVM.
        TDBInternal.expel(DatabaseMgr.connectDatasetGraph(tdbDir().toString()), true);
        Files.delete(data);
        String summary = String.format(
            "entities=%d%ntriples=%d%ngenerateSeconds=%.1f%nloadSeconds=%.1f%nindexSeconds=%.1f%nluceneBytes=%d%n",
            entities, triples, (t1 - t0) / 1e9, (t2 - t1) / 1e9, (t3 - t2) / 1e9, sizeOf(luceneDir()));
        Files.writeString(marker, summary, StandardCharsets.UTF_8);
        log("Build complete: " + summary.replace('\n', ' '));
    }

    private void writeAssembler() throws IOException {
        String template;
        try (InputStream in = SyntheticCorpus.class.getResourceAsStream("/bench/luc-query-bench.ttl")) {
            if (in == null)
                throw new FileNotFoundException("bench/luc-query-bench.ttl not on the classpath");
            template = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        String config = template
            .replace("@@TDB@@", tdbDir().toAbsolutePath().toString())
            .replace("@@LUCENE@@", IRILib.filenameToIRI(luceneDir().toAbsolutePath().toString()));
        Files.writeString(assemblerFile(), config, StandardCharsets.UTF_8);
    }

    private void load(Path data) throws IOException {
        DatasetGraph dsg = DatabaseMgr.connectDatasetGraph(tdbDir().toString());
        DataLoader loader = LoaderFactory.parallelLoader(dsg, LoaderOps.outputToLog());
        loader.startBulk();
        try (InputStream in = new GZIPInputStream(new BufferedInputStream(Files.newInputStream(data)), 1 << 16)) {
            loader.loadFromInputStream(data.toString(), in, Lang.NTRIPLES);
            loader.finishBulk();
        } catch (RuntimeException | IOException ex) {
            loader.finishException(ex);
            throw ex;
        }
    }

    /** Writes the N-Triples file and returns the number of triples written. */
    long generate(Path out) throws IOException {
        Random random = new Random(SEED);
        String[] vocabulary = vocabulary(random);
        double[] cdf = zipfCdf(vocabulary.length);
        long triples = 0;
        try (Writer w = new BufferedWriter(new OutputStreamWriter(
                new GZIPOutputStream(Files.newOutputStream(out), 1 << 16), StandardCharsets.UTF_8), 1 << 16)) {
            for (int i = 0; i < entities; i++) {
                String s = "<" + entityIri(i) + ">";
                String recordId = String.format("R%07d", i);
                triples += triple(w, s, RDF_TYPE, "<" + NS + "Record>");
                triples += triple(w, s, RDFS_LABEL, literal("Record " + recordId));
                triples += triple(w, s, NS + "recordId", literal(recordId));

                List<String> markers = new ArrayList<>(2);
                int m5 = i % 5;
                if (m5 >= 1 && m5 <= 3)
                    markers.add(COMMON_TERM);
                if (i % 47 == 7)
                    markers.add(RARE_TERM);
                triples += triple(w, s, NS + "description", literal(text(random, vocabulary, cdf, markers)));
                triples += triple(w, s, NS + "abstract", literal(text(random, vocabulary, cdf, List.of())));

                triples += triple(w, s, NS + "category", literal("category-" + random.nextInt(30)));
                triples += triple(w, s, NS + "region", literal(REGIONS[random.nextInt(REGIONS.length)]));
                String status = m5 <= 2 ? COMMON_STATUS : OTHER_STATUS[m5 - 3];
                triples += triple(w, s, NS + "status", literal(status));
                int subjectA = random.nextInt(200);
                int subjectB = (subjectA + 1 + random.nextInt(199)) % 200;
                triples += triple(w, s, NS + "subject", literal("subject-" + subjectA));
                triples += triple(w, s, NS + "subject", literal("subject-" + subjectB));
                triples += triple(w, s, NS + "year",
                    "\"" + (1950 + random.nextInt(76)) + "\"^^<" + XSD_INT + ">");

                String local = "_:l" + i;
                triples += triple(w, s, SCHEMA + "identifier", local);
                triples += triple(w, local, SCHEMA + "propertyID", literal("local"));
                triples += triple(w, local, SCHEMA + "value", literal("L-" + recordId));
                String external = "_:x" + i;
                String scheme = ID_SCHEMES[i % ID_SCHEMES.length];
                triples += triple(w, s, SCHEMA + "identifier", external);
                triples += triple(w, external, SCHEMA + "propertyID", literal(scheme));
                triples += triple(w, external, SCHEMA + "value", literal(scheme + ":" + (1_000_000 + i)));

                if ((i + 1) % 100_000 == 0)
                    log(String.format("Generated %,d entities", i + 1));
            }
        }
        return triples;
    }

    /**
     * Text of about {@link #TEXT_LENGTH} characters, with each marker inserted at a random
     * position. Inserting rather than overwriting keeps both markers when an entity has two.
     */
    private static String text(Random random, String[] vocabulary, double[] cdf, List<String> markers) {
        List<String> words = new ArrayList<>();
        int length = 0;
        while (length < TEXT_LENGTH) {
            String word = vocabulary[sample(random, cdf)];
            words.add(word);
            length += word.length() + 1;
        }
        for (String marker : markers)
            words.add(random.nextInt(words.size() + 1), marker);
        return String.join(" ", words);
    }

    private static String[] vocabulary(Random random) {
        Set<String> words = new LinkedHashSet<>();
        while (words.size() < VOCABULARY_SIZE) {
            int syllables = 1 + random.nextInt(4);
            StringBuilder sb = new StringBuilder();
            for (int k = 0; k < syllables; k++)
                sb.append(SYLLABLES[random.nextInt(SYLLABLES.length)]);
            String word = sb.toString();
            if (!word.equals(COMMON_TERM) && !word.equals(RARE_TERM))
                words.add(word);
        }
        return words.toArray(new String[0]);
    }

    private static double[] zipfCdf(int n) {
        double[] cdf = new double[n];
        double sum = 0;
        for (int k = 0; k < n; k++) {
            sum += 1.0 / (k + 1);
            cdf[k] = sum;
        }
        for (int k = 0; k < n; k++)
            cdf[k] /= sum;
        return cdf;
    }

    private static int sample(Random random, double[] cdf) {
        int idx = Arrays.binarySearch(cdf, random.nextDouble());
        return Math.min(idx >= 0 ? idx : -idx - 1, cdf.length - 1);
    }

    private static int triple(Writer w, String s, String p, String o) throws IOException {
        w.write(s);
        w.write(" <");
        w.write(p);
        w.write("> ");
        w.write(o.startsWith("_:") || o.startsWith("<") || o.startsWith("\"") ? o : "<" + o + ">");
        w.write(" .\n");
        return 1;
    }

    /** Values here are plain ASCII words, so no escaping is needed. */
    private static String literal(String value) {
        return "\"" + value + "\"";
    }

    static long sizeOf(Path path) throws IOException {
        if (!Files.exists(path))
            return 0;
        try (var stream = Files.walk(path)) {
            return stream.filter(Files::isRegularFile).mapToLong(p -> p.toFile().length()).sum();
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        try (var stream = Files.walk(path)) {
            List<Path> paths = stream.sorted(Comparator.reverseOrder()).toList();
            for (Path p : paths)
                Files.delete(p);
        }
    }

    static void log(String message) {
        System.err.println("[bench] " + message);
    }
}
