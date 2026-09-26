package com.mcback.service;

import com.mcback.model.LocationKind;
import com.mcback.model.MinecraftWorld;
import com.mcback.model.ScanIssue;
import com.mcback.model.ScanProgress;
import com.mcback.model.ScanResult;
import com.mcback.testfx.WorldFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 扫描器测试:世界数量、来源标签、排除规则、手动目录与大小缓存。 */
class WorldScannerTest {

    @TempDir
    Path tempDir;

    @Test
    void scansMultipleRootsAndReportsProgress() throws IOException {
        Path officialSaves = tempDir.resolve("official").resolve("saves");
        Files.createDirectories(officialSaves);
        WorldFixtures.vanillaWorld(officialSaves, "新的世界", "生存世界");
        WorldFixtures.moddedWorld(officialSaves, "爽包世界", "模组世界");

        Path isolatedSaves = tempDir.resolve("versions").resolve("1.21.1").resolve("saves");
        Files.createDirectories(isolatedSaves);
        WorldFixtures.vanillaWorld(isolatedSaves, "1145", "1145");
        // 名字像世界但缺少结构 -> 只记录问题,不算世界
        WorldFixtures.incompleteWorld(isolatedSaves, "半成品");
        Files.createDirectories(isolatedSaves.resolve("MyWorld.restore-backup-20260918"));

        List<ScanProgress> progress = new ArrayList<>();
        WorldScanner scanner = new WorldScanner(() -> List.of(
                new WorldRoot(officialSaves.getParent(), officialSaves, LocationKind.OFFICIAL_DEFAULT,
                        "官方启动器", "官方 .minecraft"),
                new WorldRoot(isolatedSaves.getParent(), isolatedSaves, LocationKind.VERSION_ISOLATED,
                        "官方启动器 · 版本隔离 · 1.21.1", "1.21.1")));

        ScanResult result = scanner.scan(List.of(), progress::add);

        assertEquals(3, result.worlds().size());
        assertEquals(2, result.rootCount());
        assertTrue(progress.size() >= 2, "应上报扫描进度");
        assertNotNull(progress.get(progress.size() - 1));

        MinecraftWorld survival = findWorld(result, "新的世界");
        assertEquals("生存世界", survival.displayName(), "displayName 应以 level.dat 为准");
        assertTrue(survival.info().available());
        assertTrue(survival.sizeBytes() > 0);
        assertEquals("官方启动器", survival.sourceLabel());
        assertEquals("官方 .minecraft", survival.groupName(), "分组名应原样传递到世界对象");

        MinecraftWorld modded = findWorld(result, "爽包世界");
        assertEquals(LocationKind.OFFICIAL_DEFAULT, modded.kind());

        MinecraftWorld isolated = findWorld(result, "1145");
        assertEquals(LocationKind.VERSION_ISOLATED, isolated.kind());
        assertTrue(isolated.sourceLabel().contains("版本隔离"));
        assertEquals("1.21.1", isolated.groupName(), "分组名应来自版本隔离目录名");

        assertEquals("1.21.1", isolated.groupName());

        assertEquals(2, (int) result.worldCountByRoot().get(officialSaves.toString()));
        assertEquals(1, (int) result.worldCountByRoot().get(isolatedSaves.toString()));

        // 「半成品」应作为问题上报
        assertTrue(result.issues().stream().map(ScanIssue::message).anyMatch(m -> m.contains("结构不完整")));
    }

    @Test
    void reusesSizeCacheWhenWorldUnchanged() throws IOException {
        Path saves = tempDir.resolve("saves");
        Files.createDirectories(saves);
        WorldFixtures.vanillaWorld(saves, "缓存世界", "缓存世界");
        WorldScanner scanner = new WorldScanner(() -> List.of(
                new WorldRoot(saves.getParent(), saves, LocationKind.OFFICIAL_DEFAULT, "测试")));

        scanner.scan(List.of(), null);
        int missesAfterFirst = scanner.sizeCache().misses();
        ScanResult second = scanner.scan(List.of(), null);

        assertEquals(1, missesAfterFirst);
        assertEquals(1, scanner.sizeCache().misses(), "世界未变化时不应重复统计大小");
        assertEquals(1, scanner.sizeCache().hits());
        assertEquals(1, second.worlds().size());
    }

    @Test
    void acceptsWorldDirectoryAddedManually() throws IOException {
        Path parent = tempDir.resolve("manual");
        Files.createDirectories(parent);
        Path world = WorldFixtures.vanillaWorld(parent, "手动世界", "手动世界");

        WorldScanner scanner = new WorldScanner(List::of);
        ScanResult result = scanner.scan(List.of(world), null);

        assertEquals(1, result.worlds().size());
        assertEquals(LocationKind.MANUAL, result.worlds().get(0).kind());
    }

    @Test
    void acceptsGameDirectoryAddedManually() throws IOException {
        // 用户直接添加 .minecraft 而不是里面的 saves 时也要能工作
        Path gameDir = tempDir.resolve("manual-game").resolve(".minecraft");
        Path saves = gameDir.resolve("saves");
        Files.createDirectories(saves);
        WorldFixtures.vanillaWorld(saves, "游戏目录世界", "游戏目录世界");
        Files.createDirectories(gameDir.resolve("versions"));

        WorldScanner scanner = new WorldScanner(List::of);
        ScanResult result = scanner.scan(List.of(gameDir), null);

        assertEquals(1, result.worlds().size());
        assertEquals("游戏目录世界", result.worlds().get(0).folderName());
    }

    @Test
    void reportsIssueWhenManualDirectoryIsMissing() {
        WorldScanner scanner = new WorldScanner(List::of);
        ScanResult result = scanner.scan(List.of(tempDir.resolve("not-exists")), null);

        assertTrue(result.worlds().isEmpty());
        assertFalse(result.issues().isEmpty());
        assertTrue(result.issues().get(0).message().contains("不存在"));
    }

    @Test
    void keepsScanningWhenProviderFails() {
        WorldScanner scanner = new WorldScanner(() -> {
            throw new IllegalStateException("模拟启动器检测异常");
        });

        ScanResult result = scanner.scan(List.of(), null);

        assertTrue(result.worlds().isEmpty());
        assertTrue(result.issues().stream().anyMatch(issue -> issue.message().contains("自动检测")));
    }

    private static MinecraftWorld findWorld(ScanResult result, String folderName) {
        return result.worlds().stream()
                .filter(world -> world.folderName().equals(folderName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("没有找到世界: " + folderName));
    }
}
