package com.jivesoftware.os.miru.plugin.index;

import com.jivesoftware.os.miru.api.base.MiruTermId;

/**
 *
 * @author jonathan
 */
public interface MiruFieldIndex<BM> {

    MiruInvertedIndex<BM> get(int fieldId, MiruTermId termId) throws Exception;

    MiruInvertedIndex<BM> get(int fieldId, MiruTermId termId, int considerIfIndexIdGreaterThanN) throws Exception;

    MiruInvertedIndex<BM> getOrCreateInvertedIndex(int fieldId, MiruTermId term) throws Exception;

    void index(int fieldId, MiruTermId termId, int... ids) throws Exception;

    void remove(int fieldId, MiruTermId termId, int id) throws Exception;

    void streamTermIdsForField(int fieldId, TermIdStream termIdStream) throws Exception;

    long sizeInMemory() throws Exception;

    long sizeOnDisk() throws Exception;
}
