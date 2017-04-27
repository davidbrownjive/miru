package com.jivesoftware.os.miru.siphon.api;

import com.jivesoftware.os.miru.api.activity.MiruActivity;
import com.jivesoftware.os.miru.api.activity.schema.MiruSchema;
import com.jivesoftware.os.miru.api.base.MiruTenantId;
import java.util.List;

/**
 * Created by jonathan.colt on 4/27/17.
 */
public interface MiruSiphon {

    String name();

    MiruSchema schema(MiruTenantId tenantId) throws Exception;

    List<MiruActivity> siphon(MiruTenantId tenantId,
        long rowTxId,
        byte[] prefix,
        byte[] key,
        byte[] value,
        long valueTimestamp,
        boolean valueTombstoned,
        long valueVersion) throws Exception;
}
