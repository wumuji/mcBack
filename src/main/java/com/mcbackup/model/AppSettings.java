package com.mcbackup.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 应用配置(对应 %APPDATA%\MCBackup\config.json)。
 *
 * <p>用可变对象而不是 record,因为窗口尺寸、主题等会被界面直接更新。</p>
 */
public final class AppSettings {

    /** 配置结构版本,方便以后迁移。 */
    public static final int SCHEMA_VERSION = 1;

    private Theme theme = Theme.FOLLOW_SYSTEM;
    private final List<String> manualWorldDirs = new ArrayList<>();
    private int windowWidth = 1080;
    private int windowHeight = 720;
    private int windowX = Integer.MIN_VALUE;
    private int windowY = Integer.MIN_VALUE;
    private boolean autoBackupEnabled = false;
    private int autoBackupIntervalMinutes = 10;
    private int retainCount = 20;

    public Theme getTheme() {
        return theme;
    }

    public void setTheme(Theme theme) {
        this.theme = theme == null ? Theme.FOLLOW_SYSTEM : theme;
    }

    public List<String> getManualWorldDirs() {
        return manualWorldDirs;
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
