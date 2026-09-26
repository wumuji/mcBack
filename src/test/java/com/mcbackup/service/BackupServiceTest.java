package com.mcbackup.service;

import com.mcbackup.model.BackupOptions;
import com.mcbackup.model.BackupRecord;
import com.mcbackup.model.BackupResult;
import com.mcbackup.model.LocationKind;
import com.mcbackup.model.MinecraftWorld;
import com.mcbackup.storage.BackupRepository;
import com.mcbackup.testfx.WorldFixtures;
import com.mcbackup.util.ProgressListener;
import com.mcbackup.util.ZipUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 备份服务端到端测试:目录布局、原子提交、清单、保留策略、误删保护。 */
class BackupServiceTest {

    @TempDir
    Path tempDir;

    private Path backupDir;
    private Path savesDir;

    private BackupService newService() throws IOException {
        backupDir = tempDir.resolve("backups");
        savesDir = tempDir.resolve("saves");
        Files.createDirectories(savesDir);
        return new BackupService(new BackupRepository(backupDir));
    }

    private MinecraftWorld world(String folderName, String levelName) throws IOException {
        Path worldDir = WorldFixtures.vanillaWorld(savesDir, folderName, levelName);
        return new MinecraftWorld(folderName, worldDir, savesDir, LocationKind.MANUAL, "测试",
                0L, 0L, com.mcbackup.util.FileUtils.worldChangeStamp(worldDir),
                LevelInfoReader.read(worldDir.resolve("level.dat")),
                com.mcbackup.util.FileUtils.LockProbe.FREE, null);
    }

