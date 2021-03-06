package com.jivesoftware.os.miru.api.wal;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.jivesoftware.os.miru.api.activity.MiruPartitionedActivity;

/**
 * @author jonathan.colt
 */
public class RCVSCursor implements MiruCursor<RCVSCursor, RCVSSipCursor> {

    public static final RCVSCursor INITIAL = new RCVSCursor(MiruPartitionedActivity.Type.ACTIVITY.getSort(), 0, false, null);

    public final byte sort;
    public final long activityTimestamp;
    public final boolean endOfStream;
    public final RCVSSipCursor sipCursor;

    @JsonCreator
    public RCVSCursor(@JsonProperty("sort") byte sort,
        @JsonProperty("activityTimestamp") long activityTimestamp,
        @JsonProperty("endOfStream") boolean endOfStream,
        @JsonProperty("sipCursor") RCVSSipCursor sipCursor) {
        this.sort = sort;
        this.activityTimestamp = activityTimestamp;
        this.endOfStream = endOfStream;
        this.sipCursor = sipCursor;
    }

    @Override
    public RCVSSipCursor getSipCursor() {
        return sipCursor;
    }

    @Override
    public int compareTo(RCVSCursor o) {
        return Long.compare(activityTimestamp, o.activityTimestamp);
    }

    @Override
    public String toString() {
        return "RCVSCursor{" +
            "sort=" + sort +
            ", activityTimestamp=" + activityTimestamp +
            ", endOfStream=" + endOfStream +
            ", sipCursor=" + sipCursor +
            '}';
    }
}
