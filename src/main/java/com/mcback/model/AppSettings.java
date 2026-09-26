package com.mcback.model;

import java.util.ArrayList;
import java.util.List;
import java.nio.file.Path;

/**
 * 应用配置(对应 %APPDATA%\mcBack\config.json)。
 *
 * <p>用可变对象而不是 record,因为窗口尺寸、主题等会被界面直接更新。</p>
 */
public final class AppSettings {

    /** 配置结构版本,方便以后迁移。 */
    public static final int SCHEMA_VERSION = 2;

    private Theme theme = Theme.FOLLOW_SYSTEM;
    private final List<String> manualWorldDirs = new ArrayList<>();
    /** 纳入自动备份的世界目录(绝对路径,小写)。空 = 一个都没选。 */
    private final List<String> autoBackupTargets = new ArrayList<>();
    private String backupDir = defaultBackupDir();
    private int windowWidth = 1080;
    private int windowHeight = 720;
    private int windowX = Integer.MIN_VALUE;
    private int windowY = Integer.MIN_VALUE;
    private boolean autoBackupEnabled = false;
    private int autoBackupIntervalMinutes = 10;
    private int retainCount = 20;
    /** 快速备份:区域文件(.mca)不重复压缩。速度约快 8 倍,体积约大 1.5 倍。 */
    private boolean fastBackup = true;
    /** 是否在备份完成后计算整包 SHA-256(多读一遍磁盘,默认关闭)。 */
    private boolean fullVerify = false;
    /** 关闭窗口时最小化到系统托盘。 */
    private boolean minimizeToTray = false;

    /** 默认备份位置:%USERPROFILE%\MCBackups。 */
    public static String defaultBackupDir() {
        return Path.of(System.getProperty("user.home", "."), "MCBackups").toString();
    }

    public String getBackupDir() {
        return backupDir == null || backupDir.isBlank() ? defaultBackupDir() : backupDir;
    }

    public void setBackupDir(String backupDir) {
        this.backupDir = backupDir == null || backupDir.isBlank() ? defaultBackupDir() : backupDir;
    }

    /** 备份目录的 Path 形式。 */
    public Path backupDirPath() {
        return Path.of(getBackupDir());
    }

    public Theme getTheme() {
        return theme;
    }

    public void setTheme(Theme theme) {
        this.theme = theme == null ? Theme.FOLLOW_SYSTEM : theme;
    }

    public List<String> getManualWorldDirs() {
        return manualWorldDirs;
    }

    // ------------------------------------------------------------------
    // 自动备份对象
    // ------------------------------------------------------------------

    public List<String> getAutoBackupTargets() {
        return autoBackupTargets;
    }

    public void setAutoBackupTargets(java.util.Collection<String> targets) {
        autoBackupTargets.clear();
        autoBackupTargets.addAll(com.mcback.service.BackupTargets.normalize(targets));
    }

    public boolean isAutoBackupTarget(String key) {
        return autoBackupTargets.contains(key.toLowerCase(java.util.Locale.ROOT));
    }

    /** 勾选/取消勾选一个世界;返回是否发生了变化。 */
    public boolean setAutoBackupTarget(String key, boolean selected) {
        String normalized = key.toLowerCase(java.util.Locale.ROOT);
        if (selected) {
            if (autoBackupTargets.contains(normalized)) {
                return false;
            }
            autoBackupTargets.add(normalized);
            return true;
        }
        return autoBackupTargets.remove(normalized);
    }

    public boolean hasAutoBackupTargets() {
        return !autoBackupTargets.isEmpty();
    }

    public int getWindowWidth() {
        return windowWidth;
    }

    public void setWindowWidth(int windowWidth) {
        this.windowWidth = windowWidth;
    }

    public int getWindowHeight() {
        return windowHeight;
    }

    public void setWindowHeight(int windowHeight) {
        this.windowHeight = windowHeight;
    }

    public int getWindowX() {
        return windowX;
    }

    public void setWindowX(int windowX) {
        this.windowX = windowX;
    }

    public int getWindowY() {
        return windowY;
    }

    public void setWindowY(int windowY) {
        this.windowY = windowY;
    }

    public boolean isAutoBackupEnabled() {
        return autoBackupEnabled;
    }

    public void setAutoBackupEnabled(boolean autoBackupEnabled) {
        this.autoBackupEnabled = autoBackupEnabled;
    }

    public int getAutoBackupIntervalMinutes() {
        return autoBackupIntervalMinutes;
    }

    public void setAutoBackupIntervalMinutes(int autoBackupIntervalMinutes) {
        this.autoBackupIntervalMinutes = autoBackupIntervalMinutes;
    }

    public int getRetainCount() {
        return retainCount;
    }

    public boolean isFastBackup() {
        return fastBackup;
    }

    public void setFastBackup(boolean fastBackup) {
        this.fastBackup = fastBackup;
    }

    public boolean isFullVerify() {
        return fullVerify;
    }

    public void setFullVerify(boolean fullVerify) {
        this.fullVerify = fullVerify;
    }

    public boolean isMinimizeToTray() {
        return minimizeToTray;
    }

    public void setMinimizeToTray(boolean minimizeToTray) {
        this.minimizeToTray = minimizeToTray;
    }

    public void setRetainCount(int retainCount) {
        this.retainCount = retainCount;
    }

    public void addManualWorldDir(String path) {
        if (path == null || path.isBlank()) {
            return;
        }
        boolean exists = manualWorldDirs.stream().anyMatch(existing -> existing.equalsIgnoreCase(path));
        if (!exists) {
            manualWorldDirs.add(path);
        }
    }

    public boolean removeManualWorldDir(String path) {
        return manualWorldDirs.removeIf(existing -> existing.equalsIgnoreCase(path));
    }
}
