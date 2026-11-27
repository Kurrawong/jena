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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.query.text.ShaclIndexMapping.FieldDef;
import org.apache.jena.query.text.ShaclIndexMapping.FieldOccurrence;
import org.apache.jena.query.text.ShaclIndexMapping.FieldType;
import org.apache.jena.query.text.ShaclIndexMapping.IndexProfile;
import org.apache.jena.query.text.ShaclIndexMapping.JoinStep;
import org.apache.jena.sparql.path.PathFactory;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Round-tripping the configuration stamp through Lucene commit data, and the rules about
 * when an index may be stamped.
 *
 * @see ShaclIndexStamp
 */
public class TestShaclIndexStamp {

    private static final String NS = "http://example.org/";
    private static final Node BOOK_CLASS = NodeFactory.createURI(NS + "Book");
    private static final Node TITLE_PRED = NodeFactory.createURI(NS + "title");
    private static final Node CATEGORY_PRED = NodeFactory.createURI(NS + "category");

    /**
     * @param categoryFacetable flips one flag, which is enough to change the fingerprint
     */
    private static TextIndexConfig config(boolean categoryFacetable) {
        FieldDef title = new FieldDef("title", FieldType.TEXT, null,
            true, true, false, false, false, true);
        FieldDef category = new FieldDef("category", FieldType.KEYWORD, null,
            true, true, categoryFacetable, false, true, false);

        IndexProfile profile = new IndexProfile(
            NodeFactory.createURI(NS + "BookShape"),
            Collections.singleton(BOOK_CLASS),
            "uri", "docType",
            List.of(title, category),
            List.of(occurrence(title, TITLE_PRED), occurrence(category, CATEGORY_PRED)),
            Collections.emptyList(),
            Collections.emptyList());

        EntityDefinition defn = new EntityDefinition("uri", "title");
        defn.set("title", TITLE_PRED);
        defn.set("category", CATEGORY_PRED);

        TextIndexConfig config = new TextIndexConfig(defn);
        config.setShaclMapping(new ShaclIndexMapping(Collections.singletonList(profile)));
        config.setFacetFields(Collections.singletonList("category"));
        return config;
    }

    private static FieldOccurrence occurrence(FieldDef field, Node predicate) {
        return new FieldOccurrence(
            field,
            PathFactory.pathLink(predicate),
            List.of(List.of(new JoinStep(predicate, false))),
            Set.of(predicate),
            null, null, null, null);
    }

    // ---- Round trip.

    @Test
    public void aNewIndexIsStampedWithItsConfiguration() {
        Directory dir = new ByteBuffersDirectory();
        ShaclTextIndexLucene index = new ShaclTextIndexLucene(dir, config(true));
        index.commit();

        ShaclIndexStamp.StampData stamp = ShaclIndexStamp.read(dir);
        assertNotNull(stamp, "a freshly created index should carry a stamp");
        assertEquals(index.getConfigFingerprint(), stamp.fingerprint());
        assertEquals(ShaclConfigFingerprint.FINGERPRINT_VERSION, stamp.version());
        assertNotNull(stamp.builtAt());
        assertNotNull(stamp.indexInstanceId(), "an index instance id identifies these files specifically");
    }

    @Test
    public void reopeningWithTheSameConfigurationMatches() {
        Directory dir = new ByteBuffersDirectory();
        ShaclTextIndexLucene first = new ShaclTextIndexLucene(dir, config(true));
        first.commit();
        first.close();

        ShaclTextIndexLucene second = new ShaclTextIndexLucene(dir, config(true));
        assertEquals(ShaclIndexStamp.Status.MATCH, second.getConfigStatus());
    }

    @Test
    public void reopeningWithADifferentConfigurationMismatches() {
        Directory dir = new ByteBuffersDirectory();
        ShaclTextIndexLucene first = new ShaclTextIndexLucene(dir, config(true));
        first.commit();
        first.close();

        ShaclTextIndexLucene second = new ShaclTextIndexLucene(dir, config(false));
        assertEquals(ShaclIndexStamp.Status.MISMATCH, second.getConfigStatus());
        assertNotEquals(first.getConfigFingerprint(), second.getConfigFingerprint());
    }

