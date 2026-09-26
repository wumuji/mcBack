package com.mcback.util;

import com.mcback.testfx.WorldFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ZIP 工具测试:条目结构与路径、中文名、注释、校验。 */
class ZipUtilsTest {

    @TempDir
    Path tempDir;

    @Test
    void zipsDirectoryFlattenedAtRoot() throws IOException {
        Path saves = tempDir.resolve("saves");
        Files.createDirectories(saves);
        Path world = WorldFixtures.vanillaWorld(saves, "中文世界名", "中文世界名");
        Path zip = tempDir.resolve("out.zip");

        ZipUtils.ZipStats stats = ZipUtils.zipDirectory(world, zip, "mcBack v1 中文世界名",
                ProgressListener.NOOP);

        assertTrue(Files.isRegularFile(zip));
        assertTrue(stats.files() > 0);
        Set<String> names = entryNames(zip);
        assertTrue(names.contains("level.dat"), "level.dat 必须位于 ZIP 根目录");
        assertTrue(names.contains("region/r.0.0.mca"));
        assertTrue(names.contains("playerdata/00000000-0000-0000-0000-000000000000.dat"));
        assertFalse(names.stream().anyMatch(name -> name.startsWith("中文世界名/")),
                "不能多套一层世界目录");
        // 条目 = 文件 + 空目录(空目录条目用于保留世界结构)
        assertEquals(stats.files() + stats.directories(), names.size());
        assertTrue(stats.directories() > 0, "原版世界里的空目录应被保留为目录条目");

        try (ZipFile zipFile = new ZipFile(zip.toFile(), StandardCharsets.UTF_8)) {
        assertEquals("mcBack v1 中文世界名", zipFile.getComment());
            assertTrue(zipFile.getEntry("level.dat") != null);
        }
    }

    @Test
    void verificationAcceptsHealthyZip() throws IOException {
        Path saves = tempDir.resolve("saves2");
        Files.createDirectories(saves);
        Path world = WorldFixtures.vanillaWorld(saves, "世界", "世界");
        Path zip = tempDir.resolve("healthy.zip");
        ZipUtils.zipDirectory(world, zip, "mcBack v1 world", ProgressListener.NOOP);

        ZipUtils.ZipVerification verification = ZipUtils.verify(zip);

        assertTrue(verification.valid(), verification.problem());
        assertTrue(verification.levelDatPresent());
        assertTrue(verification.regionPresent());
        assertTrue(verification.entries() > 0);
    }

    @Test
    void verificationRejectsBrokenZip() throws IOException {
        Path broken = tempDir.resolve("broken.zip");
        Files.write(broken, "this is not a zip file".getBytes(StandardCharsets.UTF_8));

        ZipUtils.ZipVerification verification = ZipUtils.verify(broken);

        assertFalse(verification.valid());
        assertTrue(verification.problem() != null && verification.problem().contains("无法打开"));
    }

    @Test
    void verificationRejectsZipWithoutLevelDat() throws IOException {
        Path source = tempDir.resolve("random");
        Files.createDirectories(source);
        Files.writeString(source.resolve("readme.txt"), "没有 level.dat");
        Path zip = tempDir.resolve("nolevel.zip");
        ZipUtils.zipDirectory(source, zip, null, ProgressListener.NOOP);

        ZipUtils.ZipVerification verification = ZipUtils.verify(zip);

        assertFalse(verification.valid());
        assertTrue(verification.problem().contains("level.dat"));
    }

    @Test
    void compressesSmallRegionFiles() throws IOException {
        Path saves = tempDir.resolve("saves3");
        Files.createDirectories(saves);
        Path world = WorldFixtures.vanillaWorld(saves, "世界", "世界");
        Path zip = tempDir.resolve("fast.zip");

        ZipUtils.zipDirectory(world, zip, null, true, ProgressListener.NOOP);

        try (ZipFile zipFile = new ZipFile(zip.toFile(), StandardCharsets.UTF_8)) {
            // 小区域文件压缩成本很低,仍然压缩
            assertEquals(ZipEntry.DEFLATED, zipFile.getEntry("region/r.0.0.mca").getMethod());
            assertEquals(ZipEntry.DEFLATED, zipFile.getEntry("level.dat").getMethod());
        }
    }

    @Test
    void storesLargeIncompressibleRegionFiles() throws IOException {
        Path saves = tempDir.resolve("saves4");
        Files.createDirectories(saves);
        Path world = WorldFixtures.vanillaWorld(saves, "世界", "世界");
        Path region = world.resolve("region").resolve("r.9.9.mca");
        java.util.Random random = new java.util.Random(42);
        byte[] noise = new byte[400 * 1024];
        random.nextBytes(noise);
        Files.write(region, noise);
        Path zip = tempDir.resolve("small.zip");

        ZipUtils.zipDirectory(world, zip, null, true, ProgressListener.NOOP);

        try (ZipFile zipFile = new ZipFile(zip.toFile(), StandardCharsets.UTF_8)) {
            assertEquals(ZipEntry.STORED, zipFile.getEntry("region/r.9.9.mca").getMethod(),
                    "压不动的大区域文件应直接存储");
        }
    }

    @Test
    void compressesLargeButCompressibleRegionFiles() throws IOException {
        Path saves = tempDir.resolve("saves5");
        Files.createDirectories(saves);
        Path world = WorldFixtures.vanillaWorld(saves, "世界", "世界");
        // 400KB 的零字节:典型「空区块」区域文件,压缩收益极大
        Files.write(world.resolve("region").resolve("r.8.8.mca"), new byte[400 * 1024]);
        Path zip = tempDir.resolve("compressible.zip");

        ZipUtils.zipDirectory(world, zip, null, true, ProgressListener.NOOP);

        try (ZipFile zipFile = new ZipFile(zip.toFile(), StandardCharsets.UTF_8)) {
            assertEquals(ZipEntry.DEFLATED, zipFile.getEntry("region/r.8.8.mca").getMethod(),
                    "能压得动的大区域文件仍然压缩");
        }
    }

    @Test
    void compressesEverythingWhenFastModeDisabled() throws IOException {
        Path saves = tempDir.resolve("saves6");
        Files.createDirectories(saves);
        Path world = WorldFixtures.vanillaWorld(saves, "世界", "世界");
        Path region = world.resolve("region").resolve("r.7.7.mca");
        java.util.Random random = new java.util.Random(7);
        byte[] noise = new byte[400 * 1024];
        random.nextBytes(noise);
        Files.write(region, noise);
        Path zip = tempDir.resolve("full.zip");

        ZipUtils.zipDirectory(world, zip, null, false, ProgressListener.NOOP);

        try (ZipFile zipFile = new ZipFile(zip.toFile(), StandardCharsets.UTF_8)) {
            assertEquals(ZipEntry.DEFLATED, zipFile.getEntry("region/r.7.7.mca").getMethod(),
                    "关闭快速模式时全部压缩");
        }
    }

    private static Set<String> entryNames(Path zip) throws IOException {
        Set<String> names = new LinkedHashSet<>();
        try (ZipFile zipFile = new ZipFile(zip.toFile(), StandardCharsets.UTF_8)) {
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                names.add(entries.nextElement().getName());
            }
        }
        return names;
    }
}
