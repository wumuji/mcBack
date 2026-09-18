package com.mcbackup.ui.components;

import com.mcbackup.ui.theme.Palette;
import com.mcbackup.ui.theme.ThemeAware;
import com.mcbackup.ui.theme.ThemeManager;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/**
 * 空状态提示:标题 + 说明 + 若干操作按钮。
 *
 * <p>文本与按钮都可以动态替换,方便在「没有世界」「没有选中世界」等场景复用。</p>
 */
public class EmptyState extends JPanel implements ThemeAware {

    private final TLabel title = new TLabel("", TLabel.Role.H2);
    private final TLabel subtitle = new TLabel("", TLabel.Role.MUTED);
    private final JPanel actions = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 0));

    public EmptyState() {
        setOpaque(false);
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        actions.setOpaque(false);
        title.setAlignmentX(Component.CENTER_ALIGNMENT);
        subtitle.setAlignmentX(Component.CENTER_ALIGNMENT);
        actions.setAlignmentX(Component.CENTER_ALIGNMENT);
        title.setHorizontalAlignment(SwingConstants.CENTER);
        subtitle.setHorizontalAlignment(SwingConstants.CENTER);
        add(Box.createVerticalGlue());
        add(title);
        add(Box.createVerticalStrut(8));
        add(subtitle);
        add(Box.createVerticalStrut(18));
        add(actions);
        add(Box.createVerticalGlue());
        setPreferredSize(new Dimension(360, 240));
    }

    public void setTitle(String text) {
        title.setText(text);
        revalidate();
        repaint();
    }

    /** 说明文字。 */
    public void setSubtitle(String text) {
        subtitle.setText(text);
        revalidate();
        repaint();
    }

    public void setActions(JComponent... components) {
        actions.removeAll();
        for (JComponent component : components) {
            actions.add(component);
        }
        actions.revalidate();
        actions.repaint();
        revalidate();
        repaint();
    }

    @Override
    public void onThemeChanged() {
        invalidate();
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        Palette p = ThemeManager.palette();
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setColor(p.alpha(p.border(), 170));
        g2.setStroke(new java.awt.BasicStroke(1.4f, java.awt.BasicStroke.CAP_ROUND,
                java.awt.BasicStroke.JOIN_ROUND, 1f, new float[]{6f, 6f}, 0f));
        g2.drawRoundRect(1, 1, getWidth() - 3, getHeight() - 3, 14, 14);
        g2.dispose();
    }
}
