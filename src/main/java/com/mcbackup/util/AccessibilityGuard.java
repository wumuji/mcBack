package com.mcbackup.util;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 辅助功能(Java Access Bridge)兼容处理。
 *
 * <p>背景:如果机器上启用了「讲述人 / 放大镜」等辅助功能,Windows 上的 Java 会写一份
 * {@code %USERPROFILE%\.accessibility.properties},内容是
 * {@code assistive_technologies=com.sun.java.accessibility.AccessBridge}。AWT 初始化时会去加载这个类,
 * 如果当前运行时里没有 {@code jdk.accessibility} 模块,加载失败会直接抛
 * {@code java.awt.AWTError: Assistive Technology not found},程序连窗口都开不出来。</p>
 *
 * <p>处理策略:</p>
 * <ol>
 *   <li>先检查配置里声明的辅助功能类在当前运行时里是否存在;</li>
 *   <li>都存在 -> 什么都不做,辅助功能照常可用;</li>
 *   <li>有不存在的 -> 只在本进程里把 assistive_technologies 置空,让程序能正常启动,
 *       同时记录一条日志说明原因(不改用户配置文件,也不影响其它程序)。</li>
 * </ol>
 */
public final class AccessibilityGuard {

    /** AWT 读取的属性名。 */
    private static final String AT_PROPERTY = "javax.accessibility.assistive_technologies";

    private AccessibilityGuard() {
    }

    /**
     * 在触碰任何 AWT 类之前调用(必须在 UIManager / Toolkit 之前)。
     *
     * @return true 表示检测到缺失并已临时关闭辅助功能加载
     */
    public static boolean applyIfNeeded() {
        return apply(configuredTechnologies());
    }

    /**
     * 按给定的辅助功能类名列表做检查并(必要时)降级。
     *
     * <p>抽出来是为了能脱离运行环境单独测试。</p>
     *
     * @return true 表示检测到缺失并已临时关闭辅助功能加载
     */
    public static boolean apply(List<String> configured) {
        List<String> missing = missingClasses(configured);
        if (missing.isEmpty()) {
            return false;
        }
        System.setProperty(AT_PROPERTY, "");
        Log.warn("当前运行时缺少辅助功能模块 %s,已为本次启动临时关闭 assistive_technologies"
                + "(不影响系统设置,也不会影响其它程序)", missing);
        return true;
    }

    /** 收集配置里声明的辅助功能类名(系统属性 + 两个约定位置的配置文件)。 */
    public static List<String> configuredTechnologies() {
        List<String> names = new ArrayList<>();
        addFromProperty(names, System.getProperty(AT_PROPERTY));
        addFromProperty(names, System.getProperty("assistive_technologies"));
        String javaHome = System.getProperty("java.home");
        if (javaHome != null) {
            names.addAll(technologiesInFile(Path.of(javaHome, "conf", "accessibility.properties")));
        }
        String userHome = System.getProperty("user.home");
        if (userHome != null) {
            names.addAll(technologiesInFile(Path.of(userHome, ".accessibility.properties")));
        }
        return names;
    }

    /** 找出当前运行时里不存在的类名。 */
    public static List<String> missingClasses(List<String> classNames) {
        List<String> missing = new ArrayList<>();
        for (String name : classNames) {
            if (name == null || name.isBlank()) {
                continue;
            }
            try {
                Class.forName(name, false, ClassLoader.getSystemClassLoader());
            } catch (Throwable notPresent) {
                missing.add(name);
            }
        }
        return missing;
    }

    private static void addFromProperty(List<String> names, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        for (String part : value.split(",")) {
            if (!part.isBlank()) {
                names.add(part.strip());
            }
        }
    }

    /** 解析一个 accessibility.properties 文件里声明的辅助功能类名。 */
    public static List<String> technologiesInFile(Path file) {
        List<String> names = new ArrayList<>();
        if (!Files.isRegularFile(file)) {
            return names;
        }
        try {
            for (String line : Files.readAllLines(file)) {
                String text = line.strip();
                if (text.startsWith("#") || !text.startsWith("assistive_technologies")) {
                    continue;
                }
                int equals = text.indexOf('=');
                if (equals > 0) {
                    addFromProperty(names, text.substring(equals + 1));
                }
            }
        } catch (Exception e) {
            Log.debug("读取辅助功能配置失败: " + file + " (" + e.getMessage() + ")");
        }
        return names;
    }
}
