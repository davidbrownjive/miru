package com.jivesoftware.os.miru.service.index.lab;

import com.google.common.primitives.Bytes;
import com.jivesoftware.os.filer.io.ByteArrayFiler;
import com.jivesoftware.os.filer.io.FilerIO;
import com.jivesoftware.os.filer.io.StripingLocksProvider;
import com.jivesoftware.os.filer.io.api.KeyRange;
import com.jivesoftware.os.filer.io.api.StackBuffer;
import com.jivesoftware.os.jive.utils.ordered.id.OrderIdProvider;
import com.jivesoftware.os.lab.api.ValueIndex;
import com.jivesoftware.os.lab.api.ValueStream;
import com.jivesoftware.os.lab.io.api.UIO;
import com.jivesoftware.os.miru.api.base.MiruTermId;
import com.jivesoftware.os.miru.plugin.MiruInterner;
import com.jivesoftware.os.miru.plugin.bitmap.MiruBitmaps;
import com.jivesoftware.os.miru.plugin.index.BitmapAndLastId;
import com.jivesoftware.os.miru.plugin.index.IndexAlignedBitmapMerger;
import com.jivesoftware.os.miru.plugin.index.MiruFieldIndex;
import com.jivesoftware.os.miru.plugin.index.MiruInvertedIndex;
import com.jivesoftware.os.miru.plugin.index.MiruTermComposer;
import com.jivesoftware.os.miru.plugin.index.MultiIndexTx;
import com.jivesoftware.os.miru.plugin.index.TermIdStream;
import com.jivesoftware.os.miru.plugin.partition.TrackError;
import com.jivesoftware.os.miru.service.index.lab.LabInvertedIndex.SizeAndBytes;
import com.jivesoftware.os.mlogger.core.MetricLogger;
import com.jivesoftware.os.mlogger.core.MetricLoggerFactory;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import org.apache.commons.lang.mutable.MutableLong;

/**
 * @author jonathan.colt
 */
public class LabFieldIndex<BM extends IBM, IBM> implements MiruFieldIndex<BM, IBM> {

    private static final MetricLogger LOG = MetricLoggerFactory.getLogger();

    private static final int KEY_TERM_OFFSET = 1 + 4; // prefix byte plus fieldId bytes

    private final OrderIdProvider idProvider;
    private final MiruBitmaps<BM, IBM> bitmaps;
    private final TrackError trackError;
    private final byte[] prefix;
    private final ValueIndex[] bitmapIndexes;
    private final ValueIndex[] termIndexes;
    private final ValueIndex[] cardinalities;
    private final boolean[] hasCardinalities;
    // We could lock on both field + termId for improved hash/striping, but we favor just termId to reduce object creation
    private final StripingLocksProvider<MiruTermId> stripingLocksProvider;
    private final MiruInterner<MiruTermId> termInterner;

    public LabFieldIndex(OrderIdProvider idProvider,
        MiruBitmaps<BM, IBM> bitmaps,
        TrackError trackError,
        byte[] prefix,
        ValueIndex[] bitmapIndexes,
        ValueIndex[] termIndexes,
        ValueIndex[] cardinalities,
        boolean[] hasCardinalities,
        StripingLocksProvider<MiruTermId> stripingLocksProvider,
        MiruInterner<MiruTermId> termInterner) throws Exception {

        this.idProvider = idProvider;
        this.bitmaps = bitmaps;
        this.trackError = trackError;
        this.prefix = prefix;
        this.bitmapIndexes = bitmapIndexes;
        this.termIndexes = termIndexes;
        this.cardinalities = cardinalities;
        this.hasCardinalities = hasCardinalities;
        this.stripingLocksProvider = stripingLocksProvider;
        this.termInterner = termInterner;
    }

    private ValueIndex getBitmapIndex(int fieldId) {
        return bitmapIndexes[fieldId % bitmapIndexes.length];
    }

    private ValueIndex getTermIndex(int fieldId) {
        return termIndexes[fieldId % termIndexes.length];
    }

    private ValueIndex getCardinalityIndex(int fieldId) {
        return cardinalities[fieldId % cardinalities.length];
    }

