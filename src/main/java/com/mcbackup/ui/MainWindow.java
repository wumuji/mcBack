package com.mcbackup.ui;

import com.mcbackup.App;
import com.mcbackup.model.AppSettings;
import com.mcbackup.model.ScanProgress;
import com.mcbackup.model.ScanResult;
import com.mcbackup.model.Theme;
import com.mcbackup.service.LauncherDetector;
import com.mcbackup.service.WorldScanner;
import com.mcbackup.service.WorldRootProvider;
import com.mcbackup.storage.SettingsRepository;
import com.mcbackup.ui.components.ScrollPaneStyler;
import com.mcbackup.ui.theme.ThemeAware;
import com.mcbackup.ui.theme.ThemeManager;
import com.mcbackup.util.Log;
import com.mcbackup.util.PathUtils;

import javax.swing.BorderFactory;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
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

/**
 * 主窗口:左侧导航 + 顶栏 + 四个页面。
 *
 * <p>线程约定:所有 Swing 操作都在 EDT 上;扫描统一交给单线程的 {@code mcbackup-scan},
 * 空闲时该线程处于等待状态,不消耗 CPU。</p>
 */
public class MainWindow extends JFrame implements ThemeAware {

    private static final Map<String, String> PAGE_TITLES = new LinkedHashMap<>();

    static {
        PAGE_TITLES.put("world", "世界");
        PAGE_TITLES.put("backup", "备份");
        PAGE_TITLES.put("export", "导出");
        PAGE_TITLES.put("settings", "设置");
    }

    private final SettingsRepository repository;
    private final AppSettings settings;
    private final List<Path> extraRoots;
    private final boolean autoScan;

    private final WorldScanner scanner;
    private final ExecutorService scanExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "mcbackup-scan");
        thread.setDaemon(true);
        return thread;
    });

    private final CardLayout pageLayout = new CardLayout();
    private final JPanel pageHost = new JPanel(pageLayout);
    private final Sidebar sidebar;
    private final TopBar topBar;
    private final WorldView worldView;
    private final SettingsView settingsView;

    private final AtomicBoolean scanRunning = new AtomicBoolean(false);
    private final AtomicBoolean rescanQueued = new AtomicBoolean(false);
    private final Runnable themeListener = this::applyTheme;

    private ScanResult lastResult = ScanResult.empty();
    private String currentPage = "world";

    public MainWindow(SettingsRepository repository, AppSettings settings, List<Path> extraRoots, boolean autoScan) {
        this(repository, settings, extraRoots, autoScan, new LauncherDetector());
    }

    /**
     * 允许注入存档目录来源。
     *
     * <p>生产代码使用 {@link LauncherDetector};测试注入固定目录,避免测试结果受开发机上的
     * 真实存档影响。</p>
     */
    public MainWindow(SettingsRepository repository, AppSettings settings, List<Path> extraRoots,
                      boolean autoScan, WorldRootProvider provider) {
        this.repository = repository;
        this.settings = settings;
        this.extraRoots = extraRoots == null ? List.of() : new ArrayList<>(extraRoots);
        this.autoScan = autoScan;
        this.scanner = new WorldScanner(provider == null ? new LauncherDetector() : provider);

        setTitle(App.NAME + " — Minecraft Java 存档备份");
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(880, 560));
        setContentPane(new BackgroundPanel());
        getContentPane().setLayout(new BorderLayout());
        applySavedBounds();

        sidebar = new Sidebar(this::navigate);
        topBar = new TopBar(() -> rescan(false), this::toggleTheme);
        worldView = new WorldView(this::addDirectory, () -> rescan(false));
        worldView.setStatusSink(topBar::setStatus);
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
        pageHost.setBorder(BorderFactory.createEmptyBorder(0, 24, 24, 24));
        pageHost.add(worldView, "world");
        pageHost.add(new BackupView(), "backup");
        pageHost.add(new ExportView(), "export");
        pageHost.add(settingsView, "settings");

        JPanel center = new JPanel(new BorderLayout());
        center.setOpaque(false);
        center.add(topBar, BorderLayout.NORTH);
        center.add(pageHost, BorderLayout.CENTER);

        getContentPane().add(sidebar, BorderLayout.WEST);
        getContentPane().add(center, BorderLayout.CENTER);

        navigate("world");
        ScrollPaneStyler.applyRecursively(this);
        ThemeManager.addListener(themeListener);

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                closeApplication();
            }
        });
        Log.info("主窗口已创建");
    }

    /** 启动后立即扫描(截屏模式下由调用方手动触发)。 */
    public void start() {
        if (autoScan) {
            rescan(false);
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
    }

    public String currentPage() {
        return currentPage;
    }

    private void selectTheme(Theme theme) {
        ThemeManager.setOption(theme);
        settings.setTheme(theme);
        repository.save(settings);
        settingsView.refreshThemeButtons();
        Log.info("主题已切换为 " + theme.displayName());
    }

    private void toggleTheme() {
        selectTheme(ThemeManager.palette().dark() ? Theme.LIGHT : Theme.DARK);
    }

    /** 主题变化:刷新所有自绘组件与滚动条。 */
    private void applyTheme() {
        // 内容面板自己画背景,背景属性也同步一份,避免原生 LAF 在边缘绘制时取到旧颜色
        getContentPane().setBackground(ThemeManager.palette().background());
        ScrollPaneStyler.applyRecursively(this);
        walkThemeAware(getContentPane());
        topBar.refreshThemeButtonText();
        revalidate();
        repaint();
    }

    private void walkThemeAware(java.awt.Container container) {
        if (container instanceof ThemeAware aware) {
            aware.onThemeChanged();
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

    /** 触发一次扫描;正在扫描时会排队一次,避免重复并发扫描。 */
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
                ScanResult result = scanner.scan(manual, this::reportProgress, forceSizeRecalc);
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

    private void reportProgress(ScanProgress progress) {
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

    /** 同步扫描一次(仅截图自检使用,会阻塞调用线程)。 */
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
    // 目录管理
    // ------------------------------------------------------------------

    private void addDirectory() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("选择 Minecraft 存档目录");
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
    // 窗口生命周期与截屏辅助
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
        ThemeManager.removeListener(themeListener);
        scanExecutor.shutdownNow();
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
        worldView.setScanResult(lastResult);
        navigate("world");
    }

    /** 测试与排查用:当前扫描结果。 */
    public ScanResult lastScanResult() {
        return lastResult;
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
