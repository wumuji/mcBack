package com.mcbackup.ui.components;

import com.mcbackup.ui.theme.Palette;
import com.mcbackup.ui.theme.ThemeAware;
import com.mcbackup.ui.theme.ThemeManager;

import javax.swing.BorderFactory;
import javax.swing.JPanel;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.LayoutManager;
import java.awt.RenderingHints;

/**
 * 卡片容器。刻意保持极简:只有 1px 描边 + 小圆角,不画阴影
 * (阴影需要额外的半透明绘制,既费一点性能,也让界面显得更花)。
 */
public class Card extends JPanel implements ThemeAware {

    private int cornerRadius = 10;

    public Card() {
        this(new java.awt.BorderLayout());
    }

    public Card(LayoutManager layout) {
        super(layout);
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
    }

    public void setCornerRadius(int cornerRadius) {
        this.cornerRadius = cornerRadius;
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
        int h = Math.max(0, getHeight() - 1);
        g.setColor(p.surface());
        g.fillRoundRect(0, 0, w, h, cornerRadius, cornerRadius);
        g.setColor(p.border());
        g.drawRoundRect(0, 0, w, h, cornerRadius, cornerRadius);
        g.dispose();
    }
}