    @Override
    public void append(int fieldId, MiruTermId termId, int[] ids, long[] counts, StackBuffer stackBuffer) throws Exception {
        getIndex("append", fieldId, termId).append(stackBuffer, ids);
        mergeCardinalities(fieldId, termId, ids, counts, stackBuffer);
    }

    @Override
    public void set(int fieldId, MiruTermId termId, int[] ids, long[] counts, StackBuffer stackBuffer) throws Exception {
        getIndex("set", fieldId, termId).set(stackBuffer, ids);
        mergeCardinalities(fieldId, termId, ids, counts, stackBuffer);
    }

    @Override
    public void setIfEmpty(int fieldId, MiruTermId termId, int id, long count, StackBuffer stackBuffer) throws Exception {
        if (getIndex("setIfEmpty", fieldId, termId).setIfEmpty(stackBuffer, id)) {
            mergeCardinalities(fieldId, termId, new int[] { id }, new long[] { count }, stackBuffer);
        }
    }

    @Override
    public void remove(int fieldId, MiruTermId termId, int[] ids, StackBuffer stackBuffer) throws Exception {
        getIndex("remove", fieldId, termId).remove(stackBuffer, ids);
        mergeCardinalities(fieldId, termId, ids, cardinalities[fieldId] != null ? new long[ids.length] : null, stackBuffer);
    }

    @Override
    public void streamTermIdsForField(String name,
        int fieldId,
        List<KeyRange> ranges,
        final TermIdStream termIdStream,
        StackBuffer stackBuffer) throws Exception {
        MutableLong bytes = new MutableLong();
        byte[] fieldIdBytes = UIO.intBytes(fieldId);
        if (ranges == null) {
            byte[] from = fieldIndexPrefixLowerInclusive(fieldIdBytes);
            byte[] to = fieldIndexPrefixUpperExclusive(fieldIdBytes);
            getTermIndex(fieldId).rangeScan(from, to, (index, key, timestamp, tombstoned, version, payload) -> {
                bytes.add(key.length);
                return termIdStream.stream(termInterner.intern(key, KEY_TERM_OFFSET, key.length - (KEY_TERM_OFFSET)));
            });
        } else {
            for (KeyRange range : ranges) {
                byte[] from = range.getStartInclusiveKey() != null
                    ? fieldIndexKey(fieldIdBytes, range.getStartInclusiveKey())
                    : fieldIndexPrefixLowerInclusive(fieldIdBytes);
                byte[] to = range.getStopExclusiveKey() != null
                    ? fieldIndexKey(fieldIdBytes, range.getStopExclusiveKey())
                    : fieldIndexPrefixUpperExclusive(fieldIdBytes);
                getTermIndex(fieldId).rangeScan(from, to, (index, key, timestamp, tombstoned, version, payload) -> {
                    bytes.add(key.length);
                    return termIdStream.stream(termInterner.intern(key, KEY_TERM_OFFSET, key.length - (KEY_TERM_OFFSET)));
                });
            }
        }

        LOG.inc("count>streamTermIdsForField>total");
        LOG.inc("count>streamTermIdsForField>" + name + ">total");
        LOG.inc("count>streamTermIdsForField>" + name + ">" + fieldId);
        LOG.inc("bytes>streamTermIdsForField>total", bytes.longValue());
        LOG.inc("bytes>streamTermIdsForField>" + name + ">total", bytes.longValue());
        LOG.inc("bytes>streamTermIdsForField>" + name + ">" + fieldId, bytes.longValue());
    }

    @Override
    public MiruInvertedIndex<BM, IBM> get(String name, int fieldId, MiruTermId termId) throws Exception {
        return getIndex(name, fieldId, termId);
    }

    @Override
    public MiruInvertedIndex<BM, IBM> getOrCreateInvertedIndex(String name, int fieldId, MiruTermId term) throws Exception {
        return getIndex(name, fieldId, term);
    }

    private byte[] fieldIndexPrefixLowerInclusive(byte[] fieldIdBytes) {
        return Bytes.concat(prefix, fieldIdBytes);
    }

    private byte[] fieldIndexPrefixUpperExclusive(byte[] fieldIdBytes) {
        byte[] bytes = fieldIndexPrefixLowerInclusive(fieldIdBytes);
        MiruTermComposer.makeUpperExclusive(bytes);
        return bytes;
    }

