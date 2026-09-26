package com.mcback.ui;

import com.mcback.model.Theme;
import com.mcback.util.Log;

import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * GUI 截图自检。
 *
 * <p>开发过程中无法直接看到屏幕,所以提供一个只用于自检的开关:按顺序切换主题与页面,
 * 把内容区渲染成 PNG。这条路径不影响正常启动(只有传了 {@code --screenshot} 才会走到)。</p>
 */
public final class ScreenshotRunner {

    /** 一张截图。 */
    public record Shot(String fileName, Theme theme, String page, boolean emptyWorlds) {
    }

    /** 默认截图组合:备份页(主控台)、备份记录页、设置页,深浅色各来一张。 */
    public static List<Shot> defaultShots() {
        return List.of(
                new Shot("backup-dark", Theme.DARK, "backup", false),
                new Shot("backup-light", Theme.LIGHT, "backup", false),
                new Shot("records-dark", Theme.DARK, "records", false),
                new Shot("settings-dark", Theme.DARK, "settings", false),
                new Shot("settings-light", Theme.LIGHT, "settings", false));
    }

    private ScreenshotRunner() {
    }

    /**
     * 依次渲染并保存所有截图,完成后退出进程。
     *
     * @param window 已显示的主窗口
     * @param outputDir 输出目录
     */
    public static void run(MainWindow window, Path outputDir, List<Shot> shots) {
        try {
            Files.createDirectories(outputDir);
        } catch (IOException e) {
            Log.error("无法创建截图目录: " + outputDir, e);
            System.exit(2);
        }
        step(window, outputDir, shots, 0);
    }

    private static void step(MainWindow window, Path outputDir, List<Shot> shots, int index) {
        if (index >= shots.size()) {
            window.restoreAfterScreenshots();
            Log.info("截图自检完成,输出目录: " + outputDir);
            System.exit(0);
        }
        Shot shot = shots.get(index);
        window.prepareScreenshot(shot.theme(), shot.page(), shot.emptyWorlds());
        // 给布局与自绘组件留出稳定时间(450ms 足够,不引入加载感)
        Timer timer = new Timer(450, event -> {
            ((Timer) event.getSource()).stop();
            try {
                capture(window, outputDir.resolve(shot.fileName() + ".png"));
                Log.info("已生成截图: " + shot.fileName() + ".png");
            } catch (IOException e) {
                Log.error("生成截图失败: " + shot.fileName(), e);
            }
            SwingUtilities.invokeLater(() -> step(window, outputDir, shots, index + 1));
        });
        timer.setRepeats(false);
        timer.start();
    }

    private static void capture(JFrame frame, Path file) throws IOException {
        java.awt.Component content = frame.getContentPane();
        dumpDiagnostics(content);
        int width = Math.max(1, content.getWidth());
        int height = Math.max(1, content.getHeight());
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        content.printAll(g);
        g.dispose();
        ImageIO.write(image, "png", file.toFile());
    }

    /**
     * 仅用于开发期排查:记录若干采样点命中的组件,便于确认哪一层负责绘制。
     */
    private static void dumpDiagnostics(java.awt.Component content) {
        for (int[] point : new int[][]{{700, 300}, {300, 300}}) {
            java.awt.Component hit = javax.swing.SwingUtilities.getDeepestComponentAt(content, point[0], point[1]);
            if (hit == null) {
                Log.debug("采样点 (%d,%d) 没有命中组件", point[0], point[1]);
                continue;
            }
            java.awt.Rectangle bounds = javax.swing.SwingUtilities.convertRectangle(
                    hit.getParent(), hit.getBounds(), content);
            Log.debug("采样点 (%d,%d) -> %s bounds=%s opaque=%s bg=%s",
                    point[0], point[1], hit.getClass().getName(), bounds,
                    hit.isOpaque(), hit.getBackground());
        }
    }
}
