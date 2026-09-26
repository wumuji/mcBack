package com.mcbackup.ui.components;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;

/**
 * 矢量小图标。
 *
 * <p>为什么手绘图标:Java Swing 对彩色 emoji 的支持依赖系统字体回退,容易出现方块或黑白字形。
 * 这里用 BasicStroke 画出简单几何图形,任何 Windows 上都能稳定显示,也不增加体积。</p>
 */
public final class Icons {

    private Icons() {
    }

    private static Graphics2D prepare(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        return g;
    }

    /** 世界:星球 + 赤道线。 */
    public static void world(Graphics2D graphics, int x, int y, int size, Color color) {
        Graphics2D g = prepare((Graphics2D) graphics.create());
        g.setColor(color);
        g.setStroke(new java.awt.BasicStroke(Math.max(1.4f, size / 11f)));
        double inset = size / 8.0;
        g.draw(new Ellipse2D.Double(x + inset, y + inset, size - inset * 2, size - inset * 2));
        g.draw(new Ellipse2D.Double(x, y + size * 0.32, size, size * 0.36));
        g.dispose();
    }

    /** 备份:箱子。 */
    public static void box(Graphics2D graphics, int x, int y, int size, Color color) {
        Graphics2D g = prepare((Graphics2D) graphics.create());
        g.setColor(color);
        float stroke = Math.max(1.4f, size / 11f);
        g.setStroke(new java.awt.BasicStroke(stroke));
        double top = y + size * 0.18;
        g.draw(new RoundRectangle2D.Double(x + size * 0.08, top, size * 0.84, size * 0.74, size * 0.2, size * 0.2));
        g.draw(new java.awt.geom.Line2D.Double(x + size * 0.08, y + size * 0.44, x + size * 0.92, y + size * 0.44));
        g.draw(new java.awt.geom.Line2D.Double(x + size * 0.38, y + size * 0.3, x + size * 0.62, y + size * 0.3));
        g.dispose();
    }

    /** 导出:向上箭头 + 底座。 */
    public static void export(Graphics2D graphics, int x, int y, int size, Color color) {
        Graphics2D g = prepare((Graphics2D) graphics.create());
        g.setColor(color);
        float stroke = Math.max(1.4f, size / 11f);
        g.setStroke(new java.awt.BasicStroke(stroke, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND));
        double cx = x + size / 2.0;
        g.draw(new java.awt.geom.Line2D.Double(cx, y + size * 0.12, cx, y + size * 0.62));
        Path2D head = new Path2D.Double();
        head.moveTo(cx - size * 0.18, y + size * 0.32);
        head.lineTo(cx, y + size * 0.1);
        head.lineTo(cx + size * 0.18, y + size * 0.32);
        g.draw(head);
        g.draw(new java.awt.geom.Line2D.Double(x + size * 0.14, y + size * 0.82, x + size * 0.86, y + size * 0.82));
        g.dispose();
    }

    /** 设置:齿轮(圆环 + 四个齿)。 */
    public static void gear(Graphics2D graphics, int x, int y, int size, Color color) {
        Graphics2D g = prepare((Graphics2D) graphics.create());
        g.setColor(color);
        float stroke = Math.max(1.4f, size / 12f);
        g.setStroke(new java.awt.BasicStroke(stroke));
        double cx = x + size / 2.0;
        double cy = y + size / 2.0;
        double r = size * 0.28;
        g.draw(new Ellipse2D.Double(cx - r, cy - r, r * 2, r * 2));
        g.fill(new Ellipse2D.Double(cx - size * 0.07, cy - size * 0.07, size * 0.14, size * 0.14));
        double outer = size * 0.46;
        double inner = size * 0.36;
        for (int i = 0; i < 4; i++) {
            double angle = Math.PI / 4 * i * 2 + Math.PI / 4;
            double x1 = cx + Math.cos(angle) * inner;
            double y1 = cy + Math.sin(angle) * inner;
            double x2 = cx + Math.cos(angle) * outer;
            double y2 = cy + Math.sin(angle) * outer;
            g.draw(new java.awt.geom.Line2D.Double(x1, y1, x2, y2));
        }
        g.dispose();
    }

