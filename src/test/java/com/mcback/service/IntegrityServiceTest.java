package com.mcback.service;

import com.mcback.model.BackupOptions;
import com.mcback.model.BackupRecord;
import com.mcback.model.LocationKind;
import com.mcback.model.MinecraftWorld;
import com.mcback.storage.BackupRepository;
import com.mcback.testfx.WorldFixtures;
import com.mcback.util.FileUtils;
import com.mcback.util.ProgressListener;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 完整性校验测试:结构校验 + 可选的 SHA-256 内容校验。 */
class IntegrityServiceTest {

    @TempDir
    Path tempDir;

    private BackupRecord backup(boolean computeHash) throws IOException {
        Path saves = tempDir.resolve("saves");
        Files.createDirectories(saves);
        Path worldDir = WorldFixtures.vanillaWorld(saves, "世界", "世界");
        Path backupDir = tempDir.resolve("backups");
        MinecraftWorld world = new MinecraftWorld("世界", worldDir, saves, LocationKind.MANUAL, "测试",
                0L, 0L, FileUtils.worldChangeStamp(worldDir),
                LevelInfoReader.read(worldDir.resolve("level.dat")),
                FileUtils.LockProbe.FREE, null);
        return new BackupService(new BackupRepository(backupDir))
                .backup(world, BackupOptions.of(backupDir, 20, true, computeHash), ProgressListener.NOOP)
                .record();
    }

    @Test
    void verifiesStructureWhenNoHashRecorded() throws IOException {
        BackupRecord record = backup(false);
        assertFalse(record.hasHash());

        IntegrityService.IntegrityReport report = new IntegrityService().verify(record, ProgressListener.NOOP);

        assertTrue(report.valid());
        assertFalse(report.hashChecked());
        assertTrue(report.fileEntries() > 0);
        assertTrue(report.summary().contains("未记录 SHA-256"), report.summary());
    }

    @Test
    void recordsAndVerifiesSha256WhenEnabled() throws IOException {
        BackupRecord record = backup(true);

        assertTrue(record.hasHash(), "开启完整校验后清单里应有 SHA-256");
        assertTrue(record.sha256().length() == 64);
        String json = Files.readString(record.manifestPath(), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"sha256\""), json);

        IntegrityService.IntegrityReport report = new IntegrityService().verify(record, ProgressListener.NOOP);

        assertTrue(report.valid());
        assertTrue(report.hashChecked());
        assertTrue(report.hashMatches());
        assertTrue(report.summary().contains("SHA-256"), report.summary());
    }

    @Test
    void detectsTamperedBackup() throws IOException {
        BackupRecord record = backup(true);
        // 在 ZIP 数据区(不是中央目录)里改一个字节:结构仍可读,但内容变了
        byte[] bytes = Files.readAllBytes(record.zipPath());
        int offset = Math.min(200, bytes.length / 2);
        bytes[offset] = (byte) (bytes[offset] ^ 0x5A);
        Files.write(record.zipPath(), bytes);

        IntegrityService.IntegrityReport report = new IntegrityService().verify(record, ProgressListener.NOOP);

        assertTrue(report.hashChecked());
        assertFalse(report.hashMatches());
        assertFalse(report.valid());
        assertNotEquals(report.storedHash(), report.actualHash());
        assertTrue(report.summary().contains("不一致"), report.summary());
    }

    @Test
    void reportsMissingFile() throws IOException {
        BackupRecord record = backup(false);
        Files.delete(record.zipPath());

        IntegrityService.IntegrityReport report = new IntegrityService().verify(record, ProgressListener.NOOP);

        assertFalse(report.valid());
        assertTrue(report.summary().contains("不存在"));
    }
}
