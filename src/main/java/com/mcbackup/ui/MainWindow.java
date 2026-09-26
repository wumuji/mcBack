package com.mcbackup.ui;

import com.mcbackup.App;
import com.mcbackup.model.AppSettings;
import com.mcbackup.model.BackupOptions;
import com.mcbackup.model.BackupRecord;
import com.mcbackup.model.BackupResult;
import com.mcbackup.model.ExportResult;
import com.mcbackup.model.MinecraftWorld;
import com.mcbackup.model.ScanProgress;
import com.mcbackup.model.ScanResult;
import com.mcbackup.model.Theme;
import com.mcbackup.service.BackupException;
import com.mcbackup.service.BackupScheduler;
import com.mcbackup.service.BackupService;
import com.mcbackup.service.ExportService;
import com.mcbackup.service.IntegrityService;
import com.mcbackup.service.LauncherDetector;
import com.mcbackup.service.RestoreService;
import com.mcbackup.service.WorldRootProvider;
import com.mcbackup.service.WorldScanner;
import com.mcbackup.storage.BackupRepository;
import com.mcbackup.storage.SettingsRepository;
import com.mcbackup.ui.components.ScrollPaneStyler;
import com.mcbackup.ui.components.AppIcon;
import com.mcbackup.ui.theme.ThemeAware;
import com.mcbackup.ui.theme.ThemeManager;
import com.mcbackup.util.FileUtils;
import com.mcbackup.util.Log;
import com.mcbackup.util.PathUtils;
import com.mcbackup.util.ProgressListener;

import javax.swing.BorderFactory;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 主窗口:左侧导航 + 顶栏 + 四个页面,并负责把后台任务与界面串起来。
 *
 * <p>线程约定:</p>
 * <ul>
 *   <li>所有 Swing 操作都在 EDT;</li>
 *   <li>扫描在 {@code mcbackup-scan} 单线程池;</li>
 *   <li>备份/导出/删除在 {@code mcbackup-worker} 单线程池(串行执行,避免磁盘抖动);</li>
 *   <li>自动备份在 {@code mcbackup-autobackup} 单线程调度器,空闲时线程在等待,不轮询。</li>
 * </ul>
 */
public class MainWindow extends JFrame implements ThemeAware {

    private static final Map<String, String> PAGE_TITLES = new LinkedHashMap<>();

    static {
        PAGE_TITLES.put("world", "世界");
        PAGE_TITLES.put("backup", "备份");
        PAGE_TITLES.put("export", "导出");
        PAGE_TITLES.put("settings", "设置");
    }

    /** 后台任务:返回一句给状态栏显示的完成信息。 */
    private interface Task {
        String run(ProgressListener listener) throws Exception;
    }

    private final SettingsRepository repository;
    private final AppSettings settings;
    private final List<Path> extraRoots;
    private final boolean autoScan;

    private final WorldScanner scanner;
    private final ExecutorService scanExecutor = newSingleThreadExecutor("mcbackup-scan");
    private final ExecutorService workerExecutor = newSingleThreadExecutor("mcbackup-worker");

    private BackupRepository backupRepository;
    private BackupService backupService;
    private final ExportService exportService = new ExportService();
    private final IntegrityService integrityService = new IntegrityService();
    private BackupScheduler scheduler;

    private final CardLayout pageLayout = new CardLayout();
    private final JPanel pageHost = new JPanel(pageLayout);
    private final Sidebar sidebar;
    private final TopBar topBar;
    private final WorldView worldView;
    private final BackupView backupView;
    private final ExportView exportView;
    private final SettingsView settingsView;

    private final AtomicBoolean scanRunning = new AtomicBoolean(false);
    private final AtomicBoolean rescanQueued = new AtomicBoolean(false);
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private final Runnable themeListener = this::applyTheme;

    private ScanResult lastResult = ScanResult.empty();
    private ScanResult previewResult;
    private String currentPage = "world";

    public MainWindow(SettingsRepository repository, AppSettings settings, List<Path> extraRoots, boolean autoScan) {
        this(repository, settings, extraRoots, autoScan, new LauncherDetector(), null);
    }

