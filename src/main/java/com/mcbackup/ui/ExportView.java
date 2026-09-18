package com.mcbackup.ui;

import com.mcbackup.ui.components.Card;
import com.mcbackup.ui.components.EmptyState;
import com.mcbackup.ui.components.FlatButton;
import com.mcbackup.ui.theme.ThemeAware;

import javax.swing.JPanel;
import java.awt.BorderLayout;

/** 「导出」页面(第一阶段只做占位)。 */
public class ExportView extends JPanel implements ThemeAware {

    public ExportView() {
        setOpaque(false);
        setLayout(new BorderLayout());
        Card card = new Card(new BorderLayout());
        EmptyState state = new EmptyState();
        state.setTitle("导出功能将在第二阶段提供");
        state.setSubtitle("导出会把世界打包成标准 ZIP:解压后直接得到 level.dat、region、playerdata 等");
        FlatButton placeholder = new FlatButton("导出世界", FlatButton.Variant.PRIMARY);
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
