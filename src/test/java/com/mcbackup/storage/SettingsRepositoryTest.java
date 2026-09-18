package com.mcbackup.storage;

import com.mcbackup.model.AppSettings;
import com.mcbackup.model.Theme;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 配置读写测试:往返、损坏回退、原子写。 */
class SettingsRepositoryTest {

    @TempDir
    Path tempDir;

    @Test
    void savesAndLoadsAllFields() {
        SettingsRepository repository = new SettingsRepository(tempDir.resolve("MCBackup"));
        AppSettings settings = new AppSettings();
        settings.setTheme(Theme.DARK);
        settings.addManualWorldDir("E:\\minecraft\\saves");
        settings.addManualWorldDir("D:\\MC\\.minecraft\\saves");
        settings.setWindowWidth(1280);
        settings.setWindowHeight(800);
        settings.setWindowX(120);
        settings.setWindowY(64);

        Path saved = repository.save(settings);
        assertNotNull(saved);
        assertTrue(Files.isRegularFile(saved));

        AppSettings loaded = repository.load();
        assertEquals(Theme.DARK, loaded.getTheme());
        assertEquals(List.of("E:\\minecraft\\saves", "D:\\MC\\.minecraft\\saves"), loaded.getManualWorldDirs());
        assertEquals(1280, loaded.getWindowWidth());
        assertEquals(800, loaded.getWindowHeight());
        assertEquals(120, loaded.getWindowX());
        assertEquals(64, loaded.getWindowY());
    }

    @Test
    void returnsDefaultsWhenConfigMissing() {
        SettingsRepository repository = new SettingsRepository(tempDir.resolve("empty"));

        AppSettings settings = repository.load();

        assertEquals(Theme.FOLLOW_SYSTEM, settings.getTheme());
        assertTrue(settings.getManualWorldDirs().isEmpty());
        assertEquals(1080, settings.getWindowWidth());
        assertEquals(720, settings.getWindowHeight());
    }

    @Test
    void fallsBackAndBacksUpBrokenConfig() throws IOException {
        Path base = tempDir.resolve("broken");
        Files.createDirectories(base);
        Path config = base.resolve("config.json");
        Files.writeString(config, "{ 这不是 JSON", StandardCharsets.UTF_8);
        SettingsRepository repository = new SettingsRepository(base);

        AppSettings settings = repository.load();

        assertEquals(Theme.FOLLOW_SYSTEM, settings.getTheme());
        assertFalse(Files.exists(config), "损坏的配置文件应被改名备份");
        try (Stream<Path> files = Files.list(base)) {
            assertTrue(files.anyMatch(path -> path.getFileName().toString().startsWith("config.json.bak-")),
                    "应生成 config.json.bak-<时间戳>");
        }
    }

    @Test
    void clampsOutOfRangeValues() throws IOException {
        Path base = tempDir.resolve("clamped");
        Files.createDirectories(base);
        Files.writeString(base.resolve("config.json"),
                "{\"windowWidth\": 10, \"windowHeight\": 999999, \"retainCount\": -5}",
                StandardCharsets.UTF_8);
        SettingsRepository repository = new SettingsRepository(base);

        AppSettings settings = repository.load();

        assertEquals(640, settings.getWindowWidth());
        assertEquals(10000, settings.getWindowHeight());
        assertEquals(1, settings.getRetainCount());
    }

    @Test
    void leavesNoTemporaryFileBehind() throws IOException {
        SettingsRepository repository = new SettingsRepository(tempDir.resolve("atomic"));
        repository.save(new AppSettings());

        try (Stream<Path> files = Files.list(repository.baseDir())) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString().endsWith(".tmp")),
                    "原子写不应留下临时文件");
        }
        assertTrue(Files.isRegularFile(repository.configPath()));
        assertTrue(repository.logsDir().toString().endsWith("logs"));
    }

    @Test
    void ignoresDuplicateManualDirectories() {
        AppSettings settings = new AppSettings();
        settings.addManualWorldDir("E:\\saves");
        settings.addManualWorldDir("e:\\SAVES");

        assertEquals(1, settings.getManualWorldDirs().size());
        assertTrue(settings.removeManualWorldDir("E:\\SAVES"));
        assertTrue(settings.getManualWorldDirs().isEmpty());
    }
}
