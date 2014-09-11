package com.jivesoftware.os.miru.service.stream.factory;

import com.google.common.base.Optional;
import com.jivesoftware.os.jive.utils.logger.MetricLogger;
import com.jivesoftware.os.jive.utils.logger.MetricLoggerFactory;
import com.jivesoftware.os.miru.api.MiruPartition;
import com.jivesoftware.os.miru.api.MiruPartitionCoord;
import com.jivesoftware.os.miru.query.MiruSolution;
import com.jivesoftware.os.miru.query.MiruSolvable;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** @author jonathan */
public class MiruLowestLatencySolver implements MiruSolver {

    private static final MetricLogger log = MetricLoggerFactory.getLogger();

    private final Executor executor;
    private final int initialSolvers;
    private final int maxNumberOfSolvers;
    private final long defaultAddAnotherSolverAfterNMillis;
    private final long failAfterNMillis;

    public MiruLowestLatencySolver(
        Executor executor,
        int initialSolvers,
        int maxNumberOfSolvers,
        long defaultAddAnotherSolverAfterNMillis,
        long failAfterNMillis) {
        this.executor = executor;
        this.initialSolvers = initialSolvers;
        this.maxNumberOfSolvers = maxNumberOfSolvers;
        this.defaultAddAnotherSolverAfterNMillis = defaultAddAnotherSolverAfterNMillis;
        this.failAfterNMillis = failAfterNMillis;
    }

    @Override
    public <R> MiruSolved<R> solve(Iterator<MiruSolvable<R>> solvables, Optional<Long> suggestedTimeoutInMillis, List<MiruPartition> orderedPartitions)
            throws InterruptedException {

        int solvers = initialSolvers;
        long failAfterTime = System.currentTimeMillis() + failAfterNMillis;
        long addAnotherSolverAfterNMillis = suggestedTimeoutInMillis.or(defaultAddAnotherSolverAfterNMillis);

        CompletionService<R> completionService = new ExecutorCompletionService<>(executor);
        int n = 0;
        long startTime = System.currentTimeMillis();
        List<SolvableFuture<R>> futures = new ArrayList<>(n);
        List<MiruPartitionCoord> triedPartitions = new ArrayList<>(n);
        MiruSolved<R> solved = null;
        try {
            while (solvables.hasNext() && solvers > 0) {
                MiruSolvable<R> solvable = solvables.next();
                log.debug("Initial solver index={} coord={}", n, solvable.getCoord());
                futures.add(new SolvableFuture<>(solvable, completionService.submit(solvable), System.currentTimeMillis()));
                solvers--;
                n++;
            }
            for (int i = 0; i < n; ++i) {
                boolean mayAddSolver = (n < maxNumberOfSolvers && solvables.hasNext());
                long timeout = Math.max(failAfterTime - System.currentTimeMillis(), 0);
                if (timeout == 0) {
                    break; // out of time
                }
                if (mayAddSolver) {
                    timeout = Math.min(timeout, addAnotherSolverAfterNMillis);
                }
                Future<R> future = completionService.poll(timeout, TimeUnit.MILLISECONDS);
                if (future == null) {
                    if (mayAddSolver) {
                        MiruSolvable<R> solvable = solvables.next();
                        log.debug("Added a solver coord={}", solvable.getCoord());
                        futures.add(new SolvableFuture<>(solvable, completionService.submit(solvable), System.currentTimeMillis()));
                        n++;
                    }
                } else {
                    try {
                        R result = future.get();
                        if (result != null) {
                            // should be few enough of these that we prefer a linear lookup
                            for (SolvableFuture<R> f : futures) {
                                if (f.future == future) {
                                    log.debug("Got a solution coord={}", f.solvable.getCoord());
                                    long usedResultElapsed = System.currentTimeMillis() - f.startTime;
                                    long totalElapsed = System.currentTimeMillis() - startTime;
                                    solved = new MiruSolved<>(
                                            new MiruSolution(f.solvable.getCoord(), orderedPartitions, triedPartitions, usedResultElapsed, totalElapsed),
                                            result);
                                    break;
                                }
                            }
                            if (solved == null) {
                                log.error("Unmatched future");
                            }
                            break;
                        }
                    } catch (ExecutionException e) {
                        log.debug("Solver failed to execute", e.getCause());
                    }
                }
            }
        } finally {
            for (SolvableFuture<R> f : futures) {
                f.future.cancel(true);
            }
        }

        return solved;
    }

    private static class SolvableFuture<R> {

        private final MiruSolvable<R> solvable;
        private final Future<R> future;
        private final long startTime;

        private SolvableFuture(MiruSolvable<R> solvable, Future<R> future, long startTime) {
            this.solvable = solvable;
            this.future = future;
            this.startTime = startTime;
        }
    }
}
