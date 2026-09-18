package com.mcbackup.ui.components;

import com.mcbackup.model.MinecraftWorld;
import com.mcbackup.ui.WorldText;
import com.mcbackup.ui.theme.Palette;
import com.mcbackup.ui.theme.ThemeAware;
import com.mcbackup.ui.theme.ThemeManager;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.Consumer;

/**
 * 世界列表中的一行(计划规定的 74px 行高,含两行文字与状态胶囊)。
 *
 * <p>整行自绘高亮,不使用 JTable,避免 Swing 表格渲染与自绘主题互相干扰。</p>
 */
public class WorldListItem extends JPanel implements ThemeAware {

    private static final int ROW_HEIGHT = 64;

    private final MinecraftWorld world;
    private final Pill statusPill;
    private final Pill issuePill;

    private boolean selected;
    private boolean hovered;

    public WorldListItem(MinecraftWorld world, Consumer<MinecraftWorld> onClick) {
        this.world = world;
        setOpaque(false);
        setLayout(new BorderLayout(12, 0));
        setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 12));
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        TLabel nameLabel = new TLabel(world.displayName(), TLabel.Role.TITLE);
        TLabel metaLabel = new TLabel(WorldText.listSubtitle(world), TLabel.Role.MUTED);

        JPanel texts = new JPanel();
        texts.setOpaque(false);
        texts.setLayout(new BoxLayout(texts, BoxLayout.Y_AXIS));
        nameLabel.setAlignmentX(LEFT_ALIGNMENT);
        metaLabel.setAlignmentX(LEFT_ALIGNMENT);
        texts.add(nameLabel);
        texts.add(Box.createVerticalStrut(4));
        texts.add(metaLabel);
        add(texts, BorderLayout.CENTER);

        JPanel pills = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        pills.setOpaque(false);
        issuePill = new Pill("结构异常", ThemeManager.palette().warning());
        issuePill.setVisible(world.issue() != null);
        statusPill = new Pill(world.runningText(), statusColor(world));
        pills.add(issuePill);
        pills.add(statusPill);
        add(pills, BorderLayout.EAST);

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
                if (onClick != null) {
                    onClick.accept(WorldListItem.this.world);
                }
            }
        });
    }

    public MinecraftWorld world() {
        return world;
    }

    public void setSelected(boolean selected) {
        this.selected = selected;
        repaint();
    }

    public static Color statusColor(MinecraftWorld world) {
        Palette p = ThemeManager.palette();
        return switch (world.lockProbe()) {
            case LOCKED -> p.warning();
            case FREE -> p.accent();
            case MISSING, UNKNOWN -> p.textMuted();
        };
    }

    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, ROW_HEIGHT);
    }

    @Override
    public Dimension getPreferredSize() {
        Dimension base = super.getPreferredSize();
        return new Dimension(base.width, Math.max(ROW_HEIGHT, base.height));
    }

    @Override
    public void onThemeChanged() {
        statusPill.setColor(statusColor(world));
        repaint();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Palette p = ThemeManager.palette();
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int w = getWidth();
        int h = getHeight();
        if (selected) {
            g.setColor(p.selectionFill());
            g.fillRoundRect(0, 0, w, h, 12, 12);
            g.setColor(p.alpha(p.accent(), 150));
            g.drawRoundRect(0, 0, w - 1, h - 1, 12, 12);
            g.setColor(p.accent());
            g.fillRoundRect(0, h / 2 - 14, 3, 28, 3, 3);
        } else if (hovered) {
            g.setColor(p.surfaceHover());
            g.fillRoundRect(0, 0, w, h, 12, 12);
        }
        g.dispose();
    }
}
