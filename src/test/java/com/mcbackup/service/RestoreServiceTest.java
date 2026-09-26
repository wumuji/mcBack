package com.mcbackup.service;

import com.mcbackup.model.BackupOptions;
import com.mcbackup.model.BackupResult;
import com.mcbackup.model.LocationKind;
import com.mcbackup.model.MinecraftWorld;
import com.mcbackup.storage.BackupRepository;
import com.mcbackup.testfx.NbtFixture;
import com.mcbackup.testfx.WorldFixtures;
import com.mcbackup.util.FileUtils;
import com.mcbackup.util.Hashing;
import com.mcbackup.util.ProgressListener;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 恢复是唯一会改动存档的操作,所以测试重点是「任何失败都不能弄坏原世界」。
class RestoreServiceTest {

    @TempDir
    Path tempDir;

    private Path savesDir;
    private Path backupDir;

    private MinecraftWorld worldOf(Path worldDir) {
        return new MinecraftWorld(worldDir.getFileName().toString(), worldDir, worldDir.getParent(),
                LocationKind.MANUAL, "测试", 0L, 0L, FileUtils.worldChangeStamp(worldDir),
                LevelInfoReader.read(worldDir.resolve("level.dat")),
                FileUtils.probeSessionLock(worldDir), null);
    }

    private BackupResult backupWorld(Path worldDir) {
        BackupRepository repository = new BackupRepository(backupDir);
        return new BackupService(repository).backup(worldOf(worldDir), BackupOptions.of(backupDir, 20),
                ProgressListener.NOOP);
    }

    private Path createWorld(String name) throws IOException {
        savesDir = tempDir.resolve("saves");
        backupDir = tempDir.resolve("backups");
        Files.createDirectories(savesDir);
        return WorldFixtures.vanillaWorld(savesDir, name, name);
    }

    @Test
    void restoresBackupAndKeepsOriginalWorld() throws IOException {
        Path world = createWorld("我的世界");
        Files.writeString(world.resolve("region").resolve("r.0.0.mca"), "原始区块数据", StandardCharsets.UTF_8);
        BackupResult backup = backupWorld(world);

        // 破坏当前世界,模拟误删或损坏
        Files.writeString(world.resolve("region").resolve("r.0.0.mca"), "被破坏的数据", StandardCharsets.UTF_8);
        Files.deleteIfExists(world.resolve("level.dat"));
        assertFalse(WorldDetector.isWorld(world));

        RestoreService.RestoreResult result = new RestoreService()
                .restore(backup.record().zipPath(), RestoreService.RestoreOptions.to(world), ProgressListener.NOOP);

        assertEquals(world, result.worldDir());
        assertTrue(WorldDetector.isWorld(world), "恢复后应是一个完整世界");
        assertEquals("原始区块数据",
                Files.readString(world.resolve("region").resolve("r.0.0.mca"), StandardCharsets.UTF_8));
        assertNotNull(result.originalBackupDir(), "原世界必须被保留下来");
        assertTrue(Files.isDirectory(result.originalBackupDir()));
        assertTrue(result.originalBackupDir().getFileName().toString().contains(".restore-backup-"));
        assertTrue(result.fileCount() > 0);
        assertFalse(WorldDetector.isWorld(result.originalBackupDir()),
                "原世界副本不应被识别为可用世界,避免被重复备份");
    }

