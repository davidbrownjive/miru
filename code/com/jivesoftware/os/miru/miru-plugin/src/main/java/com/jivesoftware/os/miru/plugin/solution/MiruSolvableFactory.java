package com.jivesoftware.os.miru.plugin.solution;

import com.google.common.base.Optional;
import com.jivesoftware.os.miru.plugin.partition.MiruQueryablePartition;
import com.jivesoftware.os.mlogger.core.MetricLogger;
import com.jivesoftware.os.mlogger.core.MetricLoggerFactory;
import java.util.concurrent.Callable;

/**
 * @param <A> answer type
 * @param <P> report type
 */
public class MiruSolvableFactory<A, P> {

    private static final MetricLogger LOG = MetricLoggerFactory.getLogger();

    private final String queryKey;
    private final Question<A, P> question;

    public MiruSolvableFactory(String queryKey, Question<A, P> question) {
        this.queryKey = queryKey;
        this.question = question;
    }

    public <BM> MiruSolvable<A> create(final MiruQueryablePartition<BM> replica, final Optional<P> report) {
        Callable<MiruPartitionResponse<A>> callable = new Callable<MiruPartitionResponse<A>>() {
            @Override
            public MiruPartitionResponse<A> call() throws Exception {
                try (MiruRequestHandle<BM> handle = replica.acquireQueryHandle()) {
                    if (handle.isLocal()) {
                        return question.askLocal(handle, report);
                    } else {
                        return question.askRemote(handle.getRequestHelper(), handle.getCoord().partitionId, report);
                    }
                } catch (Throwable t) {
                    LOG.info("Solvable encountered a problem", t);
                    throw t;
                }
            }
        };
        return new MiruSolvable<>(replica.getCoord(), callable);
    }

    public Question<A, P> getQuestion() {
        return question;
    }

    public Optional<P> getReport(Optional<A> answer) {
        return question.createReport(answer);
    }

    public String getQueryKey() {
        return queryKey;
    }
}
