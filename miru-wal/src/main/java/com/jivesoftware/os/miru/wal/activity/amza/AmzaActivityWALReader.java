package com.jivesoftware.os.miru.wal.activity.amza;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Optional;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.jivesoftware.os.amza.client.AmzaClientProvider.AmzaClient;
import com.jivesoftware.os.amza.shared.partition.PartitionProperties;
import com.jivesoftware.os.amza.shared.take.TakeCursors;
import com.jivesoftware.os.filer.io.FilerIO;
import com.jivesoftware.os.miru.api.activity.MiruPartitionId;
import com.jivesoftware.os.miru.api.activity.MiruPartitionedActivity;
import com.jivesoftware.os.miru.api.base.MiruTenantId;
import com.jivesoftware.os.miru.api.marshall.JacksonJsonObjectTypeMarshaller;
import com.jivesoftware.os.miru.api.topology.NamedCursor;
import com.jivesoftware.os.miru.api.wal.AmzaCursor;
import com.jivesoftware.os.miru.api.wal.AmzaSipCursor;
import com.jivesoftware.os.miru.api.wal.MiruActivityLookupEntry;
import com.jivesoftware.os.miru.api.wal.MiruActivityWALStatus;
import com.jivesoftware.os.miru.api.wal.MiruActivityWALStatus.WriterCount;
import com.jivesoftware.os.miru.api.wal.MiruVersionedActivityLookupEntry;
import com.jivesoftware.os.miru.api.wal.MiruWALClient.WriterCursor;
import com.jivesoftware.os.miru.wal.AmzaWALUtil;
import com.jivesoftware.os.miru.wal.activity.MiruActivityWALReader;
import com.jivesoftware.os.miru.wal.activity.rcvs.MiruActivityWALColumnKey;
import com.jivesoftware.os.miru.wal.activity.rcvs.MiruActivityWALColumnKeyMarshaller;
import com.jivesoftware.os.miru.wal.lookup.PartitionsStream;
import com.jivesoftware.os.mlogger.core.MetricLogger;
import com.jivesoftware.os.mlogger.core.MetricLoggerFactory;
import com.jivesoftware.os.routing.bird.shared.HostPort;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.commons.lang.mutable.MutableLong;

/**
 *
 */
public class AmzaActivityWALReader implements MiruActivityWALReader<AmzaCursor, AmzaSipCursor> {

    private static final MetricLogger LOG = MetricLoggerFactory.getLogger();

    private final AmzaWALUtil amzaWALUtil;
    private final MiruActivityWALColumnKeyMarshaller columnKeyMarshaller = new MiruActivityWALColumnKeyMarshaller();
    private final JacksonJsonObjectTypeMarshaller<MiruPartitionedActivity> partitionedActivityMarshaller;

    public AmzaActivityWALReader(AmzaWALUtil amzaWALUtil, ObjectMapper mapper) {
        this.amzaWALUtil = amzaWALUtil;
        this.partitionedActivityMarshaller = new JacksonJsonObjectTypeMarshaller<>(MiruPartitionedActivity.class, mapper);
    }

    private Map<String, NamedCursor> extractCursors(List<NamedCursor> cursors) {
        Map<String, NamedCursor> cursorsByName = Maps.newHashMapWithExpectedSize(cursors.size());
        for (NamedCursor namedCursor : cursors) {
            cursorsByName.put(namedCursor.name, namedCursor);
        }
        return cursorsByName;
    }

    private TakeCursors takeCursors(StreamMiruActivityWAL streamMiruActivityWAL,
        AmzaClient client,
        Map<String, NamedCursor> cursorsByName) throws Exception {
        return amzaWALUtil.take(client, cursorsByName, (rowTxId, prefix, key, value) -> {
            MiruPartitionedActivity partitionedActivity = partitionedActivityMarshaller.fromBytes(value.getValue());
            if (partitionedActivity != null && partitionedActivity.type.isActivityType()) {
                if (!streamMiruActivityWAL.stream(partitionedActivity.timestamp, partitionedActivity, value.getTimestampId())) {
                    return false;
                }
            }
            return true;
        });
    }

