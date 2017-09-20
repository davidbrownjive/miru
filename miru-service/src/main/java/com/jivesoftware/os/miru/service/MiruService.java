package com.jivesoftware.os.miru.service;

import com.google.common.base.Optional;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Iterables;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Lists;
import com.jivesoftware.os.filer.io.FilerIO;
import com.jivesoftware.os.filer.io.api.StackBuffer;
import com.jivesoftware.os.miru.api.MiruHost;
import com.jivesoftware.os.miru.api.MiruPartitionCoord;
import com.jivesoftware.os.miru.api.MiruPartitionCoordInfo;
import com.jivesoftware.os.miru.api.activity.CoordinateStream;
import com.jivesoftware.os.miru.api.activity.MiruPartitionId;
import com.jivesoftware.os.miru.api.activity.MiruPartitionedActivity;
import com.jivesoftware.os.miru.api.activity.schema.MiruFieldDefinition;
import com.jivesoftware.os.miru.api.activity.schema.MiruSchemaProvider;
import com.jivesoftware.os.miru.api.activity.schema.MiruSchemaUnavailableException;
import com.jivesoftware.os.miru.api.base.MiruTenantId;
import com.jivesoftware.os.miru.api.field.MiruFieldType;
import com.jivesoftware.os.miru.api.wal.MiruSipCursor;
import com.jivesoftware.os.miru.plugin.Miru;
import com.jivesoftware.os.miru.plugin.bitmap.MiruBitmapsDebug;
import com.jivesoftware.os.miru.plugin.context.MiruRequestContext;
import com.jivesoftware.os.miru.plugin.context.RequestContextCallback;
import com.jivesoftware.os.miru.plugin.index.BitmapAndLastId;
import com.jivesoftware.os.miru.plugin.partition.MiruPartitionDirector;
import com.jivesoftware.os.miru.plugin.partition.MiruPartitionUnavailableException;
import com.jivesoftware.os.miru.plugin.partition.MiruQueryablePartition;
import com.jivesoftware.os.miru.plugin.partition.OrderedPartitions;
import com.jivesoftware.os.miru.plugin.solution.MiruAnswerEvaluator;
import com.jivesoftware.os.miru.plugin.solution.MiruAnswerMerger;
import com.jivesoftware.os.miru.plugin.solution.MiruPartitionResponse;
import com.jivesoftware.os.miru.plugin.solution.MiruRequestHandle;
import com.jivesoftware.os.miru.plugin.solution.MiruResponse;
import com.jivesoftware.os.miru.plugin.solution.MiruSolution;
import com.jivesoftware.os.miru.plugin.solution.MiruSolutionLog;
import com.jivesoftware.os.miru.plugin.solution.MiruSolutionLogLevel;
import com.jivesoftware.os.miru.plugin.solution.MiruSolvable;
import com.jivesoftware.os.miru.plugin.solution.MiruSolvableFactory;
import com.jivesoftware.os.miru.plugin.solution.MiruTimeRange;
import com.jivesoftware.os.miru.service.partition.MiruHostedPartitionComparison;
import com.jivesoftware.os.miru.service.solver.MiruSolved;
import com.jivesoftware.os.miru.service.solver.MiruSolver;
import com.jivesoftware.os.mlogger.core.MetricLogger;
import com.jivesoftware.os.mlogger.core.MetricLoggerFactory;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * @author jonathan
 */
public class MiruService implements Miru {

    private static final MetricLogger LOG = MetricLoggerFactory.getLogger();

    private final MiruHost localhost;
    private final MiruPartitionDirector partitionDirector;
    private final MiruSolver solver;
    private final MiruHostedPartitionComparison partitionComparison;
    private final MiruSchemaProvider schemaProvider;
    private final Executor defaultExecutor;
    private final ExecutorService parallelExecutor;
    private final MiruBitmapsDebug bitmapsDebug = new MiruBitmapsDebug();