    private byte[] fieldIndexKey(byte[] fieldIdBytes, byte[] termIdBytes) {
        return Bytes.concat(prefix, fieldIdBytes, termIdBytes);
    }

    private byte[] cardinalityIndexKey(byte[] fieldIdBytes, int id, byte[] termIdBytes) {
        return Bytes.concat(prefix, fieldIdBytes, UIO.intBytes(id), termIdBytes);
    }

    private MiruInvertedIndex<BM, IBM> getIndex(String name, int fieldId, MiruTermId termId) throws Exception {
        byte[] fieldIdBytes = UIO.intBytes(fieldId);

        return new LabInvertedIndex<>(idProvider,
            bitmaps,
            trackError,
            name,
            fieldId,
            fieldIndexKey(fieldIdBytes, termId.getBytes()),
            getBitmapIndex(fieldId),
            stripingLocksProvider.lock(termId, 0));
    }

    @Override
    public void multiGet(String name, int fieldId, MiruTermId[] termIds, BitmapAndLastId<BM>[] results, StackBuffer stackBuffer) throws Exception {
        byte[] fieldIdBytes = UIO.intBytes(fieldId);
        MutableLong bytes = new MutableLong();
        getBitmapIndex(fieldId).get(
            keyStream -> {
                for (int i = 0; i < termIds.length; i++) {
                    if (termIds[i] != null) {
                        byte[] key = fieldIndexKey(fieldIdBytes, termIds[i].getBytes());
                        if (!keyStream.key(i, key, 0, key.length)) {
                            return false;
                        }
                    }
                }
                return true;
            },
            (index, key, timestamp, tombstoned, version, payload) -> {
                if (payload != null) {
                    BitmapAndLastId<BM> bitmapAndLastId = LabInvertedIndex.deser(bitmaps, trackError, payload, -1);
                    if (bitmapAndLastId != null) {
                        results[index] = bitmapAndLastId;
                    }
                }
                return true;
            });

        LOG.inc("count>multiGet>total");
        LOG.inc("count>multiGet>" + name + ">total");
        LOG.inc("count>multiGet>" + name + ">" + fieldId);
        LOG.inc("bytes>multiGet>total", bytes.longValue());
        LOG.inc("bytes>multiGet>" + name + ">total", bytes.longValue());
        LOG.inc("bytes>multiGet>" + name + ">" + fieldId, bytes.longValue());
    }

    @Override
    public void multiGetLastIds(String name, int fieldId, MiruTermId[] termIds, int[] results, StackBuffer stackBuffer) throws Exception {
        byte[] fieldIdBytes = UIO.intBytes(fieldId);
        MutableLong bytes = new MutableLong();
        getBitmapIndex(fieldId).get(
            keyStream -> {
                for (int i = 0; i < termIds.length; i++) {
                    if (termIds[i] != null) {
                        byte[] key = fieldIndexKey(fieldIdBytes, termIds[i].getBytes());
                        if (!keyStream.key(i, key, 0, key.length)) {
                            return false;
                        }
                    }
                }
                return true;
            },
            (index, key, timestamp, tombstoned, version, payload) -> {
                if (payload != null) {
                    bytes.add(payload.length);
                    results[index] = UIO.bytesInt(payload);
                }
                return true;
            });

        LOG.inc("count>multiGetLastIds>total");
        LOG.inc("count>multiGetLastIds>" + name + ">total");
        LOG.inc("count>multiGetLastIds>" + name + ">" + fieldId);
        LOG.inc("bytes>multiGetLastIds>total", bytes.longValue());
        LOG.inc("bytes>multiGetLastIds>" + name + ">total", bytes.longValue());
        LOG.inc("bytes>multiGetLastIds>" + name + ">" + fieldId, bytes.longValue());
    }

