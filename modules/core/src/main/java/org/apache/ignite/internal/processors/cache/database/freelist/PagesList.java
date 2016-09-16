/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.processors.cache.database.freelist;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.ignite.IgniteCheckedException;
import org.apache.ignite.IgniteSystemProperties;
import org.apache.ignite.internal.pagemem.Page;
import org.apache.ignite.internal.pagemem.PageIdUtils;
import org.apache.ignite.internal.pagemem.PageMemory;
import org.apache.ignite.internal.pagemem.wal.IgniteWriteAheadLogManager;
import org.apache.ignite.internal.pagemem.wal.record.delta.DataPageSetFreeListPageRecord;
import org.apache.ignite.internal.pagemem.wal.record.delta.InitNewPageRecord;
import org.apache.ignite.internal.pagemem.wal.record.delta.PagesListAddPageRecord;
import org.apache.ignite.internal.pagemem.wal.record.delta.PagesListInitNewPageRecord;
import org.apache.ignite.internal.pagemem.wal.record.delta.PagesListRemovePageRecord;
import org.apache.ignite.internal.pagemem.wal.record.delta.PagesListSetNextRecord;
import org.apache.ignite.internal.pagemem.wal.record.delta.PagesListSetPreviousRecord;
import org.apache.ignite.internal.pagemem.wal.record.delta.RecycleRecord;
import org.apache.ignite.internal.processors.cache.database.DataStructure;
import org.apache.ignite.internal.processors.cache.database.freelist.io.PagesListMetaIO;
import org.apache.ignite.internal.processors.cache.database.freelist.io.PagesListNodeIO;
import org.apache.ignite.internal.processors.cache.database.tree.io.DataPageIO;
import org.apache.ignite.internal.processors.cache.database.tree.io.IOVersions;
import org.apache.ignite.internal.processors.cache.database.tree.io.PageIO;
import org.apache.ignite.internal.processors.cache.database.tree.reuse.ReuseBag;
import org.apache.ignite.internal.processors.cache.database.tree.util.PageHandler;
import org.apache.ignite.internal.util.GridArrays;
import org.apache.ignite.internal.util.GridLongList;
import org.apache.ignite.internal.util.typedef.F;
import org.apache.ignite.internal.util.typedef.internal.S;
import org.apache.ignite.internal.util.typedef.internal.U;
import org.jetbrains.annotations.Nullable;

import static org.apache.ignite.internal.pagemem.PageIdAllocator.FLAG_DATA;
import static org.apache.ignite.internal.pagemem.PageIdAllocator.FLAG_IDX;
import static org.apache.ignite.internal.processors.cache.database.tree.io.PageIO.getPageId;
import static org.apache.ignite.internal.processors.cache.database.tree.util.PageHandler.initPage;
import static org.apache.ignite.internal.processors.cache.database.tree.util.PageHandler.isWalDeltaRecordNeeded;
import static org.apache.ignite.internal.processors.cache.database.tree.util.PageHandler.writePage;

/**
 * Striped doubly-linked list of page IDs optionally organized in buckets.
 */
public abstract class PagesList extends DataStructure {
    /** */
    private static final int TRY_LOCK_ATTEMPTS =
            IgniteSystemProperties.getInteger("IGNITE_PAGES_LIST_TRY_LOCK_ATTEMPTS", 10);

    /** */
    private static final int MAX_STRIPES_PER_BUCKET =
        IgniteSystemProperties.getInteger("IGNITE_PAGES_LIST_STRIPES_PER_BUCKET", Math.min(8, Runtime.getRuntime().availableProcessors() * 2));

    /** Page ID to store list metadata. */
    private final long metaPageId;

    /** Number of buckets. */
    private final int buckets;

    /** Name (for debug purposes). */
    protected final String name;

    /** */
    private final PageHandler<Void, Boolean> cutTail = new PageHandler<Void, Boolean>() {
        @Override public Boolean run(long pageId, Page page, PageIO pageIo, ByteBuffer buf, Void ignore, int bucket)
            throws IgniteCheckedException {
            if (getPageId(buf) != pageId)
                return Boolean.FALSE;

            PagesListNodeIO io = (PagesListNodeIO)pageIo;

            long tailId = io.getNextId(buf);

            assert tailId != 0;

            io.setNextId(buf, 0L);

            if (isWalDeltaRecordNeeded(wal, page))
                wal.log(new PagesListSetNextRecord(cacheId, pageId, 0L));

            updateTail(bucket, tailId, pageId);

            return Boolean.TRUE;
        }
    };

