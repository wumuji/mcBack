package com.mcbackup.ui.components;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 程序图标(矢量绘制,不依赖图片资源)。
 *
 * <p>同一份代码用于:窗口图标、托盘图标,以及打包时导出的 PNG/ICO。
 * 这样图标只有一个来源,不会出现「窗口和 exe 图标不一致」。</p>
 */
public final class AppIcon {

    /** 品牌绿,与界面强调色一致。 */
    private static final Color ACCENT = new Color(0x46C07A);
    private static final Color ACCENT_DARK = new Color(0x2E9E5B);
    private static final Color INK = new Color(0x0E1418);

    private AppIcon() {
    }

    /** 渲染指定尺寸的图标。 */
    public static BufferedImage render(int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

        double radius = size * 0.22;
        // 背景:圆角方块 + 轻微渐变感(两层叠色)
        g.setColor(ACCENT);
        g.fill(new java.awt.geom.RoundRectangle2D.Double(0, 0, size - 1, size - 1, radius, radius));
        g.setColor(ACCENT_DARK);
        g.fill(new java.awt.geom.RoundRectangle2D.Double(0, size * 0.55, size - 1, size * 0.45 - 1, radius, radius));

        // 箱子图形(与导航栏「备份」图标同一造型)
        double inset = size * 0.24;
        double boxSize = size - inset * 2;
        g.setColor(INK);
        g.setStroke(new java.awt.BasicStroke(Math.max(1.6f, size / 10f), java.awt.BasicStroke.CAP_ROUND,
                java.awt.BasicStroke.JOIN_ROUND));
        double top = inset + boxSize * 0.18;
        g.draw(new java.awt.geom.RoundRectangle2D.Double(inset, top, boxSize, boxSize * 0.74,
                boxSize * 0.22, boxSize * 0.22));
        g.draw(new java.awt.geom.Line2D.Double(inset, inset + boxSize * 0.44,
                inset + boxSize, inset + boxSize * 0.44));
        g.draw(new java.awt.geom.Line2D.Double(inset + boxSize * 0.3, inset + boxSize * 0.3,
                inset + boxSize * 0.7, inset + boxSize * 0.3));
        g.dispose();
        return image;
    }

    /** 导出 PNG。 */
    public static void writePng(Path target, int size) throws IOException {
        if (target.getParent() != null) {
            Files.createDirectories(target.getParent());
        }
        ImageIO.write(render(size), "png", target.toFile());
    }

    /**
     * 导出 Windows ICO(PNG 载荷形式,Windows Vista 及以上都支持)。
     *
     * <p>自己写而不是引第三方库:ICO 只是 6 字节文件头 + 每个尺寸 16 字节目录项 + PNG 数据。</p>
     */
    public static void writeIco(Path target, int... sizes) throws IOException {
        int[] used = sizes.length == 0 ? new int[]{16, 32, 48, 256} : sizes;
        byte[][] payloads = new byte[used.length][];
        for (int i = 0; i < used.length; i++) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            ImageIO.write(render(used[i]), "png", buffer);
            payloads[i] = buffer.toByteArray();
        }
        if (target.getParent() != null) {
            Files.createDirectories(target.getParent());
        }
        try (OutputStream out = Files.newOutputStream(target, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            writeShort(out, 0);
            writeShort(out, 1);
            writeShort(out, used.length);
            int offset = 6 + 16 * used.length;
            for (int i = 0; i < used.length; i++) {
                out.write(used[i] >= 256 ? 0 : used[i]);
                out.write(used[i] >= 256 ? 0 : used[i]);
                out.write(0);
                out.write(0);
                writeShort(out, 1);
                writeShort(out, 32);
                writeInt(out, payloads[i].length);
                writeInt(out, offset);
                offset += payloads[i].length;
            }
            for (byte[] payload : payloads) {
                out.write(payload);
            }
        }
    }

    private static void writeShort(OutputStream out, int value) throws IOException {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
    }

    private static void writeInt(OutputStream out, int value) throws IOException {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
        out.write((value >> 16) & 0xFF);
        out.write((value >> 24) & 0xFF);
    }
}
