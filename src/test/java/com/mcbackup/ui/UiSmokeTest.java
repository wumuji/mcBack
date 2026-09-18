package com.mcbackup.ui;

import com.mcbackup.model.AppSettings;
import com.mcbackup.model.BackupRecord;
import com.mcbackup.model.Theme;
import com.mcbackup.storage.SettingsRepository;
import com.mcbackup.model.LocationKind;
import com.mcbackup.service.WorldRoot;
import com.mcbackup.service.WorldRootProvider;
import com.mcbackup.testfx.WorldFixtures;
import com.mcbackup.ui.components.WorldListItem;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 界面冒烟测试。
 *
 * <p>开发过程中看不到屏幕,所以这里用代码断言界面结构与主题一致性:侧栏宽度、页面切换、
 * 世界列表项数量,以及「浅色主题下不允许出现深色残留区块」(曾经真实出现过的缺陷)。</p>
 */
class UiSmokeTest {

    @TempDir
    Path tempDir;

    @Test
    void buildsWindowSwitchesPagesAndKeepsThemeConsistent() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "需要图形环境才能构建 Swing 界面");

        Path saves = tempDir.resolve("official").resolve("saves");
        Files.createDirectories(saves);
        WorldFixtures.vanillaWorld(saves, "新的世界", "生存世界");
        WorldFixtures.moddedWorld(saves, "爽包世界", "模组世界");

        AtomicReference<MainWindow> windowRef = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            SettingsRepository repository = new SettingsRepository(tempDir.resolve("config"));
            AppSettings settings = new AppSettings();
            settings.addManualWorldDir(saves.toString());
            settings.setBackupDir(tempDir.resolve("backups").toString());
            WorldRootProvider provider = () -> List.of(
                    new WorldRoot(saves.getParent(), saves, LocationKind.MANUAL, "测试存档目录"));
            MainWindow window = new MainWindow(repository, settings, List.of(), false, provider);
            window.setSize(1080, 720);
            window.addNotify();
            window.validate();
            window.scanNowBlocking();
            windowRef.set(window);
        });

        MainWindow window = windowRef.get();
        assertNotNull(window);
        try {
            Component sidebar = findFirst(window.getContentPane(), component -> component instanceof Sidebar);
            assertNotNull(sidebar, "应存在侧栏");
            assertEquals(232, sidebar.getWidth(), "侧栏宽度必须是设计规范里的 232px");

            List<WorldListItem> items = new ArrayList<>();
            collect(window.getContentPane(), WorldListItem.class, items);
            assertEquals(2, items.size(), "应列出两个世界");

            SwingUtilities.invokeAndWait(() -> {
                window.navigate("backup");
                assertEquals("backup", window.currentPage());
                window.navigate("export");
                assertEquals("export", window.currentPage());
                window.navigate("settings");
                assertEquals("settings", window.currentPage());
                window.navigate("world");
                assertEquals("world", window.currentPage());
            });

            // 真实备份一次,验证「备份」页面能显示出这份备份
            BackupRecord record = window.backupSmallestWorldForPreview();
            assertNotNull(record, "应能完成一次真实备份");
            BackupView backupView = (BackupView) findFirst(window.getContentPane(),
                    component -> component instanceof BackupView);
            assertNotNull(backupView);
            assertEquals(1, backupView.records().size());
            assertEquals(1, backupView.records().stream().filter(BackupRecord::managed).count());

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

    private static <T> void collect(Container container, Class<T> type, List<T> result) {
        for (Component child : container.getComponents()) {
            if (type.isInstance(child)) {
                result.add(type.cast(child));
            }
            if (child instanceof Container childContainer) {
                collect(childContainer, type, result);
            }
        }
    }

    private static void collectOpaque(Container container, List<JComponent> result) {
        if (container instanceof JComponent component && component.isOpaque()) {
            result.add(component);
        }
        for (Component child : container.getComponents()) {
            if (child instanceof JComponent component && component.isOpaque()) {
                result.add(component);
            }
            if (child instanceof Container childContainer) {
                collectOpaque(childContainer, result);
            }
        }
    }
}
