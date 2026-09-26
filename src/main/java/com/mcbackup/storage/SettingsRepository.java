package com.mcbackup.storage;

import com.mcbackup.model.AppSettings;
import com.mcbackup.model.Theme;
import com.mcbackup.util.Json;
import com.mcbackup.util.Log;
import com.mcbackup.util.PathUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 配置读写(第一阶段只用 JSON 文件,不引入数据库)。
 *
 * <p>设计原则:配置损坏绝不能让程序启动失败。任何读取异常都会回退到默认值,
 * 并把损坏的文件改名备份为 {@code config.json.bak-<时间戳>},方便用户自查。</p>
 */
public final class SettingsRepository {

    private static final String CONFIG_FILE = "config.json";
    private static final DateTimeFormatter BACKUP_STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final Path baseDir;

    public SettingsRepository(Path baseDir) {
        this.baseDir = baseDir;
    }

    /** 默认位置:%APPDATA%\MCBackup。 */
    public static SettingsRepository defaultRepository() {
        String appData = System.getenv("APPDATA");
        Path base;
        if (appData != null && !appData.isBlank()) {
            base = Path.of(appData).resolve("MCBackup");
        } else {
            base = Path.of(System.getProperty("user.home", "."), ".mcbackup");
        }
        return new SettingsRepository(base);
    }

    public Path baseDir() {
        return baseDir;
    }

    public Path configPath() {
        return baseDir.resolve(CONFIG_FILE);
    }

    public Path logsDir() {
        return baseDir.resolve("logs");
    }

    /** 读取配置;失败时返回默认配置而不是抛异常。 */
    public AppSettings load() {
        AppSettings settings = new AppSettings();
        Path file = configPath();
        if (!PathUtils.isFile(file)) {
            Log.info("配置文件不存在,使用默认配置: " + file);
            return settings;
        }
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            Log.warn("读取配置失败,使用默认配置: %s (%s)", file, e.getMessage());
            return settings;
        }
        try {
            Map<String, Object> root = Json.parseObject(text);
            settings.setTheme(Theme.fromName(Json.optString(root, "theme", "FOLLOW_SYSTEM")));
            settings.setBackupDir(Json.optString(root, "backupDir", AppSettings.defaultBackupDir()));
            for (String dir : Json.optStringList(root, "manualWorldDirs")) {
                settings.addManualWorldDir(dir);
            }
            settings.setWindowWidth(clamp(Json.optInt(root, "windowWidth", 1080), 640, 10000));
            settings.setWindowHeight(clamp(Json.optInt(root, "windowHeight", 720), 480, 10000));
            settings.setWindowX(Json.optInt(root, "windowX", Integer.MIN_VALUE));
            settings.setWindowY(Json.optInt(root, "windowY", Integer.MIN_VALUE));
            settings.setAutoBackupEnabled(Json.optBoolean(root, "autoBackupEnabled", false));
            settings.setAutoBackupIntervalMinutes(clamp(Json.optInt(root, "autoBackupIntervalMinutes", 10), 1, 1440));
            settings.setRetainCount(clamp(Json.optInt(root, "retainCount", 20), 1, 1000));
            settings.setFastBackup(Json.optBoolean(root, "fastBackup", true));
            settings.setFullVerify(Json.optBoolean(root, "fullVerify", false));
            settings.setMinimizeToTray(Json.optBoolean(root, "minimizeToTray", false));
            Log.info("已加载配置: %s (主题=%s, 手动目录=%d)", file, settings.getTheme(),
                    settings.getManualWorldDirs().size());
            return settings;
        } catch (Json.JsonException e) {
            Path backup = backupBrokenConfig(file);
            Log.warn("配置文件格式错误,已改用默认配置。原文件备份为: %s (%s)", backup, e.getMessage());
            return settings;
        }
    }

    /**
     * 保存配置。使用「写临时文件 + 原子改名」,避免程序被强制结束时留下半个配置文件。
     *
     * @return 配置文件路径;保存失败时返回 null(调用方只提示,不中断流程)
     */
    public Path save(AppSettings settings) {
        Path file = configPath();
        Path temp = baseDir.resolve(CONFIG_FILE + ".tmp");
        try {
            Files.createDirectories(baseDir);
            String text = Json.write(toJson(settings)) + System.lineSeparator();
            Files.writeString(temp, text, StandardCharsets.UTF_8);
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailed) {
                Log.debug("原子改名不可用,回退普通覆盖: " + atomicFailed.getMessage());
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            return file;
        } catch (IOException | RuntimeException e) {
            Log.error("保存配置失败: " + file, e);
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                // 临时文件清理失败无需处理
            }
            return null;
        }
    }

    private Map<String, Object> toJson(AppSettings settings) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schemaVersion", AppSettings.SCHEMA_VERSION);
        root.put("theme", settings.getTheme().name());
        root.put("backupDir", settings.getBackupDir());
        List<String> dirs = new ArrayList<>(settings.getManualWorldDirs());
        root.put("manualWorldDirs", dirs);
        root.put("windowWidth", settings.getWindowWidth());
        root.put("windowHeight", settings.getWindowHeight());
        if (settings.getWindowX() != Integer.MIN_VALUE) {
            root.put("windowX", settings.getWindowX());
        }
        if (settings.getWindowY() != Integer.MIN_VALUE) {
            root.put("windowY", settings.getWindowY());
        }
        root.put("autoBackupEnabled", settings.isAutoBackupEnabled());
        root.put("autoBackupIntervalMinutes", settings.getAutoBackupIntervalMinutes());
        root.put("retainCount", settings.getRetainCount());
        root.put("fastBackup", settings.isFastBackup());
        root.put("fullVerify", settings.isFullVerify());
        root.put("minimizeToTray", settings.isMinimizeToTray());
        return root;
    }

    private Path backupBrokenConfig(Path file) {
        Path backup = baseDir.resolve(CONFIG_FILE + ".bak-" + BACKUP_STAMP.format(LocalDateTime.now()));
        try {
            Files.move(file, backup, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Log.errorQuietly("备份损坏的配置文件失败: " + file, e);
        }
        return backup;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
