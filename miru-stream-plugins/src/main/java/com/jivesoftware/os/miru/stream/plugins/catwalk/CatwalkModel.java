package com.jivesoftware.os.miru.stream.plugins.catwalk;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serializable;
import java.util.Arrays;
import java.util.List;

/**
 *
 */
public class CatwalkModel implements Serializable {

    public final long[] modelCounts;
    public final long totalCount;
    public final int[] numberOfModels;
    public final List<FeatureScore>[] featureScores;

    @JsonCreator
    public CatwalkModel(@JsonProperty("modelCounts") long[] modelCounts,
        @JsonProperty("totalCount") long totalCount,
        @JsonProperty("numberOfModels") int[] numberOfModels,
        @JsonProperty("featureScores") List<FeatureScore>[] featureScores) {
        this.modelCounts = modelCounts;
        this.totalCount = totalCount;
        this.numberOfModels = numberOfModels;
        this.featureScores = featureScores;
    }

    @Override
    public String toString() {
        return "CatwalkModel{" +
            "modelCounts=" + Arrays.toString(modelCounts) +
            ", totalCount=" + totalCount +
            ", numberOfModels=" + Arrays.toString(numberOfModels) +
            ", featureScores=" + Arrays.toString(featureScores) +
            '}';
    }

    @Override
    public boolean equals(Object o) {
        throw new UnsupportedOperationException("NOPE");
    }

    @Override
    public int hashCode() {
        throw new UnsupportedOperationException("NOPE");
    }
}
