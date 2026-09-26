package com.mcbackup.service;

import com.mcbackup.model.LocationKind;
import com.mcbackup.model.MinecraftWorld;
import com.mcbackup.model.WorldInfo;
import com.mcbackup.util.FileUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 自动备份对象的选择逻辑测试。 */
class BackupTargetsTest {

    @TempDir
    Path tempDir;

    private MinecraftWorld world(String folderName) throws IOException {
        Path saves = tempDir.resolve("saves");
        Files.createDirectories(saves);
        Path dir = Files.createDirectories(saves.resolve(folderName));
        Files.writeString(dir.resolve("level.dat"), "x");
        return new MinecraftWorld(folderName, dir, saves, LocationKind.MANUAL, "测试",
                0L, 0L, 0L, WorldInfo.UNAVAILABLE, FileUtils.LockProbe.FREE, null);
    }

    @Test
    void keepsOnlySelectedWorlds() throws IOException {
        MinecraftWorld a = world("存档A");
        MinecraftWorld b = world("存档B");
        MinecraftWorld c = world("存档C");

        List<MinecraftWorld> selected = BackupTargets.filter(List.of(a, b, c),
                List.of(BackupTargets.key(b)));

        assertEquals(1, selected.size());
        assertEquals("存档B", selected.get(0).folderName());
    }

    @Test
    void nothingSelectedMeansNothingBackedUp() throws IOException {
        MinecraftWorld a = world("存档A");
        assertTrue(BackupTargets.filter(List.of(a), List.of()).isEmpty(),
                "一个都没勾选时不允许备份任何世界");
    }

    @Test
    void normalizationIsCaseInsensitiveAndDeduplicated() throws IOException {
        MinecraftWorld a = world("存档A");
        String key = BackupTargets.key(a);

        List<String> normalized = BackupTargets.normalize(List.of(key, key.toUpperCase(), "  ", key));

        assertEquals(1, normalized.size());
        assertTrue(BackupTargets.isSelected(a, normalized));
    }

    @Test
    void reportsMissingTargets() throws IOException {
        MinecraftWorld a = world("存档A");
        String missing = BackupTargets.key(tempDir.resolve("saves").resolve("已经不存在的存档"));

        int missingCount = BackupTargets.missingCount(List.of(a),
                List.of(BackupTargets.key(a), missing));

        assertEquals(1, missingCount, "被删除的世界应统计为失效,但不影响其它目标");
        assertFalse(BackupTargets.isSelected(a, List.of(missing)));
    }

    @Test
    void sameFolderNameInDifferentVersionsIsDistinguished() throws IOException {
        Path savesA = tempDir.resolve("v1").resolve("saves");
        Path savesB = tempDir.resolve("v2").resolve("saves");
        Files.createDirectories(savesA.resolve("新的世界"));
        Files.createDirectories(savesB.resolve("新的世界"));
        MinecraftWorld worldA = new MinecraftWorld("新的世界", savesA.resolve("新的世界"), savesA,
                LocationKind.VERSION_ISOLATED, "测试", 0L, 0L, 0L, WorldInfo.UNAVAILABLE,
                FileUtils.LockProbe.FREE, null);
        MinecraftWorld worldB = new MinecraftWorld("新的世界", savesB.resolve("新的世界"), savesB,
                LocationKind.VERSION_ISOLATED, "测试", 0L, 0L, 0L, WorldInfo.UNAVAILABLE,
                FileUtils.LockProbe.FREE, null);

        List<MinecraftWorld> selected = BackupTargets.filter(List.of(worldA, worldB),
                List.of(BackupTargets.key(worldA)));

        assertEquals(1, selected.size(), "同名世界在不同版本下必须能区分开");
        assertEquals(savesA, selected.get(0).savesDir());
    }
}
