package com.mcbackup.ui;

import com.mcbackup.ui.components.FlatButton;
import com.mcbackup.ui.components.TLabel;
import com.mcbackup.ui.theme.Palette;
import com.mcbackup.ui.theme.ThemeAware;
import com.mcbackup.ui.theme.ThemeManager;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;

/** 顶栏:页面标题、当前状态文字,以及「重新扫描」。刻意只保留必要控件。 */
public class TopBar extends JPanel implements ThemeAware {

    private final TLabel titleLabel = new TLabel("世界", TLabel.Role.H1);
    private final TLabel statusLabel = new TLabel("准备扫描…", TLabel.Role.MUTED);
    private final FlatButton rescanButton;

    public TopBar(Runnable onRescan) {
        setOpaque(false);
        setLayout(new BorderLayout());
        setBorder(BorderFactory.createEmptyBorder(20, 24, 14, 24));

        JPanel texts = new JPanel();
        texts.setOpaque(false);
        texts.setLayout(new BoxLayout(texts, BoxLayout.Y_AXIS));
        texts.add(titleLabel);
        texts.add(Box.createVerticalStrut(6));
        texts.add(statusLabel);
        add(texts, BorderLayout.WEST);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setOpaque(false);
        buttons.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
        rescanButton = new FlatButton("重新扫描");
        rescanButton.setToolTipText("重新扫描所有存档目录");
        rescanButton.addActionListener(e -> onRescan.run());
        buttons.add(rescanButton);
        add(buttons, BorderLayout.EAST);
    }

    public void setTitle(String text) {
        titleLabel.setText(text);
    }

    public void setStatus(String text) {
        statusLabel.setText(text);
    }

    public void setRescanEnabled(boolean enabled) {
        rescanButton.setEnabled(enabled);
    }

    @Override
    public void onThemeChanged() {
        repaint();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Palette p = ThemeManager.palette();
        Graphics2D g = (Graphics2D) graphics.create();
        g.setColor(p.background());
        g.fillRect(0, 0, getWidth(), getHeight());
        g.setColor(p.border());
        g.drawLine(0, getHeight() - 1, getWidth(), getHeight() - 1);
        g.dispose();
    }
}
