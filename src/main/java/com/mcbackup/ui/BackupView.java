package com.mcbackup.ui;

import com.mcbackup.ui.components.Card;
import com.mcbackup.ui.components.EmptyState;
import com.mcbackup.ui.components.FlatButton;
import com.mcbackup.ui.theme.ThemeAware;

import javax.swing.JPanel;
import java.awt.BorderLayout;

/** 「备份」页面(第一阶段只做占位,不放任何假数据)。 */
public class BackupView extends JPanel implements ThemeAware {

    public BackupView() {
        setOpaque(false);
        setLayout(new BorderLayout());
        Card card = new Card(new BorderLayout());
        EmptyState state = new EmptyState();
        state.setTitle("备份功能将在第二阶段提供");
        state.setSubtitle("第二阶段:手动备份、流式 ZIP 压缩、完整性校验、自动备份与保留策略");
        FlatButton placeholder = new FlatButton("立即备份", FlatButton.Variant.PRIMARY);
        placeholder.setEnabled(false);
        placeholder.setToolTipText("第二阶段提供");
        state.setActions(placeholder);
        card.add(state, BorderLayout.CENTER);
        add(card, BorderLayout.CENTER);
    }

    @Override
    public void onThemeChanged() {
        repaint();
    }
}
