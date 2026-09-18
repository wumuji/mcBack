package com.mcbackup;

import com.mcbackup.model.AppSettings;
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
    public static final String VERSION = "1.0.0-phase1";

    private App() {
    }

    /** 命令行参数。 */
    public record CliOptions(Path screenshotDir, List<Path> roots, boolean consoleLog, boolean noAutoScan) {

        public static CliOptions parse(String[] args) {
            Path screenshot = null;
            List<Path> roots = new ArrayList<>();
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
                        case "--console-log" -> console = true;
                        case "--no-auto-scan" -> noAutoScan = true;
                        default -> Log.warn("未知命令行参数: " + arg);
                    }
                }
            }
            return new CliOptions(screenshot, roots, console, noAutoScan);
        }
    }

    public static void main(String[] args) {
        CliOptions options = CliOptions.parse(args);
        SettingsRepository repository = SettingsRepository.defaultRepository();
        Log.init(repository.logsDir());
        Log.setConsoleEcho(options.consoleLog());
        Log.info("启动 %s v%s (Java %s, %s %s)", NAME, VERSION,
                System.getProperty("java.version"),
                System.getProperty("os.name"),
                System.getProperty("os.arch"));
        Log.info("配置目录: " + PathUtils.toDisplayPath(repository.baseDir()));

        AppSettings settings = repository.load();
        ThemeManager.setOption(settings.getTheme());
        ThemeManager.applyDefaults();

        boolean screenshotMode = options.screenshotDir() != null;
        SwingUtilities.invokeLater(() -> {
            try {
                MainWindow window = new MainWindow(repository, settings, options.roots(),
                        !screenshotMode && !options.noAutoScan());
                window.setVisible(true);
                window.start();
                if (screenshotMode) {
                    window.scanNowBlocking();
                    ScreenshotRunner.run(window, options.screenshotDir(), ScreenshotRunner.defaultShots());
                }
            } catch (RuntimeException e) {
                Log.error("启动界面失败", e);
                System.exit(1);
            }
        });
    }
}
