package com.mcbackup;

import com.mcbackup.model.AppSettings;
import com.mcbackup.service.LauncherDetector;
import com.mcbackup.storage.SettingsRepository;
import com.mcbackup.ui.MainWindow;
import com.mcbackup.ui.ScreenshotRunner;
import com.mcbackup.ui.theme.ThemeManager;
import com.mcbackup.util.Log;
import com.mcbackup.util.PathUtils;

import javax.swing.SwingUtilities;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 程序入口。
 *
 * <p>启动顺序:解析命令行 → 初始化日志 → 读取配置 → 应用主题 → 在 EDT 上显示主窗口。
 * 任何一步失败都不会让程序「静默打不开」,而是写日志并尽量给出界面提示。</p>
 */
public final class App {

    public static final String NAME = "MC Backup";
    public static final String VERSION = "0.3.0";

    private App() {
    }

    /** 命令行参数。 */
    public record CliOptions(Path screenshotDir, List<Path> roots, Path backupDir, Path exportIconsDir,
                             Path selfTestDir, int selfTestCycles, boolean consoleLog, boolean noAutoScan) {

        public static CliOptions parse(String[] args) {
            Path screenshot = null;
            List<Path> roots = new ArrayList<>();
            Path backupDir = null;
            Path exportIcons = null;
            Path selfTest = null;
            int selfTestCycles = 1;
            boolean console = false;
            boolean noAutoScan = false;
            if (args != null) {
                for (int i = 0; i < args.length; i++) {
                    String arg = args[i];
                    switch (arg) {
                        case "--screenshot" -> {
                            if (i + 1 < args.length) {
                                screenshot = PathUtils.toPath(args[++i]);
                            }
                        }
                        case "--root" -> {
                            if (i + 1 < args.length) {
                                Path path = PathUtils.toPath(args[++i]);
                                if (path != null) {
                                    roots.add(path);
                                }
                            }
                        }
                        case "--backup-dir" -> {
                            if (i + 1 < args.length) {
                                backupDir = PathUtils.toPath(args[++i]);
                            }
                        }
                        case "--export-icons" -> {
                            if (i + 1 < args.length) {
                                exportIcons = PathUtils.toPath(args[++i]);
                            }
                        }
                        case "--self-test" -> {
                            if (i + 1 < args.length) {
                                selfTest = PathUtils.toPath(args[++i]);
                            }
                        }
                        case "--self-test-cycles" -> {
                            if (i + 1 < args.length) {
                                try {
                                    selfTestCycles = Math.max(1, Integer.parseInt(args[++i]));
                                } catch (NumberFormatException e) {
                                    Log.warn("--self-test-cycles 需要整数,已按 1 处理");
                                }
                            }
                        }
                        case "--console-log" -> console = true;
                        case "--no-auto-scan" -> noAutoScan = true;
                        default -> Log.warn("未知命令行参数: " + arg);
                    }
                }
            }
            return new CliOptions(screenshot, roots, backupDir, exportIcons, selfTest, selfTestCycles,
                    console, noAutoScan);
        }
    }

