/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.jivesoftware.os.miru.reco.plugins;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.jivesoftware.os.miru.api.MiruBackingStorage;
import com.jivesoftware.os.miru.api.MiruHost;
import com.jivesoftware.os.miru.api.activity.MiruPartitionId;
import com.jivesoftware.os.miru.api.activity.schema.MiruFieldDefinition;
import com.jivesoftware.os.miru.api.activity.schema.MiruSchema;
import com.jivesoftware.os.miru.api.base.MiruTenantId;
import com.jivesoftware.os.miru.api.query.filter.MiruAuthzExpression;
import com.jivesoftware.os.miru.api.query.filter.MiruFieldFilter;
import com.jivesoftware.os.miru.api.query.filter.MiruFilter;
import com.jivesoftware.os.miru.api.query.filter.MiruFilterOperation;
import com.jivesoftware.os.miru.plugin.test.MiruPluginTestBootstrap;
import com.jivesoftware.os.miru.query.MiruFilterUtils;
import com.jivesoftware.os.miru.query.MiruProvider;
import com.jivesoftware.os.miru.query.MiruResponse;
import com.jivesoftware.os.miru.reco.plugins.reco.CollaborativeFiltering;
import com.jivesoftware.os.miru.reco.plugins.reco.RecoAnswer;
import com.jivesoftware.os.miru.reco.plugins.reco.RecoInjectable;
import com.jivesoftware.os.miru.reco.plugins.reco.RecoQuery;
import com.jivesoftware.os.miru.service.MiruService;
import com.jivesoftware.os.miru.service.bitmap.MiruBitmapsRoaring;
import java.util.Collections;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;


/**
 * @author jonathan
 */
public class MiruCollaborativeFilterNGTest {

    MiruSchema miruSchema = new MiruSchema(
            new MiruFieldDefinition(0, "user", false, ImmutableList.of("doc"), ImmutableList.<String>of()),
            new MiruFieldDefinition(1, "doc", false, ImmutableList.of("user"), ImmutableList.of("user")));

    MiruTenantId tenant1 = new MiruTenantId("tenant1".getBytes());
    MiruPartitionId partitionId = MiruPartitionId.of(1);
    MiruHost miruHost = new MiruHost("logicalName", 1234);
    CollaborativeFilterUtil util = new CollaborativeFilterUtil();

    MiruService service;
    RecoInjectable injectable;

    @BeforeMethod
    public void setUpMethod() throws Exception {
        MiruProvider<MiruService> miruProvider = new MiruPluginTestBootstrap().bootstrap(tenant1, partitionId, miruHost,
                miruSchema, MiruBackingStorage.hybrid, new MiruBitmapsRoaring());

        this.service = miruProvider.getMiru(tenant1);
        this.injectable = new RecoInjectable(miruProvider, new CollaborativeFiltering(new MiruFilterUtils()));
    }