    /** 重新扫描:环形箭头。 */
    public static void refresh(Graphics2D graphics, int x, int y, int size, Color color) {
        Graphics2D g = prepare((Graphics2D) graphics.create());
        g.setColor(color);
        float stroke = Math.max(1.5f, size / 12f);
        g.setStroke(new java.awt.BasicStroke(stroke, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND));
        double inset = size * 0.16;
        g.draw(new Arc2D.Double(x + inset, y + inset, size - inset * 2, size - inset * 2, 60, 260, Arc2D.OPEN));
        Path2D arrow = new Path2D.Double();
        double ax = x + size * 0.78;
        double ay = y + size * 0.2;
        arrow.moveTo(ax - size * 0.16, ay);
        arrow.lineTo(ax, ay);
        arrow.lineTo(ax - size * 0.02, ay + size * 0.18);
        g.draw(arrow);
        g.dispose();
    }

    /** 浅色主题图标:太阳。 */
    public static void sun(Graphics2D graphics, int x, int y, int size, Color color) {
        Graphics2D g = prepare((Graphics2D) graphics.create());
        g.setColor(color);
        float stroke = Math.max(1.4f, size / 12f);
        g.setStroke(new java.awt.BasicStroke(stroke, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND));
        double cx = x + size / 2.0;
        double cy = y + size / 2.0;
        double r = size * 0.22;
        g.draw(new Ellipse2D.Double(cx - r, cy - r, r * 2, r * 2));
        for (int i = 0; i < 8; i++) {
            double angle = Math.PI / 4 * i;
            double inner = size * 0.32;
            double outer = size * 0.45;
            g.draw(new java.awt.geom.Line2D.Double(
                    cx + Math.cos(angle) * inner, cy + Math.sin(angle) * inner,
                    cx + Math.cos(angle) * outer, cy + Math.sin(angle) * outer));
        }
        g.dispose();
    }

    /** 深色主题图标:月亮。 */
    public static void moon(Graphics2D graphics, int x, int y, int size, Color color) {
        Graphics2D g = prepare((Graphics2D) graphics.create());
        g.setColor(color);
        Area crescent = new Area(new Ellipse2D.Double(x + size * 0.14, y + size * 0.12, size * 0.72, size * 0.76));
        crescent.subtract(new Area(new Ellipse2D.Double(x + size * 0.38, y + size * 0.0, size * 0.72, size * 0.8)));
        g.fill(crescent);
        g.dispose();
    }

    /** 文件夹。 */
    public static void folder(Graphics2D graphics, int x, int y, int size, Color color) {
        Graphics2D g = prepare((Graphics2D) graphics.create());
        g.setColor(color);
        float stroke = Math.max(1.4f, size / 12f);
        g.setStroke(new java.awt.BasicStroke(stroke));
        Path2D path = new Path2D.Double();
        path.moveTo(x + size * 0.1, y + size * 0.78);
        path.lineTo(x + size * 0.1, y + size * 0.24);
        path.lineTo(x + size * 0.42, y + size * 0.24);
        path.lineTo(x + size * 0.5, y + size * 0.36);
        path.lineTo(x + size * 0.9, y + size * 0.36);
        path.lineTo(x + size * 0.9, y + size * 0.78);
        path.closePath();
        g.draw(path);
        g.dispose();
    }

    /** 列表/记录:三条横线。 */
    public static void list(Graphics2D graphics, int x, int y, int size, Color color) {
        Graphics2D g = prepare((Graphics2D) graphics.create());
        g.setColor(color);
        float stroke = Math.max(1.4f, size / 12f);
        g.setStroke(new java.awt.BasicStroke(stroke, java.awt.BasicStroke.CAP_ROUND,
                java.awt.BasicStroke.JOIN_ROUND));
        for (int i = 0; i < 3; i++) {
            double lineY = y + size * (0.28 + 0.22 * i);
            g.draw(new java.awt.geom.Line2D.Double(x + size * 0.16, lineY, x + size * 0.86, lineY));
        }
        g.dispose();
    }
}