    @Test
    void createsVerifiedBackupWithManifestAndCleansTemp() throws IOException {
        BackupService service = newService();
        MinecraftWorld world = world("新的世界", "生存世界");

        BackupResult result = service.backup(world, BackupOptions.of(backupDir, 20), ProgressListener.NOOP);

        BackupRecord record = result.record();
        assertTrue(result.complete());
        assertTrue(Files.isRegularFile(record.zipPath()), "ZIP 必须存在");
        assertTrue(Files.isRegularFile(record.manifestPath()), "清单文件必须存在");
        assertTrue(record.zipFileName().endsWith(".zip"));
        assertTrue(record.zipBytes() > 0);
        assertTrue(record.fileCount() > 0);
        assertEquals(BackupRecord.PRODUCER, record.producer());
        assertEquals(BackupRecord.STATUS_OK, record.status());
        assertEquals(world.folderName(), record.worldFolderName());
        assertEquals("生存世界", record.worldDisplayName());
        assertEquals(world.changeStamp(), record.sourceChangeStamp());

        // 最终目录里不应留下任何 .tmp
        try (Stream<Path> files = Files.walk(backupDir)) {
            List<Path> temps = files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".tmp"))
                    .toList();
            assertTrue(temps.isEmpty(), "不应残留临时文件: " + temps);
        }
        // 临时副本目录应被清理
        Path tmpRoot = backupDir.resolve(".tmp");
        if (Files.isDirectory(tmpRoot)) {
            try (Stream<Path> children = Files.list(tmpRoot)) {
                assertEquals(0, children.count(), "临时副本应被删除");
            }
        }

        ZipUtils.ZipVerification verification = ZipUtils.verify(record.zipPath());
        assertTrue(verification.valid(), verification.problem());
        assertEquals(record.fileCount(), verification.fileEntries());
        assertTrue(verification.comment().startsWith(BackupRecord.PRODUCER));
    }

    @Test
    void backupContentMatchesWorldLayout() throws IOException {
        BackupService service = newService();
        MinecraftWorld world = world("世界", "世界");

        BackupResult result = service.backup(world, BackupOptions.of(backupDir, 20), ProgressListener.NOOP);

        try (var zipFile = new java.util.zip.ZipFile(result.record().zipPath().toFile(), StandardCharsets.UTF_8)) {
            assertNotNull(zipFile.getEntry("level.dat"));
            assertNotNull(zipFile.getEntry("region/r.0.0.mca"));
            assertNotNull(zipFile.getEntry("session.lock"));
        }
    }

    @Test
    void writesReadableManifest() throws IOException {
        BackupService service = newService();
        MinecraftWorld world = world("世界", "生存世界");
        BackupResult result = service.backup(world, BackupOptions.of(backupDir, 20), ProgressListener.NOOP);

        String json = Files.readString(result.record().manifestPath(), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"producer\": \"MCBackup\""));
        assertTrue(json.contains("\"status\": \"OK\""));
        assertTrue(json.contains("\"strategy\": \"FULL\""));

        List<BackupRecord> records = service.repository().listAll();
        assertEquals(1, records.size());
        assertTrue(records.get(0).managed());
        assertEquals(result.record().zipFileName(), records.get(0).zipFileName());
    }

    @Test
    void appliesRetentionAndKeepsNewest() throws IOException {
        BackupService service = newService();
        MinecraftWorld world = world("世界", "世界");

        for (int i = 0; i < 3; i++) {
            service.backup(world, BackupOptions.of(backupDir, 2), ProgressListener.NOOP);
            // 让文件名与创建时间拉开,避免同一秒内互相覆盖
            try {
                Thread.sleep(1100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        List<BackupRecord> records = service.repository().listForWorld(world.folderName());
        assertEquals(2, records.size(), "保留策略应只留 2 份");
        // 剩下的两份都是本程序生成的,并且 ZIP 与清单成对存在
        for (BackupRecord record : records) {
            assertTrue(record.managed());
            assertTrue(Files.isRegularFile(record.zipPath()));
            assertTrue(Files.isRegularFile(record.manifestPath()));
        }
    }

    @Test
    void neverDeletesUserProvidedZip() throws IOException {
        BackupService service = newService();
        MinecraftWorld world = world("世界", "世界");
        service.backup(world, BackupOptions.of(backupDir, 20), ProgressListener.NOOP);

        // 用户自己放进去的 ZIP(没有清单)
        Path userZip = service.repository().worldDir(world.folderName()).resolve("我自己做的备份.zip");
        Files.writeString(userZip, "user data");

        List<BackupRecord> records = service.repository().listForWorld(world.folderName());
        BackupRecord unmanaged = records.stream().filter(r -> !r.managed()).findFirst().orElseThrow();
        assertEquals("我自己做的备份.zip", unmanaged.zipFileName());

        // 保留数量设成 1,也不允许删掉用户的 ZIP
        service.repository().applyRetention(world.folderName(), 1);
        BackupRepository.DeleteOutcome outcome = service.repository().delete(unmanaged);
        assertFalse(outcome.deleted());
        assertEquals(BackupRepository.DeleteStatus.NOT_MANAGED, outcome.status());
        assertTrue(Files.isRegularFile(userZip), "用户的 ZIP 必须原样保留");
    }

    @Test
    void rejectsNonWorldDirectory() throws IOException {
        BackupService service = newService();
        Path notAWorld = savesDir.resolve("随便一个目录");
        Files.createDirectories(notAWorld);
        MinecraftWorld fake = new MinecraftWorld("随便一个目录", notAWorld, savesDir,
                LocationKind.MANUAL, "测试", 0L, 0L, 0L,
                com.mcbackup.model.WorldInfo.UNAVAILABLE,
                com.mcbackup.util.FileUtils.LockProbe.UNKNOWN, null);

        BackupException error = assertThrows(BackupException.class,
                () -> service.backup(fake, BackupOptions.of(backupDir, 20), ProgressListener.NOOP));
        assertTrue(error.getMessage().contains("不是有效的 Minecraft 世界"));
    }

    @Test
    void reportsLeftoverTempFilesWithoutDeletingThem() throws IOException {
        BackupService service = newService();
        Path leftovers = backupDir.resolve(".tmp");
        Files.createDirectories(leftovers.resolve("半截备份"));
        Files.writeString(leftovers.resolve("半截备份").resolve("level.dat"), "x");

        List<Path> found = service.repository().findLeftoverTempFiles();

        assertFalse(found.isEmpty(), "应报告上次残留的临时文件");
        assertTrue(Files.exists(leftovers.resolve("半截备份").resolve("level.dat")),
                "报告阶段不允许删除任何数据");
    }

    @Test
    void reportsProgressStages() throws IOException {
        BackupService service = newService();
        MinecraftWorld world = world("世界", "世界");
        List<String> stages = new java.util.ArrayList<>();

        service.backup(world, BackupOptions.of(backupDir, 20),
                (stage, done, total, detail) -> stages.add(stage));

        assertTrue(stages.contains("复制文件"));
        assertTrue(stages.contains("压缩"));
        assertTrue(stages.contains("校验"));
        assertTrue(stages.contains("提交"));
        assertTrue(stages.contains("清理"));
    }

    @Test
    void requiresEnoughDiskSpaceBeforeStarting() {
        assertEquals(0L, BackupService.requiredSpaceBytes(0));
        long small = 100L * 1024 * 1024;
        assertEquals((long) (small * 2.1), BackupService.requiredSpaceBytes(small));

        // 空间充足:不抛异常
        BackupService.ensureEnoughSpace(10L * 1024 * 1024 * 1024, small, "D:\\MCBackups");

        // 空间不足:给出带具体数字的提示
        BackupException error = assertThrows(BackupException.class,
                () -> BackupService.ensureEnoughSpace(small, small, "D:\\MCBackups"));
        assertTrue(error.getMessage().contains("剩余空间不足"), error.getMessage());
        assertTrue(error.getMessage().contains("D:\\MCBackups"), error.getMessage());
    }

    @Test
    void recordsThatWorldWasRunningDuringBackup() throws IOException {
        BackupService service = newService();
        MinecraftWorld world = world("正在玩的世界", "正在玩的世界");
        // 模拟 Minecraft 正在使用这个世界
        MinecraftWorld running = new MinecraftWorld(world.folderName(), world.worldDir(), world.savesDir(),
                world.kind(), world.sourceLabel(), world.groupName(), world.sizeBytes(), world.lastModified(),
                world.changeStamp(), world.info(), com.mcbackup.util.FileUtils.LockProbe.LOCKED, null);

        BackupResult result = service.backup(running, BackupOptions.of(backupDir, 20), ProgressListener.NOOP);

        assertTrue(result.record().sourceRunning(), "备份时世界在运行,应记录下来");
        String json = Files.readString(result.record().manifestPath(), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"sourceRunning\": true"), json);

        // 重新读取清单时也要保留这个标记
        List<BackupRecord> records = service.repository().listAll();
        assertEquals(1, records.size());
        assertTrue(records.get(0).sourceRunning());
    }
}