    /**
     * Two indexes in different directories built from one configuration must agree.
     * This is the case the whole design exists to permit: build on a large indexing
     * machine, serve from somewhere else, and do not call the move a change.
     */
    @Test
    public void indexLocationIsNotPartOfTheFingerprint(@TempDir Path tmp) throws Exception {
        Path a = Files.createDirectory(tmp.resolve("indexA"));
        Path b = Files.createDirectory(tmp.resolve("indexB"));

        try (Directory dirA = FSDirectory.open(a); Directory dirB = FSDirectory.open(b)) {
            ShaclTextIndexLucene indexA = new ShaclTextIndexLucene(dirA, config(true));
            ShaclTextIndexLucene indexB = new ShaclTextIndexLucene(dirB, config(true));
            indexA.commit();
            indexB.commit();

            assertEquals(indexA.getConfigFingerprint(), indexB.getConfigFingerprint(),
                "the directory an index lives in must not change its fingerprint");

            // And the stamp written at one location verifies against the other's config.
            assertEquals(ShaclIndexStamp.Status.MATCH,
                ShaclIndexStamp.compare(ShaclIndexStamp.read(dirA), indexB.getConfigFingerprint()));
        }
    }

    // ---- When an index may be stamped.

    /**
     * An index that predates fingerprinting must report UNKNOWN, and must not be quietly
     * stamped with whatever configuration happens to open it — that would manufacture a
     * match nothing has verified. Every index built before this feature shipped is here.
     */
    @Test
    public void anUnstampedIndexWithContentIsUnknownAndStaysUnstamped() throws Exception {
        Directory dir = new ByteBuffersDirectory();

        // An index with content but no stamp, as built by any earlier version.
        try (org.apache.lucene.index.IndexWriter writer = new org.apache.lucene.index.IndexWriter(
                dir, new org.apache.lucene.index.IndexWriterConfig(new org.apache.lucene.analysis.standard.StandardAnalyzer()))) {
            org.apache.lucene.document.Document doc = new org.apache.lucene.document.Document();
            doc.add(new org.apache.lucene.document.StringField("uri", NS + "book1",
                org.apache.lucene.document.Field.Store.YES));
            writer.addDocument(doc);
            writer.commit();
        }
        assertNull(ShaclIndexStamp.read(dir), "precondition: the index starts unstamped");

        ShaclTextIndexLucene index = new ShaclTextIndexLucene(dir, config(true));
        assertEquals(ShaclIndexStamp.Status.UNKNOWN, index.getConfigStatus());

        index.commit();
        assertNull(ShaclIndexStamp.read(dir),
            "opening a populated unstamped index must not stamp it - that would assert an unverified match");
    }

    /**
     * Opening a stamped index with a different configuration must not overwrite the
     * stamp: the mismatch would be destroyed by the act of reporting it.
     */
    @Test
    public void openingWithADifferentConfigurationDoesNotOverwriteTheStamp() {
        Directory dir = new ByteBuffersDirectory();
        ShaclTextIndexLucene first = new ShaclTextIndexLucene(dir, config(true));
        first.commit();
        first.close();
        String original = ShaclIndexStamp.read(dir).fingerprint();

        ShaclTextIndexLucene second = new ShaclTextIndexLucene(dir, config(false));
        second.commit();

        assertEquals(original, ShaclIndexStamp.read(dir).fingerprint(),
            "the stamp records how the content was built, not who last opened it");
    }

    /** A full rebuild re-stamps explicitly, which is the one sanctioned way to change it. */
    @Test
    public void anExplicitRebuildRestampsTheIndex() {
        Directory dir = new ByteBuffersDirectory();
        ShaclTextIndexLucene first = new ShaclTextIndexLucene(dir, config(true));
        first.commit();
        first.close();

        ShaclTextIndexLucene second = new ShaclTextIndexLucene(dir, config(false));
        second.stampConfig(null);
        second.commit();

        assertEquals(second.getConfigFingerprint(), ShaclIndexStamp.read(dir).fingerprint());
        assertEquals(ShaclIndexStamp.Status.MATCH,
            ShaclIndexStamp.compare(ShaclIndexStamp.read(dir), second.getConfigFingerprint()));
    }