    /** 便于测试注入存档目录来源。 */
    public MainWindow(SettingsRepository repository, AppSettings settings, List<Path> extraRoots,
                      boolean autoScan, WorldRootProvider provider) {
        this(repository, settings, extraRoots, autoScan, provider, null);
    }

    /**
     * 完整构造器。
     *
     * @param provider             存档目录来源(测试可注入)
     * @param backupDirOverride    临时覆盖备份目录(命令行 --backup-dir,不写入配置)
     */
    public MainWindow(SettingsRepository repository, AppSettings settings, List<Path> extraRoots,
                      boolean autoScan, WorldRootProvider provider, Path backupDirOverride) {
        this.repository = repository;
        this.settings = settings;
        this.extraRoots = extraRoots == null ? List.of() : new ArrayList<>(extraRoots);
        this.autoScan = autoScan;
        this.scanner = new WorldScanner(provider == null ? new LauncherDetector() : provider);
        if (backupDirOverride != null) {
            settings.setBackupDir(backupDirOverride.toString());
        }

        setTitle(App.NAME + " — Minecraft Java 存档备份");
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(880, 560));
        setContentPane(new BackgroundPanel());
        getContentPane().setLayout(new BorderLayout());
        applySavedBounds();

        sidebar = new Sidebar(this::navigate);
        topBar = new TopBar(() -> rescan(false));
        worldView = new WorldView(this::addDirectory, () -> rescan(false), this::backupWorld);
        worldView.setStatusSink(topBar::setStatus);
        worldView.setSelectionSink(this::onWorldSelected);
        backupView = new BackupView(new BackupView.Callbacks() {
            @Override
            public void onBackupNow() {
                backupWorld(worldView.selectedWorld());
            }

            @Override
            public void onOpenBackupDir() {
                openBackupDir();
            }

            @Override
            public void onExportRecord(BackupRecord record, Path targetZip) {
                exportRecord(record, targetZip);
            }

            @Override
            public void onDeleteRecord(BackupRecord record) {
                deleteRecord(record);
            }

            @Override
            public void onVerifyRecord(BackupRecord record) {
                verifyRecord(record);
            }

            @Override
            public void onRestoreRecord(BackupRecord record) {
                restoreRecord(record);
            }
        });
        exportView = new ExportView(new ExportView.Callbacks() {
            @Override
            public void onExportWorld(MinecraftWorld world, Path targetZip) {
                exportWorld(world, targetZip);
            }

            @Override
            public void onOpenDirectory(Path file) {
                FileUtils.openInFileBrowser(file);
            }
        });
        settingsView = new SettingsView(settings, new SettingsView.Callbacks() {
            @Override
            public void onThemeSelected(Theme theme) {
                selectTheme(theme);
            }

            @Override
            public void onAddDirectory() {
                addDirectory();
            }

            @Override
            public void onRemoveDirectory(String path) {
                removeDirectory(path);
            }

            @Override
            public void onRescan() {
                rescan(false);
            }

            @Override
            public void onBackupDirChanged(String path) {
                saveSettings("备份位置已更新:" + path);
                rebuildBackupServices();
                refreshBackupViews();
            }

            @Override
            public void onAutoBackupChanged(boolean enabled) {
                saveSettings(enabled ? "自动备份已开启" : "自动备份已关闭");
                applyAutoBackupSettings();
            }

            @Override
            public void onBackupIntervalChanged(int minutes) {
                saveSettings("自动备份间隔:" + minutes + " 分钟");
                applyAutoBackupSettings();
            }

            @Override
            public void onRetainCountChanged(int count) {
                saveSettings(count <= 0 ? "保留策略:不限制" : "保留最近 " + count + " 份备份");
            }

            @Override
            public void onCompressionChanged(boolean fast) {
                saveSettings(fast
                        ? "压缩方式:快速(区域文件直接存储,备份更快、体积略大)"
                        : "压缩方式:体积优先(全部重新压缩,速度较慢)");
            }

            @Override
            public void onFullVerifyChanged(boolean enabled) {
                saveSettings(enabled ? "已开启完整校验:每次备份额外计算 SHA-256" : "已关闭完整校验");
            }

            @Override
            public void onMinimizeToTrayChanged(boolean enabled) {
                saveSettings(enabled ? "已开启最小化到托盘" : "已关闭最小化到托盘");
                installTray();
            }
        }, repository.logsDir());

