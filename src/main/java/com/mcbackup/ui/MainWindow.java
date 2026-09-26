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
import com.mcbackup.service.BackupTargets;
import com.mcbackup.service.ExportService;
import com.mcbackup.service.IntegrityService;
import com.mcbackup.service.LauncherDetector;
import com.mcbackup.service.RestoreService;
import com.mcbackup.service.WorldRootProvider;
import com.mcbackup.service.WorldScanner;
import com.mcbackup.storage.BackupRepository;
import com.mcbackup.storage.SettingsRepository;
import com.mcbackup.ui.components.AppIcon;
import com.mcbackup.ui.components.ScrollPaneStyler;
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
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 主窗口:自动备份是主角。
 *
 * <p>页面只有三个:</p>
 * <ul>
 *   <li><b>备份</b>:状态栏(状态 + 下次备份倒计时 + 开始/暂停 + 备份设置)+ 版本 → 存档 → 存档明细;</li>
 *   <li><b>备份记录</b>:所有备份的列表与操作(恢复 / 校验 / 导出 / 删除);</li>
 *   <li><b>设置</b>:外观、存档目录、日志、关于。</li>
 * </ul>
 *
 * <p>线程约定:UI 在 EDT;扫描在 {@code mcbackup-scan};备份/导出/恢复在 {@code mcbackup-worker};
 * 定时检查在 {@code mcbackup-autobackup},空闲时都在等待,不轮询。</p>
 */
public class MainWindow extends JFrame implements ThemeAware {

    private static final Map<String, String> PAGE_TITLES = new LinkedHashMap<>();
    /** 倒计时刷新间隔:只在「备份」页可见且自动备份运行时才走这个 Timer。 */
    private static final int COUNTDOWN_INTERVAL_MS = 1000;

