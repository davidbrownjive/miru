package com.jivesoftware.os.miru.manage.deployable.balancer;

import com.google.common.base.Charsets;
import com.google.common.base.Objects;
import com.google.common.base.Optional;
import com.google.common.base.Splitter;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.HashBasedTable;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.collect.Table;
import com.jivesoftware.os.filer.io.StripingLocksProvider;
import com.jivesoftware.os.jive.utils.ordered.id.OrderIdProvider;
import com.jivesoftware.os.miru.api.MiruHost;
import com.jivesoftware.os.miru.api.MiruPartition;
import com.jivesoftware.os.miru.api.MiruPartitionCoord;
import com.jivesoftware.os.miru.api.MiruPartitionState;
import com.jivesoftware.os.miru.api.activity.MiruPartitionId;
import com.jivesoftware.os.miru.api.activity.TenantAndPartition;
import com.jivesoftware.os.miru.api.base.MiruTenantId;
import com.jivesoftware.os.miru.api.topology.HostHeartbeat;
import com.jivesoftware.os.miru.api.topology.MiruTopologyStatus;
import com.jivesoftware.os.miru.api.topology.ReaderRequestHelpers;
import com.jivesoftware.os.miru.api.wal.MiruWALClient;
import com.jivesoftware.os.miru.cluster.MiruClusterRegistry;
import com.jivesoftware.os.mlogger.core.MetricLogger;
import com.jivesoftware.os.mlogger.core.MetricLoggerFactory;
import com.jivesoftware.os.routing.bird.http.client.HttpRequestHelper;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import javax.imageio.ImageIO;

/**
 *
 */
public class MiruRebalanceDirector {

    private static final MetricLogger LOG = MetricLoggerFactory.getLogger();

    private final MiruClusterRegistry clusterRegistry;
    private final MiruWALClient<?, ?> miruWALClient;
    private final OrderIdProvider orderIdProvider;
    private final ReaderRequestHelpers readerRequestHelpers;

    public MiruRebalanceDirector(MiruClusterRegistry clusterRegistry,
        MiruWALClient miruWALClient,
        OrderIdProvider orderIdProvider,
        ReaderRequestHelpers readerRequestHelpers) {
        this.clusterRegistry = clusterRegistry;
        this.miruWALClient = miruWALClient;
        this.orderIdProvider = orderIdProvider;
        this.readerRequestHelpers = readerRequestHelpers;
    }

    public void exportTopology(OutputStream os) throws IOException {
        try {
            BufferedOutputStream buf = new BufferedOutputStream(os);
            List<MiruTenantId> tenantIds = miruWALClient.getAllTenantIds();
            AtomicLong exported = new AtomicLong(0);
            clusterRegistry.topologiesForTenants(tenantIds, status -> {
                if (status != null) {
                    buf.write(status.partition.coord.tenantId.getBytes());
                    buf.write(',');
                    buf.write(String.valueOf(status.partition.coord.partitionId.getId()).getBytes(Charsets.US_ASCII));
                    buf.write(',');
                    buf.write(status.partition.coord.host.toStringForm().getBytes(Charsets.US_ASCII));
                    buf.write('\n');
                    exported.incrementAndGet();
                }
                return status;
            });
            buf.flush();
            LOG.info("Exported {} topologies", exported.get());
        } catch (Exception x) {
            throw new RuntimeException(x);
        }
    }

    public void importTopology(InputStream in) throws Exception {
        Splitter splitter = Splitter.on(',');
        BufferedReader reader = new BufferedReader(new InputStreamReader(in));
        ListMultimap<MiruHost, TenantAndPartition> elections = ArrayListMultimap.create();
        String line;
        while ((line = reader.readLine()) != null) {
            Iterator<String> split = splitter.split(line).iterator();
            MiruTenantId tenantId = split.hasNext() ? new MiruTenantId(split.next().getBytes(Charsets.US_ASCII)) : null;
            MiruPartitionId partitionId = split.hasNext() ? MiruPartitionId.of(Integer.parseInt(split.next())) : null;
            MiruHost host = split.hasNext() ? new MiruHost(split.next()) : null;
            if (tenantId != null && partitionId != null && host != null) {
                elections.put(host, new TenantAndPartition(tenantId, partitionId));
            }
        }
        clusterRegistry.ensurePartitionCoords(elections);
        clusterRegistry.addToReplicaRegistry(elections, Long.MAX_VALUE - orderIdProvider.nextId());
        LOG.info("Imported {} topologies", elections.size());
    }

