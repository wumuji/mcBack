package com.mcback.service;

import com.mcback.model.ExportResult;
import com.mcback.model.LocationKind;
import com.mcback.model.MinecraftWorld;
import com.mcback.testfx.WorldFixtures;
import com.mcback.util.FileUtils;
import com.mcback.util.ProgressListener;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 导出服务测试:解压后直接就是世界内容、扩展名规范化、原子提交。 */
class ExportServiceTest {

    @TempDir
    Path tempDir;

    private MinecraftWorld world;

    private MinecraftWorld createWorld() throws IOException {
        Path saves = tempDir.resolve("saves");
        Files.createDirectories(saves);
        Path worldDir = WorldFixtures.vanillaWorld(saves, "新的世界", "生存世界");
        world = new MinecraftWorld("新的世界", worldDir, saves, LocationKind.MANUAL, "测试",
                0L, 0L, FileUtils.worldChangeStamp(worldDir),
                LevelInfoReader.read(worldDir.resolve("level.dat")),
                FileUtils.LockProbe.FREE, null);
        return world;
    }

    @Test
    void exportsFlattenedZipWithExpectedStructure() throws IOException {
        createWorld();
        Path target = tempDir.resolve("导出").resolve("生存世界.zip");

        ExportResult result = new ExportService().export(world, target, ProgressListener.NOOP);

        assertEquals(target, result.zipPath());
        assertTrue(Files.isRegularFile(result.zipPath()));
        assertTrue(result.fileCount() > 0);
        try (ZipFile zipFile = new ZipFile(result.zipPath().toFile(), StandardCharsets.UTF_8)) {
            assertNotNull(zipFile.getEntry("level.dat"));
            assertNotNull(zipFile.getEntry("region/r.0.0.mca"));
            assertNull(zipFile.getEntry("生存世界/level.dat"), "不能多一层世界目录");
        }
    }

    @Test
    void appendsZipExtensionWhenMissing() throws IOException {
        createWorld();
        Path target = tempDir.resolve("没有扩展名");

        ExportResult result = new ExportService().export(world, target, ProgressListener.NOOP);

        assertEquals("没有扩展名.zip", result.zipPath().getFileName().toString());
        assertTrue(Files.isRegularFile(result.zipPath()));
    }

    @Test
    void leavesNoTemporaryFileBehind() throws IOException {
        createWorld();
        Path target = tempDir.resolve("world.zip");

        new ExportService().export(world, target, ProgressListener.NOOP);

        try (var stream = Files.list(tempDir)) {
            assertFalse(stream.anyMatch(path -> path.getFileName().toString().endsWith(".tmp")),
                    "导出后不应残留 .tmp");
        }
    }

    @Test
    void failsCleanlyWhenWorldDisappears() throws IOException {
        createWorld();
        Path missing = tempDir.resolve("world-gone");
        MinecraftWorld ghost = new MinecraftWorld("world-gone", missing, tempDir, LocationKind.MANUAL,
                "测试", 0L, 0L, 0L, com.mcback.model.WorldInfo.UNAVAILABLE,
                FileUtils.LockProbe.UNKNOWN, null);

        assertThrows(BackupException.class,
                () -> new ExportService().export(ghost, tempDir.resolve("x.zip"), ProgressListener.NOOP));
    }

    @Test
    void suggestsSanitizedFileName() throws IOException {
        createWorld();
        String suggested = ExportService.suggestedFileName(world);
        assertTrue(suggested.startsWith("生存世界_"));
        assertTrue(suggested.endsWith(".zip"));
        assertFalse(suggested.contains(":"));
    }
}