    /** */
    private final CheckingPageHandler<Page, ByteBuffer> putDataPage = new CheckingPageHandler<Page, ByteBuffer>() {
        @Override protected boolean run0(long pageId,
            Page page,
            ByteBuffer buf,
            PagesListNodeIO io,
            Page dataPage,
            ByteBuffer dataPageBuf,
            int bucket) throws IgniteCheckedException {
            if (io.getNextId(buf) != 0L)
                return false; // Splitted.

            long dataPageId = dataPage.id();

            int idx = io.addPage(buf, dataPageId);

            if (idx == -1)
                handlePageFull(pageId, page, buf, io, dataPage, dataPageBuf, bucket);
            else {
                if (isWalDeltaRecordNeeded(wal, page))
                    wal.log(new PagesListAddPageRecord(cacheId, pageId, dataPageId));

                DataPageIO dataIO = DataPageIO.VERSIONS.forPage(dataPageBuf);
                dataIO.setFreeListPageId(dataPageBuf, pageId);

                if (isWalDeltaRecordNeeded(wal, dataPage))
                    wal.log(new DataPageSetFreeListPageRecord(cacheId, dataPage.id(), pageId));
            }

            return true;
        }

        /**
         * @param pageId Page ID.
         * @param page Page.
         * @param buf Buffer.
         * @param io IO.
         * @param dataPage Data page.
         * @param dataPageBuf Data page buffer.
         * @param bucket Bucket index.
         * @throws IgniteCheckedException If failed.
         */
        private void handlePageFull(
            long pageId,
            Page page,
            ByteBuffer buf,
            PagesListNodeIO io,
            Page dataPage,
            ByteBuffer dataPageBuf,
            int bucket
        ) throws IgniteCheckedException {
            long dataPageId = dataPage.id();
            DataPageIO dataIO = DataPageIO.VERSIONS.forPage(dataPageBuf);

            // Attempt to add page failed: the node page is full.
            if (isReuseBucket(bucket)) {
                // If we are on the reuse bucket, we can not allocate new page, because it may cause deadlock.
                assert dataIO.isEmpty(dataPageBuf); // We can put only empty data pages to reuse bucket.

                // Change page type to index and add it as next node page to this list.
                dataPageId = PageIdUtils.changeType(dataPageId, FLAG_IDX);

                setupNextPage(io, pageId, buf, dataPageId, dataPageBuf);

                if (isWalDeltaRecordNeeded(wal, page))
                    wal.log(new PagesListSetNextRecord(cacheId, pageId, dataPageId));

                if (isWalDeltaRecordNeeded(wal, dataPage))
                    wal.log(new PagesListInitNewPageRecord(cacheId, dataPageId, pageId, 0L));

                updateTail(bucket, pageId, dataPageId);
            }
            else {
                // Just allocate a new node page and add our data page there.
                long nextId = allocatePage(null);

                try (Page next = page(nextId)) {
                    ByteBuffer nextBuf = next.getForWrite();

                    try {
                        setupNextPage(io, pageId, buf, nextId, nextBuf);

                        if (isWalDeltaRecordNeeded(wal, page))
                            wal.log(new PagesListSetNextRecord(cacheId, pageId, nextId));

                        int idx = io.addPage(nextBuf, dataPageId);

                        // Here we should never write full page, because it is known to be new.
                        next.fullPageWalRecordPolicy(Boolean.FALSE);

                        if (isWalDeltaRecordNeeded(wal, next))
                            wal.log(new PagesListInitNewPageRecord(cacheId, nextId, pageId, dataPageId));

                        assert idx != -1;

                        dataIO.setFreeListPageId(dataPageBuf, nextId);

                        if (isWalDeltaRecordNeeded(wal, dataPage))
                            wal.log(new DataPageSetFreeListPageRecord(cacheId, dataPageId, nextId));

                        updateTail(bucket, pageId, nextId);
                    }
                    finally {
                        next.releaseWrite(true);
                    }
                }
            }
        }
    };

