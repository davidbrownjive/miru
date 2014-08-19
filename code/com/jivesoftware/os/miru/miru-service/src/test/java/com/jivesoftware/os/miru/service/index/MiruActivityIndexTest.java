package com.jivesoftware.os.miru.service.index;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jivesoftware.os.jive.utils.chunk.store.ChunkStoreInitializer;
import com.jivesoftware.os.jive.utils.io.Filer;
import com.jivesoftware.os.jive.utils.io.RandomAccessFiler;
import com.jivesoftware.os.miru.api.activity.MiruActivity;
import com.jivesoftware.os.miru.api.base.MiruTenantId;
import com.jivesoftware.os.miru.service.index.disk.MiruMemMappedActivityIndex;
import com.jivesoftware.os.miru.service.index.disk.MiruOnDiskActivityIndex;
import com.jivesoftware.os.miru.service.index.memory.MiruInMemoryActivityIndex;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import org.apache.commons.lang.RandomStringUtils;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

public class MiruActivityIndexTest {

    @Test(dataProvider = "miruActivityIndexDataProvider")
    public void testSetActivity(MiruActivityIndex miruActivityIndex, boolean throwsUnsupportedExceptionOnSet) {
        MiruActivity miruActivity = buildMiruActivity(new MiruTenantId(RandomStringUtils.randomAlphabetic(10).getBytes()), 1, new String[0], 5);
        try {
            miruActivityIndex.set(0, miruActivity);
            if (throwsUnsupportedExceptionOnSet) {
                fail("This implementation of the MiruActivityIndex should have thrown an UnsupportedOperationException");
            }
        } catch (UnsupportedOperationException e) {
            if (!throwsUnsupportedExceptionOnSet) {
                fail("This implementation of the MiruActivityIndex shouldn't have thrown an UnsupportedOperationException", e);
            }
        }
    }

    @Test(dataProvider = "miruActivityIndexDataProvider")
    public void testSetActivityOutOfBounds(MiruActivityIndex miruActivityIndex, boolean throwsUnsupportedExceptionOnSet) {
        MiruActivity miruActivity = buildMiruActivity(new MiruTenantId(RandomStringUtils.randomAlphabetic(10).getBytes()), 1, new String[0], 5);
        try {
            miruActivityIndex.set(-1, miruActivity);
            if (throwsUnsupportedExceptionOnSet) {
                fail("This implementation of the MiruActivityIndex should have thrown an UnsupportedOperationException");
            }
            fail("Expected an IllegalArgumentException but never got it");
        } catch (UnsupportedOperationException e) {
            if (!throwsUnsupportedExceptionOnSet) {
                fail("This implementation of the MiruActivityIndex shouldn't have thrown an UnsupportedOperationException", e);
            }
        } catch (IllegalArgumentException e) {
            // We want to get here, fall through
        }
    }

    @Test(dataProvider = "miruActivityIndexDataProviderWithData")
    public void testGetActivity(MiruActivityIndex miruActivityIndex, MiruActivity[] expectedActivities) {
        assertTrue(expectedActivities.length == 3);
        assertEquals(miruActivityIndex.get(0), expectedActivities[0]);
        assertEquals(miruActivityIndex.get(1), expectedActivities[1]);
        assertEquals(miruActivityIndex.get(2), expectedActivities[2]);
    }

    @Test(dataProvider = "miruActivityIndexDataProviderWithData", expectedExceptions = IllegalArgumentException.class)
    public void testGetActivityOverCapacity(MiruActivityIndex miruActivityIndex, MiruActivity[] expectedActivities) {
        miruActivityIndex.get(expectedActivities.length); // This should throw an exception
    }

