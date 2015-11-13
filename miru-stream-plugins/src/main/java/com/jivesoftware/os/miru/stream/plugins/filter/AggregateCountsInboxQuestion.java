package com.jivesoftware.os.miru.stream.plugins.filter;

import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.jivesoftware.os.miru.api.MiruHost;
import com.jivesoftware.os.miru.api.MiruQueryServiceException;
import com.jivesoftware.os.miru.api.activity.MiruPartitionId;
import com.jivesoftware.os.miru.api.base.MiruStreamId;
import com.jivesoftware.os.miru.api.query.filter.MiruAuthzExpression;
import com.jivesoftware.os.miru.plugin.backfill.MiruJustInTimeBackfillerizer;
import com.jivesoftware.os.miru.plugin.bitmap.MiruBitmaps;
import com.jivesoftware.os.miru.plugin.bitmap.MiruBitmapsDebug;
import com.jivesoftware.os.miru.plugin.context.MiruRequestContext;
import com.jivesoftware.os.miru.plugin.solution.MiruPartitionResponse;
import com.jivesoftware.os.miru.plugin.solution.MiruRemotePartition;
import com.jivesoftware.os.miru.plugin.solution.MiruRequest;
import com.jivesoftware.os.miru.plugin.solution.MiruRequestHandle;
import com.jivesoftware.os.miru.plugin.solution.MiruSolutionLog;
import com.jivesoftware.os.miru.plugin.solution.MiruTimeRange;
import com.jivesoftware.os.miru.plugin.solution.Question;
import com.jivesoftware.os.mlogger.core.MetricLogger;
import com.jivesoftware.os.mlogger.core.MetricLoggerFactory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author jonathan
 */
public class AggregateCountsInboxQuestion implements Question<AggregateCountsQuery, AggregateCountsAnswer, AggregateCountsReport> {

    private static final MetricLogger LOG = MetricLoggerFactory.getLogger();

    private final AggregateCounts aggregateCounts;
    private final MiruJustInTimeBackfillerizer backfillerizer;
    private final MiruRequest<AggregateCountsQuery> request;
    private final MiruRemotePartition<AggregateCountsQuery, AggregateCountsAnswer, AggregateCountsReport> remotePartition;
    private final boolean unreadOnly;
    private final MiruBitmapsDebug bitmapsDebug = new MiruBitmapsDebug();

    public AggregateCountsInboxQuestion(AggregateCounts aggregateCounts,
        MiruJustInTimeBackfillerizer backfillerizer,
        MiruRequest<AggregateCountsQuery> request,
        MiruRemotePartition<AggregateCountsQuery, AggregateCountsAnswer, AggregateCountsReport> remotePartition,
        boolean unreadOnly) {

        Preconditions.checkArgument(!MiruStreamId.NULL.equals(request.query.streamId), "Inbox queries require a streamId");
        this.aggregateCounts = aggregateCounts;
        this.backfillerizer = backfillerizer;
        this.request = request;
        this.remotePartition = remotePartition;
        this.unreadOnly = unreadOnly;
    }