    /** */
    private final CheckingPageHandler<ReuseBag, Void> putReuseBag = new CheckingPageHandler<ReuseBag, Void>() {
        @SuppressWarnings("ForLoopReplaceableByForEach")
        @Override protected boolean run0(final long pageId,
            Page page,
            final ByteBuffer buf,
            PagesListNodeIO io,
            ReuseBag bag,
            Void ignore,
            int bucket) throws IgniteCheckedException {
            if (io.getNextId(buf) != 0L)
                return false; // Splitted.

            long nextId;
            ByteBuffer prevBuf = buf;
            long prevId = pageId;

            List<Page> locked = null;

            try {
                while ((nextId = bag.pollFreePage()) != 0L) {
                    int idx = io.addPage(prevBuf, nextId);

                    if (idx == -1) { // Attempt to add page failed: the node page is full.
                        try (Page next = page(nextId)) {
                            ByteBuffer nextBuf = next.getForWrite();

                            if (locked == null)
                                locked = new ArrayList<>(2);

                            locked.add(next);

                            setupNextPage(io, prevId, prevBuf, nextId, nextBuf);

                            if (isWalDeltaRecordNeeded(wal, page))
                                wal.log(new PagesListSetNextRecord(cacheId, pageId, nextId));

                            // Here we should never write full page, because it is known to be new.
                            next.fullPageWalRecordPolicy(Boolean.FALSE);

                            if (isWalDeltaRecordNeeded(wal, next))
                                wal.log(new PagesListInitNewPageRecord(cacheId, nextId, pageId, 0L));

                            // Switch to this new page, which is now a part of our list
                            // to add the rest of the bag to the new page.
                            prevBuf = nextBuf;
                            prevId = nextId;
                            page = next;
                        }
                    }
                    else {
                        // TODO: use single WAL record for bag?
                        if (isWalDeltaRecordNeeded(wal, page))
                            wal.log(new PagesListAddPageRecord(cacheId, pageId, nextId));
                    }
                }
            }
            finally {
                if (locked != null) {
                    // We have to update our bucket with the new tail.
                    updateTail(bucket, pageId, prevId);

                    // Release write.
                    for (int i = 0; i < locked.size(); i++)
                        locked.get(i).releaseWrite(true);
                }
            }

            return true;
        }
    };

    /**
     * @param cacheId Cache ID.
     * @param name Name (for debug purpose).
     * @param pageMem Page memory.
     * @param buckets Number of buckets.
     * @param wal Write ahead log manager.
     * @param metaPageId Metadata page ID.
     * @throws IgniteCheckedException If failed.
     */
    public PagesList(int cacheId,
        String name,
        PageMemory pageMem,
        int buckets,
        IgniteWriteAheadLogManager wal,
        long metaPageId) throws IgniteCheckedException {
        super(cacheId, pageMem, wal);
        this.name = name;
        this.buckets = buckets;
        this.metaPageId = metaPageId;
    }

    /**
     * @param metaPageId Metadata page ID.
     * @param initNew {@code True} if new list if created, {@code false} if should be initialized from metadata.
     * @throws IgniteCheckedException If failed.
     */
    protected final void init(long metaPageId, boolean initNew) throws IgniteCheckedException {
        if (metaPageId != 0L) {
            if (initNew) {
                try (Page page = page(metaPageId)) {
                    initPage(metaPageId, page, PagesListMetaIO.VERSIONS.latest(), wal);
                }
            }
            else {
                Map<Integer, GridLongList> bucketsData = new HashMap<>();

                long nextPageId = metaPageId;

                while (nextPageId != 0) {
                    try (Page page = page(nextPageId)) {
                        ByteBuffer buf = page.getForRead();

                        try {
                            PagesListMetaIO io = PagesListMetaIO.VERSIONS.forPage(buf);

                            io.getBucketsData(buf, bucketsData);

                            long next0 = io.getNextMetaPageId(buf);

                            assert next0 != nextPageId :
                                "Loop detected [next=" + U.hexLong(next0) + ", cur=" + U.hexLong(nextPageId) + ']';

                            nextPageId = next0;
                        }
                        finally {
                            page.releaseRead();
                        }
                    }
                }

                for (Map.Entry<Integer, GridLongList> e : bucketsData.entrySet()) {
                    int bucket = e.getKey();

                    Stripe[] old = getBucket(bucket);
                    assert old == null;

                    long[] upd = e.getValue().array();

                    Stripe[] tails = new Stripe[upd.length];

                    for (int i = 0; i < upd.length; i++)
                        tails[i] = new Stripe(upd[i]);

                    boolean ok = casBucket(bucket, null, tails);
                    assert ok;
                }
            }
        }
    }