    // ---- Version handling.

    /**
     * A stamp from a serialisation this build does not understand is UNKNOWN, not
     * MISMATCH. Without this, the first change to the format reports every deployed index
     * as broken.
     */
    @Test
    public void anUnrecognisedFingerprintVersionIsUnknownNotMismatch() {
        ShaclIndexStamp.StampData future = new ShaclIndexStamp.StampData(
            "sha256:whatever", ShaclConfigFingerprint.FINGERPRINT_VERSION + 1,
            "2026-08-13T00:00:00Z", "9.9.9", "id", null);
        assertEquals(ShaclIndexStamp.Status.UNKNOWN, ShaclIndexStamp.compare(future, "sha256:whatever"));
    }

    @Test
    public void anAbsentStampIsUnknown() {
        assertEquals(ShaclIndexStamp.Status.UNKNOWN, ShaclIndexStamp.compare(null, "sha256:anything"));
        assertNull(ShaclIndexStamp.read(new ByteBuffersDirectory()), "an empty directory holds no stamp");
    }

    // ---- Pairing.

    @Test
    public void pairingIdentifiesCrossedArtifacts() {
        ShaclIndexStamp.StampData stamp = new ShaclIndexStamp.StampData(
            "sha256:same", ShaclConfigFingerprint.FINGERPRINT_VERSION, "t", "v", "indexId", "dataset-A");

        assertEquals(ShaclIndexStamp.Status.MATCH, ShaclIndexStamp.comparePairing(stamp, "dataset-A"));
        assertEquals(ShaclIndexStamp.Status.MISMATCH, ShaclIndexStamp.comparePairing(stamp, "dataset-B"));
        assertEquals(ShaclIndexStamp.Status.UNKNOWN, ShaclIndexStamp.comparePairing(stamp, null));
    }

    /**
     * The case the fingerprint alone cannot see: same shapes, different data. Both
     * indexes fingerprint identically, so only the pairing tells them apart.
     */
    @Test
    public void identicalShapesOverDifferentDatasetsAgreeOnConfigButNotOnPairing() {
        Directory dirA = new ByteBuffersDirectory();
        Directory dirB = new ByteBuffersDirectory();
        ShaclTextIndexLucene indexA = new ShaclTextIndexLucene(dirA, config(true));
        ShaclTextIndexLucene indexB = new ShaclTextIndexLucene(dirB, config(true));
        indexA.stampConfig("dataset-A");
        indexB.stampConfig("dataset-B");
        indexA.commit();
        indexB.commit();

        ShaclIndexStamp.StampData stampA = ShaclIndexStamp.read(dirA);
        ShaclIndexStamp.StampData stampB = ShaclIndexStamp.read(dirB);

        assertEquals(stampA.fingerprint(), stampB.fingerprint(),
            "same shapes means the configuration check cannot tell these apart");
        assertEquals(ShaclIndexStamp.Status.MISMATCH, ShaclIndexStamp.comparePairing(stampA, "dataset-B"),
            "the pairing is what catches a crossed mount");
        assertNotEquals(stampA.indexInstanceId(), stampB.indexInstanceId());
    }

    // ---- Dataset instance id sidecar.

    @Test
    public void datasetIdIsMintedOnceAndThenStable(@TempDir Path tmp) {
        String first = DatasetInstanceId.readOrMint(tmp);
        assertNotNull(first);
        assertEquals(first, DatasetInstanceId.readOrMint(tmp), "minting is idempotent");
        assertEquals(first, DatasetInstanceId.read(tmp));
        assertTrue(Files.isRegularFile(tmp.resolve(DatasetInstanceId.FILENAME)));
    }

    /** Moving a dataset must not change its identity - that is the whole point. */
    @Test
    public void datasetIdSurvivesBeingMoved(@TempDir Path tmp) throws Exception {
        Path original = Files.createDirectory(tmp.resolve("original"));
        String id = DatasetInstanceId.readOrMint(original);

        Path moved = tmp.resolve("moved");
        Files.move(original, moved);

        assertEquals(id, DatasetInstanceId.read(moved));
    }

