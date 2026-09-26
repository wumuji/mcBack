package com.mcback.ui.theme;

import com.mcback.model.Theme;
import com.mcback.util.Log;

import javax.swing.BorderFactory;
import javax.swing.UIManager;
import java.awt.Color;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * 主题管理:保存当前选择,解析「跟随系统」,并把颜色下发给所有监听者。
 */
public final class ThemeManager {

    private static final List<Runnable> LISTENERS = new CopyOnWriteArrayList<>();

    private static Theme option = Theme.FOLLOW_SYSTEM;
    private static Palette palette = Palette.DARK;
    private static Boolean systemDarkCache;

    private ThemeManager() {
    }

    /** 设置主题并通知界面刷新。 */
    public static void setOption(Theme theme) {
        option = theme == null ? Theme.FOLLOW_SYSTEM : theme;
        palette = resolve(option);
        applyDefaults();
        for (Runnable listener : LISTENERS) {
            try {
                listener.run();
            } catch (RuntimeException e) {
                Log.errorQuietly("主题刷新失败", e);
            }
        }
    }

    public static Theme option() {
        return option;
    }

    public static Palette palette() {
        return palette;
    }

    public static void addListener(Runnable listener) {
        LISTENERS.add(listener);
    }

    public static void removeListener(Runnable listener) {
        LISTENERS.remove(listener);
    }

    /** 解析主题选项为具体配色。 */
    public static Palette resolve(Theme theme) {
        return switch (theme) {
            case LIGHT -> Palette.LIGHT;
            case DARK -> Palette.DARK;
            case FOLLOW_SYSTEM -> systemPrefersDark() ? Palette.DARK : Palette.LIGHT;
        };
    }

    /**
     * 读取 Windows 的「应用模式」设置。
     *
     * <p>用 reg query 而不是注册表库,避免为一个小功能引入原生依赖。
     * 读取失败时按深色处理(与计划里的默认值一致)。</p>
     */
    public static boolean systemPrefersDark() {
        if (systemDarkCache != null) {
            return systemDarkCache;
        }
        boolean dark = true;
        try {
            ProcessBuilder builder = new ProcessBuilder("reg", "query",
                    "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                    "/v", "AppsUseLightTheme");
            builder.redirectErrorStream(true);
            Process process = builder.start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), Charset.defaultCharset()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                }
            }
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
            String text = output.toString();
            if (text.contains("0x0")) {
                dark = true;
            } else if (text.contains("0x1")) {
                dark = false;
            }
        } catch (Exception e) {
            Log.warn("读取系统主题失败,按深色处理: %s", e.getMessage());
        }
        systemDarkCache = dark;
        Log.info("系统主题偏好: %s", dark ? "深色" : "浅色");
        return dark;
    }

    /** 把配色与字体下发给 Swing 原生组件(对话框、文件选择器、提示等)。 */
    public static void applyDefaults() {
        Palette p = palette;
        UIManager.put("Panel.background", p.background());
        UIManager.put("Panel.foreground", p.text());
        UIManager.put("Panel.font", UiFonts.regular(13));
        UIManager.put("Label.foreground", p.text());
        UIManager.put("Label.background", p.background());
        UIManager.put("Label.font", UiFonts.regular(13));
        UIManager.put("Button.font", UiFonts.medium(13));
        UIManager.put("OptionPane.background", p.surface());
        UIManager.put("OptionPane.messageForeground", p.text());
        UIManager.put("OptionPane.messageFont", UiFonts.regular(13));
        UIManager.put("OptionPane.buttonFont", UiFonts.medium(13));
        UIManager.put("ToolTip.background", p.surface());
        UIManager.put("ToolTip.foreground", p.text());
        UIManager.put("ToolTip.font", UiFonts.regular(12));
        UIManager.put("ToolTip.border", BorderFactory.createLineBorder(p.border()));
        UIManager.put("ScrollPane.background", p.background());
        UIManager.put("Viewport.background", p.background());
        UIManager.put("CheckBox.background", p.surface());
        UIManager.put("CheckBox.foreground", p.text());
        UIManager.put("CheckBox.font", UiFonts.regular(13));
        UIManager.put("RadioButton.background", p.surface());
        UIManager.put("RadioButton.foreground", p.text());
        UIManager.put("RadioButton.font", UiFonts.regular(13));
        UIManager.put("ComboBox.background", p.surface());
        UIManager.put("ComboBox.foreground", p.text());
        UIManager.put("ComboBox.font", UiFonts.regular(13));
        UIManager.put("TextField.background", p.dark() ? p.background() : Color.WHITE);
        UIManager.put("TextField.foreground", p.text());
        UIManager.put("TextField.caretForeground", p.text());
        UIManager.put("TextField.font", UiFonts.regular(13));
        UIManager.put("TextArea.background", p.surface());
        UIManager.put("TextArea.foreground", p.text());
        UIManager.put("List.background", p.surface());
        UIManager.put("List.foreground", p.text());
        UIManager.put("List.font", UiFonts.regular(13));
        UIManager.put("FileChooser.background", p.surface());
        UIManager.put("FileChooser.foreground", p.text());
        UIManager.put("FileChooser.listFont", UiFonts.regular(13));
    }
}