    /**
     * @throws IgniteCheckedException If failed.
     */
    public void saveMetadata() throws IgniteCheckedException {
        assert metaPageId != 0;

        Page curPage = null;
        ByteBuffer curBuf = null;
        PagesListMetaIO curIo = null;

        long nextPageId = metaPageId;

        try {
            for (int bucket = 0; bucket < buckets; bucket++) {
                Stripe[] tails = getBucket(bucket);

                if (tails != null) {
                    int tailIdx = 0;

                    while (tailIdx < tails.length) {
                        int written = curPage != null ? curIo.addTails(curBuf, bucket, tails, tailIdx) : 0;

                        if (written == 0) {
                            if (nextPageId == 0L) {
                                nextPageId = allocatePageNoReuse();

                                if (curPage != null) {
                                    curIo.setNextMetaPageId(curBuf, nextPageId);

                                    releaseAndClose(curPage);
                                    curPage = null;
                                }

                                curPage = page(nextPageId);
                                curBuf = curPage.getForWrite();

                                curIo = PagesListMetaIO.VERSIONS.latest();

                                curIo.initNewPage(curBuf, nextPageId);
                            }
                            else {
                                releaseAndClose(curPage);
                                curPage = null;

                                curPage = page(nextPageId);
                                curBuf = curPage.getForWrite();

                                curIo = PagesListMetaIO.VERSIONS.forPage(curBuf);

                                curIo.resetCount(curBuf);
                            }

                            nextPageId = curIo.getNextMetaPageId(curBuf);
                        }
                        else
                            tailIdx += written;
                    }
                }
            }
        }
        finally {
            releaseAndClose(curPage);
        }

        while (nextPageId != 0L) {
            try (Page page = page(nextPageId)) {
                try {
                    ByteBuffer buf = page.getForWrite();
                    PagesListMetaIO io = PagesListMetaIO.VERSIONS.forPage(buf);

                    io.resetCount(buf);

                    nextPageId = io.getNextMetaPageId(buf);
                }
                finally {
                    page.releaseWrite(true);
                }
            }
        }
    }

    /**
     * @param page Page.
     */
    private void releaseAndClose(Page page) {
        if (page != null) {
            try {
                // No special WAL record because we most likely changed the whole page.
                page.fullPageWalRecordPolicy(true);

                page.releaseWrite(true);
            }
            finally {
                page.close();
            }
        }
    }

    /**
     * @param bucket Bucket index.
     * @return Bucket.
     */
    protected abstract Stripe[] getBucket(int bucket);

    /**
     * @param bucket Bucket index.
     * @param exp Expected bucket.
     * @param upd Updated bucket.
     * @return {@code true} If succeeded.
     */
    protected abstract boolean casBucket(int bucket, Stripe[] exp, Stripe[] upd);

    /**
     * @param bucket Bucket index.
     * @return {@code true} If it is a reuse bucket.
     */
    protected abstract boolean isReuseBucket(int bucket);

    /**
     * @param io IO.
     * @param prevId Previous page ID.
     * @param prev Previous page buffer.
     * @param nextId Next page ID.
     * @param next Next page buffer.
     */
    private void setupNextPage(PagesListNodeIO io, long prevId, ByteBuffer prev, long nextId, ByteBuffer next) {
        assert io.getNextId(prev) == 0L;

        io.initNewPage(next, nextId);
        io.setPreviousId(next, prevId);

        io.setNextId(prev, nextId);
    }

    /**
     * Adds stripe to the given bucket.
     *
     * @param bucket Bucket.
     * @throws IgniteCheckedException If failed.
     * @return Tail page ID.
     */
    private Stripe addStripe(int bucket, boolean reuse) throws IgniteCheckedException {
        long pageId = reuse ? allocatePage(null) : allocatePageNoReuse();

        try (Page page = page(pageId)) {
            initPage(pageId, page, PagesListNodeIO.VERSIONS.latest(), wal);
        }

        Stripe stripe = new Stripe(pageId);

        for (;;) {
            Stripe[] old = getBucket(bucket);
            Stripe[] upd;

            if (old != null) {
                int len = old.length;

                upd = Arrays.copyOf(old, len + 1);

                upd[len] = stripe;
            }
            else
                upd = new Stripe[]{stripe};

            if (casBucket(bucket, old, upd))
                return stripe;
        }
    }

    /**
     * @param bucket Bucket index.
     * @param oldTailId Old tail page ID to replace.
     * @param newTailId New tail page ID.
     */
    private void updateTail(int bucket, long oldTailId, long newTailId) {
        int idx = -1;

        for (;;) {
            Stripe[] tails = getBucket(bucket);

            // Tail must exist to be updated.
            assert !F.isEmpty(tails) : "Missing tails [bucket=" + bucket + ", tails=" + Arrays.toString(tails) + ']';

            idx = findTailIndex(tails, oldTailId, idx);

            assert tails[idx].tailId == oldTailId;

            if (newTailId == 0L) {
                Stripe[] newTails;

                if (tails.length != 1)
                    newTails = GridArrays.remove(tails, idx);
                else
                    newTails = null; // Drop the bucket completely.

                if (casBucket(bucket, tails, newTails))
                    return;
            }
            else {
                // It is safe to assign new tail since we do it only when write lock lock on tail is held.
                tails[idx].tailId = newTailId;

                return;
            }
        }
    }

