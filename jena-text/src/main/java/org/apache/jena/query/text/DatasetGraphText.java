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

import java.util.Iterator;
import java.util.List;

import org.apache.jena.atlas.lib.Bytes;

import org.apache.jena.dboe.transaction.txn.ComponentId;
import org.apache.jena.dboe.transaction.txn.TransactionCoordinator;
import org.apache.jena.dboe.transaction.txn.TransactionalComponent;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.query.ReadWrite;
import org.apache.jena.query.TxnType;
import org.apache.jena.query.text.changes.DatasetGraphTextMonitor;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.core.GraphView;
import org.apache.jena.sparql.core.Transactional;
import org.apache.jena.tdb1.transaction.TransactionManager;
import org.apache.lucene.queryparser.classic.QueryParserBase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DatasetGraphText extends DatasetGraphTextMonitor implements Transactional {
    private static Logger log = LoggerFactory.getLogger(DatasetGraphText.class);
    private final TextIndex textIndex;
    // Every index that joins the transaction: textIndex alone, or every index in a
    // TextIndexRegistry. Each has its own IndexWriter, and documents it has not
    // committed are invisible to searches.
    private final List<TextIndex> txnIndexes;
    private final Graph dftGraph;
    private final boolean closeIndexOnClose;
    // Lock needed for commit/abort that perform an index operation and a dataset
    // operation when the underlying datsetGraph does not coordinate the commit.
    private final Object txnExitLock = new Object();

    // If we are going to implement Transactional, and not delegate to a completely
    // transaction dataset graph (e.g. TDB) then we are going to have to do as
    // DatasetGraphWithLock.
    private final ThreadLocal<ReadWrite> readWriteMode = new ThreadLocal<>();

    private Runnable delegateCommit = () -> {
        super.commit();
    };

    private Runnable delegateAbort = () -> {
        super.abort();
    };

    private Runnable nonDelegatedCommit = () -> {
        if ( readWriteMode.get() == ReadWrite.WRITE )
            commit_W();
        else
            commit_R();
    };

    private Runnable nonDelegatedAbort = () -> {
        if ( readWriteMode.get() == ReadWrite.WRITE )
            abort_W();
        else
            abort_R();
    };

    private Runnable commitAction = null;
    private Runnable abortAction = null;

    public DatasetGraphText(DatasetGraph dsg, TextIndex index, TextDocProducer producer) {
        this(dsg, index, producer, false);
    }

    public DatasetGraphText(DatasetGraph dsg, TextIndex index, TextDocProducer producer, boolean closeIndexOnClose) {
        this(dsg, index, List.of(index), producer, closeIndexOnClose);
    }

    /**
     * A dataset over every index in {@code registry}. Writes are committed to and rolled
     * back from all of them; {@link #getTextIndex()} returns the registry's default.
     */
    public DatasetGraphText(DatasetGraph dsg, TextIndexRegistry registry, TextDocProducer producer, boolean closeIndexOnClose) {
        this(dsg, registry.getDefault(), List.copyOf(registry.all()), producer, closeIndexOnClose);
    }

    // First component id for a TDB2 text index; index i in txnIndexes takes BASE_COMPONENT_ID + i.
    // Index 0 keeps the id used before multi-index support, {2, 4, 6, 10}.
    private static final int BASE_COMPONENT_ID = 0x0204060A;

    @SuppressWarnings("removal")
    private DatasetGraphText(DatasetGraph dsg, TextIndex index, List<TextIndex> txnIndexes,
                             TextDocProducer producer, boolean closeIndexOnClose) {
        super(dsg, producer);
        this.textIndex = index;
        this.txnIndexes = txnIndexes;
        dftGraph = GraphView.createDefaultGraph(this);
        this.closeIndexOnClose = closeIndexOnClose;

        if ( org.apache.jena.tdb1.sys.TDBInternal.isTDB1(dsg) ) {
            TransactionManager txnMgr = org.apache.jena.tdb1.sys.TDBInternal.getTransactionManager(dsg);
            for ( TextIndex idx : txnIndexes )
                txnMgr.addAdditionComponent(new TextIndexTDB1(idx));
            commitAction = delegateCommit;
            abortAction = delegateAbort;
        } else if ( org.apache.jena.tdb2.sys.TDBInternal.isTDB2(dsg) ) {
            TransactionCoordinator coord = org.apache.jena.tdb2.sys.TDBInternal.getTransactionCoordinator(dsg);
            // The coordinator keys components by id, so each index needs its own.
            for ( int i = 0; i < txnIndexes.size(); i++ ) {
                byte[] componentID = Bytes.intToBytes(BASE_COMPONENT_ID + i);
                TransactionalComponent tc = new TextIndexDB(ComponentId.create(null, componentID), txnIndexes.get(i));
                coord.modifyConfig(() -> coord.addExternal(tc));
            }
            commitAction = delegateCommit;
            abortAction = delegateAbort;
        } else {
            commitAction = nonDelegatedCommit;
            abortAction = nonDelegatedAbort;
        }
    }

    // ---- Intercept these and force the use of views.
    @Override
    public Graph getDefaultGraph() {
        return dftGraph;
    }

    @Override
    public Graph getGraph(Node graphNode) {
        return GraphView.createNamedGraph(this, graphNode);
    }

    // ----

    public TextIndex getTextIndex() {
        return textIndex;
    }

    /** Search the text index on the default text field */
    public Iterator<TextHit> search(String queryString) {
        return search(queryString, null);
    }

    /** Search the text index on the text field associated with the predicate */
    public Iterator<TextHit> search(String queryString, Node predicate) {
        return search(queryString, predicate, -1);
    }

    /** Search the text index on the default text field */
    public Iterator<TextHit> search(String queryString, int limit) {
        return search(queryString, null, limit);
    }

    /** Search the text index on the text field associated with the predicate */
    public Iterator<TextHit> search(String queryString, Node predicate, int limit) {
        return search(queryString, predicate, null, null, limit);
    }

    /**
     * Search the text index on the text field associated with the predicate within
     * graph
     */
    public Iterator<TextHit> search(String queryString, Node predicate, String graphURI, String lang, int limit) {
        queryString = QueryParserBase.escape(queryString);
        if ( predicate != null ) {
            String f = textIndex.getDocDef().getField(predicate);
            queryString = f + ":" + queryString;
        }
        List<TextHit> results = textIndex.query(predicate, queryString, graphURI, lang, limit);
        return results.iterator();
    }

    @Override
    public void begin(TxnType txnType) {
        switch (txnType) {
            case READ_PROMOTE :
            case READ_COMMITTED_PROMOTE :
                throw new UnsupportedOperationException("begin(" + txnType + ")");
            default :
        }
        begin(TxnType.convert(txnType));
    }

    @Override
    public void begin(ReadWrite readWrite) {
        readWriteMode.set(readWrite);
        super.begin(readWrite);
        super.getMonitor().start();
    }

    @Override
    public void commit() {
        super.getMonitor().finish();
        commitAction.run();
        readWriteMode.set(null);
    }

    /**
     * Rollback all changes, discarding any exceptions that occur.
     */
    @Override
    public void abort() {
        synchronized (txnExitLock) {
            super.getMonitor().finish();
            abortAction.run();
            readWriteMode.set(null);
        }
    }

    private void commit_R() {
        // No index action needed.
        super.commit();
    }

    private void commit_W() {
        synchronized (txnExitLock) {
            super.getMonitor().finish();
            // Phase 1
            try {
                for ( TextIndex idx : txnIndexes )
                    idx.prepareCommit();
            } catch (Throwable t) {
                log.error("Exception in prepareCommit: " + t.getMessage(), t);
                abort();
                throw new TextIndexException(t);
            }

            // Phase 2
            try {
                // Hard to do atomically.
                super.commit();
                for ( TextIndex idx : txnIndexes )
                    idx.commit();
            } catch (Throwable t) {
                log.error("Exception in commit: " + t.getMessage(), t);
                abort();
                throw new TextIndexException(t);
            }
        }
    }

    private void abort_R() {
        try {
            super.abort();
        } catch (Throwable t) {
            log.warn("Exception in abort: " + t.getMessage(), t);
        }
    }

    private void abort_W() {
        synchronized (txnExitLock) {
            // Roll back on both objects, discarding any exceptions that occur
            try {
                super.abort();
            } catch (Throwable t) {
                log.warn("Exception in abort: " + t.getMessage(), t);
            }
            for ( TextIndex idx : txnIndexes ) {
                try {
                    idx.rollback();
                } catch (Throwable t) {
                    log.warn("Exception in abort: " + t.getMessage(), t);
                }
            }
        }
    }

    @Override
    public boolean isInTransaction() {
        return readWriteMode.get() != null;
    }

    @Override
    public void end() {
        if ( !isInTransaction() ) {
            super.end();
            return;
        }
        ReadWrite rwMode = readWriteMode.get();
        switch(rwMode) {
            case READ ->{
                super.getMonitor().finish();
                super.end();
                readWriteMode.set(null);
            }
            case WRITE ->{
                // If we are still in a write transaction at this point, then commit
                // was never called, so rollback the TextIndex and the dataset.
                super.getMonitor().finish();
                abortAction.run();
                super.end();
                readWriteMode.set(null);
            }
        }
    }

    @Override
    public boolean supportsTransactions() {
        return super.supportsTransactions();
    }

    /**
     * Declare whether {@link #abort} is supported. This goes along with clearing up
     * after exceptions inside application transaction code.
     */
    @Override
    public boolean supportsTransactionAbort() {
        return super.supportsTransactionAbort();
    }

    @Override
    public void close() {
        super.close();
        if ( closeIndexOnClose ) {
            for ( TextIndex idx : txnIndexes )
                idx.close();
        }
    }
}
