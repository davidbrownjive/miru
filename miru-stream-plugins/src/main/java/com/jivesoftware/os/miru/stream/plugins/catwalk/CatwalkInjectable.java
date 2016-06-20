package com.jivesoftware.os.miru.stream.plugins.catwalk;

import com.google.common.base.Optional;
import com.jivesoftware.os.miru.api.MiruQueryServiceException;
import com.jivesoftware.os.miru.api.activity.MiruPartitionId;
import com.jivesoftware.os.miru.api.base.MiruTenantId;
import com.jivesoftware.os.miru.plugin.Miru;
import com.jivesoftware.os.miru.plugin.MiruProvider;
import com.jivesoftware.os.miru.plugin.partition.MiruPartitionUnavailableException;
import com.jivesoftware.os.miru.plugin.solution.MiruPartitionResponse;
import com.jivesoftware.os.miru.plugin.solution.MiruRequest;
import com.jivesoftware.os.miru.plugin.solution.MiruRequestAndReport;
import com.jivesoftware.os.miru.plugin.solution.MiruResponse;
import com.jivesoftware.os.miru.plugin.solution.MiruSolutionLogLevel;
import com.jivesoftware.os.miru.plugin.solution.MiruSolvableFactory;
import java.util.concurrent.Executor;

/**
 *
 */
public class CatwalkInjectable {

    private final MiruProvider<? extends Miru> provider;
    private final Catwalk catwalk;
    private final Executor catwalkExecutor;
    private final int topNValuesPerFeature;
    private final long maxHeapPressureInBytes;

    public CatwalkInjectable(MiruProvider<? extends Miru> provider,
        Catwalk catwalk,
        Executor catwalkExecutor,
        int topNValuesPerFeature,
        long maxHeapPressureInBytes) {
        this.provider = provider;
        this.catwalk = catwalk;
        this.catwalkExecutor = catwalkExecutor;
        this.topNValuesPerFeature = topNValuesPerFeature;
        this.maxHeapPressureInBytes = maxHeapPressureInBytes;
    }

    public MiruResponse<CatwalkAnswer> strut(MiruRequest<CatwalkQuery> request) throws MiruQueryServiceException, InterruptedException {
        try {
            MiruTenantId tenantId = request.tenantId;
            Miru miru = provider.getMiru(tenantId);
            return miru.askAndMerge(tenantId,
                new MiruSolvableFactory<>(request.name, provider.getStats(),
                    "catwalk",
                    new CatwalkQuestion(catwalk,
                        request,
                        provider.getRemotePartition(CatwalkRemotePartition.class),
                        topNValuesPerFeature,
                        maxHeapPressureInBytes)),
                new CatwalkAnswerEvaluator(),
                new CatwalkAnswerMerger(request.query.desiredNumberOfResults),
                CatwalkAnswer.EMPTY_RESULTS,
                miru.getDefaultExecutor(),
                request.logLevel);
        } catch (MiruPartitionUnavailableException | InterruptedException e) {
            throw e;
        } catch (Exception e) {
            //TODO throw http error codes
            throw new MiruQueryServiceException("Failed to catwalk", e);
        }
    }

    public MiruPartitionResponse<CatwalkAnswer> strut(MiruPartitionId partitionId,
        MiruRequestAndReport<CatwalkQuery, CatwalkReport> requestAndReport)
        throws MiruQueryServiceException, InterruptedException {
        try {
            MiruTenantId tenantId = requestAndReport.request.tenantId;
            Miru miru = provider.getMiru(tenantId);
            return miru.askImmediate(tenantId,
                partitionId,
                new MiruSolvableFactory<>(requestAndReport.request.name, provider.getStats(),
                    "catwalk",
                    new CatwalkQuestion(catwalk,
                        requestAndReport.request,
                        provider.getRemotePartition(CatwalkRemotePartition.class),
                        topNValuesPerFeature,
                        maxHeapPressureInBytes)),
                Optional.fromNullable(requestAndReport.report),
                CatwalkAnswer.EMPTY_RESULTS,
                MiruSolutionLogLevel.NONE);
        } catch (MiruPartitionUnavailableException | InterruptedException e) {
            throw e;
        } catch (Exception e) {
            //TODO throw http error codes
            throw new MiruQueryServiceException("Failed remote catwalk for partition: " + partitionId.getId(), e);
        }
    }

    public MiruResponse<CatwalkAnswer> strut(MiruPartitionId partitionId,
        MiruRequest<CatwalkQuery> request) throws MiruQueryServiceException, InterruptedException {
        try {
            MiruTenantId tenantId = request.tenantId;
            Miru miru = provider.getMiru(tenantId);
            return miru.askAndMergePartition(tenantId,
                partitionId,
                new MiruSolvableFactory<>(request.name, provider.getStats(),
                    "catwalk",
                    new CatwalkQuestion(catwalk,
                        request,
                        provider.getRemotePartition(CatwalkRemotePartition.class),
                        topNValuesPerFeature,
                        maxHeapPressureInBytes)),
                new CatwalkAnswerMerger(request.query.desiredNumberOfResults),
                CatwalkAnswer.EMPTY_RESULTS,
                catwalkExecutor,
                request.logLevel);
        } catch (MiruPartitionUnavailableException | InterruptedException e) {
            throw e;
        } catch (Exception e) {
            //TODO throw http error codes
            throw new MiruQueryServiceException("Failed single catwalk for partition: " + partitionId.getId(), e);
        }
    }

}