    /**
     * @param tails Tails.
     * @param tailId Tail ID to find.
     * @param expIdx Expected index.
     * @return First found index of the given tail ID.
     */
    private static int findTailIndex(Stripe[] tails, long tailId, int expIdx) {
        if (expIdx != -1 && tails.length > expIdx && tails[expIdx].tailId == tailId)
            return expIdx;

        for (int i = 0; i < tails.length; i++) {
            if (tails[i].tailId == tailId)
                return i;
        }

        throw new IllegalStateException("Tail not found: " + tailId);
    }

    /**
     * @param bucket Bucket.
     * @return Page ID where the given page
     * @throws IgniteCheckedException If failed.
     */
    private Stripe getPageForPut(int bucket) throws IgniteCheckedException {
        Stripe[] tails = getBucket(bucket);

        if (tails == null)
            return addStripe(bucket, true);

        return randomTail(tails);
    }

    /**
     * @param tails Tails.
     * @return Random tail.
     */
    private static Stripe randomTail(Stripe[] tails) {
        int len = tails.length;

        assert len != 0;

        return tails[randomInt(len)];
    }

    /**
     * !!! For tests only, does not provide any correctness guarantees for concurrent access.
     *
     * @param bucket Bucket index.
     * @return Number of pages stored in this list.
     * @throws IgniteCheckedException If failed.
     */
    protected final long storedPagesCount(int bucket) throws IgniteCheckedException {
        long res = 0;

        Stripe[] tails = getBucket(bucket);

        if (tails != null) {
            for (int i = 0; i < tails.length; i++) {
                long pageId = tails[i].tailId;

                try (Page page = page(pageId)) {
                    ByteBuffer buf = page.getForRead();

                    try {
                        PagesListNodeIO io = PagesListNodeIO.VERSIONS.forPage(buf);

                        int cnt = io.getCount(buf);

                        assert cnt >= 0;

                        res += cnt;
                    }
                    finally {
                        page.releaseRead();
                    }
                }
            }
        }

        return res;
    }

    /**
     * @param bag Reuse bag.
     * @param dataPageBuf Data page buffer.
     * @param bucket Bucket.
     * @throws IgniteCheckedException If failed.
     */
    protected final void put(ReuseBag bag, Page dataPage, ByteBuffer dataPageBuf, int bucket)
        throws IgniteCheckedException {
        assert bag == null ^ dataPageBuf == null;

        int lockAttempt = 0;

        for (;;) {
            Stripe stripe = getPageForPut(bucket);

            long tailId = stripe.tailId;

            try (Page tail = page(tailId)) {
                ByteBuffer buf = writeLockPage(tail, bucket, lockAttempt++);

                if (buf == null)
                    continue;

                boolean ok = false;

                try {
                    ok = bag != null ?
                        // Here we can always take pages from the bag to build our list.
                        writePage0(tailId, buf, tail, putReuseBag, bag, null, bucket) :
                        // Here we can use the data page to build list only if it is empty and
                        // it is being put into reuse bucket. Usually this will be true, but there is
                        // a case when there is no reuse bucket in the free list, but then deadlock
                        // on node page allocation from separate reuse list is impossible.
                        // If the data page is not empty it can not be put into reuse bucket and thus
                        // the deadlock is impossible as well.
                        writePage0(tailId, buf, tail, putDataPage, dataPage, dataPageBuf, bucket);

                    if (ok)
                        return;
                }
                finally {
                    tail.releaseWrite(ok);
                }
            }
        }
    }

    private static <X, Y> boolean writePage0(long pageId,
        ByteBuffer buf,
        Page page,
        CheckingPageHandler<X, Y> h,
        X arg1,
        Y arg2,
        int bucket) throws IgniteCheckedException {
        PageIO io = PageIO.getPageIO(buf);

        return h.run(pageId, page, io, buf, arg1, arg2, bucket);
    }

    /**
     * @param bucket Bucket index.
     * @return Page for take.
     */
    private Stripe getPageForTake(int bucket) {
        Stripe[] tails = getBucket(bucket);

        if (tails == null)
            return null;

        return randomTail(tails);
    }