    public void shiftTopologies(Optional<MiruHost> fromHost, ShiftPredicate shiftPredicate, final SelectHostsStrategy selectHostsStrategy) throws Exception {
        LinkedHashSet<HostHeartbeat> hostHeartbeats = clusterRegistry.getAllHosts();
        List<MiruHost> allHosts = hostHeartbeats.stream().map(input -> input.host).collect(Collectors.toList());

        int moved = 0;
        int skipped = 0;
        int missed = 0;
        List<MiruTenantId> tenantIds;
        if (fromHost.isPresent()) {
            tenantIds = clusterRegistry.getTenantsForHost(fromHost.get());
        } else {
            tenantIds = miruWALClient.getAllTenantIds();
        }

        Table<MiruTenantId, MiruPartitionId, Shift> shiftTable = HashBasedTable.create();
        for (MiruTenantId tenantId : tenantIds) {
            int numberOfReplicas = clusterRegistry.getNumberOfReplicas(tenantId);
            List<MiruPartition> partitionsForTenant = clusterRegistry.getPartitionsForTenant(tenantId);
            MiruPartitionId currentPartitionId = miruWALClient.getLargestPartitionId(tenantId);
            Table<MiruTenantId, MiruPartitionId, List<MiruPartition>> replicaTable = extractPartitions(
                selectHostsStrategy.isCurrentPartitionOnly(), tenantId, partitionsForTenant, currentPartitionId);
            for (Table.Cell<MiruTenantId, MiruPartitionId, List<MiruPartition>> cell : replicaTable.cellSet()) {
                MiruPartitionId partitionId = cell.getColumnKey();
                List<MiruPartition> partitions = cell.getValue();
                Set<MiruHost> hostsWithPartition = partitions.stream().map(input -> input.coord.host).collect(Collectors.toSet());
                if (fromHost.isPresent() && !hostsWithPartition.contains(fromHost.get())) {
                    missed++;
                    LOG.trace("Missed {} {}", tenantId, partitionId);
                    continue;
                } else if (!shiftPredicate.needsToShift(tenantId, partitionId, hostHeartbeats, partitions)) {
                    skipped++;
                    LOG.trace("Skipped {} {}", tenantId, partitionId);
                    continue;
                }

                MiruHost pivotHost;
                if (fromHost.isPresent()) {
                    pivotHost = fromHost.get();
                } else if (partitions.isEmpty()) {
                    pivotHost = allHosts.get(Math.abs(Objects.hashCode(tenantId, partitionId) % allHosts.size()));
                } else {
                    pivotHost = partitions.get(0).coord.host;
                }
                List<MiruHost> hostsToElect = selectHostsStrategy.selectHosts(pivotHost, allHosts, partitions, numberOfReplicas);
                shiftTable.put(tenantId, partitionId, new Shift(Lists.transform(partitions, input -> input.coord.host), hostsToElect));
                moved++;
            }
        }
        electHosts(shiftTable);
        LOG.info("Done shifting, moved={} skipped={} missed={}", moved, skipped, missed);
        LOG.inc("rebalance>moved", moved);
        LOG.inc("rebalance>skipped", skipped);
        LOG.inc("rebalance>missed", missed);
    }

    private static class Shift {

        private final List<MiruHost> fromHosts;
        private final List<MiruHost> hostsToElect;

        public Shift(List<MiruHost> fromHosts, List<MiruHost> hostsToElect) {
            this.fromHosts = fromHosts;
            this.hostsToElect = hostsToElect;
        }
    }