        pageHost.setOpaque(false);
        pageHost.setBorder(BorderFactory.createEmptyBorder(0, 24, 24, 24));
        pageHost.add(worldView, "world");
        pageHost.add(backupView, "backup");
        pageHost.add(exportView, "export");
        pageHost.add(settingsView, "settings");

        JPanel center = new JPanel(new BorderLayout());
        center.setOpaque(false);
        center.add(topBar, BorderLayout.NORTH);
        center.add(pageHost, BorderLayout.CENTER);
        getContentPane().add(sidebar, BorderLayout.WEST);
        getContentPane().add(center, BorderLayout.CENTER);

        rebuildBackupServices();
        navigate("world");
        applyAutoBackupSettings();
        setIconImage(AppIcon.render(64));
        applyTheme();
        installTray();
        ThemeManager.addListener(themeListener);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                closeApplication();
            }
        });
        Log.info("主窗口已创建,备份目录: " + PathUtils.toDisplayPath(backupRepository.backupDir()));
    }

    private static ExecutorService newSingleThreadExecutor(String name) {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        });
    }

    /** 启动后的动作:首次扫描、检查上次残留的临时文件。 */
    public void start() {
        checkLeftoverTempFiles();
        if (autoScan) {
            rescan(false);
        }
    }

    // ------------------------------------------------------------------
    // 服务装配
    // ------------------------------------------------------------------

    /** 备份目录变化时重建相关服务与调度器。 */
    private void rebuildBackupServices() {
        if (scheduler != null) {
            scheduler.shutdown();
        }
        backupRepository = new BackupRepository(settings.backupDirPath());
        backupService = new BackupService(backupRepository);
        scheduler = new BackupScheduler(
                () -> lastResult.worlds(),
                backupService,
                this::backupOptions,
                backupRepository,
                new AutoBackupListener());
    }

    private BackupOptions backupOptions() {
        return BackupOptions.of(settings.backupDirPath(), settings.getRetainCount(),
                settings.isFastBackup(), settings.isFullVerify());
    }

    private void applyAutoBackupSettings() {
        boolean enabled = settings.isAutoBackupEnabled();
        if (enabled) {
            scheduler.start(settings.getAutoBackupIntervalMinutes());
        } else {
            scheduler.stop();
        }
        backupView.setAutoBackupStatus(enabled, settings.getAutoBackupIntervalMinutes());
    }

    /** 自动备份事件 → 状态栏文字(界面不刷日志)。 */
    private final class AutoBackupListener implements BackupScheduler.Listener {

        @Override
        public void onSkipped(MinecraftWorld world, String reason) {
            Log.debug("自动备份跳过 %s:%s", world.displayName(), reason);
        }

        @Override
        public void onBackupStarted(MinecraftWorld world) {
            SwingUtilities.invokeLater(() -> topBar.setStatus("自动备份:正在备份 " + world.displayName() + "…"));
        }

        @Override
        public void onBackupFinished(MinecraftWorld world, BackupResult result) {
            SwingUtilities.invokeLater(() -> topBar.setStatus("自动备份完成:" + world.displayName()
                    + "(" + result.record().sizeText() + ")"));
        }

        @Override
        public void onBackupFailed(MinecraftWorld world, Exception error) {
            SwingUtilities.invokeLater(() -> topBar.setStatus("自动备份失败:"
                    + world.displayName() + " — " + error.getMessage() + "(详见日志)"));
        }

        @Override
        public void onTickFinished(int backedUp, int skipped, long elapsedMillis) {
            SwingUtilities.invokeLater(() -> {
                if (backedUp + skipped > 0) {
                    topBar.setStatus("自动备份检查:备份 " + backedUp + " 个,跳过 " + skipped
                            + " 个(没有变化),耗时 " + elapsedMillis + " ms");
                }
                refreshBackupViews();
            });
        }
    }

    // ------------------------------------------------------------------
    // 页面切换与主题
    // ------------------------------------------------------------------

    public void navigate(String key) {
        String page = PAGE_TITLES.containsKey(key) ? key : "world";
        currentPage = page;
        pageLayout.show(pageHost, page);
        topBar.setTitle(PAGE_TITLES.get(page));
        sidebar.setSelected(page);
        if ("backup".equals(page) || "export".equals(page)) {
            refreshBackupViews();
        }
    }

    public String currentPage() {
        return currentPage;
    }

    private void selectTheme(Theme theme) {
        ThemeManager.setOption(theme);
        saveSettings("主题已切换为 " + theme.displayName());
    }

    private void saveSettings(String status) {
        settings.setTheme(ThemeManager.option());
        repository.save(settings);
        settingsView.refreshThemeButtons();
        settingsView.refreshBackupControls();
        if (status != null) {
            topBar.setStatus(status);
        }
    }

    private void applyTheme() {
        getContentPane().setBackground(ThemeManager.palette().background());
        ScrollPaneStyler.applyRecursively(this);
        walkThemeAware(getContentPane());
        revalidate();
        repaint();
    }

    private void walkThemeAware(java.awt.Container container) {
        com.mcbackup.ui.theme.Palette palette = ThemeManager.palette();
        if (container instanceof ThemeAware aware) {
            aware.onThemeChanged();
        }
        // Swing 原生控件不会自动跟随 UIManager,需要显式设置颜色
        if (container instanceof javax.swing.JComboBox<?> combo) {
            combo.setBackground(palette.surface());
            combo.setForeground(palette.text());
        } else if (container instanceof javax.swing.JTextField field) {
            field.setBackground(palette.dark() ? palette.background() : java.awt.Color.WHITE);
            field.setForeground(palette.text());
            field.setCaretColor(palette.text());
        } else if (container instanceof javax.swing.JTextArea area) {
            area.setBackground(palette.surface());
            area.setForeground(palette.text());
        }
        for (java.awt.Component child : container.getComponents()) {
            if (child instanceof java.awt.Container childContainer) {
                walkThemeAware(childContainer);
            }
        }
    }

    private void onWorldSelected(MinecraftWorld world) {
        backupView.setSelectedWorld(world);
        exportView.selectWorld(world);
    }

    // ------------------------------------------------------------------
    // 扫描
    // ------------------------------------------------------------------

    public void rescan(boolean forceSizeRecalc) {
        if (scanRunning.get()) {
            rescanQueued.set(true);
            topBar.setStatus("正在扫描,已排队一次重新扫描…");
            return;
        }
        scanRunning.set(true);
        topBar.setRescanEnabled(false);
        topBar.setStatus("正在扫描存档目录…");
        List<Path> manual = manualRoots();
        scanExecutor.submit(() -> {
            try {
                ScanResult result = scanner.scan(manual, this::reportScanProgress, forceSizeRecalc);
                SwingUtilities.invokeLater(() -> applyScanResult(result));
            } catch (RuntimeException e) {
                Log.errorQuietly("扫描失败", e);
                SwingUtilities.invokeLater(() -> {
                    topBar.setStatus("扫描失败:" + e.getMessage() + "(详情见日志)");
                    topBar.setRescanEnabled(true);
                });
            } finally {
                scanRunning.set(false);
                if (rescanQueued.getAndSet(false)) {
                    SwingUtilities.invokeLater(() -> rescan(false));
                }
            }
        });
    }

    private void reportScanProgress(ScanProgress progress) {
        String text = "正在扫描(" + progress.scannedRoots() + "/" + progress.totalRoots() + ") · " + progress.label();
        SwingUtilities.invokeLater(() -> {
            topBar.setStatus(text);
            worldView.setScanning(progress.label());
        });
    }

    private void applyScanResult(ScanResult result) {
        lastResult = result;
        worldView.setScanResult(result);
        settingsView.setScanResult(result);
        exportView.setScanResult(result);
        StringBuilder status = new StringBuilder("已扫描 ")
                .append(result.rootCount()).append(" 个候选目录 · 发现 ")
                .append(result.worlds().size()).append(" 个世界 · ")
                .append(result.elapsedMillis()).append(" ms");
        if (!result.issues().isEmpty()) {
            status.append(" · ").append(result.issues().size()).append(" 条提示");
        }
        topBar.setStatus(status.toString());
        topBar.setRescanEnabled(true);
    }

    /** 同步扫描一次(截图自检用)。 */
    public ScanResult scanNowBlocking() {
        try {
            ScanResult result = scanner.scan(manualRoots(), null, false);
            if (SwingUtilities.isEventDispatchThread()) {
                applyScanResult(result);
            } else {
                SwingUtilities.invokeAndWait(() -> applyScanResult(result));
            }
            return result;
        } catch (Exception e) {
            Log.errorQuietly("同步扫描失败", e);
            return lastResult;
        }
    }

    private List<Path> manualRoots() {
        List<Path> paths = new ArrayList<>();
        for (String raw : settings.getManualWorldDirs()) {
            Path path = PathUtils.toPath(raw);
            if (path != null) {
                paths.add(path);
            }
        }
        paths.addAll(extraRoots);
        return paths;
    }

    // ------------------------------------------------------------------
    // 备份 / 导出 / 删除
    // ------------------------------------------------------------------

    private void backupWorld(MinecraftWorld world) {
        if (world == null) {
            topBar.setStatus("请先在「世界」页面选择一个世界");
            return;
        }
        submitTask("正在备份 " + world.displayName() + "…", listener -> {
            BackupResult result = backupService.backup(world, backupOptions(), listener);
            String suffix = result.complete() ? "" : " · 有文件未复制,详见日志";
            return "备份完成:" + result.record().zipFileName()
                    + "(" + result.record().sizeText() + ",耗时 " + result.record().durationText() + ")" + suffix;
        });
    }

    private void exportWorld(MinecraftWorld world, Path target) {
        submitTask("正在导出 " + world.displayName() + "…", listener -> {
            ExportResult result = exportService.export(world, target, listener);
            SwingUtilities.invokeLater(() -> exportView.setLastExport(result.zipPath()));
            return "导出完成:" + PathUtils.toDisplayPath(result.zipPath())
                    + "(" + FileUtils.humanSize(result.zipBytes()) + ")";
        });
    }

    private void exportRecord(BackupRecord record, Path target) {
        submitTask("正在导出备份到 " + PathUtils.toDisplayPath(target) + "…", listener ->
                "已导出备份:" + PathUtils.toDisplayPath(
                        exportService.copyZip(record.zipPath(), target, listener)));
    }

    private void deleteRecord(BackupRecord record) {
        submitTask("正在删除备份 " + record.zipFileName() + "…", listener -> {
            BackupRepository.DeleteOutcome outcome = backupRepository.delete(record);
            if (outcome.status() == BackupRepository.DeleteStatus.FAILED) {
                throw new BackupException(outcome.message());
            }
            return outcome.message();
        });
    }

    /** 校验一份备份:结构 + (有记录时)SHA-256。 */
    private void verifyRecord(BackupRecord record) {
        submitTask("正在校验 " + record.zipFileName() + "…", listener -> {
            IntegrityService.IntegrityReport report = integrityService.verify(record, listener);
            SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this,
                    report.summary() + "\n\n文件:" + record.zipFileName()
                            + "\n条目:" + report.fileEntries() + " 个文件"
                            + "\n耗时:" + report.durationMillis() + " ms"
                            + (report.hashChecked()
                                    ? "\n清单哈希:" + shortHash(report.storedHash())
                                            + "\n实际哈希:" + shortHash(report.actualHash())
                                    : ""),
                    "备份校验", report.valid() ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.WARNING_MESSAGE));
            return report.summary();
        });
    }

    private static String shortHash(String hash) {
        if (hash == null || hash.isBlank()) {
            return "(无)";
        }
        return hash.length() <= 16 ? hash : hash.substring(0, 16) + "…";
    }

    /** 恢复一份备份。 */
    private void restoreRecord(BackupRecord record) {
        Path target = resolveRestoreTarget(record);
        if (target == null) {
            return;
        }
        submitTask("正在恢复 " + record.zipFileName() + "…", listener -> {
            RestoreService.RestoreResult result = new RestoreService()
                    .restore(record.zipPath(), new RestoreService.RestoreOptions(target, record.sha256()), listener);
            SwingUtilities.invokeLater(() -> {
                JOptionPane.showMessageDialog(this,
                        "恢复完成。\n\n世界目录:" + PathUtils.toDisplayPath(result.worldDir())
                                + (result.originalBackupDir() == null ? ""
                                        : "\n原世界已保留:" + PathUtils.toDisplayPath(result.originalBackupDir()))
                                + "\n文件数:" + result.fileCount()
                                + "\n耗时:" + result.durationMillis() + " ms"
                                + "\n\n确认世界正常后,可以自行删除保留的那份原世界。",
                        "恢复完成", JOptionPane.INFORMATION_MESSAGE);
                rescan(false);
            });
            return "恢复完成:" + PathUtils.toDisplayPath(result.worldDir());
        });
    }

    /** 决定恢复到哪个世界目录:优先用备份清单里记录的原路径。 */
    private Path resolveRestoreTarget(BackupRecord record) {
        String recorded = record.worldPath();
        if (recorded != null && !recorded.isBlank()) {
            return Path.of(recorded);
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("选择恢复成哪个世界目录(建议放在 saves 目录下)");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setAcceptAllFileFilterUsed(false);
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return null;
        }
        File selected = chooser.getSelectedFile();
        return selected == null ? null : selected.toPath();
    }

    // ------------------------------------------------------------------
    // 系统托盘
    // ------------------------------------------------------------------

    private void installTray() {
        if (!settings.isMinimizeToTray()) {
            TraySupport.remove();
            return;
        }
        if (!TraySupport.isSupported()) {
            topBar.setStatus("当前环境不支持系统托盘,已忽略「最小化到托盘」设置");
            return;
        }
        boolean installed = TraySupport.install(new TraySupport.Actions() {
            @Override
            public void showWindow() {
                SwingUtilities.invokeLater(MainWindow.this::restoreFromTray);
            }

            @Override
            public void backupChangedWorlds() {
                // 与自动备份走同一套逻辑:只备份有变化的世界
                SwingUtilities.invokeLater(() -> topBar.setStatus("托盘触发:正在检查需要备份的世界…"));
                workerExecutor.submit(() -> scheduler.tick());
            }

            @Override
            public void exitApplication() {
                SwingUtilities.invokeLater(MainWindow.this::exitApplication);
            }
        });
        if (installed && !isVisible()) {
            restoreFromTray();
        }
    }

    private void restoreFromTray() {
        setVisible(true);
        setState(JFrame.NORMAL);
        toFront();
        repaint();
    }

    private void hideToTray() {
        setVisible(false);
        TraySupport.notifyMessage(App.NAME,
                "程序仍在后台运行,自动备份继续生效。双击托盘图标可以重新打开窗口。");
        Log.info("窗口已最小化到托盘");
    }

    private void openBackupDir() {
        try {
            backupRepository.ensureExists();
        } catch (Exception e) {
            Log.warn("创建备份目录失败: %s", e.getMessage());
        }
        if (!FileUtils.openInFileBrowser(backupRepository.backupDir())) {
            topBar.setStatus("无法打开备份目录:" + PathUtils.toDisplayPath(backupRepository.backupDir()));
        }
    }

    /**
     * 提交一个后台任务。
     *
     * <p>串行执行:同一时刻只允许一个备份/导出/删除任务,避免多个大文件操作互相拖慢磁盘。</p>
     */
    private void submitTask(String runningStatus, Task task) {
        if (!busy.compareAndSet(false, true)) {
            topBar.setStatus("已有任务正在执行,请稍候…");
            return;
        }
        setBusyUi(true);
        topBar.setStatus(runningStatus);
        workerExecutor.submit(() -> {
            try {
                String message = task.run(new ThrottledProgress());
                SwingUtilities.invokeLater(() -> topBar.setStatus(message));
            } catch (BackupException e) {
                String text = e.getMessage();
                SwingUtilities.invokeLater(() -> {
                    topBar.setStatus(text);
                    JOptionPane.showMessageDialog(this, text, "MC Backup", JOptionPane.ERROR_MESSAGE);
                });
            } catch (Exception e) {
                Log.errorQuietly("后台任务失败", e);
                SwingUtilities.invokeLater(() -> {
                    topBar.setStatus("操作失败:" + e.getMessage());
                    JOptionPane.showMessageDialog(this, "操作失败:" + e.getMessage(),
                            "MC Backup", JOptionPane.ERROR_MESSAGE);
                });
            } finally {
                busy.set(false);
                SwingUtilities.invokeLater(() -> {
                    setBusyUi(false);
                    refreshBackupViews();
                });
            }
        });
    }

    private void setBusyUi(boolean value) {
        topBar.setRescanEnabled(!value);
        backupView.setBusy(value);
        exportView.setBusy(value);
    }

    /** 重新读取备份列表(IO 放在后台线程,结果回 EDT)。 */
    private void refreshBackupViews() {
        BackupRepository current = backupRepository;
        workerExecutor.submit(() -> {
            List<BackupRecord> records = current.listAll();
            SwingUtilities.invokeLater(() -> backupView.setBackups(records));
        });
    }

    /** 进度回调:限流到 ~7 次/秒,避免把 EDT 刷爆。 */
    private final class ThrottledProgress implements ProgressListener {

        private long lastPush;
        private String lastStage = "";

        @Override
        public void onProgress(String stage, int done, int total, String detail) {
            long now = System.currentTimeMillis();
            boolean stageChanged = !stage.equals(lastStage);
            if (!stageChanged && now - lastPush < 150) {
                return;
            }
            lastPush = now;
            lastStage = stage;
            String name = detail == null ? "" : detail;
            int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
            if (slash >= 0) {
                name = name.substring(slash + 1);
            }
            String text = stage + (total > 0 ? " " + done + "/" + total : "") + (name.isBlank() ? "" : " · " + name);
            SwingUtilities.invokeLater(() -> topBar.setStatus(text));
        }
    }

    // ------------------------------------------------------------------
    // 目录管理
    // ------------------------------------------------------------------

    private void addDirectory() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("选择 Minecraft 存档目录(可以是 saves、游戏目录或世界目录)");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setAcceptAllFileFilterUsed(false);
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File selected = chooser.getSelectedFile();
        if (selected == null) {
            return;
        }
        String path = selected.getAbsolutePath();
        settings.addManualWorldDir(path);
        Log.info("已添加存档目录: " + path);
        saveSettings("已添加目录:" + path);
        rescan(false);
    }

    private void removeDirectory(String path) {
        settings.removeManualWorldDir(path);
        Log.info("已移除存档目录: " + path);
        saveSettings("已移除目录:" + path);
        rescan(false);
    }

    // ------------------------------------------------------------------
    // 崩溃残留检查
    // ------------------------------------------------------------------

    private void checkLeftoverTempFiles() {
        BackupRepository current = backupRepository;
        workerExecutor.submit(() -> {
            List<Path> leftovers = current.findLeftoverTempFiles();
            if (!leftovers.isEmpty()) {
                SwingUtilities.invokeLater(() -> showRecoveryDialog(leftovers));
            }
        });
    }

    private void showRecoveryDialog(List<Path> leftovers) {
        StringBuilder text = new StringBuilder("检测到上一次操作没有正常完成,留下了 ")
                .append(leftovers.size()).append(" 个临时文件/目录:\n");
        leftovers.stream().limit(5).forEach(path ->
                text.append("  · ").append(PathUtils.toDisplayPath(path)).append('\n'));
        if (leftovers.size() > 5) {
            text.append("  …\n");
        }
        text.append("\n这些文件不会被自动删除,你可以先看看再决定。");
        int choice = JOptionPane.showOptionDialog(this, text.toString(), "MC Backup",
                JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE, null,
                new Object[]{"删除临时文件", "打开备份目录", "稍后处理"}, "稍后处理");
        if (choice == 0) {
            int removed = backupRepository.cleanTempFiles(leftovers);
            topBar.setStatus("已清理 " + removed + " 个临时文件/目录");
        } else if (choice == 1) {
            openBackupDir();
        } else {
            topBar.setStatus("已保留临时文件,可稍后在备份目录里查看");
        }
    }

    // ------------------------------------------------------------------
    // 窗口生命周期与截图辅助
    // ------------------------------------------------------------------

    private void applySavedBounds() {
        setSize(new Dimension(settings.getWindowWidth(), settings.getWindowHeight()));
        if (settings.getWindowX() == Integer.MIN_VALUE || settings.getWindowY() == Integer.MIN_VALUE
                || !isOnScreen(settings.getWindowX(), settings.getWindowY())) {
            setLocationRelativeTo(null);
        } else {
            setLocation(settings.getWindowX(), settings.getWindowY());
        }
    }

    private static boolean isOnScreen(int x, int y) {
        try {
            for (var device : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
                Rectangle bounds = device.getDefaultConfiguration().getBounds();
                if (bounds.contains(x, y)) {
                    return true;
                }
            }
        } catch (RuntimeException e) {
            return false;
        }
        return false;
    }

    private void closeApplication() {
        // 开了「最小化到托盘」时,关闭按钮只是把窗口收起来,自动备份继续在后台跑
        if (TraySupport.isInstalled() && settings.isMinimizeToTray()) {
            hideToTray();
            return;
        }
        exitApplication();
    }

    /** 真正退出程序。 */
    private void exitApplication() {
        if (busy.get()) {
            int answer = JOptionPane.showConfirmDialog(this,
                    "还有任务正在执行(备份/导出)。确定要退出吗?\n未完成的临时文件会保留在备份目录里。",
                    "MC Backup", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (answer != JOptionPane.YES_OPTION) {
                return;
            }
        }
        try {
            settings.setWindowWidth(getWidth());
            settings.setWindowHeight(getHeight());
            if ((getExtendedState() & JFrame.MAXIMIZED_BOTH) == 0) {
                settings.setWindowX(getX());
                settings.setWindowY(getY());
            }
            repository.save(settings);
            Log.info("已保存窗口状态,准备退出");
        } catch (RuntimeException e) {
            Log.errorQuietly("保存窗口状态失败", e);
        }
        shutdown();
        dispose();
        System.exit(0);
    }

    /** 释放后台资源(测试与正常退出都会调用)。 */
    void shutdown() {
        TraySupport.remove();
        ThemeManager.removeListener(themeListener);
        if (scheduler != null) {
            scheduler.shutdown();
        }
        scanExecutor.shutdownNow();
        workerExecutor.shutdownNow();
    }

    /** 截图自检:切换到指定主题/页面,并按需要展示空状态。 */
    public void prepareScreenshot(Theme theme, String page, boolean emptyWorlds) {
        ThemeManager.setOption(theme);
        navigate(page);
        if ("world".equals(page)) {
            worldView.setScanResult(emptyWorlds ? ScanResult.empty() : lastResult);
        }
        validate();
        repaint();
    }

    /** 截图自检:恢复真实数据。 */
    public void restoreAfterScreenshots() {
        if (previewResult != null) {
            worldView.setScanResult(previewResult);
        } else {
            worldView.setScanResult(lastResult);
        }
        navigate("world");
    }

    /** 截图自检:标记当前世界列表来自预览(恢复时用)。 */
    public void markPreview() {
        previewResult = lastResult;
    }

    /** 截图/冒烟:对最小的世界做一次真实备份,让备份页有真实数据。 */
    public BackupRecord backupSmallestWorldForPreview() {
        MinecraftWorld smallest = lastResult.worlds().stream()
                .min(java.util.Comparator.comparingLong(MinecraftWorld::sizeBytes))
                .orElse(null);
        if (smallest == null) {
            Log.warn("没有可备份的世界,跳过预览备份");
            return null;
        }
        try {
            BackupResult result = backupService.backup(smallest, backupOptions(), ProgressListener.NOOP);
            backupView.setBackups(backupRepository.listAll());
            Log.info("预览备份完成: %s", result.record().zipFileName());
            return result.record();
        } catch (RuntimeException e) {
            Log.error("预览备份失败", e);
            return null;
        }
    }

    /** 测试与排查用:当前扫描结果。 */
    public ScanResult lastScanResult() {
        return lastResult;
    }

    /** 测试用:后台任务是否在执行。 */
    boolean isBusy() {
        return busy.get();
    }

    @Override
    public void onThemeChanged() {
        repaint();
    }

    /** 只负责填充主题背景色的内容面板。 */
    private static final class BackgroundPanel extends JPanel {
        private static final long serialVersionUID = 1L;

        @Override
        protected void paintComponent(Graphics g) {
            com.mcbackup.ui.theme.Palette p = ThemeManager.palette();
            g.setColor(p.background());
            g.fillRect(0, 0, getWidth(), getHeight());
        }
    }
}
