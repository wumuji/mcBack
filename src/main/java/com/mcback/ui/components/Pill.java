package com.mcback.ui.components;

import com.mcback.ui.theme.Palette;
import com.mcback.ui.theme.ThemeAware;
import com.mcback.ui.theme.ThemeManager;
import com.mcback.ui.theme.UiFonts;

import javax.swing.JComponent;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/** 状态胶囊:小圆角标签,用于「未运行 / 可能运行中 / 来源标签」等。 */
public class Pill extends JComponent implements ThemeAware {

    private String text;
    private Color color;

    public Pill(String text, Color color) {
        this.text = text == null ? "" : text;
        this.color = color;
        setFont(UiFonts.medium(11));
        setOpaque(false);
    }

    public void setText(String text) {
        this.text = text == null ? "" : text;
        revalidate();
        repaint();
    }

    public void setColor(Color color) {
        this.color = color;
        repaint();
    }

    public String text() {
        return text;
    }

    @Override
    public Dimension getPreferredSize() {
        FontMetrics metrics = getFontMetrics(getFont());
        return new Dimension(metrics.stringWidth(text) + 20, 22);
    }

    @Override
    public Dimension getMinimumSize() {
        return getPreferredSize();
    }

    @Override
    public void onThemeChanged() {
        repaint();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Palette p = ThemeManager.palette();
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        int w = getWidth();
        int h = getHeight();
        g.setColor(p.alpha(color, 38));
        g.fillRoundRect(0, 0, w, h, h, h);
        g.setColor(p.alpha(color, 110));
        g.drawRoundRect(0, 0, w - 1, h - 1, h, h);
        g.setFont(getFont());
        FontMetrics metrics = g.getFontMetrics();
        g.setColor(color);
        g.drawString(text, (w - metrics.stringWidth(text)) / 2,
                (h - metrics.getHeight()) / 2 + metrics.getAscent());
        g.dispose();
    }
}