    @Test
    public void readingNeverMints(@TempDir Path tmp) {
        assertNull(DatasetInstanceId.read(tmp), "read must not create a sidecar");
        assertTrue(Files.notExists(tmp.resolve(DatasetInstanceId.FILENAME)));
    }

    @Test
    public void missingDirectoryIsNullNotAnError(@TempDir Path tmp) {
        Path absent = tmp.resolve("does-not-exist");
        assertNull(DatasetInstanceId.read(absent));
        assertNull(DatasetInstanceId.readOrMint(absent));
    }

    // ---- Locating the dataset a pairing id belongs to.

    @Test
    public void anInMemoryDatasetHasNoLocationAndNoId() {
        org.apache.jena.sparql.core.DatasetGraph mem =
            org.apache.jena.sparql.core.DatasetGraphFactory.createTxnMem();
        assertNull(DatasetLocations.tdb2ContainerPath(mem));
        assertNull(DatasetLocations.datasetInstanceId(mem, true),
            "an in-memory dataset has nowhere to keep an identity, and that is not an error");
    }

    /** The pairing id must be found through the wrapper layers a text dataset adds. */
    @Test
    public void aTdb2DatasetHasALocationAndMintsAnId(@TempDir Path tmp) throws Exception {
        Path dbDir = Files.createDirectory(tmp.resolve("DB"));
        org.apache.jena.sparql.core.DatasetGraph tdb2 =
            org.apache.jena.tdb2.DatabaseMgr.connectDatasetGraph(dbDir.toString());

        Path container = DatasetLocations.tdb2ContainerPath(tdb2);
        assertNotNull(container, "a TDB2 dataset should report its container directory");
        assertEquals(dbDir.toRealPath(), container.toRealPath());

        String id = DatasetLocations.datasetInstanceId(tdb2, true);
        assertNotNull(id);
        assertEquals(id, DatasetLocations.datasetInstanceId(tdb2, false), "the id must be stable once minted");
        assertTrue(Files.isRegularFile(dbDir.resolve(DatasetInstanceId.FILENAME)),
            "the sidecar belongs in the container directory, beside Data-NNNN");
    }

    /**
     * End to end: two datasets, identical shapes. The configuration check passes for both
     * — as it should, the configuration really is the same — and only the pairing shows
     * that the index does not belong to the dataset it has been mounted against.
     */
    @Test
    public void crossedDatasetAndIndexIsCaughtOnlyByThePairing(@TempDir Path tmp) throws Exception {
        Path dbA = Files.createDirectory(tmp.resolve("dbA"));
        Path dbB = Files.createDirectory(tmp.resolve("dbB"));
        String idA = DatasetInstanceId.readOrMint(dbA);
        String idB = DatasetInstanceId.readOrMint(dbB);
        assertNotEquals(idA, idB);

        Directory indexForA = new ByteBuffersDirectory();
        ShaclTextIndexLucene index = new ShaclTextIndexLucene(indexForA, config(true));
        index.stampConfig(idA);
        index.commit();
        index.close();

        ShaclIndexStamp.StampData stamp = ShaclIndexStamp.read(indexForA);

        // Mounted against the dataset it was built from.
        assertEquals(ShaclIndexStamp.Status.MATCH, ShaclIndexStamp.comparePairing(stamp, idA));
        // Mounted against the other one: same config, wrong data.
        assertEquals(ShaclIndexStamp.Status.MISMATCH, ShaclIndexStamp.comparePairing(stamp, idB));
        assertEquals(ShaclIndexStamp.Status.MATCH,
            ShaclIndexStamp.compare(stamp, index.getConfigFingerprint()),
            "the configuration check cannot see this - that is why the pairing exists");
    }