    public MiruService(MiruHost localhost,
        MiruPartitionDirector partitionDirector,
        MiruHostedPartitionComparison partitionComparison,
        MiruSolver solver,
        MiruSchemaProvider schemaProvider,
        Executor defaultExecutor,
        ExecutorService parallelExecutor) {

        this.localhost = localhost;
        this.partitionDirector = partitionDirector;
        this.partitionComparison = partitionComparison;
        this.solver = solver;
        this.schemaProvider = schemaProvider;
        this.defaultExecutor = defaultExecutor;
        this.parallelExecutor = parallelExecutor;
    }

    public void writeToIndex(List<MiruPartitionedActivity> partitionedActivities) throws Exception {
        ListMultimap<MiruTenantId, MiruPartitionedActivity> perTenantPartitionedActivities = ArrayListMultimap.create();
        for (MiruPartitionedActivity partitionedActivity : partitionedActivities) {
            perTenantPartitionedActivities.put(partitionedActivity.tenantId, partitionedActivity);
        }
        partitionDirector.index(perTenantPartitionedActivities);
    }

    @Override
    public Executor getDefaultExecutor() {
        return defaultExecutor;
    }

    @Override
    public <Q, A, P> MiruResponse<A> askAndMerge(
        MiruTenantId tenantId,
        final MiruSolvableFactory<Q, A, P> solvableFactory,
        MiruAnswerEvaluator<A> evaluator,
        MiruAnswerMerger<A> merger,
        A defaultValue,
        Executor executor,
        MiruSolutionLogLevel logLevel)
        throws Exception {

        try {
            schemaProvider.getSchema(tenantId);
        } catch (MiruSchemaUnavailableException e) {
            return new MiruResponse<>(null, null, 0, true, Collections.emptyList(),
                Collections.singletonList("Schema has not been registered for this tenantId"));
        }

        LOG.startTimer("askAndMerge");

        A answer;
        List<MiruSolution> solutions = Lists.newArrayList();
        List<Integer> incompletePartitionIds = Lists.newArrayList();
        final MiruSolutionLog solutionLog = new MiruSolutionLog(logLevel);
        solutionLog.log(MiruSolutionLogLevel.INFO, "Solving: host:{} tenantId:{} question:{}", localhost, tenantId, solvableFactory.getQuestion());
        long totalElapsed;

        try {
            Iterable<? extends OrderedPartitions<?, ?>> partitionReplicas = partitionDirector.allQueryablePartitionsInOrder(
                tenantId, solvableFactory.getRequestName(), solvableFactory.getQueryKey());

            Optional<A> lastAnswer = Optional.absent();

            List<ExpectedSolution<A>> expectedSolutions = Lists.newArrayList();
            for (OrderedPartitions<?, ?> orderedPartitions : partitionReplicas) {
                Optional<Long> suggestedTimeoutInMillis = partitionComparison.suggestTimeout(orderedPartitions.tenantId, orderedPartitions.partitionId,
                    solvableFactory.getRequestName(), solvableFactory.getQueryKey());
                solutionLog.log(MiruSolutionLogLevel.INFO, "Solving partition:{} for tenant:{} with timeout:{}",
                    orderedPartitions.partitionId.getId(), orderedPartitions.tenantId, suggestedTimeoutInMillis.or(-1L));

                if (evaluator.useParallelSolver()) {
                    expectedSolutions.add(new ParallelExpectedSolution<>(orderedPartitions, solvableFactory, suggestedTimeoutInMillis, executor, solutionLog));
                } else {
                    expectedSolutions.add(new SerialExpectedSolution<>(orderedPartitions, solvableFactory, suggestedTimeoutInMillis, executor, solutionLog));
                }
            }

            boolean done = false;
            for (ExpectedSolution<A> expectedSolution : expectedSolutions) {
                if (done) {
                    expectedSolution.cancel();
                } else {
                    MiruSolved<A> solved = expectedSolution.get(lastAnswer);
                    if (solved == null) {
                        solutionLog.log(MiruSolutionLogLevel.WARN, "No solution for partition:{}", expectedSolution.getPartitionId());
                        solutionLog.log(MiruSolutionLogLevel.WARN, "WARNING result set is incomplete! elapse:{}",
                            (System.currentTimeMillis() - expectedSolution.getStart()));
                        incompletePartitionIds.add(expectedSolution.getPartitionId().getId());
                        if (evaluator.stopOnUnsolvablePartition()) {
                            solutionLog.log(MiruSolutionLogLevel.ERROR, "ERROR result set is unsolvable");
                            done = true;
                        }
                    } else {
                        solutionLog.log(MiruSolutionLogLevel.INFO, "Solved partition:{}. elapse:{} millis",
                            expectedSolution.getPartitionId(), (System.currentTimeMillis() - expectedSolution.getStart()));
                        solutions.add(solved.solution);

                        A currentAnswer = solved.answer;
                        solutionLog.log(MiruSolutionLogLevel.INFO, "Merging solution set from partition:{}", expectedSolution.getPartitionId());
                        long start = System.currentTimeMillis();
                        A merged = merger.merge(lastAnswer, currentAnswer, solutionLog);
                        solutionLog.log(MiruSolutionLogLevel.INFO, "Merged. elapse:{} millis", (System.currentTimeMillis() - start));

                        lastAnswer = Optional.of(merged);
                        if (evaluator.isDone(merged, solutionLog)) {
                            done = true;
                        }
                    }
                }
            }

            partitionComparison.analyzeSolutions(solutions, solvableFactory.getRequestName(), solvableFactory.getQueryKey());

            answer = merger.done(lastAnswer, defaultValue, solutionLog);
        } finally {
            totalElapsed = LOG.stopTimer("askAndMerge");
        }

        LOG.inc("askAndMerge");
        LOG.inc("askAndMerge", tenantId.toString());
        LOG.inc("askAndMerge>query>" + solvableFactory.getQueryKey());
        LOG.inc("askAndMerge>query>" + solvableFactory.getQueryKey(), tenantId.toString());

        return new MiruResponse<>(answer, solutions, totalElapsed, false, incompletePartitionIds, solutionLog.asList());
    }

