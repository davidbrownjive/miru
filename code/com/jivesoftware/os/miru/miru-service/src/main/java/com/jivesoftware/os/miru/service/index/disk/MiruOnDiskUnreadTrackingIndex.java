package com.jivesoftware.os.miru.service.index.disk;

import com.google.common.base.Optional;
import com.jivesoftware.os.filer.io.StripingLocksProvider;
import com.jivesoftware.os.filer.keyed.store.KeyedFilerStore;
import com.jivesoftware.os.miru.api.base.MiruStreamId;
import com.jivesoftware.os.miru.api.base.MiruTenantId;
import com.jivesoftware.os.miru.plugin.bitmap.MiruBitmaps;
import com.jivesoftware.os.miru.plugin.index.MiruInvertedIndex;
import com.jivesoftware.os.miru.plugin.index.MiruInvertedIndexAppender;
import com.jivesoftware.os.miru.plugin.index.MiruUnreadTrackingIndex;
import com.jivesoftware.os.miru.service.index.BulkExport;
import com.jivesoftware.os.miru.service.index.BulkImport;
import com.jivesoftware.os.miru.service.index.SimpleBulkExport;
import com.jivesoftware.os.miru.service.index.memory.KeyedInvertedIndexStream;
import java.io.IOException;
import java.util.Collections;

/** @author jonathan */
public class MiruOnDiskUnreadTrackingIndex<BM> implements MiruUnreadTrackingIndex<BM>,
    BulkImport<Void, KeyedInvertedIndexStream<BM>> {

    private final MiruBitmaps<BM> bitmaps;
    private final KeyedFilerStore store;
    private final StripingLocksProvider<MiruStreamId> stripingLocksProvider;
    private final long newFilerInitialCapacity = 512;

    public MiruOnDiskUnreadTrackingIndex(MiruBitmaps<BM> bitmaps, KeyedFilerStore store, StripingLocksProvider<MiruStreamId> stripingLocksProvider)
        throws Exception {
        this.bitmaps = bitmaps;
        this.store = store;
        this.stripingLocksProvider = stripingLocksProvider;
        /*
        //TODO actual capacity? should this be shared with a key prefix?
        this.store = new PartitionedMapChunkBackedKeyedStore(
            new FileBackedMapChunkFactory(8, false, 8, false, 100, mapDirectories),
            new FileBackedMapChunkFactory(8, false, 8, false, 100, swapDirectories),
            chunkStore,
            keyedStoreStripingLocksProvider,
            4); //TODO expose number of partitions
        */
    }

    @Override
    public void index(MiruStreamId streamId, int id) throws Exception {
        getAppender(streamId).append(id);
    }

    @Override
    public Optional<BM> getUnread(MiruStreamId streamId) throws Exception {
        return new MiruOnDiskInvertedIndex<>(bitmaps, store, streamId.getBytes(), -1, -1, stripingLocksProvider.lock(streamId)).getIndex();
    }

    @Override
    public MiruInvertedIndexAppender getAppender(MiruStreamId streamId) throws Exception {
        return getOrCreateUnread(streamId);
    }

    private MiruInvertedIndex<BM> getOrCreateUnread(MiruStreamId streamId) throws Exception {
        return new MiruOnDiskInvertedIndex<>(bitmaps, store, streamId.getBytes(), -1, newFilerInitialCapacity, stripingLocksProvider.lock(streamId));
    }

    @Override
    public void applyRead(MiruStreamId streamId, BM readMask) throws Exception {
        MiruInvertedIndex<BM> unread = getOrCreateUnread(streamId);
        unread.andNotToSourceSize(Collections.singletonList(readMask));
    }

    @Override
    public void applyUnread(MiruStreamId streamId, BM unreadMask) throws Exception {
        MiruInvertedIndex<BM> unread = getOrCreateUnread(streamId);
        unread.orToSourceSize(unreadMask);
    }

    @Override
    public long sizeInMemory() throws Exception {
        return 0;
    }

    @Override
    public long sizeOnDisk() throws Exception {
        return 0;
    }

    @Override
    public void close() {
        store.close();
    }

    @Override
    public void bulkImport(final MiruTenantId tenantId, BulkExport<Void, KeyedInvertedIndexStream<BM>> export) throws Exception {
        export.bulkExport(tenantId, new KeyedInvertedIndexStream<BM>() {
            @Override
            public boolean stream(byte[] key, MiruInvertedIndex<BM> importIndex) throws IOException {
                try {
                    MiruOnDiskInvertedIndex<BM> invertedIndex = new MiruOnDiskInvertedIndex<>(bitmaps, store, key, -1, -1, new Object());
                    invertedIndex.bulkImport(tenantId, new SimpleBulkExport<>(importIndex));
                    return true;
                } catch (Exception e) {
                    throw new IOException("Failed to stream import", e);
                }
            }
        });
    }
}
