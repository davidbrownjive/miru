package com.jivesoftware.os.miru.plugin.index;

import com.google.common.primitives.Bytes;
import com.jivesoftware.os.miru.api.activity.schema.MiruSchema;
import com.jivesoftware.os.miru.api.base.MiruTermId;

/**
 *
 */
public class MiruIndexUtil {

    public MiruTermId makeBloomTerm(MiruTermId fieldValue, String fieldName) {
        return makeComposite(fieldValue, "|", fieldName);
    }

    public MiruTermId makeLatestTerm() {
        return makeComposite(new MiruTermId(MiruSchema.RESERVED_AGGREGATE.getBytes()), "~", MiruSchema.RESERVED_AGGREGATE);
    }

    public MiruTermId makePairedLatestTerm(MiruTermId fieldValue, String fieldName) {
        return makeComposite(fieldValue, "^", fieldName);
    }

    private MiruTermId makeComposite(MiruTermId fieldValue, String separator, String fieldName) {
        return new MiruTermId(Bytes.concat(fieldValue.getBytes(), separator.getBytes(), fieldName.getBytes()));
    }

}