    public void removeHost(MiruHost miruHost) throws Exception {
        List<HttpRequestHelper> requestHelpers = readerRequestHelpers.get(Optional.of(miruHost));
        for (HttpRequestHelper requestHelper : requestHelpers) {
            try {
                String result = requestHelper.executeDeleteRequest("/miru/config/hosts/" + miruHost.getLogicalName() + "/" + miruHost.getPort(),
                    String.class, null);
                if (result != null) {
                    break;
                } else {
                    LOG.warn("Empty removeHost response from {}, trying another", requestHelper);
                }
            } catch (Exception e) {
                LOG.warn("Failed removeHost request to {}, trying another", new Object[]{requestHelper}, e);
            }
        }
    }

    private void electHosts(Table<MiruTenantId, MiruPartitionId, Shift> shiftTable) throws Exception {
        ListMultimap<MiruHost, TenantAndPartition> elections = ArrayListMultimap.create();
        int elected = 0;
        for (Table.Cell<MiruTenantId, MiruPartitionId, Shift> cell : shiftTable.cellSet()) {
            MiruTenantId tenantId = cell.getRowKey();
            MiruPartitionId partitionId = cell.getColumnKey();
            Shift shift = cell.getValue();
            LOG.debug("Elect from {} to {} for {} {}", shift.fromHosts, shift.hostsToElect, tenantId, partitionId);
            for (MiruHost host : shift.hostsToElect) {
                elections.put(host, new TenantAndPartition(tenantId, partitionId));
            }
            elected += shift.hostsToElect.size();
        }

        clusterRegistry.ensurePartitionCoords(elections);
        clusterRegistry.addToReplicaRegistry(elections, Long.MAX_VALUE - orderIdProvider.nextId());
        LOG.inc("rebalance>elect", elected);
    }

    private Table<MiruTenantId, MiruPartitionId, List<MiruPartition>> extractPartitions(boolean currentPartitionOnly,
        MiruTenantId tenantId,
        List<MiruPartition> partitionsForTenant,
        final MiruPartitionId currentPartitionId) {

        Table<MiruTenantId, MiruPartitionId, List<MiruPartition>> replicaTable = HashBasedTable.create();

        if (currentPartitionOnly) {
            replicaTable.put(tenantId, currentPartitionId, Lists.<MiruPartition>newArrayList());
        } else {
            for (MiruPartitionId partitionId = currentPartitionId; partitionId != null; partitionId = partitionId.prev()) {
                replicaTable.put(tenantId, partitionId, Lists.<MiruPartition>newArrayList());
            }
        }

        for (MiruPartition partition : partitionsForTenant) {
            MiruPartitionId partitionId = partition.coord.partitionId;
            List<MiruPartition> partitions = replicaTable.get(tenantId, partitionId);
            if (partitions != null) {
                partitions.add(partition);
            }
        }

        return replicaTable;
    }

    private static final int VISUAL_PARTITION_HEIGHT = 4;
    private static final int VISUAL_PADDING = 2;
    private static final int VISUAL_PADDING_HALVED = VISUAL_PADDING / 2;
    private static final Color[] COLORS;

    static {
        COLORS = new Color[MiruPartitionState.values().length];
        Arrays.fill(COLORS, Color.WHITE);
        COLORS[MiruPartitionState.offline.ordinal()] = Color.GRAY;
        COLORS[MiruPartitionState.bootstrap.ordinal()] = Color.BLUE;
        COLORS[MiruPartitionState.rebuilding.ordinal()] = Color.MAGENTA;
        COLORS[MiruPartitionState.online.ordinal()] = Color.GREEN;
    }

    public void rebuildTenantPartition(MiruHost miruHost, MiruTenantId tenantId, MiruPartitionId partitionId) throws Exception {
        readerRequestHelpers.get(miruHost).executeRequest("",
            "/miru/config/rebuild/prioritize/" + tenantId + "/" + partitionId,
            String.class, null);
    }

    private static class VisualizeContext {

        private final List<MiruHost> allHosts;
        private final Set<MiruHost> unhealthyHosts;
        private final List<List<MiruTenantId>> splitTenantIds;

        private VisualizeContext(List<MiruHost> allHosts, Set<MiruHost> unhealthyHosts, List<List<MiruTenantId>> splitTenantIds) {
            this.allHosts = allHosts;
            this.unhealthyHosts = unhealthyHosts;
            this.splitTenantIds = splitTenantIds;
        }
    }

