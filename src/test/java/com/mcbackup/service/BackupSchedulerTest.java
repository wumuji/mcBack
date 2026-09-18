package com.mcbackup.service;

import com.mcbackup.model.BackupOptions;
import com.mcbackup.model.BackupRecord;
import com.mcbackup.model.LocationKind;
import com.mcbackup.model.MinecraftWorld;
import com.mcbackup.storage.BackupRepository;
import com.mcbackup.testfx.WorldFixtures;
import com.mcbackup.util.FileUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 自动备份调度测试:变化检测、跳过、失败不中断、启动停止。 */
class BackupSchedulerTest {

    @TempDir
    Path tempDir;

    private record Fixture(BackupScheduler scheduler, BackupRepository repository, Path worldDir,
                           List<String> events, AtomicInteger failures) {
    }

    private Fixture fixture(boolean worldIsValid) throws IOException {
        Path saves = tempDir.resolve("saves");
        Files.createDirectories(saves);
        Path worldDir = worldIsValid
                ? WorldFixtures.vanillaWorld(saves, "世界", "生存世界")
                : Files.createDirectories(saves.resolve("世界"));
        Path backupDir = tempDir.resolve("backups");
        BackupRepository repository = new BackupRepository(backupDir);
        List<String> events = new ArrayList<>();
        AtomicInteger failures = new AtomicInteger();

        java.util.function.Supplier<List<MinecraftWorld>> worlds = () -> {
            MinecraftWorld world = new MinecraftWorld("世界", worldDir, saves, LocationKind.MANUAL, "测试",
                    0L, 0L, FileUtils.worldChangeStamp(worldDir),
                    LevelInfoReader.read(worldDir.resolve("level.dat")),
                    FileUtils.LockProbe.FREE, null);
            return List.of(world);
        };
        BackupScheduler.Listener listener = new BackupScheduler.Listener() {
            @Override
            public void onSkipped(MinecraftWorld world, String reason) {
                events.add("skipped:" + reason);
            }

            @Override
            public void onBackupStarted(MinecraftWorld world) {
                events.add("started");
            }

            @Override
            public void onBackupFinished(MinecraftWorld world, com.mcbackup.model.BackupResult result) {
                events.add("finished:" + result.record().zipFileName());
            }

            @Override
            public void onBackupFailed(MinecraftWorld world, Exception error) {
                failures.incrementAndGet();
                events.add("failed");
            }

            @Override
            public void onTickFinished(int backedUp, int skipped, long elapsedMillis) {
                events.add("tick:" + backedUp + "/" + skipped);
            }
        };
        BackupScheduler scheduler = new BackupScheduler(worlds, new BackupService(repository),
                () -> BackupOptions.of(backupDir, 20), repository, listener);
        return new Fixture(scheduler, repository, worldDir, events, failures);
    }

    @Test
    void backsUpFirstTimeThenSkipsUnchangedWorld() throws IOException, InterruptedException {
        Fixture fixture = fixture(true);

        fixture.scheduler().tick();
        assertTrue(fixture.events().contains("tick:1/0"), "第一次应备份:" + fixture.events());
        List<BackupRecord> afterFirst = fixture.repository().listForWorld("世界");
        assertEquals(1, afterFirst.size());

        fixture.events().clear();
        fixture.scheduler().tick();
        assertTrue(fixture.events().stream().anyMatch(event -> event.startsWith("skipped:")),
                "世界没有变化时应跳过:" + fixture.events());
        assertTrue(fixture.events().contains("tick:0/1"), fixture.events().toString());
        assertEquals(1, fixture.repository().listForWorld("世界").size(), "跳过时不应新增备份");
    }

    @Test
    void backsUpAgainWhenWorldChanges() throws IOException {
        Fixture fixture = fixture(true);
        fixture.scheduler().tick();
        fixture.events().clear();

        // 模拟 Minecraft 保存世界:level.dat 时间变化
        Files.setLastModifiedTime(fixture.worldDir().resolve("level.dat"),
                FileTime.fromMillis(System.currentTimeMillis() + 10_000));

        fixture.scheduler().tick();

        assertTrue(fixture.events().contains("tick:1/0"), "世界变化后应再次备份:" + fixture.events());
        assertTrue(fixture.repository().listForWorld("世界").size() >= 2);
    }

    @Test
    void reportsFailureWithoutBreakingScheduler() throws IOException {
        Fixture fixture = fixture(false);

        fixture.scheduler().tick();

        assertEquals(1, fixture.failures().get(), "无效世界应报告一次失败");
        assertTrue(fixture.events().contains("tick:0/0"), fixture.events().toString());
    }

    @Test
    void startsAndStops() {
        Fixture fixture;
        try {
            fixture = fixture(true);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        assertFalse(fixture.scheduler().isRunning());

        fixture.scheduler().start(10);
        assertTrue(fixture.scheduler().isRunning());
        assertEquals(10, fixture.scheduler().intervalMinutes());

        fixture.scheduler().start(5);
        assertEquals(5, fixture.scheduler().intervalMinutes());

        fixture.scheduler().stop();
        assertFalse(fixture.scheduler().isRunning());

        fixture.scheduler().start(0);
        assertFalse(fixture.scheduler().isRunning(), "间隔为 0 应视为关闭自动备份");
        fixture.scheduler().shutdown();
    }
}