    @Test
    void refusesToRestoreWhileWorldIsLocked() throws IOException {
        Path world = createWorld("正在玩的世界");
        BackupResult backup = backupWorld(world);
        Path lockFile = world.resolve("session.lock");
        byte[] before = Files.readAllBytes(world.resolve("level.dat"));

        try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.WRITE);
             FileLock lock = channel.lock()) {
            BackupException error = assertThrows(BackupException.class, () -> new RestoreService()
                    .restore(backup.record().zipPath(), RestoreService.RestoreOptions.to(world),
                            ProgressListener.NOOP));
            assertTrue(error.getMessage().contains("正在使用"), error.getMessage());
            assertTrue(error.getMessage().contains("退出 Minecraft"), error.getMessage());
        }

        assertTrue(WorldDetector.isWorld(world));
        assertArrayEquals(before, Files.readAllBytes(world.resolve("level.dat")));
    }

    @Test
    void doesNotTouchWorldWhenBackupIsCorrupt() throws IOException {
        Path world = createWorld("世界");
        BackupResult backup = backupWorld(world);
        byte[] before = Files.readAllBytes(world.resolve("level.dat"));
        Files.write(backup.record().zipPath(), new byte[]{1, 2, 3, 4, 5});

        BackupException error = assertThrows(BackupException.class, () -> new RestoreService()
                .restore(backup.record().zipPath(), RestoreService.RestoreOptions.to(world),
                        ProgressListener.NOOP));

        assertTrue(error.getMessage().contains("校验未通过"), error.getMessage());
        assertTrue(WorldDetector.isWorld(world));
        assertArrayEquals(before, Files.readAllBytes(world.resolve("level.dat")));
        assertNoRestoreLeftovers(savesDir.getParent());
    }

    @Test
    void detectsSha256Mismatch() throws IOException {
        Path world = createWorld("世界");
        BackupResult backup = backupWorld(world);
        String wrong = "0".repeat(64);

        BackupException error = assertThrows(BackupException.class, () -> new RestoreService()
                .restore(backup.record().zipPath(),
                        new RestoreService.RestoreOptions(world, wrong), ProgressListener.NOOP));

        assertTrue(error.getMessage().contains("SHA-256"), error.getMessage());
        assertTrue(WorldDetector.isWorld(world));
    }

    @Test
    void acceptsMatchingSha256() throws IOException {
        Path world = createWorld("世界");
        BackupResult backup = backupWorld(world);
        String correct = Hashing.sha256(backup.record().zipPath());

        RestoreService.RestoreResult result = new RestoreService().restore(backup.record().zipPath(),
                new RestoreService.RestoreOptions(world, correct), ProgressListener.NOOP);

        assertTrue(WorldDetector.isWorld(result.worldDir()));
    }

    @Test
    void restoresIntoMissingDirectoryWithoutOriginalCopy() throws IOException {
        Path world = createWorld("世界");
        BackupResult backup = backupWorld(world);
        Path target = savesDir.resolve("恢复出来的新世界");

        RestoreService.RestoreResult result = new RestoreService().restore(backup.record().zipPath(),
                RestoreService.RestoreOptions.to(target), ProgressListener.NOOP);

        assertTrue(WorldDetector.isWorld(target));
        assertNull(result.originalBackupDir(), "目标原本不存在时不该有旧世界副本");
    }

    @Test
    void rejectsZipContainingPathTraversal() throws IOException {
        Path world = createWorld("世界");
        Path evil = tempDir.resolve("evil.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(evil))) {
            zip.putNextEntry(new ZipEntry("level.dat"));
            zip.write(NbtFixture.levelDat("坏备份", "1.21.1", 3955, 0, 2, false, 0));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("region/r.0.0.mca"));
            zip.write(new byte[16]);
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("../escaped.txt"));
            zip.write("逃逸内容".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }

        BackupException error = assertThrows(BackupException.class, () -> new RestoreService()
                .restore(evil, RestoreService.RestoreOptions.to(world), ProgressListener.NOOP));

        assertTrue(error.getMessage().contains("非法路径") || error.getMessage().contains("可疑路径条目"),
                error.getMessage());
        assertFalse(Files.exists(tempDir.resolve("escaped.txt")), "不允许写到目标目录之外");
        assertTrue(WorldDetector.isWorld(world));
        assertNoRestoreLeftovers(savesDir.getParent());
    }

    @Test
    void reportsProgressStages() throws IOException {
        Path world = createWorld("世界");
        BackupResult backup = backupWorld(world);
        List<String> stages = new ArrayList<>();

        new RestoreService().restore(backup.record().zipPath(), RestoreService.RestoreOptions.to(world),
                (stage, done, total, detail) -> stages.add(stage));

        assertTrue(stages.contains("校验备份"), stages.toString());
        assertTrue(stages.contains("解压"), stages.toString());
        assertTrue(stages.contains("替换世界"), stages.toString());
    }

    private void assertNoRestoreLeftovers(Path directory) throws IOException {
        try (var stream = Files.list(directory)) {
            List<Path> leftovers = stream
                    .filter(path -> path.getFileName().toString().startsWith(".mcbackup-restore-"))
                    .toList();
            assertTrue(leftovers.isEmpty(), "不应残留恢复临时目录: " + leftovers);
        }
    }
}