    /**
     * @param page Page.
     * @param bucket Bucket.
     * @param lockAttempt Lock attempts counter.
     * @return Buffer if page is locket of {@code null} if can retry lock.
     * @throws IgniteCheckedException If failed.
     */
    @Nullable private ByteBuffer writeLockPage(Page page, int bucket, int lockAttempt) throws IgniteCheckedException {
        ByteBuffer buf = page.tryGetForWrite();

        if (buf != null)
            return buf;

        if (lockAttempt == TRY_LOCK_ATTEMPTS) {
            Stripe[] stripes = getBucket(bucket);

            if (stripes == null || stripes.length < MAX_STRIPES_PER_BUCKET) {
                addStripe(bucket, false);

                return null;
            }
        }

        return lockAttempt < TRY_LOCK_ATTEMPTS ? null : page.getForWrite();
    }

    /**
     * @param bucket Bucket index.
     * @param initIoVers Optional IO to initialize page.
     * @return Removed page ID.
     * @throws IgniteCheckedException If failed.
     */
    protected final long takeEmptyPage(int bucket, @Nullable IOVersions initIoVers) throws IgniteCheckedException {
        int lockAttempt = 0;

        for (;;) {
            Stripe stripe = getPageForTake(bucket);

            if (stripe == null)
                return 0L;

            long tailId = stripe.tailId;

            try (Page tail = page(tailId)) {
                ByteBuffer tailBuf = writeLockPage(tail, bucket, lockAttempt++);

                if (tailBuf == null)
                    continue;

                try {
                    if (getPageId(tailBuf) != tailId)
                        continue;

                    PagesListNodeIO io = PagesListNodeIO.VERSIONS.forPage(tailBuf);

                    if (io.getNextId(tailBuf) != 0)
                        continue;

                    long pageId = io.takeAnyPage(tailBuf);

                    if (pageId != 0L) {
                        if (isWalDeltaRecordNeeded(wal, tail))
                            wal.log(new PagesListRemovePageRecord(cacheId, tailId, pageId));

                        return pageId;
                    }

                    // The tail page is empty, we can unlink and return it if we have a previous page.
                    long prevId = io.getPreviousId(tailBuf);

                    if (prevId != 0L) {
                        try (Page prev = page(prevId)) {
                            // Lock pages from next to previous.
                            Boolean ok = writePage(prevId, prev, cutTail, null, bucket);

                            assert ok;
                        }

                        if (initIoVers != null) {
                            tailId = PageIdUtils.changeType(tailId, FLAG_DATA);

                            PageIO initIo = initIoVers.latest();

                            initIo.initNewPage(tailBuf, tailId);

                            if (isWalDeltaRecordNeeded(wal, tail))
                                wal.log(new InitNewPageRecord(cacheId, tail.id(), initIo.getType(), initIo.getVersion(), tailId));
                        }
                        else {
                            tailId = PageIdUtils.rotatePageId(tailId);

                            PageIO.setPageId(tailBuf, tailId);

                            if (isWalDeltaRecordNeeded(wal, tail))
                                wal.log(new RecycleRecord(cacheId, tail.id(), tailId));
                        }

                        return tailId;
                    }

                    // If we do not have a previous page (we are at head), then we still can return
                    // current page but we have to drop the whole stripe. Since it is a reuse bucket,
                    // we will not do that, but just return 0L, because this may produce contention on
                    // meta page.

                    return 0L;
                }
                finally {
                    tail.releaseWrite(true);
                }
            }
        }
    }

    /**
     * @param dataPage Data page.
     * @param dataPageBuf Data page buffer.
     * @param dataIO Data page IO.
     * @param bucket Bucket index.
     * @throws IgniteCheckedException If failed.
     * @return {@code True} if page was removed.
     */
    protected final boolean removeDataPage(Page dataPage, ByteBuffer dataPageBuf, DataPageIO dataIO, int bucket)
        throws IgniteCheckedException {
        long dataPageId = dataPage.id();

        long pageId = dataIO.getFreeListPageId(dataPageBuf);

        assert pageId != 0;

        try (Page page = page(pageId)) {
            long prevId;
            long nextId;

            long recycleId = 0L;

            ByteBuffer buf = page.getForWrite();

            boolean rmvd = false;

            try {
                if (getPageId(buf) != pageId)
                    return false;

                PagesListNodeIO io = PagesListNodeIO.VERSIONS.forPage(buf);

                rmvd = io.removePage(buf, dataPageId);

                if (!rmvd)
                    return false;

                if (isWalDeltaRecordNeeded(wal, page))
                    wal.log(new PagesListRemovePageRecord(cacheId, pageId, dataPageId));

                // Reset free list page ID.
                dataIO.setFreeListPageId(dataPageBuf, 0L);

                if (isWalDeltaRecordNeeded(wal, dataPage))
                    wal.log(new DataPageSetFreeListPageRecord(cacheId, dataPageId, 0L));

                if (!io.isEmpty(buf))
                    return true; // In optimistic case we still have something in the page and can leave it as is.

                // If the page is empty, we have to try to drop it and link next and previous with each other.
                nextId = io.getNextId(buf);
                prevId = io.getPreviousId(buf);

                // If there are no next page, then we can try to merge without releasing current write lock,
                // because if we will need to lock previous page, the locking order will be already correct.
                if (nextId == 0L)
                    recycleId = mergeNoNext(pageId, page, buf, prevId, bucket);
            }
            finally {
                page.releaseWrite(rmvd);
            }

            // Perform a fair merge after lock release (to have a correct locking order).
            if (nextId != 0L)
                recycleId = merge(page, pageId, nextId, bucket);

            if (recycleId != 0L)
                reuseList.addForRecycle(new SingletonReuseBag(recycleId));

            return true;
        }
    }