    private final Cache<String, VisualizeContext> contextCache = CacheBuilder.newBuilder()
        .maximumSize(10)
        .expireAfterAccess(10, TimeUnit.MINUTES)
        .weakValues()
        .build();
    private final StripingLocksProvider<String> tokenLocks = new StripingLocksProvider<>(64);

    public void visualizeTopologies(int width, final int split, int index, String token, OutputStream out) throws Exception {
        VisualizeContext context;
        synchronized (tokenLocks.lock(token, 0)) {
            context = contextCache.get(token, () -> {
                LinkedHashSet<HostHeartbeat> heartbeats = clusterRegistry.getAllHosts();
                List<MiruHost> allHosts = Lists.newArrayList();
                Set<MiruHost> unhealthyHosts = Sets.newHashSet();
                for (HostHeartbeat heartbeat : heartbeats) {
                    allHosts.add(heartbeat.host);
                    if (heartbeat.heartbeat < System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(1)) { //TODO configure
                        unhealthyHosts.add(heartbeat.host);
                    }
                }

                List<MiruTenantId> allTenantIds = miruWALClient.getAllTenantIds();
                List<List<MiruTenantId>> splitTenantIds = Lists.partition(allTenantIds, Math.max(1, (allTenantIds.size() + split - 1) / split));

                return new VisualizeContext(allHosts, unhealthyHosts, splitTenantIds);
            });
        }

        final Table<MiruTenantId, MiruPartitionId, List<MiruTopologyStatus>> topologies = HashBasedTable.create();
        List<MiruTenantId> tenantIds = (index < context.splitTenantIds.size() - 1) ? context.splitTenantIds.get(index) : Collections.<MiruTenantId>emptyList();
        clusterRegistry.topologiesForTenants(tenantIds, status -> {
            if (status != null) {
                MiruPartitionCoord coord = status.partition.coord;
                List<MiruTopologyStatus> statuses = topologies.get(coord.tenantId, coord.partitionId);
                if (statuses == null) {
                    statuses = Lists.newArrayList();
                    topologies.put(coord.tenantId, coord.partitionId, statuses);
                }
                statuses.add(status);
            }
            return status;
        });

        int numHosts = context.allHosts.size();
        int numPartitions = topologies.size();
        if (numHosts == 0) {
            throw new IllegalStateException("Not enough data");
        }

        int visualHostWidth = (width - VISUAL_PADDING) / context.allHosts.size() - VISUAL_PADDING;
        int w = VISUAL_PADDING + (visualHostWidth + VISUAL_PADDING) * numHosts;
        int h = VISUAL_PADDING + (VISUAL_PARTITION_HEIGHT + VISUAL_PADDING) * numPartitions;
        BufferedImage bi = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D ig2 = bi.createGraphics();

        int y = VISUAL_PADDING;
        for (List<MiruTopologyStatus> statuses : topologies.values()) {
            int unhealthyPartitions = 0;
            int offlinePartitions = 0;
            int onlinePartitions = 0;
            for (MiruTopologyStatus status : statuses) {
                if (context.unhealthyHosts.contains(status.partition.coord.host)) {
                    unhealthyPartitions++;
                }
                if (status.partition.info.state == MiruPartitionState.offline) {
                    offlinePartitions++;
                }
                if (status.partition.info.state == MiruPartitionState.online) {
                    onlinePartitions++;
                }
            }
            Color unhealthyColor = null;
            if (unhealthyPartitions > 0) {
                int numReplicas = statuses.size();
                float unhealthyPct = unhealthyPartitions / numReplicas;
                if (unhealthyPct < 0.33f) {
                    unhealthyColor = Color.YELLOW;
                } else if (unhealthyPct < 0.67f) {
                    unhealthyColor = Color.ORANGE;
                } else {
                    unhealthyColor = Color.RED;
                }
            }

            if (offlinePartitions < statuses.size() && onlinePartitions == 0) {
                // partition appears to be awake, but nothing is online, so paint the background
                ig2.setColor(new Color(64, 0, 0));
                ig2.fillRect(VISUAL_PADDING_HALVED,
                    y - VISUAL_PADDING_HALVED,
                    (visualHostWidth + VISUAL_PADDING) * numHosts,
                    VISUAL_PARTITION_HEIGHT + VISUAL_PADDING);
            }

            for (MiruTopologyStatus status : statuses) {
                int x = VISUAL_PADDING + context.allHosts.indexOf(status.partition.coord.host) * (visualHostWidth + VISUAL_PADDING);
                if (context.unhealthyHosts.contains(status.partition.coord.host)) {
                    ig2.setColor(unhealthyColor);
                } else {
                    ig2.setColor(COLORS[status.partition.info.state.ordinal()]);
                }
                ig2.fillRect(x, y, visualHostWidth, VISUAL_PARTITION_HEIGHT);
            }

            y += VISUAL_PARTITION_HEIGHT + VISUAL_PADDING;
        }

        ImageIO.write(bi, "PNG", out);
    }