    @Override
    public <Q, A, P> MiruResponse<A> askAndMergePartition(
        MiruTenantId tenantId,
        MiruPartitionId partitionId,
        MiruSolvableFactory<Q, A, P> solvableFactory,
        MiruAnswerMerger<A> merger,
        A defaultValue,
        A destroyedValue,
        Executor executor,
        MiruSolutionLogLevel logLevel)
        throws Exception {

        try {
            schemaProvider.getSchema(tenantId);
        } catch (MiruSchemaUnavailableException e) {
            return new MiruResponse<>(null, null, 0, true, Collections.emptyList(),
                Collections.singletonList("Schema has not been registered for this tenantId"));
        }

        LOG.startTimer("askAndMergePartition");

        A answer;
        MiruSolutionLog solutionLog = new MiruSolutionLog(logLevel);
        List<MiruSolution> solutions = Lists.newArrayList();
        List<Integer> incompletePartitionIds = Lists.newArrayList();
        long totalElapsed;

        try {
            Optional<Long> suggestedTimeoutInMillis = partitionComparison.suggestTimeout(tenantId, partitionId,
                solvableFactory.getRequestName(), solvableFactory.getQueryKey());

            OrderedPartitions<?, ?> orderedPartitions = partitionDirector.queryablePartitionInOrder(tenantId,
                partitionId,
                solvableFactory.getRequestName(),
                solvableFactory.getQueryKey());
            if (orderedPartitions == null) {
                LOG.inc("askAndMergePartition>destroyed");
                answer = merger.done(Optional.absent(), destroyedValue, solutionLog);
            } else {
                long start = System.currentTimeMillis();
                MiruSolved<A> solved = new SerialExpectedSolution<>(orderedPartitions,
                    solvableFactory,
                    suggestedTimeoutInMillis,
                    executor,
                    solutionLog)
                    .get(Optional.absent());

                Optional<A> lastAnswer = Optional.absent();
                if (solved == null) {
                    solutionLog.log(MiruSolutionLogLevel.WARN, "No solution for partition:{}", partitionId);
                    incompletePartitionIds.add(partitionId.getId());
                } else {
                    solutionLog.log(MiruSolutionLogLevel.INFO, "Solved partition:{}. elapse:{} millis",
                        partitionId, (System.currentTimeMillis() - start));
                    solutions.add(solved.solution);
                    lastAnswer = Optional.of(merger.merge(Optional.absent(), solved.answer, solutionLog));
                }

                partitionComparison.analyzeSolutions(solutions, solvableFactory.getRequestName(), solvableFactory.getQueryKey());

                answer = merger.done(lastAnswer, defaultValue, solutionLog);
            }
        } finally {
            totalElapsed = LOG.stopTimer("askAndMergePartition");
        }

        return new MiruResponse<>(answer, solutions, totalElapsed, false, incompletePartitionIds, solutionLog.asList());
    }