    /**
     * @param page Page.
     * @param pageId Page ID.
     * @param buf Page byte buffer.
     * @param prevId Previous page ID.
     * @param bucket Bucket index.
     * @return Page ID to recycle.
     * @throws IgniteCheckedException If failed.
     */
    private long mergeNoNext(long pageId, Page page, ByteBuffer buf, long prevId, int bucket)
        throws IgniteCheckedException {
        // If we do not have a next page (we are tail) and we are on reuse bucket,
        // then we can leave as is as well, because it is normal to have an empty tail page here.
        if (isReuseBucket(bucket))
            return 0L;

        if (prevId != 0L) { // Cut tail if we have a previous page.
            try (Page prev = page(prevId)) {
                Boolean ok = writePage(prevId, prev, cutTail, null, bucket);

                assert ok; // Because we keep lock on current tail and do a world consistency check.
            }
        }
        else // If we don't have a previous, then we are tail page of free list, just drop the stripe.
            updateTail(bucket, pageId, 0L);

        return recyclePage(pageId, page, buf);
    }

    /**
     * @param pageId Page ID.
     * @param page Page.
     * @param nextId Next page ID.
     * @param bucket Bucket index.
     * @return Page ID to recycle.
     * @throws IgniteCheckedException If failed.
     */
    private long merge(Page page, long pageId, long nextId, int bucket)
        throws IgniteCheckedException {
        assert nextId != 0; // We should do mergeNoNext then.

        // Lock all the pages in correct order (from next to previous) and do the merge in retry loop.
        for (;;) {
            try (Page next = nextId == 0L ? null : page(nextId)) {
                boolean write = false;

                ByteBuffer nextBuf = next == null ? null : next.getForWrite();
                ByteBuffer buf = page.getForWrite();

                try {
                    if (getPageId(buf) != pageId)
                        return 0L; // Someone has merged or taken our empty page concurrently. Nothing to do here.

                    PagesListNodeIO io = PagesListNodeIO.VERSIONS.forPage(buf);

                    if (!io.isEmpty(buf))
                        return 0L; // No need to merge anymore.

                    // Check if we see a consistent state of the world.
                    if (io.getNextId(buf) == nextId) {
                        long recycleId = doMerge(pageId, page, buf, io, next, nextId, nextBuf, bucket);

                        write = true;

                        return recycleId; // Done.
                    }

                    // Reread next page ID and go for retry.
                    nextId = io.getNextId(buf);
                }
                finally {
                    if (next != null)
                        next.releaseWrite(write);

                    page.releaseWrite(write);
                }
            }
        }
    }

    /**
     * @param page Page.
     * @param pageId Page ID.
     * @param io IO.
     * @param buf Byte buffer.
     * @param next Next page.
     * @param nextId Next page ID.
     * @param nextBuf Next buffer.
     * @param bucket Bucket index.
     * @return Page to recycle.
     * @throws IgniteCheckedException If failed.
     */
    private long doMerge(
        long pageId,
        Page page,
        ByteBuffer buf,
        PagesListNodeIO io,
        Page next,
        long nextId,
        ByteBuffer nextBuf,
        int bucket)
        throws IgniteCheckedException {
        long prevId = io.getPreviousId(buf);

        if (nextId == 0L)
            return mergeNoNext(pageId, page, buf, prevId, bucket);
        else {
            // No one must be able to merge it while we keep a reference.
            assert getPageId(nextBuf) == nextId;

            if (prevId == 0L) { // No previous page: we are at head.
                // These references must be updated at the same time in write locks.
                assert PagesListNodeIO.VERSIONS.forPage(nextBuf).getPreviousId(nextBuf) == pageId;

                PagesListNodeIO nextIO = PagesListNodeIO.VERSIONS.forPage(nextBuf);
                nextIO.setPreviousId(nextBuf, 0);

                if (isWalDeltaRecordNeeded(wal, next))
                    wal.log(new PagesListSetPreviousRecord(cacheId, nextId, 0L));
            }
            else // Do a fair merge: link previous and next to each other.
                fairMerge(prevId, pageId, nextId, next, nextBuf);

            return recyclePage(pageId, page, buf);
        }
    }