    @Override
    public void multiTxIndex(String name,
        int fieldId,
        MiruTermId[] termIds,
        int considerIfLastIdGreaterThanN,
        StackBuffer stackBuffer,
        MultiIndexTx<IBM> indexTx) throws Exception {

        byte[] fieldIdBytes = UIO.intBytes(fieldId);
        MutableLong bytes = new MutableLong();
        getBitmapIndex(fieldId).get(
            keyStream -> {
                for (int i = 0; i < termIds.length; i++) {
                    if (termIds[i] != null) {
                        byte[] key = fieldIndexKey(fieldIdBytes, termIds[i].getBytes());
                        if (!keyStream.key(i, key, 0, key.length)) {
                            return false;
                        }
                    }
                }
                return true;
            },
            (index, key, timestamp, tombstoned, version, payload) -> {
                if (payload != null) {
                    bytes.add(payload.length);
                    int lastId = -1;
                    if (considerIfLastIdGreaterThanN >= 0) {
                        lastId = UIO.bytesInt(payload);
                    }
                    if (lastId < 0 || lastId > considerIfLastIdGreaterThanN) {
                        indexTx.tx(index, lastId, null, new ByteArrayFiler(payload), LabInvertedIndex.LAST_ID_LENGTH, stackBuffer);
                    }
                }
                return true;
            });

        LOG.inc("count>multiTxIndex>total");
        LOG.inc("count>multiTxIndex>" + name + ">total");
        LOG.inc("count>multiTxIndex>" + name + ">" + fieldId);
        LOG.inc("bytes>multiTxIndex>total", bytes.longValue());
        LOG.inc("bytes>multiTxIndex>" + name + ">total", bytes.longValue());
        LOG.inc("bytes>multiTxIndex>" + name + ">" + fieldId, bytes.longValue());
    }

    @Override
    public long getCardinality(int fieldId, MiruTermId termId, int id, StackBuffer stackBuffer) throws Exception {
        if (hasCardinalities[fieldId]) {
            byte[] fieldIdBytes = UIO.intBytes(fieldId);
            long[] count = { 0 };
            getCardinalityIndex(fieldId).get(cardinalityIndexKey(fieldIdBytes, id, termId.getBytes()),
                (int index, byte[] key, long timestamp, boolean tombstoned, long version, byte[] payload) -> {
                    if (payload != null && !tombstoned) {
                        count[0] = UIO.bytesLong(payload);
                    }
                    return false;
                });
            return count[0];
        }
        return -1;
    }

    @Override
    public long[] getCardinalities(int fieldId, MiruTermId termId, int[] ids, StackBuffer stackBuffer) throws Exception {
        long[] counts = new long[ids.length];
        if (hasCardinalities[fieldId]) {
            byte[] fieldIdBytes = UIO.intBytes(fieldId);
            ValueIndex cardinalityIndex = getCardinalityIndex(fieldId);
            for (int i = 0; i < ids.length; i++) {
                if (ids[i] >= 0) {
                    int index = i;
                    //TODO multiget
                    cardinalityIndex.get(cardinalityIndexKey(fieldIdBytes, ids[i], termId.getBytes()),
                        (int index1, byte[] key, long timestamp, boolean tombstoned, long version, byte[] payload) -> {
                            if (payload != null && !tombstoned) {
                                counts[index] = UIO.bytesLong(payload);
                            }
                            return false;
                        });
                }
            }
        } else {
            Arrays.fill(counts, -1);
        }
        return counts;
    }

    @Override
    public long getGlobalCardinality(int fieldId, MiruTermId termId, StackBuffer stackBuffer) throws Exception {
        return getCardinality(fieldId, termId, -1, stackBuffer);
    }

