package com.mcbackup.ui;

import com.mcbackup.App;
import com.mcbackup.model.AppSettings;
import com.mcbackup.model.ScanResult;
import com.mcbackup.model.Theme;
import com.mcbackup.service.WorldRoot;
import com.mcbackup.ui.components.Card;
import com.mcbackup.ui.components.FlatButton;
import com.mcbackup.ui.components.Pill;
import com.mcbackup.ui.components.ScrollPaneStyler;
import com.mcbackup.ui.components.TLabel;
import com.mcbackup.ui.theme.ThemeAware;
import com.mcbackup.ui.theme.ThemeManager;
import com.mcbackup.util.FileUtils;
import com.mcbackup.util.PathUtils;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 「设置」页面。
 *
 * <p>第一阶段只提供真正生效的设置项:主题、存档目录管理、日志目录、关于信息。
 * 备份间隔、保留数量、托盘等留到后续阶段,避免出现点了没反应的开关。</p>
 */
public class SettingsView extends JPanel implements ThemeAware {

    /** 页面回调。 */
    public interface Callbacks {

        void onThemeSelected(Theme theme);

        void onAddDirectory();

        void onRemoveDirectory(String path);

        void onRescan();

        void onBackupDirChanged(String path);

        void onAutoBackupChanged(boolean enabled);

        void onBackupIntervalChanged(int minutes);

        void onRetainCountChanged(int count);

        void onCompressionChanged(boolean fast);
    }

    private final AppSettings settings;
    private final Callbacks callbacks;
    private final Path logsDir;

    private final Map<Theme, FlatButton> themeButtons = new EnumMap<>(Theme.class);
    private final JPanel rootList = new JPanel();
    private final JPanel issueBox = new JPanel();
    private final TLabel rootSummary = new TLabel("", TLabel.Role.MUTED);
    private final TLabel backupDirLabel = new TLabel("", TLabel.Role.BODY);
    private final TLabel autoBackupLabel = new TLabel("", TLabel.Role.BODY);
    private final FlatButton autoBackupButton = new FlatButton("开启自动备份");
    private final JComboBox<Integer> intervalCombo = new JComboBox<>(new Integer[]{5, 10, 15, 30, 60, 120});
    private final JComboBox<String> retainCombo = new JComboBox<>(
            new String[]{"5 份", "10 份", "20 份", "50 份", "100 份", "不限制"});
    private final JComboBox<String> compressionCombo = new JComboBox<>(
            new String[]{"快速(区域文件不重复压缩)", "体积优先(全部压缩)"});
    private ScanResult lastResult = ScanResult.empty();
    /** 程序化更新控件时抑制回调,避免出现循环触发。 */
    private boolean updating;

    public SettingsView(AppSettings settings, Callbacks callbacks, Path logsDir) {
        this.settings = settings;
        this.callbacks = callbacks;
        this.logsDir = logsDir;
        setOpaque(false);
        setLayout(new BorderLayout());

        JPanel content = new JPanel();
        content.setOpaque(false);
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.add(appearanceCard());
        content.add(Box.createVerticalStrut(16));
        content.add(backupCard());
        content.add(Box.createVerticalStrut(16));
        content.add(directoryCard());
        content.add(Box.createVerticalStrut(16));
        content.add(logCard());
        content.add(Box.createVerticalStrut(16));
        content.add(aboutCard());
        content.add(Box.createVerticalGlue());

        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setOpaque(false);
        wrapper.add(content, BorderLayout.NORTH);

        JScrollPane scroll = new JScrollPane(wrapper);
        ScrollPaneStyler.apply(scroll);
        add(scroll, BorderLayout.CENTER);

        refreshThemeButtons();
        refreshBackupControls();
    }

    // ------------------------------------------------------------------
    // 备份
    // ------------------------------------------------------------------