    public List<Map<String, String>> visualize() throws Exception {

        List<MiruHost> allHosts = Lists.newArrayList();
        Set<MiruHost> unhealthyHosts = Sets.newHashSet();
        LinkedHashSet<HostHeartbeat> heartbeats = clusterRegistry.getAllHosts();
        Map<MiruHost, Integer> hostToIndex = new HashMap<>();
        int i = 0;
        for (HostHeartbeat heartbeat : heartbeats) {
            allHosts.add(heartbeat.host);
            hostToIndex.put(heartbeat.host, i);
            i++;
            if (heartbeat.heartbeat < System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(1)) { //TODO configure
                unhealthyHosts.add(heartbeat.host);
            }
        }

        Table<MiruTenantId, MiruPartitionId, List<MiruTopologyStatus>> topologies = HashBasedTable.create();
        List<MiruTenantId> tenantIds = miruWALClient.getAllTenantIds();
        clusterRegistry.topologiesForTenants(tenantIds, status -> {
            if (status != null) {
                MiruPartitionCoord coord = status.partition.coord;
                List<MiruTopologyStatus> statuses = topologies.get(coord.tenantId, coord.partitionId);
                if (statuses == null) {
                    statuses = Lists.newArrayList();
                    topologies.put(coord.tenantId, coord.partitionId, statuses);
                }
                statuses.add(status);
            }
            return status;
        });

        int numHosts = allHosts.size();
        if (numHosts == 0) {
            throw new IllegalStateException("Not enough data");
        }

        List<Map<String, String>> rows = new ArrayList<>();
        for (List<MiruTopologyStatus> statuses : topologies.values()) {

            Color[] statusColors = new Color[numHosts];
            int unhealthyPartitions = 0;
            int offlinePartitions = 0;
            int onlinePartitions = 0;
            for (MiruTopologyStatus status : statuses) {
                if (unhealthyHosts.contains(status.partition.coord.host)) {
                    unhealthyPartitions++;
                }
                if (status.partition.info.state == MiruPartitionState.offline) {
                    offlinePartitions++;
                }
                if (status.partition.info.state == MiruPartitionState.online) {
                    onlinePartitions++;
                }
            }
            Color unhealthyColor = null;
            if (unhealthyPartitions > 0) {
                int numReplicas = statuses.size();
                float unhealthyPct = unhealthyPartitions / numReplicas;
                if (unhealthyPct < 0.33f) {
                    unhealthyColor = Color.YELLOW;
                } else if (unhealthyPct < 0.67f) {
                    unhealthyColor = Color.ORANGE;
                } else {
                    unhealthyColor = Color.RED;
                }
            }

            if (offlinePartitions < statuses.size() && onlinePartitions == 0) {
                Color c = new Color(64, 0, 0);
            }

            for (MiruTopologyStatus status : statuses) {
                if (unhealthyHosts.contains(status.partition.coord.host)) {
                    Color c = unhealthyColor;
                } else {
                    Color c = COLORS[status.partition.info.state.ordinal()];
                }
            }

//            List<String> row = new ArrayList<>();
//            row.add(null);
//            for (int c = 0; c < statusColors.length; c++) {
//                row.add(null);
//            }
//            row.add(null);
        }
        return rows;
    }
}
