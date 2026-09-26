package com.mcbackup.ui.components;

import com.mcbackup.ui.theme.Palette;
import com.mcbackup.ui.theme.ThemeAware;
import com.mcbackup.ui.theme.ThemeManager;
import com.mcbackup.ui.theme.UiFonts;

import javax.swing.JComponent;
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
 * 左侧导航项。
 *
 * <p>刻意不做动画:选中态直接切换,省掉一个持续触发的 Timer,空闲时 CPU 保持 0。</p>
 */
public class NavItem extends JComponent implements ThemeAware {

    /** 图标种类(矢量绘制,见 {@link Icons})。 */
    public enum Glyph {
        WORLD, BOX, EXPORT, GEAR, LIST
    }

    private final Glyph glyph;
    private final String label;
    private final Runnable action;

    private boolean selected;
    private boolean hovered;

    public NavItem(Glyph glyph, String label, Runnable action) {
        this.glyph = glyph;
        this.label = label;
        this.action = action;
        setFont(UiFonts.medium(13));
        setOpaque(false);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setPreferredSize(new Dimension(190, 40));
        setMinimumSize(new Dimension(120, 40));
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

            @Override
            public void mouseClicked(MouseEvent e) {
                if (NavItem.this.action != null) {
                    NavItem.this.action.run();
                }
            }
        });
    }

    public boolean isSelected() {
        return selected;
    }

    public void setSelected(boolean value) {
        if (selected != value) {
            selected = value;
            repaint();
        }
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

        if (selected || hovered) {
            g.setColor(p.alpha(p.accent(), selected ? 42 : 22));
            g.fillRoundRect(0, 0, w, h, 10, 10);
        }
        if (selected) {
            g.setColor(p.accent());
            g.fillRoundRect(0, h / 2 - 9, 3, 18, 3, 3);
        }

        boolean active = selected;
        Color iconColor = active ? p.accent() : p.textMuted();
        int iconSize = 18;
        int iconX = 16;
        int iconY = (h - iconSize) / 2;
        switch (glyph) {
            case WORLD -> Icons.world(g, iconX, iconY, iconSize, iconColor);
            case BOX -> Icons.box(g, iconX, iconY, iconSize, iconColor);
            case EXPORT -> Icons.export(g, iconX, iconY, iconSize, iconColor);
            case GEAR -> Icons.gear(g, iconX, iconY, iconSize, iconColor);
            case LIST -> Icons.list(g, iconX, iconY, iconSize, iconColor);
        }

        g.setFont(getFont());
        FontMetrics metrics = g.getFontMetrics();
        g.setColor(active ? p.accent() : p.text());
        g.drawString(label, iconX + iconSize + 14, (h - metrics.getHeight()) / 2 + metrics.getAscent());
        g.dispose();
    }
}