    private boolean isEndOfStream(AmzaClient client) throws Exception {
        byte[] fromKey = columnKeyMarshaller.toLexBytes(new MiruActivityWALColumnKey(MiruPartitionedActivity.Type.END.getSort(), Long.MIN_VALUE));
        byte[] toKey = columnKeyMarshaller.toLexBytes(new MiruActivityWALColumnKey(MiruPartitionedActivity.Type.BEGIN.getSort(), Long.MAX_VALUE));
        Set<Integer> begins = Sets.newHashSet();
        Set<Integer> ends = Sets.newHashSet();
        client.scan(null, fromKey, null, toKey, (rowTxId, prefix, key, value) -> {
            MiruPartitionedActivity partitionedActivity = partitionedActivityMarshaller.fromBytes(value.getValue());
            if (partitionedActivity != null) {
                if (partitionedActivity.type == MiruPartitionedActivity.Type.BEGIN) {
                    begins.add(partitionedActivity.writerId);
                } else if (partitionedActivity.type == MiruPartitionedActivity.Type.END) {
                    ends.add(partitionedActivity.writerId);
                }
            }
            return true;
        });
        return !begins.isEmpty() && ends.containsAll(begins);
    }

    @Override
    public HostPort[] getRoutingGroup(MiruTenantId tenantId, MiruPartitionId partitionId) throws Exception {
        return amzaWALUtil.getActivityRoutingGroup(tenantId, partitionId, Optional.<PartitionProperties>absent());
    }

    @Override
    public AmzaCursor stream(MiruTenantId tenantId,
        MiruPartitionId partitionId,
        AmzaCursor cursor,
        int batchSize,
        StreamMiruActivityWAL streamMiruActivityWAL) throws Exception {

        AmzaClient client = amzaWALUtil.getActivityClient(tenantId, partitionId);
        if (client == null) {
            return cursor;
        }

        Map<String, NamedCursor> cursorsByName = cursor != null ? extractCursors(cursor.cursors) : Maps.newHashMap();
        Map<String, NamedCursor> sipCursorsByName = cursor != null && cursor.sipCursor != null
            ? extractCursors(cursor.sipCursor.cursors) : Maps.newHashMap();

        TakeCursors takeCursors = takeCursors(streamMiruActivityWAL, client, cursorsByName);

        amzaWALUtil.mergeCursors(cursorsByName, takeCursors);
        amzaWALUtil.mergeCursors(sipCursorsByName, takeCursors);

        return new AmzaCursor(cursorsByName.values(), new AmzaSipCursor(sipCursorsByName.values(), false));
    }

    @Override
    public AmzaSipCursor streamSip(MiruTenantId tenantId,
        MiruPartitionId partitionId,
        AmzaSipCursor sipCursor,
        int batchSize,
        StreamMiruActivityWAL streamMiruActivityWAL) throws Exception {

        AmzaClient client = amzaWALUtil.getActivityClient(tenantId, partitionId);
        if (client == null) {
            return sipCursor;
        }

        Map<String, NamedCursor> sipCursorsByName = sipCursor != null ? extractCursors(sipCursor.cursors) : Maps.newHashMap();

        TakeCursors takeCursors = takeCursors(streamMiruActivityWAL, client, sipCursorsByName);
        if (takeCursors.tookToEnd) {
            streamMiruActivityWAL.stream(-1, null, -1);
        }
        boolean endOfStream = takeCursors.tookToEnd && isEndOfStream(client);

        amzaWALUtil.mergeCursors(sipCursorsByName, takeCursors);

        return new AmzaSipCursor(sipCursorsByName.values(), endOfStream);
    }

    @Override
    public WriterCursor getCursorForWriterId(MiruTenantId tenantId, MiruPartitionId partitionId, int writerId) throws Exception {
        LOG.inc("getCursorForWriterId");
        LOG.inc("getCursorForWriterId>" + writerId, tenantId.toString());
        AmzaClient client = amzaWALUtil.getActivityClient(tenantId, partitionId);
        if (client != null) {
            byte[] key = columnKeyMarshaller.toLexBytes(new MiruActivityWALColumnKey(MiruPartitionedActivity.Type.BEGIN.getSort(), (long) writerId));
            byte[] valueBytes = client.getValue(null, key);
            if (valueBytes != null) {
                MiruPartitionedActivity latestBoundaryActivity = partitionedActivityMarshaller.fromBytes(valueBytes);
                return new WriterCursor(latestBoundaryActivity.getPartitionId(), latestBoundaryActivity.index);
            }
        }
        return new WriterCursor(0, 0);
    }