    private JComponent backupCard() {
        Card card = new Card(new BorderLayout(0, 14));
        card.add(sectionTitle("备份"), BorderLayout.NORTH);

        JPanel column = new JPanel();
        column.setOpaque(false);
        column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));

        // 备份位置
        JPanel dirRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        dirRow.setOpaque(false);
        dirRow.setAlignmentX(LEFT_ALIGNMENT);
        backupDirLabel.setPreferredSize(new Dimension(520, 20));
        FlatButton changeDir = new FlatButton("更改目录");
        changeDir.addActionListener(e -> chooseBackupDir());
        dirRow.add(new TLabel("位置:", TLabel.Role.MUTED));
        dirRow.add(backupDirLabel);
        dirRow.add(changeDir);
        column.add(dirRow);
        column.add(Box.createVerticalStrut(12));

        // 自动备份
        JPanel autoRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        autoRow.setOpaque(false);
        autoRow.setAlignmentX(LEFT_ALIGNMENT);
        autoBackupButton.addActionListener(e -> {
            boolean enabled = !settings.isAutoBackupEnabled();
            settings.setAutoBackupEnabled(enabled);
            refreshBackupControls();
            callbacks.onAutoBackupChanged(enabled);
        });
        autoRow.add(new TLabel("自动备份:", TLabel.Role.MUTED));
        autoRow.add(autoBackupLabel);
        autoRow.add(autoBackupButton);
        column.add(autoRow);
        column.add(Box.createVerticalStrut(12));

        // 间隔与保留
        JPanel comboRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        comboRow.setOpaque(false);
        comboRow.setAlignmentX(LEFT_ALIGNMENT);
        intervalCombo.addActionListener(e -> {
            if (updating || intervalCombo.getSelectedItem() == null) {
                return;
            }
            int minutes = (Integer) intervalCombo.getSelectedItem();
            settings.setAutoBackupIntervalMinutes(minutes);
            callbacks.onBackupIntervalChanged(minutes);
        });
        retainCombo.addActionListener(e -> {
            if (updating || retainCombo.getSelectedIndex() < 0) {
                return;
            }
            int count = retainCountAt(retainCombo.getSelectedIndex());
            settings.setRetainCount(count);
            callbacks.onRetainCountChanged(count);
        });
        compressionCombo.addActionListener(e -> {
            if (updating || compressionCombo.getSelectedIndex() < 0) {
                return;
            }
            boolean fast = compressionCombo.getSelectedIndex() == 0;
            settings.setFastBackup(fast);
            callbacks.onCompressionChanged(fast);
        });
        comboRow.add(new TLabel("间隔:", TLabel.Role.MUTED));
        comboRow.add(intervalCombo);
        comboRow.add(Box.createHorizontalStrut(16));
        comboRow.add(new TLabel("保留:", TLabel.Role.MUTED));
        comboRow.add(retainCombo);
        column.add(comboRow);
        column.add(Box.createVerticalStrut(8));

        JPanel compressionRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        compressionRow.setOpaque(false);
        compressionRow.setAlignmentX(LEFT_ALIGNMENT);
        compressionRow.add(new TLabel("压缩:", TLabel.Role.MUTED));
        compressionRow.add(compressionCombo);
        compressionCombo.setToolTipText("<html>快速:大区域文件不重复压缩,备份快约 8 倍,CPU 占用低,体积可能大一些<br>"
                + "体积优先:全部重新压缩,备份更小,但耗时明显更长</html>");
        column.add(compressionRow);
        column.add(Box.createVerticalStrut(8));

        TLabel hint = new TLabel("自动备份只在世界确实发生变化时才复制文件;世界没变会直接跳过。", TLabel.Role.MUTED);
        hint.setAlignmentX(LEFT_ALIGNMENT);
        column.add(hint);

        card.add(column, BorderLayout.CENTER);
        return card;
    }

    /** 用当前配置刷新备份相关控件。 */
    public final void refreshBackupControls() {
        updating = true;
        try {
            backupDirLabel.setText(PathUtils.toDisplayPath(settings.backupDirPath()));
            boolean enabled = settings.isAutoBackupEnabled();
            autoBackupLabel.setText(enabled ? "已开启" : "已关闭");
            autoBackupButton.setText(enabled ? "关闭自动备份" : "开启自动备份");
            intervalCombo.setSelectedItem(nearestInterval(settings.getAutoBackupIntervalMinutes()));
            intervalCombo.setEnabled(enabled);
            retainCombo.setSelectedIndex(indexForRetainCount(settings.getRetainCount()));
            compressionCombo.setSelectedIndex(settings.isFastBackup() ? 0 : 1);
        } finally {
            updating = false;
        }
        repaint();
    }

    private static int retainCountAt(int index) {
        return switch (index) {
            case 0 -> 5;
            case 1 -> 10;
            case 2 -> 20;
            case 3 -> 50;
            case 4 -> 100;
            default -> 0;
        };
    }

    private static int indexForRetainCount(int count) {
        return switch (count) {
            case 5 -> 0;
            case 10 -> 1;
            case 20 -> 2;
            case 50 -> 3;
            case 100 -> 4;
            default -> count <= 0 ? 5 : 2;
        };
    }

    private static int nearestInterval(int minutes) {
        int[] values = {5, 10, 15, 30, 60, 120};
        int best = values[0];
        int bestDiff = Integer.MAX_VALUE;
        for (int value : values) {
            int diff = Math.abs(value - minutes);
            if (diff < bestDiff) {
                bestDiff = diff;
                best = value;
            }
        }
        return best;
    }

    private void chooseBackupDir() {
        javax.swing.JFileChooser chooser = new javax.swing.JFileChooser();
        chooser.setDialogTitle("选择备份目录");
        chooser.setFileSelectionMode(javax.swing.JFileChooser.DIRECTORIES_ONLY);
        chooser.setAcceptAllFileFilterUsed(false);
        if (chooser.showOpenDialog(this) != javax.swing.JFileChooser.APPROVE_OPTION) {
            return;
        }
        java.io.File selected = chooser.getSelectedFile();
        if (selected == null) {
            return;
        }
        String path = selected.getAbsolutePath();
        settings.setBackupDir(path);
        refreshBackupControls();
        callbacks.onBackupDirChanged(path);
    }

    // ------------------------------------------------------------------
    // 外观
    // ------------------------------------------------------------------

    private JComponent appearanceCard() {
        Card card = new Card(new BorderLayout(0, 14));
        card.add(sectionTitle("外观"), BorderLayout.NORTH);

        JPanel column = new JPanel();
        column.setOpaque(false);
        column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
        TLabel hint = new TLabel("「跟随系统」会读取 Windows 的应用模式设置,读取失败时按深色显示。", TLabel.Role.MUTED);
        hint.setAlignmentX(LEFT_ALIGNMENT);
        column.add(hint);
        column.add(Box.createVerticalStrut(12));

        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        row.setOpaque(false);
        row.setAlignmentX(LEFT_ALIGNMENT);
        for (Theme theme : new Theme[]{Theme.FOLLOW_SYSTEM, Theme.LIGHT, Theme.DARK}) {
            FlatButton button = new FlatButton(theme.displayName());
            button.addActionListener(e -> {
                callbacks.onThemeSelected(theme);
                refreshThemeButtons();
            });
            themeButtons.put(theme, button);
            row.add(button);
        }
        column.add(row);
        card.add(column, BorderLayout.CENTER);
        return card;
    }

    /** 重新标记主题按钮的选中态。 */
    public void refreshThemeButtons() {
        Theme current = ThemeManager.option();
        themeButtons.forEach((theme, button) ->
                button.setVariant(theme == current ? FlatButton.Variant.PRIMARY : FlatButton.Variant.SECONDARY));
    }

    // ------------------------------------------------------------------
    // 存档目录
    // ------------------------------------------------------------------

    private JComponent directoryCard() {
        Card card = new Card(new BorderLayout(0, 14));

        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.add(sectionTitle("存档目录"), BorderLayout.WEST);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setOpaque(false);
        FlatButton add = new FlatButton("添加目录", FlatButton.Variant.PRIMARY);
        add.addActionListener(e -> callbacks.onAddDirectory());
        FlatButton rescan = new FlatButton("重新扫描");
        rescan.addActionListener(e -> callbacks.onRescan());
        buttons.add(rescan);
        buttons.add(add);
        header.add(buttons, BorderLayout.EAST);
        card.add(header, BorderLayout.NORTH);

        JPanel column = new JPanel();
        column.setOpaque(false);
        column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
        TLabel hint = new TLabel("自动检测覆盖官方启动器、版本隔离实例与常见启动器;手动添加的目录会保存到配置。",
                TLabel.Role.MUTED);
        hint.setAlignmentX(LEFT_ALIGNMENT);
        column.add(hint);
        column.add(Box.createVerticalStrut(10));
        rootSummary.setAlignmentX(LEFT_ALIGNMENT);
        column.add(rootSummary);
        column.add(Box.createVerticalStrut(12));

        rootList.setOpaque(false);
        rootList.setLayout(new BoxLayout(rootList, BoxLayout.Y_AXIS));
        rootList.setAlignmentX(LEFT_ALIGNMENT);
        column.add(rootList);

        issueBox.setOpaque(false);
        issueBox.setLayout(new BoxLayout(issueBox, BoxLayout.Y_AXIS));
        issueBox.setAlignmentX(LEFT_ALIGNMENT);
        column.add(issueBox);

        card.add(column, BorderLayout.CENTER);
        return card;
    }

    /** 更新目录列表与问题提示。 */
    public void setScanResult(ScanResult result) {
        this.lastResult = result == null ? ScanResult.empty() : result;
        rootList.removeAll();
        Map<String, Integer> counts = new LinkedHashMap<>(lastResult.worldCountByRoot());
        for (WorldRoot root : lastResult.roots()) {
            rootList.add(buildRootRow(root, counts.getOrDefault(root.savesDir().toString(), 0)));
            rootList.add(Box.createVerticalStrut(8));
        }
        if (lastResult.roots().isEmpty()) {
            TLabel empty = new TLabel("没有检测到任何存档目录。", TLabel.Role.MUTED);
            empty.setAlignmentX(LEFT_ALIGNMENT);
            rootList.add(empty);
        }
        rootSummary.setText("共 " + lastResult.roots().size() + " 个目录,发现 "
                + lastResult.worlds().size() + " 个世界");

        issueBox.removeAll();
        if (!lastResult.issues().isEmpty()) {
            issueBox.add(Box.createVerticalStrut(6));
            TLabel issueTitle = new TLabel("扫描提示(" + lastResult.issues().size() + " 条)", TLabel.Role.MUTED);
            issueTitle.setAlignmentX(LEFT_ALIGNMENT);
            issueBox.add(issueTitle);
            lastResult.issues().stream().limit(5).forEach(issue -> {
                TLabel line = new TLabel("· " + issue.message() + " — " + issue.path(), TLabel.Role.MUTED);
                line.setAlignmentX(LEFT_ALIGNMENT);
                issueBox.add(line);
            });
        }
        rootList.revalidate();
        rootList.repaint();
        issueBox.revalidate();
        issueBox.repaint();
    }

    private JComponent buildRootRow(WorldRoot root, int worldCount) {
        JPanel row = new JPanel(new BorderLayout(12, 0));
        row.setOpaque(false);
        row.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 56));

        JPanel texts = new JPanel();
        texts.setOpaque(false);
        texts.setLayout(new BoxLayout(texts, BoxLayout.Y_AXIS));
        TLabel path = new TLabel(root.displayPath(), TLabel.Role.BODY);
        TLabel meta = new TLabel(root.label(), TLabel.Role.MUTED);
        path.setAlignmentX(LEFT_ALIGNMENT);
        meta.setAlignmentX(LEFT_ALIGNMENT);
        texts.add(path);
        texts.add(Box.createVerticalStrut(2));
        texts.add(meta);
        row.add(texts, BorderLayout.CENTER);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        right.setOpaque(false);
        right.add(new Pill(worldCount + " 个世界", ThemeManager.palette().accent()));
        if (root.kind() == com.mcbackup.model.LocationKind.MANUAL) {
            FlatButton remove = new FlatButton("移除", FlatButton.Variant.DANGER);
            remove.addActionListener(e -> callbacks.onRemoveDirectory(root.savesDir().toString()));
            right.add(remove);
        }
        row.add(right, BorderLayout.EAST);
        return row;
    }

    // ------------------------------------------------------------------
    // 日志与关于
    // ------------------------------------------------------------------

    private JComponent logCard() {
        Card card = new Card(new BorderLayout(0, 14));
        card.add(sectionTitle("日志"), BorderLayout.NORTH);

        JPanel column = new JPanel();
        column.setOpaque(false);
        column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
        TLabel hint = new TLabel("日志按天写入,保留最近 14 天;界面上只显示汇总,详细记录在日志文件里。",
                TLabel.Role.MUTED);
        hint.setAlignmentX(LEFT_ALIGNMENT);
        TLabel path = new TLabel(PathUtils.toDisplayPath(logsDir), TLabel.Role.BODY);
        path.setAlignmentX(LEFT_ALIGNMENT);
        column.add(hint);
        column.add(Box.createVerticalStrut(10));
        column.add(path);
        column.add(Box.createVerticalStrut(12));
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        row.setOpaque(false);
        row.setAlignmentX(LEFT_ALIGNMENT);
        FlatButton open = new FlatButton("打开日志目录");
        open.addActionListener(e -> FileUtils.openInFileBrowser(logsDir));
        row.add(open);
        column.add(row);
        card.add(column, BorderLayout.CENTER);
        return card;
    }

    private JComponent aboutCard() {
        Card card = new Card(new BorderLayout(0, 14));
        card.add(sectionTitle("关于"), BorderLayout.NORTH);

        JPanel column = new JPanel();
        column.setOpaque(false);
        column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
        addInfoRow(column, "版本", App.NAME + " v" + App.VERSION);
        addInfoRow(column, "运行时", "Java " + System.getProperty("java.version")
                + "(" + System.getProperty("java.vendor") + ")");
        addInfoRow(column, "系统", System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        addInfoRow(column, "配置文件", PathUtils.toDisplayPath(settingsPathHint()));
        card.add(column, BorderLayout.CENTER);
        return card;
    }

    private Path settingsPathHint() {
        return logsDir.getParent() == null ? logsDir : logsDir.getParent().resolve("config.json");
    }

    private void addInfoRow(JPanel column, String label, String value) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        row.setOpaque(false);
        row.setAlignmentX(LEFT_ALIGNMENT);
        TLabel labelComponent = new TLabel(label + ":", TLabel.Role.MUTED);
        labelComponent.setPreferredSize(new Dimension(80, 20));
        row.add(labelComponent);
        row.add(new TLabel(value, TLabel.Role.BODY));
        column.add(row);
        column.add(Box.createVerticalStrut(6));
    }

    private JComponent sectionTitle(String text) {
        return new TLabel(text, TLabel.Role.H2);
    }

    /** 便于测试与刷新:最近一次扫描结果。 */
    ScanResult lastResult() {
        return lastResult;
    }

    @Override
    public void onThemeChanged() {
        refreshThemeButtons();
        setScanResult(lastResult);
        repaint();
    }
}
