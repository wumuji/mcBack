package com.mcback.ui.components;

import com.mcback.ui.theme.Palette;
import com.mcback.ui.theme.ThemeAware;
import com.mcback.ui.theme.ThemeManager;
import com.mcback.ui.theme.UiFonts;

import javax.swing.JButton;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * 扁平圆角按钮(10px 圆角)。四种视觉变体,绘制全部自绘,不依赖系统 Look and Feel。
 */
public class FlatButton extends JButton implements ThemeAware {

    public enum Variant {
        /** 主操作(强调色实心)。 */
        PRIMARY,
        /** 次要操作(描边)。 */
        SECONDARY,
        /** 低干扰操作(无描边)。 */
        GHOST,
        /** 危险操作(红色描边)。 */
        DANGER
    }

    private static final int RADIUS = 10;

    private Variant variant;
    private boolean hovered;

    public FlatButton(String text) {
        this(text, Variant.SECONDARY);
    }

    public FlatButton(String text, Variant variant) {
        super(text == null ? "" : text);
        this.variant = variant;
        setFont(UiFonts.medium(13));
        setContentAreaFilled(false);
        setBorderPainted(false);
        setFocusPainted(false);
        setOpaque(false);
        setRolloverEnabled(false);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                hovered = true;
                repaint();
            }

            @Override
            public void mouseExited(MouseEvent e) {
                hovered = false;
                repaint();
            }
        });
    }

    public Variant variant() {
        return variant;
    }

    /** 允许在分段控件里切换选中态样式。 */
    public void setVariant(Variant variant) {
        this.variant = variant;
        repaint();
    }

    @Override
    public Dimension getPreferredSize() {
        FontMetrics metrics = getFontMetrics(getFont());
        int width = metrics.stringWidth(getText()) + 32;
        int height = Math.max(34, metrics.getHeight() + 16);
        return new Dimension(width, height);
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
        boolean enabled = isEnabled();
        boolean pressed = getModel().isArmed() && getModel().isPressed();

        Color fill = new Color(0, 0, 0, 0);
        Color textColor = p.text();
        Color outline = null;

        switch (variant) {
            case PRIMARY -> {
                if (!enabled) {
                    fill = p.alpha(p.textMuted(), 45);
                    textColor = p.textMuted();
                } else {
                    fill = pressed ? p.accentHover() : (hovered ? p.accentHover() : p.accent());
                    textColor = p.onAccent();
                }
            }
            case SECONDARY -> {
                fill = hovered && enabled ? p.surfaceHover() : p.alpha(p.surface(), 0);
                outline = enabled ? p.border() : p.alpha(p.border(), 120);
                textColor = enabled ? p.text() : p.textMuted();
            }
            case GHOST -> {
                fill = hovered && enabled ? p.surfaceHover() : p.alpha(p.surface(), 0);
                textColor = enabled ? (hovered ? p.text() : p.textMuted()) : p.textMuted();
            }
            case DANGER -> {
                fill = hovered && enabled ? p.alpha(p.danger(), 40) : p.alpha(p.danger(), 18);
                outline = p.alpha(p.danger(), enabled ? 160 : 80);
                textColor = enabled ? p.danger() : p.textMuted();
            }
        }

        if (fill.getAlpha() > 0) {
            g.setColor(fill);
            g.fillRoundRect(0, 0, w, h, RADIUS, RADIUS);
        }
        if (outline != null) {
            g.setColor(outline);
            g.drawRoundRect(0, 0, w - 1, h - 1, RADIUS, RADIUS);
        }

        g.setFont(getFont());
        FontMetrics metrics = g.getFontMetrics();
        String text = getText();
        int textX = Math.max(6, (w - metrics.stringWidth(text)) / 2);
        int textY = (h - metrics.getHeight()) / 2 + metrics.getAscent();
        if (pressed && enabled) {
            textY += 1;
        }
        g.setColor(textColor);
        g.drawString(text, textX, textY);
        g.dispose();
    }
}