    public static void main(String[] args) {
        CliOptions options = CliOptions.parse(args);
        SettingsRepository repository = SettingsRepository.defaultRepository();
        Log.init(repository.logsDir());
        // 截图自检属于开发期工具,始终把日志回显到控制台
        Log.setConsoleEcho(options.consoleLog() || options.screenshotDir() != null);
        Log.info("启动 %s v%s (Java %s, %s %s)", NAME, VERSION,
                System.getProperty("java.version"),
                System.getProperty("os.name"),
                System.getProperty("os.arch"));
        Log.info("配置目录: " + PathUtils.toDisplayPath(repository.baseDir()));

        // 必须在任何 AWT 类之前:部分机器启用了辅助功能,而裁剪后的运行时可能没有对应模块
        com.mcbackup.util.AccessibilityGuard.applyIfNeeded();

        AppSettings settings = repository.load();
        ThemeManager.setOption(settings.getTheme());
        ThemeManager.applyDefaults();

        // 无界面的命令行模式(导出图标 / 自检)要放在单实例检查之前:
        // 它们不碰存档、不需要窗口,也不该被"已有实例在运行"挡住。
        // 打包脚本用它导出 exe 图标(与窗口/托盘图标同源),导出后直接退出
        if (options.exportIconsDir() != null) {
            try {
                com.mcbackup.ui.components.AppIcon.writePng(
                        options.exportIconsDir().resolve("icon-256.png"), 256);
                com.mcbackup.ui.components.AppIcon.writeIco(
                        options.exportIconsDir().resolve("icon.ico"), 16, 32, 48, 256);
                Log.info("图标已导出到 " + PathUtils.toDisplayPath(options.exportIconsDir()));
                System.out.println("icons exported to " + options.exportIconsDir());
            } catch (Exception e) {
                Log.error("导出图标失败", e);
                System.exit(3);
            }
            return;
        }

        // 自检模式(发布冒烟用):不打开界面,跑一遍 备份 → 校验 → 恢复 → 比对
        if (options.selfTestDir() != null) {
            Path backupDir = options.backupDir() != null
                    ? options.backupDir()
                    : repository.baseDir().resolve("self-test-backups");
            Log.setConsoleEcho(true);
            com.mcbackup.service.SelfTest.Result result = com.mcbackup.service.SelfTest.run(
                    options.selfTestDir(), backupDir, options.selfTestCycles());
            System.out.println(result.text());
            Log.info("自检结果: %s", result.passed() ? "PASS" : "FAIL");
            System.exit(result.passed() ? 0 : 1);
        }

        // 单实例:自动备份是常驻的,两个实例同时跑会互相抢同一个世界和备份目录
        if (!com.mcbackup.util.SingleInstanceGuard.acquire(repository.baseDir().resolve(".instance.lock"))) {
            Log.warn("检测到已有 MC Backup 在运行,本次启动退出");
            javax.swing.SwingUtilities.invokeLater(() -> {
                javax.swing.JOptionPane.showMessageDialog(null,
                        "MC Backup 已经在运行。\n\n"
                                + "同一个存档只能被一个实例备份,请在任务栏或系统托盘里找到已经打开的窗口。\n"
                                + "(如果确实找不到窗口,可以在任务管理器里结束 MCBackup.exe 后重试)",
                        NAME, javax.swing.JOptionPane.INFORMATION_MESSAGE);
                System.exit(0);
            });
            return;
        }

        boolean screenshotMode = options.screenshotDir() != null;
        SwingUtilities.invokeLater(() -> {
            try {
                MainWindow window = new MainWindow(repository, settings, options.roots(),
                        !screenshotMode && !options.noAutoScan(), new LauncherDetector(), options.backupDir());
                window.setVisible(true);
                window.start();
                if (screenshotMode) {
                    window.scanNowBlocking();
                    // 真实做一次最小世界的备份,让「备份」页展示的是真实数据而不是假数据
                    window.backupSmallestWorldForPreview();
                    // 再在内存里临时打开自动备份,这样截图里能看到倒计时(不写配置)
                    window.previewAutoBackupState();
                    ScreenshotRunner.run(window, options.screenshotDir(), ScreenshotRunner.defaultShots());
                }
            } catch (Throwable e) {
                // 连 Error(例如缺少模块导致的 AWTError)也要留下痕迹,绝不能静默退出
                Log.error("启动界面失败", e instanceof Exception exception ? exception : new RuntimeException(e));
                try {
                    javax.swing.JOptionPane.showMessageDialog(null,
                            "程序启动失败:" + e + "\n\n详细信息见日志:\n"
                                    + PathUtils.toDisplayPath(repository.logsDir()),
                            NAME, javax.swing.JOptionPane.ERROR_MESSAGE);
                } catch (Throwable ignored) {
                    // 连对话框都弹不出来时,日志是最后的信息来源
                }
                System.exit(1);
            }
        });
    }
}