    @Test(enabled = true)
    public void basicTest() throws Exception {

        AtomicInteger time = new AtomicInteger();
        Random rand = new Random(1234);
        int numqueries = 2;
        int numberOfUsers = 2;
        int numberOfDocument = 500_000;
        int numberOfViewsPerUser = 2;
        System.out.println("Building activities....");
        long start = System.currentTimeMillis();
        int count = 0;
        int numGroups = 10;
        for (int i = 0; i < numberOfUsers; i++) {
            String user = "bob" + i;
            int randSeed = i % numGroups;
            Random userRand = new Random(randSeed * 137);
            for (int r = 0; r < 2 * (i / numGroups); r++) {
                userRand.nextInt(numberOfDocument);
            }
            for (int d = 0; d < numberOfViewsPerUser; d++) {
                int docId = userRand.nextInt(numberOfDocument);
                long activityTime = time.incrementAndGet();
                service.writeToIndex(Collections.singletonList(util.viewActivity(tenant1, partitionId, activityTime, user, String.valueOf(docId))));
                if (++count % 10_000 == 0) {
                    System.out.println("Finished " + count + " in " + (System.currentTimeMillis() - start) + " ms");
                }
            }
        }

        System.out.println("Built and indexed " + count + " in " + (System.currentTimeMillis() - start) + "millis");

        service.writeToIndex(Collections.singletonList(util.viewActivity(tenant1, partitionId, time.incrementAndGet(), "bob0", "1")));
        service.writeToIndex(Collections.singletonList(util.viewActivity(tenant1, partitionId, time.incrementAndGet(), "bob0", "2")));
        service.writeToIndex(Collections.singletonList(util.viewActivity(tenant1, partitionId, time.incrementAndGet(), "bob0", "3")));
        service.writeToIndex(Collections.singletonList(util.viewActivity(tenant1, partitionId, time.incrementAndGet(), "bob0", "4")));
        service.writeToIndex(Collections.singletonList(util.viewActivity(tenant1, partitionId, time.incrementAndGet(), "bob0", "9")));

        service.writeToIndex(Collections.singletonList(util.viewActivity(tenant1, partitionId, time.incrementAndGet(), "frank", "1")));
        service.writeToIndex(Collections.singletonList(util.viewActivity(tenant1, partitionId, time.incrementAndGet(), "frank", "2")));
        service.writeToIndex(Collections.singletonList(util.viewActivity(tenant1, partitionId, time.incrementAndGet(), "frank", "3")));
        service.writeToIndex(Collections.singletonList(util.viewActivity(tenant1, partitionId, time.incrementAndGet(), "frank", "4")));
        service.writeToIndex(Collections.singletonList(util.viewActivity(tenant1, partitionId, time.incrementAndGet(), "frank", "10")));

        service.writeToIndex(Collections.singletonList(util.viewActivity(tenant1, partitionId, time.incrementAndGet(), "jane", "2")));
        service.writeToIndex(Collections.singletonList(util.viewActivity(tenant1, partitionId, time.incrementAndGet(), "jane", "3")));
        service.writeToIndex(Collections.singletonList(util.viewActivity(tenant1, partitionId, time.incrementAndGet(), "jane", "4")));
        service.writeToIndex(Collections.singletonList(util.viewActivity(tenant1, partitionId, time.incrementAndGet(), "jane", "11")));

        service.writeToIndex(Collections.singletonList(util.viewActivity(tenant1, partitionId, time.incrementAndGet(), "liz", "3")));
        service.writeToIndex(Collections.singletonList(util.viewActivity(tenant1, partitionId, time.incrementAndGet(), "liz", "4")));
        service.writeToIndex(Collections.singletonList(util.viewActivity(tenant1, partitionId, time.incrementAndGet(), "liz", "12")));
        service.writeToIndex(Collections.singletonList(util.viewActivity(tenant1, partitionId, time.incrementAndGet(), "liz", "12")));

        System.out.println("Running queries...");

        for (int i = 0; i < numqueries; i++) {
            String user = "bob" + rand.nextInt(numberOfUsers);
            MiruFieldFilter miruFieldFilter = new MiruFieldFilter("user", ImmutableList.of(util.makeComposite(user, "^", "doc")));
            MiruFilter filter = new MiruFilter(
                    MiruFilterOperation.or,
                    Optional.of(ImmutableList.of(miruFieldFilter)),
                    Optional.<ImmutableList<MiruFilter>>absent());

            long s = System.currentTimeMillis();
            MiruResponse<RecoAnswer> recoResult = injectable.collaborativeFilteringRecommendations(new RecoQuery(tenant1,
                    filter,
                    MiruAuthzExpression.NOT_PROVIDED,
                    "doc", "doc", "doc",
                    "user", "user", "user",
                    "doc", "doc",
                    10));

            System.out.println("recoResult:" + recoResult);
            System.out.println("Took:" + (System.currentTimeMillis() - s));
        }

    }

}