    static {
        PAGE_TITLES.put("backup", "备份");
        PAGE_TITLES.put("records", "备份记录");
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
    private final AutoBackupView autoBackupView;
    private final BackupRecordsView recordsView;
    private final SettingsView settingsView;
    private final Timer countdownTimer;

    private final AtomicBoolean scanRunning = new AtomicBoolean(false);
    private final AtomicBoolean rescanQueued = new AtomicBoolean(false);
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private final Runnable themeListener = this::applyTheme;

    private ScanResult lastResult = ScanResult.empty();
    private ScanResult previewResult;
    private String currentPage = "backup";
    private String autoBackupStatusHint = "";

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
     * @param backupDirOverride 临时覆盖备份目录(命令行 --backup-dir,不写入配置)
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
        setMinimumSize(new Dimension(960, 600));
        setContentPane(new BackgroundPanel());
        getContentPane().setLayout(new BorderLayout());
        applySavedBounds();

        sidebar = new Sidebar(this::navigate);
        topBar = new TopBar(() -> rescan(false));
        autoBackupView = new AutoBackupView(new AutoBackupView.Callbacks() {
            @Override
            public void onTargetToggled(MinecraftWorld world, boolean selected) {
                toggleTarget(world, selected);
            }

            @Override
            public void onSelectGroup(String groupName, boolean selected) {
                selectGroup(groupName, selected);
            }

            @Override
            public void onBackupNow(MinecraftWorld world) {
                backupWorld(world);
            }

            @Override
            public void onExportWorld(MinecraftWorld world) {
                exportWorldWithChooser(world);
            }

            @Override
            public void onOpenWorldDir(MinecraftWorld world) {
                FileUtils.openInFileBrowser(world.worldDir());
            }

            @Override
            public void onViewRecords(MinecraftWorld world) {
                recordsView.setFilter(world.folderName());
                navigate("records");
            }

            @Override
            public void onToggleAutoBackup() {
                toggleAutoBackup();
            }

            @Override
            public void onBackupOnce() {
                backupChangedWorldsNow();
            }

            @Override
            public void onOpenBackupSettings() {
                showBackupSettings();
            }

            @Override
            public void onOpenBackupDir() {
                openBackupDir();
            }

            @Override
            public void onSelectRecentWorlds() {
                selectRecentWorlds();
            }
        });
        recordsView = new BackupRecordsView(new BackupRecordsView.Callbacks() {
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
        }, repository.logsDir());

        pageHost.setOpaque(false);
        pageHost.setBorder(BorderFactory.createEmptyBorder(0, 20, 18, 20));
        pageHost.add(autoBackupView, "backup");
        pageHost.add(recordsView, "records");
        pageHost.add(settingsView, "settings");

        JPanel center = new JPanel(new BorderLayout());
        center.setOpaque(false);
        center.add(topBar, BorderLayout.NORTH);
        center.add(pageHost, BorderLayout.CENTER);
        getContentPane().add(sidebar, BorderLayout.WEST);
        getContentPane().add(center, BorderLayout.CENTER);

        countdownTimer = new Timer(COUNTDOWN_INTERVAL_MS, e -> updateCountdown());
        countdownTimer.setRepeats(true);

        rebuildBackupServices();
        migrateAutoBackupState();
        navigate("backup");
        applyAutoBackupSettings();
        setIconImage(AppIcon.render(64));
        ThemeManager.addListener(themeListener);
        applyTheme();
        installTray();
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

    private void rebuildBackupServices() {
        if (scheduler != null) {
            scheduler.shutdown();
        }
        backupRepository = new BackupRepository(settings.backupDirPath());
        backupService = new BackupService(backupRepository);
        scheduler = new BackupScheduler(
                this::selectedWorlds,
                backupService,
                this::backupOptions,
                backupRepository,
                new AutoBackupListener());
    }

    /** 只有被勾选、并且本次扫描确实存在的世界才会进入自动备份。 */
    private List<MinecraftWorld> selectedWorlds() {
        return BackupTargets.filter(lastResult.worlds(), settings.getAutoBackupTargets());
    }

    private BackupOptions backupOptions() {
        return BackupOptions.of(settings.backupDirPath(), settings.getRetainCount(),
                settings.isFastBackup(), settings.isFullVerify());
    }

    /**
     * 老配置兼容:以前开启过自动备份但没有「勾选了哪些存档」的概念,
     * 这时不能默默把所有存档都备份一遍,而是先暂停并提示用户重新选择。
     */
    private void migrateAutoBackupState() {
        if (settings.isAutoBackupEnabled() && !settings.hasAutoBackupTargets()) {
            settings.setAutoBackupEnabled(false);
            repository.save(settings);
            autoBackupStatusHint = "自动备份已暂停:请先勾选要自动备份的存档";
            Log.warn("检测到旧配置开启了自动备份但没有选择存档,已暂停并要求重新选择");
        }
    }

    private void applyAutoBackupSettings() {
        boolean enabled = settings.isAutoBackupEnabled() && settings.hasAutoBackupTargets();
        if (enabled) {
            scheduler.start(settings.getAutoBackupIntervalMinutes());
        } else {
            scheduler.stop();
        }
        autoBackupView.setAutoBackupState(enabled, settings.hasAutoBackupTargets());
        updateCountdownTimer();
        updateCountdown();
    }

    // ------------------------------------------------------------------
    // 自动备份控制
    // ------------------------------------------------------------------

    private void toggleAutoBackup() {
        if (scheduler.isRunning()) {
            scheduler.stop();
            settings.setAutoBackupEnabled(false);
            repository.save(settings);
            autoBackupStatusHint = "自动备份已暂停";
            Log.info("用户暂停了自动备份");
        } else {
            if (!settings.hasAutoBackupTargets()) {
                JOptionPane.showMessageDialog(this,
                        "请先在中间列表勾选要自动备份的存档。\n\n"
                                + "勾选后只会备份这些存档,扫描到的其它世界不会被自动备份。",
                        "还没有选择存档", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            if (selectedWorlds().isEmpty()) {
                JOptionPane.showMessageDialog(this,
                        "勾选的存档在当前扫描结果里找不到,请点「重新扫描」或重新选择。",
                        "找不到存档", JOptionPane.WARNING_MESSAGE);
                return;
            }
            settings.setAutoBackupEnabled(true);
            repository.save(settings);
            scheduler.start(settings.getAutoBackupIntervalMinutes());
            autoBackupStatusHint = "自动备份已启动,每 " + settings.getAutoBackupIntervalMinutes() + " 分钟检查一次";
            Log.info("用户启动了自动备份");
        }
        applyAutoBackupSettings();
        refreshAutoBackupView();
    }

    /** 立即按自动备份的规则跑一次:只备份勾选过、并且有变化的世界。 */
    private void backupChangedWorldsNow() {
        if (!settings.hasAutoBackupTargets()) {
            topBar.setStatus("请先勾选要备份的存档");
            return;
        }
        submitTask("正在检查需要备份的存档…", listener -> {
            int before = backupRepository.listAll().size();
            scheduler.tick();
            int after = backupRepository.listAll().size();
            return after > before
                    ? "已备份 " + (after - before) + " 个有变化的存档"
                    : "勾选的存档都没有变化,已跳过";
        });
    }

    private void toggleTarget(MinecraftWorld world, boolean selected) {
        boolean changed = settings.setAutoBackupTarget(BackupTargets.key(world), selected);
        if (!changed) {
            return;
        }
        repository.save(settings);
        Log.info("%s 自动备份:%s", selected ? "纳入" : "移出", world.displayName());
        if (selected) {
            boolean hasBackup = !backupRepository.listForWorld(world.folderName()).isEmpty();
            if (!hasBackup) {
                int answer = JOptionPane.showConfirmDialog(this,
                        "要现在先备份一份「" + world.displayName() + "」作为基线吗?\n\n"
                                + "之后自动备份只会在它发生变化时再备份。",
                        "纳入自动备份", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
                if (answer == JOptionPane.YES_OPTION) {
                    backupWorld(world);
                }
            }
        }
        autoBackupView.setAutoBackupState(scheduler.isRunning(), settings.hasAutoBackupTargets());
        refreshAutoBackupView();
    }

    private void selectGroup(String groupName, boolean selected) {
        for (MinecraftWorld world : lastResult.worlds()) {
            if (groupName.equals(world.groupName())) {
                settings.setAutoBackupTarget(BackupTargets.key(world), selected);
            }
        }
        repository.save(settings);
        autoBackupView.setAutoBackupState(scheduler.isRunning(), settings.hasAutoBackupTargets());
        refreshAutoBackupView();
    }

    /**
     * 首次使用引导:把最近玩过的 3 个世界勾上。
     *
     * <p>只勾选、不自动开始备份,让用户自己按「开始自动备份」——避免未经确认就开始写磁盘。</p>
     */
    private void selectRecentWorlds() {
        List<MinecraftWorld> recent = lastResult.worlds().stream()
                .sorted(java.util.Comparator.comparingLong(MinecraftWorld::lastModified).reversed())
                .limit(3)
                .toList();
        if (recent.isEmpty()) {
            topBar.setStatus("还没有发现存档,可以先到设置里添加存档目录");
            return;
        }
        for (MinecraftWorld world : recent) {
            settings.setAutoBackupTarget(BackupTargets.key(world), true);
        }
        repository.save(settings);
        autoBackupView.setAutoBackupState(scheduler.isRunning(), settings.hasAutoBackupTargets());
        refreshAutoBackupView();
        topBar.setStatus("已勾选最近玩过的 " + recent.size() + " 个世界,点「开始自动备份」即可");
        Log.info("首次引导:已勾选最近玩过的 %d 个世界", recent.size());
    }

    private void updateCountdownTimer() {
        boolean shouldRun = "backup".equals(currentPage) && scheduler.isRunning();
        if (shouldRun && !countdownTimer.isRunning()) {
            countdownTimer.start();
        } else if (!shouldRun && countdownTimer.isRunning()) {
            countdownTimer.stop();
        }
    }

    private void updateCountdown() {
        if (!scheduler.isRunning()) {
            autoBackupView.setCountdown("未启动");
            return;
        }
        long seconds = scheduler.secondsUntilNextRun();
        autoBackupView.setCountdown("下次备份:" + BackupScheduler.formatCountdown(seconds) + "后");
    }

    /** 自动备份事件 → 界面提示(不刷日志)。 */
    private final class AutoBackupListener implements BackupScheduler.Listener {

        @Override
        public void onSkipped(MinecraftWorld world, String reason) {
            Log.debug("自动备份跳过 %s:%s", world.displayName(), reason);
        }

        @Override
        public void onBackupStarted(MinecraftWorld world) {
            SwingUtilities.invokeLater(() -> {
                autoBackupStatusHint = "正在备份:" + world.displayName();
                autoBackupView.setStatusHint(autoBackupStatusHint, false);
            });
        }

        @Override
        public void onBackupFinished(MinecraftWorld world, BackupResult result) {
            SwingUtilities.invokeLater(() -> {
                autoBackupStatusHint = "上次备份:" + world.displayName() + " " + result.record().createdText()
                        + "(" + result.record().sizeText() + ")";
                autoBackupView.setStatusHint(autoBackupStatusHint, false);
                recordsView.setNotice("", false);
            });
        }

        @Override
        public void onBackupFailed(MinecraftWorld world, Exception error) {
            SwingUtilities.invokeLater(() -> {
                String message = "自动备份失败:" + world.displayName() + " — " + error.getMessage();
                autoBackupStatusHint = "上次备份失败:" + world.displayName() + "(详见日志)";
                autoBackupView.setStatusHint(autoBackupStatusHint, true);
                recordsView.setNotice(message + "(详情见日志,不会影响原存档)", true);
                TraySupport.notifyMessage(App.NAME + " 自动备份失败", message);
            });
        }

        @Override
        public void onTickFinished(int backedUp, int skipped, long elapsedMillis) {
            SwingUtilities.invokeLater(() -> {
                if (backedUp + skipped > 0) {
                    autoBackupStatusHint = "上次检查:备份 " + backedUp + " 个,跳过 " + skipped
                            + " 个(没有变化)";
                    autoBackupView.setStatusHint(autoBackupStatusHint, false);
                }
                refreshAutoBackupView();
                refreshRecordsView();
                updateCountdown();
            });
        }
    }

    // ------------------------------------------------------------------
    // 页面切换与主题
    // ------------------------------------------------------------------

    public void navigate(String key) {
        String page = PAGE_TITLES.containsKey(key) ? key : "backup";
        currentPage = page;
        pageLayout.show(pageHost, page);
        topBar.setTitle(PAGE_TITLES.get(page));
        sidebar.setSelected(page);
        if ("backup".equals(page)) {
            refreshAutoBackupView();
        } else if ("records".equals(page)) {
            refreshRecordsView();
        }
        updateCountdownTimer();
        updateCountdown();
    }

    public String currentPage() {
        return currentPage;
    }

    private void selectTheme(Theme theme) {
        ThemeManager.setOption(theme);
        settings.setTheme(theme);
        repository.save(settings);
        settingsView.refreshThemeButtons();
        topBar.setStatus("主题已切换为 " + theme.displayName());
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
        SwingUtilities.invokeLater(() -> topBar.setStatus(text));
    }

    private void applyScanResult(ScanResult result) {
        lastResult = result;
        settingsView.setScanResult(result);
        refreshAutoBackupView();
        refreshRecordsView();
        StringBuilder status = new StringBuilder("已扫描 ")
                .append(result.rootCount()).append(" 个候选目录 · 发现 ")
                .append(result.worlds().size()).append(" 个世界 · ")
                .append(result.elapsedMillis()).append(" ms");
        if (!result.issues().isEmpty()) {
            status.append(" · ").append(result.issues().size()).append(" 条提示");
        }
        int selected = settings.getAutoBackupTargets().size();
        if (selected > 0) {
            status.append(" · 自动备份对象 ").append(selected).append(" 个");
        }
        topBar.setStatus(status.toString());
        topBar.setRescanEnabled(true);
        applyAutoBackupSettings();
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
    // 界面数据刷新
    // ------------------------------------------------------------------

    /** 读取备份清单(IO 在后台线程),然后刷新「备份」与「备份记录」两个页面。 */
    private void refreshAutoBackupView() {
        autoBackupView.setContext(settings, lastResult, latestByFolder(), countByFolder());
        autoBackupView.setAutoBackupState(scheduler.isRunning(), settings.hasAutoBackupTargets());
        autoBackupView.setStatusHint(autoBackupStatusHint, autoBackupStatusHint.contains("失败"));
    }

    private void refreshRecordsView() {
        BackupRepository current = backupRepository;
        workerExecutor.submit(() -> {
            List<BackupRecord> records = current.listAll();
            SwingUtilities.invokeLater(() -> recordsView.setBackups(records));
        });
    }

    private Map<String, BackupRecord> latestByFolder() {
        Map<String, BackupRecord> latest = new LinkedHashMap<>();
        for (BackupRecord record : backupRepository.listAll()) {
            latest.putIfAbsent(record.worldFolderName(), record);
        }
        return latest;
    }

    private Map<String, Integer> countByFolder() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (BackupRecord record : backupRepository.listAll()) {
            counts.merge(record.worldFolderName(), 1, Integer::sum);
        }
        return counts;
    }

    // ------------------------------------------------------------------
    // 备份 / 导出 / 删除 / 校验 / 恢复
    // ------------------------------------------------------------------

    private void backupWorld(MinecraftWorld world) {
        if (world == null) {
            topBar.setStatus("请先选择一个存档");
            return;
        }
        submitTask("正在备份 " + world.displayName() + "…", listener -> {
            BackupResult result = backupService.backup(world, backupOptions(), listener);
            String suffix = result.complete() ? "" : " · 有文件未复制,详见日志";
            autoBackupStatusHint = "上次备份:" + result.record().createdText()
                    + "(" + result.record().sizeText() + ")";
            return "备份完成:" + result.record().zipFileName()
                    + "(" + result.record().sizeText() + ",耗时 " + result.record().durationText() + ")" + suffix;
        });
    }

    /** 从存档明细里的「导出…」按钮进入:选目标位置后导出。 */
    private void exportWorldWithChooser(MinecraftWorld world) {
        if (world == null) {
            return;
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("导出到…");
        chooser.setSelectedFile(new File(ExportService.suggestedFileName(world)));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path target = PathUtils.toPath(chooser.getSelectedFile().getAbsolutePath());
        if (target == null) {
            return;
        }
        if (Files.exists(target) && JOptionPane.showConfirmDialog(this,
                "目标文件已存在,要覆盖吗?\n" + PathUtils.toDisplayPath(target),
                "MC Backup", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) {
            return;
        }
        submitTask("正在导出 " + world.displayName() + "…", listener -> {
            ExportResult result = exportService.export(world, target, listener);
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

    private void showBackupSettings() {
        BackupSettingsDialog.Result result = BackupSettingsDialog.show(this, settings);
        if (result == null) {
            return;
        }
        boolean dirChanged = !result.backupDir().equalsIgnoreCase(settings.getBackupDir());
        settings.setBackupDir(result.backupDir());
        settings.setAutoBackupIntervalMinutes(result.intervalMinutes());
        settings.setRetainCount(result.retainCount());
        settings.setFastBackup(result.fastBackup());
        settings.setFullVerify(result.fullVerify());
        settings.setMinimizeToTray(result.minimizeToTray());
        repository.save(settings);
        if (dirChanged) {
            rebuildBackupServices();
        }
        applyAutoBackupSettings();
        refreshAutoBackupView();
        refreshRecordsView();
        installTray();
        topBar.setStatus("备份设置已更新");
    }

    /**
     * 提交一个后台任务(串行执行,避免多个大文件操作互相拖慢磁盘)。
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
                SwingUtilities.invokeLater(() -> {
                    topBar.setStatus(message);
                    refreshAutoBackupView();
                    refreshRecordsView();
                });
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
                    refreshAutoBackupView();
                });
            }
        });
    }

    private void setBusyUi(boolean value) {
        topBar.setRescanEnabled(!value);
        autoBackupView.setBusy(value);
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
            String text = stage + (total > 0 ? " " + done + "/" + total : "")
                    + (name.isBlank() ? "" : " · " + name);
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
        repository.save(settings);
        Log.info("已添加存档目录: " + path);
        topBar.setStatus("已添加目录:" + path);
        rescan(false);
    }

    private void removeDirectory(String path) {
        settings.removeManualWorldDir(path);
        repository.save(settings);
        Log.info("已移除存档目录: " + path);
        topBar.setStatus("已移除目录:" + path);
        rescan(false);
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
                SwingUtilities.invokeLater(() -> topBar.setStatus("托盘触发:正在检查需要备份的存档…"));
                workerExecutor.submit(() -> {
                    scheduler.tick();
                    SwingUtilities.invokeLater(() -> {
                        refreshAutoBackupView();
                        refreshRecordsView();
                    });
                });
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
        countdownTimer.stop();
        TraySupport.notifyMessage(App.NAME,
                "程序仍在后台运行,自动备份继续生效。双击托盘图标可以重新打开窗口。");
        Log.info("窗口已最小化到托盘");
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
                    "还有任务正在执行(备份/导出/恢复)。确定要退出吗?\n未完成的临时文件会保留在备份目录里。",
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
        countdownTimer.stop();
        TraySupport.remove();
        com.mcbackup.util.SingleInstanceGuard.release();
        ThemeManager.removeListener(themeListener);
        if (scheduler != null) {
            scheduler.shutdown();
        }
        scanExecutor.shutdownNow();
        workerExecutor.shutdownNow();
    }

    /** 截图自检:切换到指定主题/页面。 */
    public void prepareScreenshot(Theme theme, String page, boolean emptyWorlds) {
        ThemeManager.setOption(theme);
        navigate(page);
        validate();
        repaint();
    }

    /** 截图自检:恢复真实数据。 */
    public void restoreAfterScreenshots() {
        if (previewResult != null) {
            lastResult = previewResult;
        }
        scheduler.stop();
        settings.setAutoBackupEnabled(false);
        navigate("backup");
        refreshAutoBackupView();
        updateCountdown();
    }

    /**
     * 截图/预览用:在内存里临时勾选最小的世界并启动自动备份,好让截图里能看到倒计时。
     *
     * <p>只改内存对象,不写配置(截图模式随后就退出)。</p>
     */
    public void previewAutoBackupState() {
        previewResult = lastResult;
        MinecraftWorld smallest = lastResult.worlds().stream()
                .min(java.util.Comparator.comparingLong(MinecraftWorld::sizeBytes))
                .orElse(null);
        if (smallest == null) {
            return;
        }
        settings.setAutoBackupTarget(BackupTargets.key(smallest), true);
        settings.setAutoBackupEnabled(true);
        scheduler.start(settings.getAutoBackupIntervalMinutes());
        autoBackupStatusHint = "上次备份:" + smallest.displayName();
        refreshAutoBackupView();
        updateCountdown();
    }

    /** 截图/冒烟:对最小的世界做一次真实备份,让备份页与记录页有真实数据。 */
    public BackupRecord backupSmallestWorldForPreview() {
        MinecraftWorld smallest = lastResult.worlds().stream()
                .min(java.util.Comparator.comparingLong(MinecraftWorld::sizeBytes))
                .orElse(null);
        if (smallest == null) {
            Log.warn("没有可备份的世界,跳过预览备份");
            return null;
        }
        try {
            settings.setAutoBackupTarget(BackupTargets.key(smallest), true);
            BackupResult result = backupService.backup(smallest, backupOptions(), ProgressListener.NOOP);
            autoBackupStatusHint = "上次备份:" + result.record().createdText()
                    + "(" + result.record().sizeText() + ")";
            refreshAutoBackupView();
            refreshRecordsView();
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

    /** 测试用:当前自动备份是否在运行。 */
    boolean isAutoBackupRunning() {
        return scheduler != null && scheduler.isRunning();
    }

    /** 测试用:直接触发一次自动备份检查(等价于「立即备份一次」)。 */
    void tickAutoBackupForTest() {
        scheduler.tick();
    }

    /** 测试用:同步刷新两个页面(生产路径是后台线程 + invokeLater,测试要确定性)。 */
    void refreshViewsNowForTest() {
        recordsView.setBackups(backupRepository.listAll());
        refreshAutoBackupView();
    }

    /** 测试用:当前被勾选且存在的世界数量。 */
    int selectedWorldCount() {
        return selectedWorlds().size();
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
