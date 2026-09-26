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
 * 「设置」页面:只保留与备份无关的全局设置(外观、存档目录、日志、关于)。
 *
 * <p>备份相关的设置(位置/间隔/保留/压缩/校验/托盘)统一收在「备份」页的「备份设置…」里。</p>
 */
public class SettingsView extends JPanel implements ThemeAware {

    /** 页面回调。 */
    public interface Callbacks {

        void onThemeSelected(Theme theme);

        void onAddDirectory();

        void onRemoveDirectory(String path);

        void onRescan();
    }

    private final AppSettings settings;
    private final Callbacks callbacks;
    private final Path logsDir;

    private final Map<Theme, FlatButton> themeButtons = new EnumMap<>(Theme.class);
    private final JPanel rootList = new JPanel();
    private final JPanel issueBox = new JPanel();
    private final TLabel rootSummary = new TLabel("", TLabel.Role.MUTED);
    private ScanResult lastResult = ScanResult.empty();

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
        content.add(Box.createVerticalStrut(14));
        content.add(directoryCard());
        content.add(Box.createVerticalStrut(14));
        content.add(logCard());
        content.add(Box.createVerticalStrut(14));
        content.add(aboutCard());
        content.add(Box.createVerticalGlue());

        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setOpaque(false);
        wrapper.add(content, BorderLayout.NORTH);

        JScrollPane scroll = new JScrollPane(wrapper);
        ScrollPaneStyler.apply(scroll);
        add(scroll, BorderLayout.CENTER);
        refreshThemeButtons();
    }

    private JComponent appearanceCard() {
        Card card = new Card(new BorderLayout(0, 12));
        card.add(sectionTitle("外观"), BorderLayout.NORTH);

        JPanel column = new JPanel();
        column.setOpaque(false);
        column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
        TLabel hint = new TLabel("「跟随系统」会读取 Windows 的应用模式设置,读取失败时按深色显示。",
                TLabel.Role.MUTED);
        hint.setAlignmentX(LEFT_ALIGNMENT);
        column.add(hint);
        column.add(Box.createVerticalStrut(10));

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

    private JComponent directoryCard() {
        Card card = new Card(new BorderLayout(0, 12));

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
        column.add(Box.createVerticalStrut(8));
        rootSummary.setAlignmentX(LEFT_ALIGNMENT);
        column.add(rootSummary);
        column.add(Box.createVerticalStrut(10));

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

    private JComponent logCard() {
        Card card = new Card(new BorderLayout(0, 12));
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
        column.add(Box.createVerticalStrut(8));
        column.add(path);
        column.add(Box.createVerticalStrut(10));
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
        Card card = new Card(new BorderLayout(0, 12));
        card.add(sectionTitle("关于"), BorderLayout.NORTH);

        JPanel column = new JPanel();
        column.setOpaque(false);
        column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
        addInfoRow(column, "版本", App.NAME + " v" + App.VERSION);
        addInfoRow(column, "运行时", "Java " + System.getProperty("java.version")
                + "(" + System.getProperty("java.vendor") + ")");
        addInfoRow(column, "系统", System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        addInfoRow(column, "配置文件", PathUtils.toDisplayPath(configPathHint()));
        addInfoRow(column, "备份设置", "在「备份」页的「备份设置…」里");
        card.add(column, BorderLayout.CENTER);
        return card;
    }

    private Path configPathHint() {
        Path parent = logsDir.getParent();
        return parent == null ? logsDir : parent.resolve("config.json");
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
        column.add(Box.createVerticalStrut(5));
    }

    private JComponent sectionTitle(String text) {
        return new TLabel(text, TLabel.Role.H2);
    }

    /** 便于测试。 */
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