    /**
     * A brand new index adopts the identity of the dataset it is first attached to, so
     * that a later crossed mount has something to be caught against. An index that
     * already has content keeps the pairing recorded when its documents were written.
     */
    @Test
    public void aNewIndexAdoptsItsDatasetButAnExistingOneDoesNot(@TempDir Path tmp) throws Exception {
        Path dbA = Files.createDirectory(tmp.resolve("dbA"));
        Path dbB = Files.createDirectory(tmp.resolve("dbB"));
        org.apache.jena.sparql.core.DatasetGraph dsgA =
            org.apache.jena.tdb2.DatabaseMgr.connectDatasetGraph(dbA.toString());
        org.apache.jena.sparql.core.DatasetGraph dsgB =
            org.apache.jena.tdb2.DatabaseMgr.connectDatasetGraph(dbB.toString());

        Directory dir = new ByteBuffersDirectory();
        ShaclTextIndexLucene fresh = new ShaclTextIndexLucene(dir, config(true));
        fresh.checkOrCompletePairing(dsgA);
        fresh.commit();
        fresh.close();

        String adopted = ShaclIndexStamp.read(dir).pairedDatasetId();
        assertNotNull(adopted, "a new index should adopt the dataset it is attached to");
        assertEquals(DatasetInstanceId.read(dbA), adopted);

        // Reopened and attached to the other dataset: the pairing must not be rewritten.
        ShaclTextIndexLucene reopened = new ShaclTextIndexLucene(dir, config(true));
        reopened.checkOrCompletePairing(dsgB);
        reopened.commit();

        assertEquals(adopted, ShaclIndexStamp.read(dir).pairedDatasetId(),
            "an existing index must keep the pairing its documents were written under");
        assertEquals(ShaclIndexStamp.Status.MISMATCH,
            ShaclIndexStamp.comparePairing(ShaclIndexStamp.read(dir), DatasetInstanceId.read(dbB)),
            "attaching a paired index to another dataset is a mismatch");
    }

    /**
     * A paired index attached to a dataset that has never been indexed mints an identity
     * for it, so the crossed mount is reported rather than passed over as unknown. This
     * is the plain-{@code tdbloader} case: the database has no identity of its own.
     */
    @Test
    public void aPairedIndexMintsAnIdentityForAnAnonymousDataset(@TempDir Path tmp) throws Exception {
        Path dbA = Files.createDirectory(tmp.resolve("dbA"));
        Path dbB = Files.createDirectory(tmp.resolve("dbB"));
        org.apache.jena.sparql.core.DatasetGraph dsgB =
            org.apache.jena.tdb2.DatabaseMgr.connectDatasetGraph(dbB.toString());

        Directory dir = new ByteBuffersDirectory();
        ShaclTextIndexLucene built = new ShaclTextIndexLucene(dir, config(true));
        built.stampConfig(DatasetInstanceId.readOrMint(dbA));
        built.commit();
        built.close();

        assertNull(DatasetInstanceId.read(dbB), "precondition: dbB has never been indexed");

        ShaclTextIndexLucene crossed = new ShaclTextIndexLucene(dir, config(true));
        crossed.checkOrCompletePairing(dsgB);

        String mintedForB = DatasetInstanceId.read(dbB);
        assertNotNull(mintedForB, "a paired index should give the attached dataset an identity to be judged by");
        assertEquals(ShaclIndexStamp.Status.MISMATCH,
            ShaclIndexStamp.comparePairing(ShaclIndexStamp.read(dir), mintedForB));
    }

    /**
     * An <em>unpaired</em> index reaches no verdict, so it must not write into the
     * dataset directory. There would be nothing to compare the identity against.
     */
    @Test
    public void anUnpairedIndexDoesNotMintAnIdForTheDataset(@TempDir Path tmp) throws Exception {
        Path db = Files.createDirectory(tmp.resolve("db"));
        org.apache.jena.sparql.core.DatasetGraph dsg =
            org.apache.jena.tdb2.DatabaseMgr.connectDatasetGraph(db.toString());

        Directory dir = new ByteBuffersDirectory();
        ShaclTextIndexLucene first = new ShaclTextIndexLucene(dir, config(true));
        first.stampConfig(null);
        first.commit();
        first.close();

        ShaclTextIndexLucene reopened = new ShaclTextIndexLucene(dir, config(true));
        reopened.checkOrCompletePairing(dsg);

        assertNull(DatasetInstanceId.read(db),
            "with no pairing to check against there is no verdict, so no reason to write");
    }
}