    @Override
    public <BM> MiruPartitionResponse<AggregateCountsAnswer> askLocal(MiruRequestHandle<BM, ?> handle,
        Optional<AggregateCountsReport> report)
        throws Exception {

        MiruSolutionLog solutionLog = new MiruSolutionLog(request.logLevel);
        MiruRequestContext<BM, ?> context = handle.getRequestContext();
        MiruBitmaps<BM> bitmaps = handle.getBitmaps();

        if (handle.canBackfill()) {
            backfillerizer.backfill(bitmaps, context, request.query.streamFilter, solutionLog, request.tenantId,
                handle.getCoord().partitionId, request.query.streamId);
        }

        List<BM> ands = new ArrayList<>();
        List<BM> counterAnds = new ArrayList<>();

        if (!context.getTimeIndex().intersects(request.query.answerTimeRange)) {
            LOG.debug("No answer time index intersection");
            return new MiruPartitionResponse<>(
                aggregateCounts.getAggregateCounts(solutionLog, bitmaps, context, request, report, bitmaps.create(), Optional.absent()),
                solutionLog.asList());
        }

        if (!MiruTimeRange.ALL_TIME.equals(request.query.answerTimeRange)) {
            MiruTimeRange timeRange = request.query.answerTimeRange;

            ands.add(bitmaps.buildTimeRangeMask(context.getTimeIndex(), timeRange.smallestTimestamp, timeRange.largestTimestamp));
        }
        if (!MiruTimeRange.ALL_TIME.equals(request.query.countTimeRange)) {
            counterAnds.add(bitmaps.buildTimeRangeMask(
                context.getTimeIndex(), request.query.countTimeRange.smallestTimestamp, request.query.countTimeRange.largestTimestamp));
        }

        Optional<BM> inbox = context.getInboxIndex().getInbox(request.query.streamId).getIndex();
        if (inbox.isPresent()) {
            ands.add(inbox.get());
        } else {
            // Short-circuit if the user doesn't have an inbox here
            LOG.debug("No user inbox");
            return new MiruPartitionResponse<>(
                aggregateCounts.getAggregateCounts(solutionLog, bitmaps, context, request, report, bitmaps.create(), Optional.of(bitmaps.create())),
                solutionLog.asList());
        }

        if (!MiruAuthzExpression.NOT_PROVIDED.equals(request.authzExpression)) {
            ands.add(context.getAuthzIndex().getCompositeAuthz(request.authzExpression));
        }

        if (unreadOnly) {
            Optional<BM> unreadIndex = context.getUnreadTrackingIndex().getUnread(request.query.streamId).getIndex();
            if (unreadIndex.isPresent()) {
                ands.add(unreadIndex.get());
            }
        }
        ands.add(bitmaps.buildIndexMask(context.getActivityIndex().lastId(), context.getRemovalIndex().getIndex()));

        BM answer = bitmaps.create();
        bitmapsDebug.debug(solutionLog, bitmaps, "ands", ands);
        bitmaps.and(answer, ands);

        counterAnds.add(answer);
        if (!unreadOnly) {
            // if unreadOnly is true, the read-tracking index would already be applied to the answer
            Optional<BM> unreadIndex = context.getUnreadTrackingIndex().getUnread(request.query.streamId).getIndex();
            if (unreadIndex.isPresent()) {
                counterAnds.add(unreadIndex.get());
            }
        }
        BM counter = bitmaps.create();
        bitmapsDebug.debug(solutionLog, bitmaps, "counterAnds", ands);
        bitmaps.and(counter, counterAnds);

        return new MiruPartitionResponse<>(
            aggregateCounts.getAggregateCounts(solutionLog, bitmaps, context, request, report, answer, Optional.of(counter)),
            solutionLog.asList());
    }

    @Override
    public MiruPartitionResponse<AggregateCountsAnswer> askRemote(MiruHost host,
        MiruPartitionId partitionId,
        Optional<AggregateCountsReport> report) throws MiruQueryServiceException {
        return remotePartition.askRemote(host, partitionId, request, report);
    }

    @Override
    public Optional<AggregateCountsReport> createReport(Optional<AggregateCountsAnswer> answer) {
        Optional<AggregateCountsReport> report = Optional.absent();
        if (answer.isPresent()) {

            Map<String, AggregateCountsReportConstraint> constraintReport = new HashMap<>();
            for (Map.Entry<String, AggregateCountsAnswerConstraint> entry : answer.get().constraints.entrySet()) {
                AggregateCountsAnswerConstraint value = entry.getValue();
                constraintReport.put(entry.getKey(),
                    new AggregateCountsReportConstraint(value.aggregateTerms, value.skippedDistincts, value.collectedDistincts));
            }

            report = Optional.of(new AggregateCountsReport(constraintReport));
        }
        return report;
    }

}