    @Override
    public <Q, A, P> MiruPartitionResponse<A> askImmediate(
        MiruTenantId tenantId,
        MiruPartitionId partitionId,
        MiruSolvableFactory<Q, A, P> factory,
        Optional<P> report,
        A defaultValue,
        MiruSolutionLogLevel logLevel)
        throws Exception {
        Optional<? extends MiruQueryablePartition<?, ?>> partition = getLocalTenantPartition(tenantId, partitionId);

        if (partition.isPresent()) {
            Callable<MiruPartitionResponse<A>> callable = factory.create((MiruQueryablePartition) partition.get(), report, null);
            MiruPartitionResponse<A> answer = callable.call();

            LOG.inc("askImmediate");
            LOG.inc("askImmediate", tenantId.toString());
            LOG.inc("askImmediate>query>" + factory.getQueryKey());
            LOG.inc("askImmediate>query>" + factory.getQueryKey(), tenantId.toString());

            return answer;
        } else {
            throw new MiruPartitionUnavailableException("partition is NOT present. partitionId:" + partitionId);
        }
    }

    /**
     * Proactively warm a tenant for immediate use.
     */
    public void warm(MiruTenantId tenantId) throws Exception {
        partitionDirector.warm(tenantId);
    }

    /**
     * Inspect a field term.
     */
    public String inspect(MiruTenantId tenantId, MiruPartitionId partitionId, String fieldName, String termValue) throws Exception {
        Optional<? extends MiruQueryablePartition<?, ?>> partition = getLocalTenantPartition(tenantId, partitionId);
        if (partition.isPresent()) {
            return inspect((MiruQueryablePartition) partition.get(), fieldName, termValue);
        } else {
            return "Partition unavailable";
        }
    }

    private <BM extends IBM, IBM> String inspect(MiruQueryablePartition<BM, IBM> partition, String fieldName, String termValue) throws Exception {
        try (MiruRequestHandle<BM, IBM, ?> handle = partition.inspectRequestHandle(false)) {
            MiruRequestContext<BM, IBM, ?> requestContext = handle.getRequestContext();
            int fieldId = requestContext.getSchema().getFieldId(fieldName);
            MiruFieldDefinition fieldDefinition = requestContext.getSchema().getFieldDefinition(fieldId);
            StackBuffer stackBuffer = new StackBuffer();
            BitmapAndLastId<BM> container = new BitmapAndLastId<>();
            requestContext.getFieldIndexProvider()
                .getFieldIndex(MiruFieldType.primary)
                .get("inspect", fieldId, requestContext.getTermComposer().compose(requestContext.getSchema(), fieldDefinition, stackBuffer, termValue))
                .getIndex(container, stackBuffer);
            if (container.isSet()) {
                return bitmapsDebug.toString(handle.getBitmaps(), container.getBitmap());
            } else {
                return "Index not present";
            }
        }
    }

