package com.mcbackup.ui;

import com.mcbackup.model.MinecraftWorld;
import com.mcbackup.model.ScanResult;
import com.mcbackup.ui.components.Card;
import com.mcbackup.ui.components.EmptyState;
import com.mcbackup.ui.components.FlatButton;
import com.mcbackup.ui.components.ScrollPaneStyler;
import com.mcbackup.ui.components.TLabel;
import com.mcbackup.ui.components.WorldListItem;
import com.mcbackup.ui.theme.ThemeAware;
import com.mcbackup.util.FileUtils;
import com.mcbackup.util.PathUtils;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 「世界」页面:左侧世界列表 + 右侧世界详情。
 *
 * <p>第一阶段只做浏览:备份按钮以禁用态占位,并明确提示功能将在第二阶段提供,
 * 避免用户以为按钮坏了。</p>
 */
public class WorldView extends JPanel implements ThemeAware {

    private static final String CARD_EMPTY = "empty";
    private static final String CARD_DETAIL = "detail";

    private final JPanel listItems = new JPanel();
    private final TLabel listCount = new TLabel("", TLabel.Role.MUTED);
    private final Card detailCard = new Card(new BorderLayout());
    private final JPanel detailBody = new JPanel(new BorderLayout());
    private final Card emptyCard = new Card(new BorderLayout());
    private final EmptyState emptyState = new EmptyState();
    private final CardLayout detailLayout = new CardLayout();
    private final JPanel detailHost = new JPanel(detailLayout);

    private final Map<String, WorldListItem> itemsByKey = new LinkedHashMap<>();
    private final Runnable onAddDirectory;
    private final Runnable onRescan;
    private final Consumer<MinecraftWorld> onBackup;

    private ScanResult result = ScanResult.empty();
    private MinecraftWorld selected;
    private Consumer<String> statusSink = text -> {
    };
    private Consumer<MinecraftWorld> selectionSink = world -> {
    };

    public WorldView(Runnable onAddDirectory, Runnable onRescan, Consumer<MinecraftWorld> onBackup) {
        this.onAddDirectory = onAddDirectory;
        this.onRescan = onRescan;
        this.onBackup = onBackup;
        setOpaque(false);
        setLayout(new BorderLayout(16, 0));

        detailHost.setOpaque(false);
        detailHost.add(emptyCard, CARD_EMPTY);

        // 必须显式透明:JPanel 默认不透明,若保留构造时的 UIManager 背景色,
        // 切换主题后会出现「深色残留区块」。
        detailBody.setOpaque(false);
        detailCard.setLayout(new BorderLayout());
        detailCard.add(detailBody, BorderLayout.CENTER);
        detailHost.add(detailCard, CARD_DETAIL);

        add(buildListCard(), BorderLayout.WEST);
        add(detailHost, BorderLayout.CENTER);

        showEmpty("还没有发现任何 Minecraft 世界",
                "点击「重新扫描」或手动添加存档目录",
                buildAddButton(), buildRescanButton());
    }

