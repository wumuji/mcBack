package com.mcbackup.service;

import com.mcbackup.testfx.NbtFixture;
import com.mcbackup.testfx.WorldFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 世界识别测试:重点是「不能只看文件夹名字」。 */
class WorldDetectorTest {

    @TempDir
    Path tempDir;

    @Test
    void recognizesVanillaWorld() throws IOException {
        Path saves = tempDir.resolve("saves");
        Files.createDirectories(saves);
        Path world = WorldFixtures.vanillaWorld(saves, "新的世界", "生存世界");

        WorldDetector.Detection detection = WorldDetector.detect(world);

        assertTrue(detection.isWorld());
        assertTrue(detection.hasLevelDat());
        assertTrue(detection.markers() >= WorldDetector.REQUIRED_MARKERS);
        assertTrue(detection.matchedMarkers().contains("region"));
    }

    @Test
    void recognizesModdedWorldWithDimensionFolders() throws IOException {
        Path saves = tempDir.resolve("saves");
        Files.createDirectories(saves);
        Path world = WorldFixtures.moddedWorld(saves, "爽包世界", "模组世界");

        assertTrue(WorldDetector.isWorld(world));
    }

    @Test
    void rejectsEmptyDirectory() throws IOException {
        Path dir = tempDir.resolve("新建文件夹");
        Files.createDirectories(dir);

        assertFalse(WorldDetector.isWorld(dir));
    }

    @Test
    void rejectsFolderWithWorldLikeNameButNoStructure() throws IOException {
        Path dir = tempDir.resolve("saves");
        Files.createDirectories(dir.resolve("world"));
        Files.writeString(dir.resolve("readme.txt"), "这不是世界");

        assertFalse(WorldDetector.isWorld(dir), "名字像存档但结构不符,必须拒绝");
    }

    @Test
    void reportsIncompleteStructureWhenOnlyOneMarker() throws IOException {
        Path dir = tempDir.resolve("半成品世界");
        Files.createDirectories(dir.resolve("region"));
        Files.write(dir.resolve("level.dat"), NbtFixture.levelDat("半成品", "1.21.1", 3955, 0, 2, false, 0));

        WorldDetector.Detection detection = WorldDetector.detect(dir);

        assertTrue(detection.hasLevelDat());
        assertEquals(1, detection.markers());
        assertFalse(detection.isWorld(), "只命中 1 项特征不应算世界");
    }

    @Test
    void excludesRestoreBackupAndTemporaryDirectories() throws IOException {
        Path dir = tempDir.resolve("MyWorld.restore-backup-20260918-173000");
        Files.createDirectories(dir.resolve("region"));
        Files.write(dir.resolve("level.dat"), NbtFixture.levelDat("备份", "1.21.1", 3955, 0, 2, false, 0));
        Files.createDirectories(dir.resolve("playerdata"));

        WorldDetector.Detection detection = WorldDetector.detect(dir);

        assertFalse(detection.isWorld());
        assertNotNull(detection.excludedReason());
        assertEquals("备份/临时目录", detection.excludedReason());
    }

    @Test
    void excludesHiddenFolders() {
        assertNotNull(WorldDetector.exclusionReason(".minecraft"));
        assertNotNull(WorldDetector.exclusionReason("myworld.tmp"));
        assertNotNull(WorldDetector.exclusionReason("myworld.bak"));
        assertEquals(null, WorldDetector.exclusionReason("新的世界"));
    }
}
