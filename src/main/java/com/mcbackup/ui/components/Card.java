package com.mcbackup.ui.components;

import com.mcbackup.ui.theme.Palette;
import com.mcbackup.ui.theme.ThemeAware;
import com.mcbackup.ui.theme.ThemeManager;

import javax.swing.BorderFactory;
import javax.swing.JPanel;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.LayoutManager;
import java.awt.RenderingHints;

/**
 * 圆角卡片容器。所有页面内容都放在卡片里,统一圆角、描边与内边距。
 */
public class Card extends JPanel implements ThemeAware {

    private int cornerRadius = 14;
    private boolean shadow = true;

    public Card() {
        this(new java.awt.BorderLayout());
    }

    public Card(LayoutManager layout) {
        super(layout);
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(18, 18, 18, 18));
    }

    public void setCornerRadius(int cornerRadius) {
        this.cornerRadius = cornerRadius;
        repaint();
    }

    public void setShadow(boolean shadow) {
        this.shadow = shadow;
        repaint();
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
        int w = Math.max(0, getWidth() - 1);
        int h = Math.max(0, getHeight() - 3);
        if (shadow) {
            g.setColor(new Color(0, 0, 0, p.dark() ? 60 : 16));
            g.fillRoundRect(1, 2, w - 2, h - 1, cornerRadius, cornerRadius);
        }
        g.setColor(p.surface());
        g.fillRoundRect(0, 0, w, h, cornerRadius, cornerRadius);
        g.setColor(p.border());
        g.drawRoundRect(0, 0, w, h, cornerRadius, cornerRadius);
        g.dispose();
    }
}
