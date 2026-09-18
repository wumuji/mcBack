package com.mcbackup.ui.components;

import com.mcbackup.ui.theme.Palette;
import com.mcbackup.ui.theme.ThemeAware;
import com.mcbackup.ui.theme.ThemeManager;
import com.mcbackup.ui.theme.UiFonts;

import javax.swing.JComponent;
import javax.swing.Timer;
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
 * <p>选中高亮使用 120ms 的 ease-out 插值动画(Timer 驱动)。这是界面上仅有的两处动画之一,
 * 不做常驻动画,保证空闲时 CPU 接近 0。</p>
 */
public class NavItem extends JComponent implements ThemeAware {

    /** 图标种类(矢量绘制,见 {@link Icons})。 */
    public enum Glyph {
        WORLD, BOX, EXPORT, GEAR
    }

    private static final int DURATION_MS = 120;
    private static final int FRAME_MS = 16;

    private final Glyph glyph;
    private final String label;
    private final Runnable action;

    private boolean selected;
    private boolean hovered;
    private float highlight;
    private float target;
    private float animationFrom;
    private long animationStart;
    private Timer animation;

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
                animateTo(selected ? 1f : 0.45f);
            }

            @Override
            public void mouseExited(MouseEvent e) {
                hovered = false;
                animateTo(selected ? 1f : 0f);
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
            animateTo(selected ? 1f : (hovered ? 0.45f : 0f));
        }
    }

    private void animateTo(float newTarget) {
        target = newTarget;
        animationFrom = highlight;
        animationStart = System.currentTimeMillis();
        if (animation != null && animation.isRunning()) {
            return;
        }
        animation = new Timer(FRAME_MS, e -> {
            float elapsed = System.currentTimeMillis() - animationStart;
            float progress = Math.min(1f, elapsed / DURATION_MS);
            float eased = 1f - (1f - progress) * (1f - progress);
            highlight = animationFrom + (target - animationFrom) * eased;
            repaint();
            if (progress >= 1f) {
                highlight = target;
                ((Timer) e.getSource()).stop();
            }
        });
        animation.start();
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

        int fillAlpha = (int) (highlight * (selected ? 46 : 26));
        if (fillAlpha > 0) {
            g.setColor(p.alpha(p.accent(), fillAlpha));
            g.fillRoundRect(0, 0, w, h, 10, 10);
        }
        if (selected) {
            g.setColor(p.accent());
            g.fillRoundRect(0, h / 2 - 9, 3, 18, 3, 3);
        }

        boolean active = highlight > 0.5f;
        Color iconColor = active ? p.accent() : p.textMuted();
        int iconSize = 18;
        int iconX = 16;
        int iconY = (h - iconSize) / 2;
        switch (glyph) {
            case WORLD -> Icons.world(g, iconX, iconY, iconSize, iconColor);
            case BOX -> Icons.box(g, iconX, iconY, iconSize, iconColor);
            case EXPORT -> Icons.export(g, iconX, iconY, iconSize, iconColor);
            case GEAR -> Icons.gear(g, iconX, iconY, iconSize, iconColor);
        }

        g.setFont(getFont());
        FontMetrics metrics = g.getFontMetrics();
        g.setColor(active ? p.accent() : p.text());
        g.drawString(label, iconX + iconSize + 14, (h - metrics.getHeight()) / 2 + metrics.getAscent());
        g.dispose();
    }
}