    /**
     * Link previous and next to each other.
     *
     * @param prevId Previous Previous page ID.
     * @param pageId Page ID.
     * @param next Next page.
     * @param nextId Next page ID.
     * @param nextBuf Next buffer.
     * @throws IgniteCheckedException If failed.
     */
    private void fairMerge(long prevId,
        long pageId,
        long nextId,
        Page next,
        ByteBuffer nextBuf)
        throws IgniteCheckedException {
        try (Page prev = page(prevId)) {
            ByteBuffer prevBuf = prev.getForWrite();

            try {
                assert getPageId(prevBuf) == prevId; // Because we keep a reference.

                PagesListNodeIO prevIO = PagesListNodeIO.VERSIONS.forPage(prevBuf);
                PagesListNodeIO nextIO = PagesListNodeIO.VERSIONS.forPage(nextBuf);

                // These references must be updated at the same time in write locks.
                assert prevIO.getNextId(prevBuf) == pageId;
                assert nextIO.getPreviousId(nextBuf) == pageId;

                prevIO.setNextId(prevBuf, nextId);

                if (isWalDeltaRecordNeeded(wal, prev))
                    wal.log(new PagesListSetNextRecord(cacheId, prevId, nextId));

                nextIO.setPreviousId(nextBuf, prevId);

                if (isWalDeltaRecordNeeded(wal, next))
                    wal.log(new PagesListSetPreviousRecord(cacheId, nextId, prevId));
            }
            finally {
                prev.releaseWrite(true);
            }
        }
    }

    /**
     * @param page Page.
     * @param pageId Page ID.
     * @param buf Byte buffer.
     * @return Rotated page ID.
     * @throws IgniteCheckedException If failed.
     */
    private long recyclePage(long pageId, Page page, ByteBuffer buf) throws IgniteCheckedException {
        pageId = PageIdUtils.rotatePageId(pageId);

        PageIO.setPageId(buf, pageId);

        if (isWalDeltaRecordNeeded(wal, page))
            wal.log(new RecycleRecord(cacheId, page.id(), pageId));

        return pageId;
    }

    /**
     * Page handler.
     */
    private static abstract class CheckingPageHandler<X, Y>  {
        /**
         * @param pageId Page ID.
         * @param page Page.
         * @param buf Buffer.
         * @param io IO.
         * @param arg1 Argument 1.
         * @param arg2 Argument 2.
         * @param bucket Bucket.
         * @throws IgniteCheckedException If failed.
         * @return Result.
         */
        public final boolean run(long pageId, Page page, PageIO io, ByteBuffer buf, X arg1, Y arg2, int bucket)
            throws IgniteCheckedException {
            if (getPageId(buf) != pageId)
                return Boolean.FALSE;

            assert io instanceof PagesListNodeIO : io;

            return run0(pageId, page, buf, (PagesListNodeIO)io, arg1, arg2, bucket);
        }

        /**
         * @param pageId Page ID.
         * @param page Page.
         * @param buf Buffer.
         * @param io IO.
         * @param arg1 Argument 1.
         * @param arg2 Argument 2.
         * @param bucket Bucket.
         * @throws IgniteCheckedException If failed.
         * @return Result.
         */
        protected abstract boolean run0(long pageId,
            Page page,
            ByteBuffer buf,
            PagesListNodeIO io,
            X arg1,
            Y arg2,
            int bucket) throws IgniteCheckedException;
    }

    /**
     * Singleton reuse bag.
     */
    private static final class SingletonReuseBag implements ReuseBag {
        /** */
        long pageId;

        /**
         * @param pageId Page ID.
         */
        SingletonReuseBag(long pageId) {
            this.pageId = pageId;
        }

        /** {@inheritDoc} */
        @Override public void addFreePage(long pageId) {
            throw new IllegalStateException("Should never be called.");
        }

        /** {@inheritDoc} */
        @Override public long pollFreePage() {
            long res = pageId;

            pageId = 0L;

            return res;
        }

        /** {@inheritDoc} */
        @Override public String toString() {
            return S.toString(SingletonReuseBag.class, this);
        }
    }

    /**
     *
     */
    public static final class Stripe {
        /** */
        public volatile long tailId;

        /**
         * @param tailId Tail ID.
         */
        Stripe(long tailId) {
            this.tailId = tailId;
        }
    }
}