    private JComponent buildListCard() {
        Card card = new Card(new BorderLayout(0, 12));
        card.setPreferredSize(new Dimension(392, 0));
        card.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 10));

        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        TLabel title = new TLabel("世界列表", TLabel.Role.H2);
        header.add(title, BorderLayout.WEST);
        header.add(listCount, BorderLayout.EAST);
        card.add(header, BorderLayout.NORTH);

        listItems.setOpaque(false);
        listItems.setLayout(new BoxLayout(listItems, BoxLayout.Y_AXIS));
        JScrollPane scroll = new JScrollPane(listItems,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUnitIncrement(18);
        ScrollPaneStyler.apply(scroll);
        card.add(scroll, BorderLayout.CENTER);
        return card;
    }

    /** 更新扫描结果并重建列表。 */
    public void setScanResult(ScanResult newResult) {
        this.result = newResult == null ? ScanResult.empty() : newResult;
        rebuildList();
        listCount.setText(result.worlds().size() + " 个");
        if (result.worlds().isEmpty()) {
            showEmpty("还没有发现任何 Minecraft 世界",
                    "已扫描 " + result.rootCount() + " 个候选目录,可以手动添加存档目录",
                    buildAddButton(), buildRescanButton());
            return;
        }
        MinecraftWorld toSelect = selected == null ? result.worlds().get(0) : findSelected();
        select(toSelect);
    }

    private MinecraftWorld findSelected() {
        if (selected == null) {
            return null;
        }
        for (MinecraftWorld world : result.worlds()) {
            if (world.key().equals(selected.key())) {
                return world;
            }
        }
        return result.worlds().get(0);
    }

    private void rebuildList() {
        listItems.removeAll();
        itemsByKey.clear();
        for (MinecraftWorld world : result.worlds()) {
            WorldListItem item = new WorldListItem(world, this::select);
            item.setAlignmentX(LEFT_ALIGNMENT);
            itemsByKey.put(world.key(), item);
            listItems.add(item);
            listItems.add(Box.createVerticalStrut(4));
        }
        listItems.revalidate();
        listItems.repaint();
        ScrollPaneStyler.applyRecursively(this);
    }

    /** 选中某个世界并显示详情。 */
    public void select(MinecraftWorld world) {
        if (world == null) {
            return;
        }
        selected = world;
        itemsByKey.forEach((key, item) -> item.setSelected(key.equals(world.key())));
        buildDetail(world);
        detailLayout.show(detailHost, CARD_DETAIL);
        selectionSink.accept(world);
    }

    /** 扫描过程中的占位提示。 */
    public void setScanning(String label) {
        if (result.worlds().isEmpty()) {
            showEmpty("正在扫描存档目录…", label, buildRescanButton());
        }
    }

    public void setStatusSink(Consumer<String> sink) {
        this.statusSink = sink == null ? text -> {
        } : sink;
    }

    /** 选中世界变化时通知外部(用于同步「备份」「导出」两个页面)。 */
    public void setSelectionSink(Consumer<MinecraftWorld> sink) {
        this.selectionSink = sink == null ? world -> {
        } : sink;
    }

    public MinecraftWorld selectedWorld() {
        return selected;
    }

    private void buildDetail(MinecraftWorld world) {
        detailBody.removeAll();
        detailBody.setBorder(BorderFactory.createEmptyBorder(4, 6, 2, 6));

        JPanel header = new JPanel();
        header.setOpaque(false);
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        TLabel name = new TLabel(world.displayName(), TLabel.Role.H1);
        TLabel path = new TLabel(PathUtils.toDisplayPath(world.worldDir()), TLabel.Role.MUTED);
        name.setAlignmentX(LEFT_ALIGNMENT);
        path.setAlignmentX(LEFT_ALIGNMENT);
        header.add(name);
        header.add(Box.createVerticalStrut(6));
        header.add(path);
        header.add(Box.createVerticalStrut(16));
        detailBody.add(header, BorderLayout.NORTH);

        detailBody.add(buildFields(world), BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        actions.setOpaque(false);
        actions.setBorder(BorderFactory.createEmptyBorder(16, 0, 0, 0));

        FlatButton backup = new FlatButton("立即备份", FlatButton.Variant.PRIMARY);
        backup.setToolTipText("把这个世界完整备份为 ZIP");
        backup.addActionListener(e -> onBackup.accept(world));
        FlatButton openDir = new FlatButton("打开世界目录");
        openDir.addActionListener(e -> {
            if (!FileUtils.openInFileBrowser(world.worldDir())) {
                statusSink.accept("无法打开目录:" + PathUtils.toDisplayPath(world.worldDir()));
            }
        });
        FlatButton copyPath = new FlatButton("复制路径", FlatButton.Variant.GHOST);
        copyPath.addActionListener(e -> {
            boolean ok = FileUtils.copyToClipboard(PathUtils.toDisplayPath(world.worldDir()));
            statusSink.accept(ok ? "已复制世界路径" : "复制路径失败,请检查剪贴板权限");
        });
        actions.add(backup);
        actions.add(openDir);
        actions.add(copyPath);
        detailBody.add(actions, BorderLayout.SOUTH);

        detailBody.revalidate();
        detailBody.repaint();
    }

    private JComponent buildFields(MinecraftWorld world) {
        JPanel fields = new JPanel(new GridBagLayout());
        fields.setOpaque(false);
        List<String[]> rows = WorldText.detailFields(world);
        int row = 0;
        for (String[] pair : rows) {
            GridBagConstraints labelConstraints = new GridBagConstraints();
            labelConstraints.gridx = 0;
            labelConstraints.gridy = row;
            labelConstraints.anchor = GridBagConstraints.WEST;
            labelConstraints.insets = new Insets(0, 0, 12, 18);
            TLabel label = new TLabel(pair[0], TLabel.Role.MUTED);
            fields.add(label, labelConstraints);

            GridBagConstraints valueConstraints = new GridBagConstraints();
            valueConstraints.gridx = 1;
            valueConstraints.gridy = row;
            valueConstraints.anchor = GridBagConstraints.WEST;
            valueConstraints.weightx = 1;
            valueConstraints.fill = GridBagConstraints.HORIZONTAL;
            valueConstraints.insets = new Insets(0, 0, 12, 0);
            TLabel value = new TLabel(pair[1], TLabel.Role.BODY);
            fields.add(value, valueConstraints);
            row++;
        }
        GridBagConstraints filler = new GridBagConstraints();
        filler.gridx = 0;
        filler.gridy = row;
        filler.weighty = 1;
        filler.fill = GridBagConstraints.VERTICAL;
        fields.add(Box.createVerticalGlue(), filler);
        return fields;
    }

    private void showEmpty(String title, String subtitle, JComponent... actions) {
        emptyState.setTitle(title);
        emptyState.setSubtitle(subtitle);
        emptyState.setActions(actions);
        emptyCard.removeAll();
        emptyCard.add(emptyState, BorderLayout.CENTER);
        emptyCard.revalidate();
        emptyCard.repaint();
        detailLayout.show(detailHost, CARD_EMPTY);
    }

    private JComponent buildAddButton() {
        FlatButton button = new FlatButton("添加存档目录", FlatButton.Variant.PRIMARY);
        button.addActionListener(e -> onAddDirectory.run());
        return button;
    }

    private JComponent buildRescanButton() {
        FlatButton button = new FlatButton("重新扫描");
        button.addActionListener(e -> onRescan.run());
        return button;
    }

    /** 世界列表项(测试与界面刷新时可用)。 */
    List<WorldListItem> listItemComponents() {
        return new ArrayList<>(itemsByKey.values());
    }

    @Override
    public void onThemeChanged() {
        invalidate();
        repaint();
    }
}
