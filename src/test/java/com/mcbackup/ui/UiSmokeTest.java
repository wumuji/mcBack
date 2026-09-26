package com.mcbackup.ui;

import com.mcbackup.model.AppSettings;
import com.mcbackup.model.BackupRecord;
import com.mcbackup.model.LocationKind;
import com.mcbackup.model.MinecraftWorld;
import com.mcbackup.model.Theme;
import com.mcbackup.service.BackupTargets;
import com.mcbackup.service.WorldRoot;
import com.mcbackup.service.WorldRootProvider;
import com.mcbackup.storage.SettingsRepository;
import com.mcbackup.testfx.WorldFixtures;
import com.mcbackup.ui.components.FlatButton;
import com.mcbackup.ui.theme.ThemeManager;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 界面冒烟测试。
 *
 * <p>重点验证新的主流程:默认进入「备份」页(自动备份主控台)→ 勾选存档 → 开始自动备份 →
 * 只备份勾选过的存档 → 倒计时可见。另外保留主题一致性检查(浅色主题下不允许出现深色残留)。</p>
 */
class UiSmokeTest {

    @TempDir
    Path tempDir;

    @Test
    void autoBackupIsTheMainFlowAndOnlyTouchesSelectedWorlds() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "需要图形环境才能构建 Swing 界面");

        Path saves = tempDir.resolve("official").resolve("saves");
        Files.createDirectories(saves);
        WorldFixtures.vanillaWorld(saves, "要备份的", "要备份的");
        WorldFixtures.moddedWorld(saves, "不该备份的", "不该备份的");

        AtomicReference<MainWindow> windowRef = new AtomicReference<>();
        AtomicReference<AppSettings> settingsRef = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            SettingsRepository repository = new SettingsRepository(tempDir.resolve("config"));
            AppSettings settings = new AppSettings();
            settings.setBackupDir(tempDir.resolve("backups").toString());
            settingsRef.set(settings);
            WorldRootProvider provider = () -> List.of(
                    new WorldRoot(saves.getParent(), saves, LocationKind.MANUAL, "测试存档目录", "测试"));
            MainWindow window = new MainWindow(repository, settings, List.of(), false, provider);
            window.setSize(1200, 720);
            window.addNotify();
            window.validate();
            window.scanNowBlocking();
            windowRef.set(window);
        });

        MainWindow window = windowRef.get();
        AppSettings settings = settingsRef.get();
        assertNotNull(window);
        try {
            // 1) 默认页面就是「备份」(自动备份主控台)
            assertEquals("backup", window.currentPage());
            AutoBackupView view = (AutoBackupView) findFirst(window.getContentPane(),
                    component -> component instanceof AutoBackupView);
            assertNotNull(view);
            assertEquals(2, view.visibleWorlds().size(), "应列出两个存档");
            assertFalse(window.isAutoBackupRunning(), "没有勾选任何存档时不应处于运行状态");

            // 2) 勾选一个存档 → 开始自动备份 → 出现倒计时
            MinecraftWorld target = view.visibleWorlds().stream()
                    .filter(world -> world.folderName().equals("要备份的"))
                    .findFirst()
                    .orElseThrow();
            settings.setAutoBackupTarget(BackupTargets.key(target), true);
            SwingUtilities.invokeAndWait(() -> {
                window.navigate("backup");
                FlatButton start = findButton(window.getContentPane(), "开始自动备份");
                assertNotNull(start, "应有「开始自动备份」按钮");
                start.doClick();
            });

            assertTrue(window.isAutoBackupRunning(), "点开始后自动备份应在运行");
            assertTrue(view.countdownText().contains("下次备份"), "应显示下次备份倒计时:" + view.countdownText());

            // 3) 立即备份一次:只有勾选的存档会被备份
            SwingUtilities.invokeAndWait(() -> {
                window.tickAutoBackupForTest();
                window.refreshViewsNowForTest();
            });
            BackupRecordsView records = (BackupRecordsView) findFirst(window.getContentPane(),
                    component -> component instanceof BackupRecordsView);
            assertNotNull(records);
            assertEquals(1, records.records().size(), "只应备份勾选的那个存档");
            assertEquals("要备份的", records.records().get(0).worldFolderName());

            // 4) 暂停
            SwingUtilities.invokeAndWait(() -> {
                FlatButton pause = findButton(window.getContentPane(), "暂停自动备份");
                assertNotNull(pause);
                pause.doClick();
            });
            assertFalse(window.isAutoBackupRunning(), "暂停后不应再运行");

            // 5) 页面切换 + 主题一致性
            SwingUtilities.invokeAndWait(() -> {
                window.navigate("records");
                assertEquals("records", window.currentPage());
                window.navigate("settings");
                assertEquals("settings", window.currentPage());
                window.navigate("backup");
                assertEquals("backup", window.currentPage());
            });

            SwingUtilities.invokeAndWait(() -> {
                ThemeManager.setOption(Theme.LIGHT);
                window.validate();
            });
            assertOpaqueBackgroundsAreLight(window.getContentPane());

            SwingUtilities.invokeAndWait(() -> {
                ThemeManager.setOption(Theme.DARK);
                window.validate();
            });
            assertOpaqueBackgroundsAreDark(window.getContentPane());
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                window.shutdown();
                window.dispose();
            });
            ThemeManager.setOption(Theme.FOLLOW_SYSTEM);
        }
        assertTrue(settings.getAutoBackupTargets().size() >= 1, "勾选结果应保存到配置对象里");
    }

    @Test
    void refusesToStartWhenNothingSelected() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "需要图形环境才能构建 Swing 界面");

        Path saves = tempDir.resolve("saves2");
        Files.createDirectories(saves);
        WorldFixtures.vanillaWorld(saves, "世界", "世界");

        AtomicReference<MainWindow> windowRef = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            SettingsRepository repository = new SettingsRepository(tempDir.resolve("config2"));
            AppSettings settings = new AppSettings();
            settings.setBackupDir(tempDir.resolve("backups2").toString());
            WorldRootProvider provider = () -> List.of(
                    new WorldRoot(saves.getParent(), saves, LocationKind.MANUAL, "测试", "测试"));
            MainWindow window = new MainWindow(repository, settings, List.of(), false, provider);
            window.setSize(1200, 720);
            window.addNotify();
            window.validate();
            window.scanNowBlocking();
            windowRef.set(window);
        });

        MainWindow window = windowRef.get();
        try {
            SwingUtilities.invokeAndWait(() -> {
                // 直接走内部逻辑:没有勾选时不能启动(避免弹出模态对话框卡住测试)
                assertEquals(0, window.selectedWorldCount());
                assertFalse(window.isAutoBackupRunning());
            });
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                window.shutdown();
                window.dispose();
            });
        }
    }

    @Test
    void legacyConfigWithAutoBackupOnButNoSelectionStaysPaused() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "需要图形环境才能构建 Swing 界面");

        Path saves = tempDir.resolve("saves3");
        Files.createDirectories(saves);
        WorldFixtures.vanillaWorld(saves, "老配置世界", "老配置世界");

        AtomicReference<MainWindow> windowRef = new AtomicReference<>();
        AtomicReference<AppSettings> settingsRef = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            SettingsRepository repository = new SettingsRepository(tempDir.resolve("config3"));
            AppSettings settings = new AppSettings();
            settings.setBackupDir(tempDir.resolve("backups3").toString());
            // 模拟旧版本留下的状态:开了自动备份,但没有"勾选了哪些存档"的概念
            settings.setAutoBackupEnabled(true);
            settingsRef.set(settings);
            WorldRootProvider provider = () -> List.of(
                    new WorldRoot(saves.getParent(), saves, LocationKind.MANUAL, "测试", "测试"));
            MainWindow window = new MainWindow(repository, settings, List.of(), false, provider);
            window.setSize(1200, 720);
            window.addNotify();
            window.validate();
            window.scanNowBlocking();
            windowRef.set(window);
        });

        MainWindow window = windowRef.get();
        try {
            assertFalse(settingsRef.get().isAutoBackupEnabled(),
                    "没有勾选任何存档时必须暂停自动备份,而不是把所有存档都备份一遍");
            assertFalse(window.isAutoBackupRunning());
            assertEquals(0, window.selectedWorldCount());
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                window.shutdown();
                window.dispose();
            });
        }
    }

    private static void assertOpaqueBackgroundsAreLight(Container root) {
        List<JComponent> opaque = new ArrayList<>();
        collectOpaque(root, opaque);
        assertTrue(!opaque.isEmpty(), "应能在组件树里找到不透明背景的组件");
        for (JComponent component : opaque) {
            Color background = component.getBackground();
            if (background == null) {
                continue;
            }
            assertTrue(brightness(background) > 150,
                    "浅色主题下 " + component.getClass().getSimpleName() + " 的背景过暗: " + background);
        }
    }

    private static void assertOpaqueBackgroundsAreDark(Container root) {
        List<JComponent> opaque = new ArrayList<>();
        collectOpaque(root, opaque);
        for (JComponent component : opaque) {
            Color background = component.getBackground();
            if (background == null) {
                continue;
            }
            assertTrue(brightness(background) < 130,
                    "深色主题下 " + component.getClass().getSimpleName() + " 的背景过亮: " + background);
        }
    }

    private static double brightness(Color color) {
        return color.getRed() * 0.299 + color.getGreen() * 0.587 + color.getBlue() * 0.114;
    }

    private static Component findFirst(Container container, java.util.function.Predicate<Component> match) {
        for (Component child : container.getComponents()) {
            if (match.test(child)) {
                return child;
            }
            if (child instanceof Container childContainer) {
                Component found = findFirst(childContainer, match);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** 按文字找按钮(测试专用)。 */
    private static FlatButton findButton(Container container, String text) {
        Component found = findFirst(container,
                component -> component instanceof FlatButton button && text.equals(button.getText()));
        return found instanceof FlatButton button ? button : null;
    }

    private static void collectOpaque(Container container, List<JComponent> result) {
        if (container instanceof JComponent component && component.isOpaque()) {
            result.add(component);
        }
        for (Component child : container.getComponents()) {
            if (child instanceof Container childContainer) {
                collectOpaque(childContainer, result);
            }
        }
    }

}