    public boolean isAvailable(MiruTenantId tenantId, MiruPartitionId partitionId) throws Exception {
        Optional<? extends MiruQueryablePartition<?, ?>> partition = getLocalTenantPartition(tenantId, partitionId);
        return partition.isPresent() && partition.get().isAvailable();
    }

    public boolean checkInfo(MiruTenantId tenantId, MiruPartitionId partitionId, MiruPartitionCoordInfo info) throws Exception {
        return partitionDirector.checkInfo(tenantId, partitionId, info);
    }

    public MiruPartitionCoordInfo getInfo(MiruTenantId tenantId, MiruPartitionId partitionId) throws Exception {
        return partitionDirector.getInfo(tenantId, partitionId);
    }

    public boolean prioritizeRebuild(MiruTenantId tenantId, MiruPartitionId partitionId) throws Exception {
        return partitionDirector.prioritizeRebuild(tenantId, partitionId);
    }

    public boolean compact(MiruTenantId tenantId, MiruPartitionId partitionId) throws Exception {
        return partitionDirector.compact(tenantId, partitionId);
    }

    public boolean rebuildTimeRange(MiruTimeRange miruTimeRange, boolean hotDeploy, boolean chunkStores, boolean labIndex) throws Exception {
        return partitionDirector.rebuildTimeRange(miruTimeRange, hotDeploy, chunkStores, labIndex);
    }

    @Override
    public Optional<? extends MiruQueryablePartition<?, ?>> getQueryablePartition(MiruPartitionCoord coord) throws Exception {
        return partitionDirector.getQueryablePartition(coord);
    }

    @Override
    public OrderedPartitions<?, ?> getOrderedPartitions(String requestName,
        String queryKey,
        MiruPartitionCoord coord) throws Exception {
        return partitionDirector.queryablePartitionInOrder(coord.tenantId,
            coord.partitionId,
            requestName,
            queryKey);
    }

    private Optional<? extends MiruQueryablePartition<?, ?>> getLocalTenantPartition(MiruTenantId tenantId, MiruPartitionId partitionId) throws Exception {
        MiruPartitionCoord localPartitionCoord = new MiruPartitionCoord(tenantId, partitionId, localhost);
        return partitionDirector.getQueryablePartition(localPartitionCoord);
    }

    public boolean expectedTopologies(Optional<MiruTenantId> tenantId, CoordinateStream stream) throws Exception {
        return partitionDirector.expectedTopologies(tenantId, stream);
    }

    public void introspect(MiruTenantId tenantId, MiruPartitionId partitionId, RequestContextCallback callback) throws Exception {
        Optional<? extends MiruQueryablePartition<?, ?>> partition = getLocalTenantPartition(tenantId, partitionId);
        if (partition.isPresent()) {
            MiruQueryablePartition<?, ?> hostedPartition = partition.get();
            try (MiruRequestHandle<?, ?, ? extends MiruSipCursor<?>> handle = hostedPartition.inspectRequestHandle(false)) {
                callback.call(handle.getRequestContext());
            }
        }
    }

    private interface ExpectedSolution<A> {
        MiruPartitionId getPartitionId();

        MiruSolved<A> get(Optional<A> lastAnswer) throws Exception;

        void cancel() throws Exception;

        long getStart();
    }

    private class ParallelExpectedSolution<A, BM extends IBM, IBM> implements ExpectedSolution<A> {

        private final OrderedPartitions<BM, IBM> orderedPartitions;
        private final Future<MiruSolved<A>> future;
        private final long start;