    @Override
    public void multiMerge(int fieldId, MiruTermId[] termIds, IndexAlignedBitmapMerger<BM> merger, StackBuffer stackBuffer) throws Exception {
        byte[] fieldIdBytes = UIO.intBytes(fieldId);

        MutableLong bytesRead = new MutableLong();
        MutableLong bytesWrite = new MutableLong();
        int[] indexPowers = new int[32];

        SizeAndBytes[] sabs = new SizeAndBytes[termIds.length];
        ValueIndex fieldIndex = getBitmapIndex(fieldId);
        fieldIndex.get(
            keyStream -> {
                for (int i = 0; i < termIds.length; i++) {
                    if (termIds[i] != null) {
                        byte[] key = fieldIndexKey(fieldIdBytes, termIds[i].getBytes());
                        if (!keyStream.key(i, key, 0, key.length)) {
                            return false;
                        }
                    }
                }
                return true;
            },
            (index, key, timestamp1, tombstoned, version1, payload) -> {
                BitmapAndLastId<BM> backing = null;
                if (payload != null) {
                    bytesRead.add(payload.length);
                    backing = LabInvertedIndex.deser(bitmaps, trackError, payload, -1);
                }
                BitmapAndLastId<BM> merged = merger.merge(index, backing);
                if (merged != null) {
                    try {
                        SizeAndBytes sizeAndBytes = LabInvertedIndex.getSizeAndBytes(bitmaps, merged.bitmap, merged.lastId);
                        indexPowers[FilerIO.chunkPower(sizeAndBytes.filerSizeInBytes, 0)]++;
                        sabs[index] = sizeAndBytes;
                    } catch (Exception e) {
                        throw new IOException("Failed to serialize bitmap", e);
                    }
                }
                return true;
            });

        long timestamp = System.currentTimeMillis();
        long version = idProvider.nextId();
        fieldIndex.append(stream -> {
            for (int i = 0; i < termIds.length; i++) {
                if (sabs[i] != null) {
                    bytesWrite.add(sabs[i].bytes.length);
                    stream.stream(-1, fieldIndexKey(fieldIdBytes, termIds[i].getBytes()), timestamp, false, version, sabs[i].bytes);
                }
            }
            return true;
        }, false);

        getTermIndex(fieldId).append(valueStream -> {
            for (int i = 0; i < termIds.length; i++) {
                if (termIds[i] != null) {
                    byte[] key = fieldIndexKey(fieldIdBytes, termIds[i].getBytes());
                    if (!valueStream.stream(-1, key, timestamp, false, version, null)) {
                        return false;
                    }
                }
            }
            return true;
        }, true);

        LOG.inc("count>multiMerge>total");
        LOG.inc("count>multiMerge>" + fieldId);
        LOG.inc("bytes>multiMergeRead>total", bytesRead.longValue());
        LOG.inc("bytes>multiMergeRead>" + fieldId, bytesRead.longValue());
        LOG.inc("bytes>multiMergeWrite>total", bytesWrite.longValue());
        LOG.inc("bytes>multiMergeWrite>" + fieldId, bytesWrite.longValue());
        for (int i = 0; i < indexPowers.length; i++) {
            if (indexPowers[i] > 0) {
                LOG.inc("count>multiMerge>power>" + i, indexPowers[i]);
            }
        }
    }

    @Override
    public void mergeCardinalities(int fieldId, MiruTermId termId, int[] ids, long[] counts, StackBuffer stackBuffer) throws Exception {
        if (hasCardinalities[fieldId] && counts != null) {
            byte[] fieldBytes = UIO.intBytes(fieldId);
            ValueIndex cardinalityIndex = getCardinalityIndex(fieldId);

            long[] merged = new long[counts.length];
            long delta = 0;
            System.arraycopy(counts, 0, merged, 0, counts.length);
            for (int i = 0; i < ids.length; i++) {
                int index = i;
                //TODO multiget
                cardinalityIndex.get(cardinalityIndexKey(fieldBytes, ids[i], termId.getBytes()),
                    (int index1, byte[] key, long timestamp, boolean tombstoned, long version, byte[] payload) -> {
                        if (payload != null && !tombstoned) {
                            merged[index] += UIO.bytesLong(payload);
                        }
                        return false;
                    });
                delta += merged[i] - counts[i];
            }

            long[] globalCount = { 0 };
            cardinalityIndex.get(cardinalityIndexKey(fieldBytes, -1, termId.getBytes()),
                (int index, byte[] key, long timestamp, boolean tombstoned, long version, byte[] payload) -> {
                    if (payload != null && !tombstoned) {
                        globalCount[0] = UIO.bytesLong(payload);
                    }
                    return false;
                });
            globalCount[0] += delta;

            long timestamp = System.currentTimeMillis();
            long version = idProvider.nextId();
            cardinalityIndex.append(valueStream -> {
                for (int i = 0; i < ids.length; i++) {
                    byte[] key = cardinalityIndexKey(fieldBytes, ids[i], termId.getBytes());
                    if (!valueStream.stream(-1, key, timestamp, false, version, UIO.longBytes(merged[i]))) {
                        return false;
                    }
                }

                byte[] globalKey = cardinalityIndexKey(fieldBytes, -1, termId.getBytes());
                valueStream.stream(-1, globalKey, timestamp, false, version, UIO.longBytes(globalCount[0]));
                return true;
            }, true);
        }
    }

}