    @DataProvider(name = "miruActivityIndexDataProvider")
    public Object[][] miruActivityIndexDataProvider() throws Exception {
        MiruInMemoryActivityIndex miruInMemoryActivityIndex = new MiruInMemoryActivityIndex();

        final File memMap = File.createTempFile("memmap", "activityindex");
        MiruMemMappedActivityIndex miruMemMappedActivityIndex = new MiruMemMappedActivityIndex(
            new MiruFilerProvider() {
                @Override
                public File getBackingFile() {
                    return memMap;
                }

                @Override
                public Filer getFiler(long length) throws IOException {
                    return new RandomAccessFiler(memMap, "rw");
                }
            },
            Files.createTempDirectory("memmap").toFile(),
            Files.createTempDirectory("memmap").toFile(),
            new ChunkStoreInitializer().initialize(File.createTempFile("memmap", "chunk").getAbsolutePath(), 512, true),
            new ObjectMapper());
        miruMemMappedActivityIndex.bulkImport(miruInMemoryActivityIndex);

        final File onDisk = File.createTempFile("ondisk", "activityindex");
        MiruOnDiskActivityIndex miruOnDiskActivityIndex = new MiruOnDiskActivityIndex(new MiruFilerProvider() {
            @Override
            public File getBackingFile() {
                return onDisk;
            }

            @Override
            public Filer getFiler(long length) throws IOException {
                return new RandomAccessFiler(onDisk, "rw");
            }
        }, new ObjectMapper());
        miruOnDiskActivityIndex.bulkImport(miruInMemoryActivityIndex);

        return new Object[][] {
            { miruInMemoryActivityIndex, false },
            { miruMemMappedActivityIndex, false },
            { miruOnDiskActivityIndex, true }
        };
    }

    @DataProvider(name = "miruActivityIndexDataProviderWithData")
    public Object[][] miruActivityIndexDataProviderWithData() throws Exception {
        MiruTenantId tenantId = new MiruTenantId(RandomStringUtils.randomAlphabetic(10).getBytes());
        MiruActivity miruActivity1 = buildMiruActivity(tenantId, 1, new String[0], 3);
        MiruActivity miruActivity2 = buildMiruActivity(tenantId, 2, new String[0], 4);
        MiruActivity miruActivity3 = buildMiruActivity(tenantId, 3, new String[] { "abcde" }, 1);
        final MiruActivity[] miruActivities = new MiruActivity[] { miruActivity1, miruActivity2, miruActivity3 };

        // Add activities to in-memory index
        MiruInMemoryActivityIndex miruInMemoryActivityIndex = new MiruInMemoryActivityIndex();
        miruInMemoryActivityIndex.bulkImport(new BulkExport<MiruActivity[]>() {
            @Override
            public MiruActivity[] bulkExport() throws Exception {
                return miruActivities;
            }
        });

        // Add activities to mem-mapped index
        final File memMap = File.createTempFile("memmap", "activityindex");
        MiruMemMappedActivityIndex miruMemMappedActivityIndex = new MiruMemMappedActivityIndex(
            new MiruFilerProvider() {
                @Override
                public File getBackingFile() {
                    return memMap;
                }

                @Override
                public Filer getFiler(long length) throws IOException {
                    return new RandomAccessFiler(memMap, "rw");
                }
            },
            Files.createTempDirectory("memmap").toFile(),
            Files.createTempDirectory("memmap").toFile(),
            new ChunkStoreInitializer().initialize(File.createTempFile("memmap", "chunk").getAbsolutePath(), 512, true),
            new ObjectMapper());
        miruMemMappedActivityIndex.bulkImport(miruInMemoryActivityIndex);

        // Add activities to on-disk index
        final File onDisk = File.createTempFile("ondisk", "activityindex");
        MiruOnDiskActivityIndex miruOnDiskActivityIndex = new MiruOnDiskActivityIndex(new MiruFilerProvider() {
            @Override
            public File getBackingFile() {
                return onDisk;
            }

            @Override
            public Filer getFiler(long length) throws IOException {
                return new RandomAccessFiler(onDisk, "rw");
            }
        }, new ObjectMapper());
        miruOnDiskActivityIndex.bulkImport(miruInMemoryActivityIndex);

        return new Object[][] {
            { miruInMemoryActivityIndex, miruActivities },
            { miruMemMappedActivityIndex, miruActivities },
            { miruOnDiskActivityIndex, miruActivities }
        };
    }

    private MiruActivity buildMiruActivity(MiruTenantId tenantId, long time, String[] authz, int numberOfRandomFields) {
        MiruActivity.Builder builder = new MiruActivity.Builder(tenantId, time, authz, 0);
        for (int i = 0; i < numberOfRandomFields; i++) {
            builder.putFieldValue(RandomStringUtils.randomAlphabetic(5), RandomStringUtils.randomAlphanumeric(5));
        }
        return builder.build();
    }
}