        public <Q, P> ParallelExpectedSolution(OrderedPartitions<BM, IBM> orderedPartitions,
            MiruSolvableFactory<Q, A, P> solvableFactory,
            Optional<Long> suggestedTimeoutInMillis,
            Executor executor,
            MiruSolutionLog solutionLog) {

            Iterable<MiruSolvable<A>> solvables = Iterables.transform(orderedPartitions.partitions, replica -> {
                if (replica.isLocal()) {
                    solutionLog.log(MiruSolutionLogLevel.INFO, "Created local solvable for coord={}.", replica.getCoord());
                }
                return solvableFactory.create(replica, solvableFactory.getReport(Optional.absent()), new MiruSolutionLog(solutionLog.getLevel()));
            });

            this.orderedPartitions = orderedPartitions;
            this.start = System.currentTimeMillis();
            this.future = parallelExecutor.submit(() -> {
                solutionLog.log(MiruSolutionLogLevel.INFO, "Parallel solution began execution in {} ms", System.currentTimeMillis() - start);
                MiruSolved<A> solved = solver.solve(solvableFactory.getRequestName(),
                    solvableFactory.getQueryKey(),
                    orderedPartitions.tenantId,
                    orderedPartitions.partitionId,
                    solvables.iterator(),
                    suggestedTimeoutInMillis,
                    executor,
                    solutionLog);

                long elapsed = System.currentTimeMillis() - start;
                LOG.inc("parallel>elapsed>pow>" + FilerIO.chunkPower(elapsed, 0));
                solutionLog.log(MiruSolutionLogLevel.INFO, "Parallel solution finished execution in {} ms", elapsed);
                return solved;
            });
        }

        @Override
        public MiruPartitionId getPartitionId() {
            return orderedPartitions.partitionId;
        }

        @Override
        public MiruSolved<A> get(Optional<A> lastAnswer) throws Exception {
            return future.get();
        }

        @Override
        public void cancel() throws Exception {
            future.cancel(true);
        }

        @Override
        public long getStart() {
            return start;
        }
    }

    private class SerialExpectedSolution<Q, A, P, BM extends IBM, IBM> implements ExpectedSolution<A> {

        private final OrderedPartitions<BM, IBM> orderedPartitions;
        private final MiruSolvableFactory<Q, A, P> solvableFactory;
        private final Optional<Long> suggestedTimeoutInMillis;
        private final Executor executor;
        private final MiruSolutionLog solutionLog;

        private long start;

        public SerialExpectedSolution(OrderedPartitions<BM, IBM> orderedPartitions,
            MiruSolvableFactory<Q, A, P> solvableFactory,
            Optional<Long> suggestedTimeoutInMillis,
            Executor executor,
            MiruSolutionLog solutionLog) {
            this.orderedPartitions = orderedPartitions;
            this.executor = executor;
            this.solvableFactory = solvableFactory;
            this.suggestedTimeoutInMillis = suggestedTimeoutInMillis;
            this.solutionLog = solutionLog;
        }

        @Override
        public MiruPartitionId getPartitionId() {
            return orderedPartitions.partitionId;
        }

        @Override
        public MiruSolved<A> get(Optional<A> lastAnswer) throws Exception {
            Iterable<MiruSolvable<A>> solvables = Iterables.transform(orderedPartitions.partitions, replica -> {
                if (replica.isLocal()) {
                    solutionLog.log(MiruSolutionLogLevel.INFO, "Created local solvable for coord={}.", replica.getCoord());
                }
                return solvableFactory.create(replica, solvableFactory.getReport(lastAnswer), new MiruSolutionLog(solutionLog.getLevel()));
            });

            start = System.currentTimeMillis();
            return solver.solve(solvableFactory.getRequestName(),
                solvableFactory.getQueryKey(),
                orderedPartitions.tenantId,
                orderedPartitions.partitionId,
                solvables.iterator(),
                suggestedTimeoutInMillis,
                executor,
                solutionLog);
        }

        @Override
        public void cancel() throws Exception {
        }

        @Override
        public long getStart() {
            return start;
        }
    }
}
