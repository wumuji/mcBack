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

    @Test
    void exposesCountdownToNextRun() throws IOException {
        Fixture fixture = fixture(true);
        assertEquals(0L, fixture.scheduler().nextRunAtMillis(), "未启动时没有计划时间");
        assertEquals(-1L, fixture.scheduler().secondsUntilNextRun());

        fixture.scheduler().start(10);
        long next = fixture.scheduler().nextRunAtMillis();
        assertTrue(next > System.currentTimeMillis(), "启动后应给出未来的执行时间");
        long seconds = fixture.scheduler().secondsUntilNextRun();
        assertTrue(seconds > 9 * 60 && seconds <= 10 * 60, "倒计时应在 10 分钟以内:" + seconds);

        fixture.scheduler().tick();
        long afterTick = fixture.scheduler().nextRunAtMillis();
        assertTrue(afterTick >= next, "每次执行后应重新计时");

        fixture.scheduler().stop();
        assertEquals(0L, fixture.scheduler().nextRunAtMillis(), "暂停后不应再有计划时间");
    }

    @Test
    void formatsCountdownForHumans() {
        assertEquals("45 秒", BackupScheduler.formatCountdown(45));
        assertEquals("2 分 5 秒", BackupScheduler.formatCountdown(125));
        assertEquals("1 小时 5 分", BackupScheduler.formatCountdown(3900));
        assertEquals("未启动", BackupScheduler.formatCountdown(-1));
    }

    @Test
    void onlyBacksUpWorldsProvidedByTheSupplier() throws IOException {
        // 调度器只认「喂给它的世界」:MainWindow 会把扫描结果按勾选过滤后再传进来
        Path saves = tempDir.resolve("saves-filter");
        Files.createDirectories(saves);
        Path kept = WorldFixtures.vanillaWorld(saves, "要备份的", "要备份的");
        WorldFixtures.vanillaWorld(saves, "不该备份的", "不该备份的");
        Path backupDir = tempDir.resolve("backups-filter");
        BackupRepository repository = new BackupRepository(backupDir);
        MinecraftWorld only = new MinecraftWorld("要备份的", kept, saves, LocationKind.MANUAL, "测试",
                0L, 0L, FileUtils.worldChangeStamp(kept),
                LevelInfoReader.read(kept.resolve("level.dat")), FileUtils.LockProbe.FREE, null);
        BackupScheduler scheduler = new BackupScheduler(List::of,
                new BackupService(repository),
                () -> BackupOptions.of(backupDir, 20),
                repository,
                BackupScheduler.Listener.NOOP);

        scheduler.tick();

        assertEquals(0, repository.listAll().size(), "没有喂世界时不应备份任何东西");
        scheduler.shutdown();

        BackupScheduler withOne = new BackupScheduler(() -> List.of(only),
                new BackupService(repository),
                () -> BackupOptions.of(backupDir, 20),
                repository,
                BackupScheduler.Listener.NOOP);
        withOne.tick();
        withOne.shutdown();

        assertEquals(1, repository.listAll().size());
        assertEquals("要备份的", repository.listAll().get(0).worldFolderName());
    }
}
