package com.mcback.ui;

import com.mcback.ui.components.AppIcon;
import com.mcback.util.Log;

import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.event.ActionListener;

/**
 * 系统托盘支持(可选功能)。
 *
 * <p>纯粹用 JDK 自带的 {@link SystemTray},不安装任何后台服务;托盘不可用时(远程桌面、
 * 或系统限制)整体降级为普通窗口行为,不影响其它功能。</p>
 */
public final class TraySupport {

    /** 托盘菜单动作。 */
    public interface Actions {

        void showWindow();

        void backupChangedWorlds();

        void exitApplication();
    }

    private static TrayIcon trayIcon;

    private TraySupport() {
    }

    /** 当前环境是否支持托盘。 */
    public static boolean isSupported() {
        try {
            return !java.awt.GraphicsEnvironment.isHeadless() && SystemTray.isSupported();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * 安装托盘图标。
     *
     * @return 是否安装成功
     */
    public static synchronized boolean install(Actions actions) {
        if (!isSupported() || trayIcon != null) {
            return false;
        }
        try {
            PopupMenu menu = new PopupMenu();
            MenuItem show = new MenuItem("显示主窗口");
            show.addActionListener(event -> actions.showWindow());
            MenuItem backup = new MenuItem("立即备份有变化的世界");
            backup.addActionListener(event -> actions.backupChangedWorlds());
            MenuItem exit = new MenuItem("退出");
            exit.addActionListener(event -> actions.exitApplication());
            menu.add(show);
            menu.add(backup);
            menu.addSeparator();
            menu.add(exit);

            TrayIcon icon = new TrayIcon(AppIcon.render(16), "MC Backup", menu);
            icon.setImageAutoSize(true);
            icon.addActionListener(showWindowListener(actions));
            SystemTray.getSystemTray().add(icon);
            trayIcon = icon;
            Log.info("系统托盘已启用");
            return true;
        } catch (Exception e) {
            Log.warn("启用系统托盘失败,将继续使用普通窗口模式: %s", e.getMessage());
            trayIcon = null;
            return false;
        }
    }

    private static ActionListener showWindowListener(Actions actions) {
        return event -> actions.showWindow();
    }

    /** 移除托盘图标(退出时调用)。 */
    public static synchronized void remove() {
        if (trayIcon != null) {
            try {
                SystemTray.getSystemTray().remove(trayIcon);
            } catch (RuntimeException e) {
                Log.debug("移除托盘图标失败: " + e.getMessage());
            }
            trayIcon = null;
        }
    }

    public static synchronized boolean isInstalled() {
        return trayIcon != null;
    }

    /** 弹一个托盘气泡提示(托盘不可用时静默忽略)。 */
    public static synchronized void notifyMessage(String title, String text) {
        if (trayIcon == null) {
            return;
        }
        try {
            trayIcon.displayMessage(title, text, TrayIcon.MessageType.INFO);
        } catch (RuntimeException e) {
            Log.debug("托盘提示失败: " + e.getMessage());
        }
    }
}