    @Override
    public MiruActivityWALStatus getStatus(MiruTenantId tenantId, MiruPartitionId partitionId) throws Exception {
        final Map<Integer, WriterCount> counts = Maps.newHashMap();
        final List<Integer> begins = Lists.newArrayList();
        final List<Integer> ends = Lists.newArrayList();
        AmzaClient client = amzaWALUtil.getActivityClient(tenantId, partitionId);
        if (client != null) {
            byte[] fromKey = columnKeyMarshaller.toLexBytes(new MiruActivityWALColumnKey(MiruPartitionedActivity.Type.END.getSort(), 0));
            byte[] toKey = columnKeyMarshaller.toLexBytes(new MiruActivityWALColumnKey(MiruPartitionedActivity.Type.BEGIN.getSort(), Long.MAX_VALUE));
            client.scan(null, fromKey, null, toKey, (rowTxId, prefix, key, value) -> {
                if (value != null) {
                    MiruPartitionedActivity partitionedActivity = partitionedActivityMarshaller.fromBytes(value.getValue());
                    if (partitionedActivity.type == MiruPartitionedActivity.Type.BEGIN) {
                        counts.put(partitionedActivity.writerId, new WriterCount(partitionedActivity.writerId, partitionedActivity.index));
                        begins.add(partitionedActivity.writerId);
                    } else if (partitionedActivity.type == MiruPartitionedActivity.Type.END) {
                        ends.add(partitionedActivity.writerId);
                    }
                }
                return true;
            });
        }
        return new MiruActivityWALStatus(partitionId, Lists.newArrayList(counts.values()), begins, ends);
    }

    @Override
    public long oldestActivityClockTimestamp(MiruTenantId tenantId, MiruPartitionId partitionId) throws Exception {
        final MutableLong oldestClockTimestamp = new MutableLong(-1);
        AmzaClient client = amzaWALUtil.getActivityClient(tenantId, partitionId);
        if (client != null) {
            byte[] fromKey = columnKeyMarshaller.toBytes(new MiruActivityWALColumnKey(MiruPartitionedActivity.Type.ACTIVITY.getSort(), 0L));
            byte[] toKey = columnKeyMarshaller.toBytes(new MiruActivityWALColumnKey(MiruPartitionedActivity.Type.END.getSort(), 0L));
            client.scan(null, fromKey, null, toKey, (rowTxId, prefix, key, value) -> {
                if (value != null) {
                    MiruPartitionedActivity partitionedActivity = partitionedActivityMarshaller.fromBytes(value.getValue());
                    if (partitionedActivity != null && partitionedActivity.type.isActivityType()) {
                        oldestClockTimestamp.setValue(partitionedActivity.clockTimestamp);
                        return false;
                    }
                }
                return true;
            });
        }
        return oldestClockTimestamp.longValue();
    }

    @Override
    public List<MiruVersionedActivityLookupEntry> getVersionedEntries(MiruTenantId tenantId, MiruPartitionId partitionId, Long[] timestamps) throws Exception {
        AmzaClient client = amzaWALUtil.getActivityClient(tenantId, partitionId);
        if (client == null) {
            return null;
        }

        MiruVersionedActivityLookupEntry[] entries = new MiruVersionedActivityLookupEntry[timestamps.length];
        int[] index = new int[1];
        client.get(null, unprefixedWALKeyStream -> {
            for (Long timestamp : timestamps) {
                if (timestamp != null && !unprefixedWALKeyStream.stream(FilerIO.longBytes(timestamp))) {
                    return false;
                }
                index[0]++;
            }
            return true;
        }, (prefix, key, value, timestamp) -> {
            if (value != null) {
                MiruPartitionedActivity partitionedActivity = partitionedActivityMarshaller.fromBytes(value);
                entries[index[0]] = new MiruVersionedActivityLookupEntry(
                    partitionedActivity.timestamp,
                    timestamp,
                    new MiruActivityLookupEntry(
                        partitionedActivity.partitionId.getId(),
                        partitionedActivity.index,
                        partitionedActivity.writerId,
                        !partitionedActivity.activity.isPresent()));
            }
            return true;
        });
        return Arrays.asList(entries);
    }

    @Override
    public void allPartitions(PartitionsStream partitionsStream) throws Exception {
        amzaWALUtil.allActivityPartitions(partitionsStream);
    }

    @Override
    public long clockMax(MiruTenantId tenantId, MiruPartitionId partitionId) throws Exception {
        AmzaClient client = amzaWALUtil.getActivityClient(tenantId, partitionId);
        long[] clockTimestamp = { -1L };
        if (client != null) {
            byte[] fromKey = columnKeyMarshaller.toBytes(new MiruActivityWALColumnKey(MiruPartitionedActivity.Type.END.getSort(), Long.MIN_VALUE));
            client.scan(null, fromKey, null, null, (rowTxId, prefix, key, value) -> {
                if (value != null) {
                    MiruPartitionedActivity partitionedActivity = partitionedActivityMarshaller.fromBytes(value.getValue());
                    if (partitionedActivity != null) {
                        clockTimestamp[0] = Math.max(clockTimestamp[0], partitionedActivity.clockTimestamp);
                    }
                }
                return true;
            });
        }
        return clockTimestamp[0];
    }
}
