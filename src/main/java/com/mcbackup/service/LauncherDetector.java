package com.mcbackup.service;

import com.mcbackup.model.LocationKind;
import com.mcbackup.util.Log;
import com.mcbackup.util.PathUtils;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 自动发现 Minecraft 存档目录。
 *
 * <p>覆盖三种策略,互为补充:</p>
 * <ol>
 *   <li><b>固定布局</b>:官方启动器的 {@code .minecraft\saves}、PCL 的版本隔离目录
 *       {@code .minecraft\versions\<版本>\saves}、Prism/MultiMC/ATLauncher 的实例目录等。</li>
 *   <li><b>盘符根</b>:很多玩家把游戏装在 {@code E:\.minecraft} 之类的自定义位置,
 *       这里逐个盘符检查 {@code <盘符>\.minecraft},不做全盘递归。</li>
 *   <li><b>启动器配置兜底</b>:对 PCL.ini / hmcl.json 等已知配置文件做「路径字符串粗提取」,
 *       解析失败只记日志,不影响其它来源。</li>
 * </ol>
 */
public final class LauncherDetector implements WorldRootProvider {

    /** 从配置文件里粗暴提取 Windows 绝对路径,例如 {@code D:\MC\.minecraft}。 */
    private static final Pattern WINDOWS_PATH = Pattern.compile("[A-Za-z]:[\\\\/][^\"'\\r\\n<>|?*]*");

    /** 读取启动器配置文件的最大字节数,防止误读大文件。 */
    private static final long MAX_CONFIG_BYTES = 512 * 1024;

    /** 环境依赖,抽出来是为了单元测试能注入临时目录。 */
    public interface Environment {

        Path roamingAppData();

        Path userHome();

        List<Path> driveRoots();

        List<Path> configFiles();
    }

    private final Environment env;

    public LauncherDetector() {
        this(systemEnvironment());
    }

    public LauncherDetector(Environment env) {
        this.env = env;
    }

    /** 真实运行环境。 */
    public static Environment systemEnvironment() {
        String appData = System.getenv("APPDATA");
        Path userHome = Paths.get(System.getProperty("user.home", "."));
        Path roaming = (appData != null && !appData.isBlank())
                ? Paths.get(appData)
                : userHome.resolve("AppData").resolve("Roaming");
        return new Environment() {
            @Override
            public Path roamingAppData() {
                return roaming;
            }

            @Override
            public Path userHome() {
                return userHome;
            }

            @Override
            public List<Path> driveRoots() {
                List<Path> roots = new ArrayList<>();
                for (var root : FileSystems.getDefault().getRootDirectories()) {
                    roots.add(root);
                }
                return roots;
            }

            @Override
            public List<Path> configFiles() {
                List<Path> files = new ArrayList<>();
                files.add(roaming.resolve("PCL").resolve("PCL.ini"));
                files.add(roaming.resolve("PCLCE").resolve("PCL.ini"));
                files.add(roaming.resolve("PCL").resolve("config.ini"));
                files.add(roaming.resolve(".hmcl").resolve("hmcl.json"));
                files.add(roaming.resolve("PrismLauncher").resolve("prismlauncher.cfg"));
                files.add(roaming.resolve("MultiMC").resolve("multimc.cfg"));
                return files;
            }
        };
    }

    @Override
    public List<WorldRoot> discover() {
        Map<String, WorldRoot> roots = new LinkedHashMap<>();

        // 1) 官方启动器默认目录
        addGameDir(roots, env.roamingAppData().resolve(".minecraft"), LocationKind.OFFICIAL_DEFAULT, "官方启动器");

        // 2) 各盘符根下的 .minecraft(玩家自定义安装位置)
        for (Path driveRoot : env.driveRoots()) {
            addGameDir(roots, driveRoot.resolve(".minecraft"), LocationKind.OFFICIAL_DEFAULT,
                    "官方启动器(盘符根)");
        }

        // 3) 各启动器的实例目录
        Path roaming = env.roamingAppData();
        addInstances(roots, roaming.resolve("PrismLauncher").resolve("instances"), LocationKind.PRISM, "Prism");
        addInstances(roots, roaming.resolve("MultiMC").resolve("instances"), LocationKind.MULTIMC, "MultiMC");
        addInstances(roots, roaming.resolve("MultiMC5").resolve("instances"), LocationKind.MULTIMC, "MultiMC");
        addInstances(roots, roaming.resolve("ATLauncher").resolve("instances"), LocationKind.ATLAUNCHER, "ATLauncher");
        addInstances(roots, roaming.resolve("ModrinthApp").resolve("profiles"), LocationKind.MODRINTH, "Modrinth");
        addInstances(roots, env.userHome().resolve("curseforge").resolve("minecraft").resolve("Instances"),
                LocationKind.CURSEFORGE, "CurseForge");
        // HMCL / LabyMod 的默认游戏目录就是 %APPDATA%\.minecraft,上面已覆盖;
        // 这里再检查它们可能使用的独立目录。
        addGameDir(roots, roaming.resolve(".hmcl").resolve(".minecraft"), LocationKind.HMCL, "HMCL");
        addGameDir(roots, roaming.resolve("LabyMod").resolve("minecraft"), LocationKind.LABYMOD, "LabyMod");

        // 4) 启动器配置里的自定义路径(兜底)
        for (Path config : env.configFiles()) {
            probeConfigFile(roots, config);
        }
        // PCL 的 ini 也可能直接放在 .minecraft 里
        for (WorldRoot root : new ArrayList<>(roots.values())) {
            probeConfigFile(roots, root.gameDir().resolve("PCL.ini"));
        }

        Log.info("自动检测到 %d 个存档目录", roots.size());
        for (WorldRoot root : roots.values()) {
            Log.info("  检测到存档目录: %s(%s)", root.displayPath(), root.label());
        }
        return new ArrayList<>(roots.values());
    }

    // ------------------------------------------------------------------
    // 固定布局
    // ------------------------------------------------------------------

    /** 添加一个游戏目录(取其中的 saves 与版本隔离目录)。 */
    private void addGameDir(Map<String, WorldRoot> roots, Path gameDir, LocationKind kind, String label) {
        if (!PathUtils.isDirectory(gameDir)) {
            return;
        }
        Path saves = gameDir.resolve("saves");
        if (PathUtils.isDirectory(saves)) {
            put(roots, new WorldRoot(gameDir, saves, kind, label));
        }
        Path versions = gameDir.resolve("versions");
        if (!PathUtils.isDirectory(versions)) {
            return;
        }
        try (var stream = Files.newDirectoryStream(versions)) {
            for (Path version : stream) {
                if (!PathUtils.isDirectory(version)) {
                    continue;
                }
                Path versionSaves = version.resolve("saves");
                if (PathUtils.isDirectory(versionSaves)) {
                    String name = version.getFileName().toString();
                    put(roots, new WorldRoot(version, versionSaves, LocationKind.VERSION_ISOLATED,
                            label + " · 版本隔离 · " + name));
                }
            }
        } catch (IOException e) {
            Log.warn("读取版本目录失败: %s (%s)", versions, e.getMessage());
        }
    }

    /** 添加某个启动器的实例根目录下的所有实例(Pism/MultiMC/ATLauncher/Modrinth/CurseForge)。 */
    private void addInstances(Map<String, WorldRoot> roots, Path instancesDir, LocationKind kind, String label) {
        if (!PathUtils.isDirectory(instancesDir)) {
            return;
        }
        try (var stream = Files.newDirectoryStream(instancesDir)) {
            for (Path instance : stream) {
                if (!PathUtils.isDirectory(instance)) {
                    continue;
                }
                String name = instance.getFileName().toString();
                String instanceLabel = label + " · " + name;
                // 不同启动器的实例结构不一样,逐个尝试常见布局
                boolean added = tryAddInstance(roots, instance.resolve(".minecraft"), kind, instanceLabel);
                added |= tryAddInstance(roots, instance.resolve("minecraft"), kind, instanceLabel);
                added |= tryAddInstance(roots, instance, kind, instanceLabel);
                if (!added) {
                    Log.debug("实例目录中没有找到 saves: " + instance);
                }
            }
        } catch (IOException e) {
            Log.warn("读取实例目录失败: %s (%s)", instancesDir, e.getMessage());
        }
    }

    private boolean tryAddInstance(Map<String, WorldRoot> roots, Path gameDir, LocationKind kind, String label) {
        Path saves = gameDir.resolve("saves");
        if (!PathUtils.isDirectory(saves)) {
            return false;
        }
        put(roots, new WorldRoot(gameDir, saves, kind, label));
        return true;
    }

    // ------------------------------------------------------------------
    // 配置文件路径粗提取
    // ------------------------------------------------------------------

    /**
     * 从启动器配置文件里提取可能的游戏目录。
     *
     * <p>这里刻意不做完整 JSON/INI 解析:不同启动器、不同版本的字段名都在变,
     * 只要能把 {@code X:\...} 形式的路径找出来并验证其存在性,就已经达到目的。
     * 提取失败只记录日志。</p>
     */
    void probeConfigFile(Map<String, WorldRoot> roots, Path configFile) {
        if (!PathUtils.isFile(configFile)) {
            return;
        }
        String text;
        try {
            if (Files.size(configFile) > MAX_CONFIG_BYTES) {
                Log.debug("跳过过大的启动器配置文件: " + configFile);
                return;
            }
            byte[] bytes = Files.readAllBytes(configFile);
            text = decodeLenient(bytes);
        } catch (IOException | SecurityException e) {
            Log.warn("读取启动器配置失败: %s (%s)", configFile, e.getMessage());
            return;
        }
        Matcher matcher = WINDOWS_PATH.matcher(text);
        int found = 0;
        while (matcher.find()) {
            String raw = matcher.group();
            if (extractRoot(roots, raw, configFile.getFileName().toString())) {
                found++;
            }
        }
        if (found > 0) {
            Log.info("从 %s 中提取到 %d 个可用游戏目录", configFile, found);
        }
    }

    /** 对单个路径候选做校验,能识别成游戏目录就加入结果。 */
    private boolean extractRoot(Map<String, WorldRoot> roots, String raw, String sourceName) {
        // 配置文件里反斜杠常被转义成 \\ ,先还原
        String cleaned = PathUtils.cleanInput(raw.replace("\\\\", "\\"));
        if (cleaned == null) {
            return false;
        }
        Path path;
        try {
            path = Paths.get(cleaned);
        } catch (RuntimeException e) {
            return false;
        }
        if (!PathUtils.isDirectory(path)) {
            return false;
        }
        String label = "启动器配置 · " + sourceName;
        String name = path.getFileName() == null ? "" : path.getFileName().toString();
        if (name.equalsIgnoreCase("saves")) {
            Path gameDir = path.getParent() == null ? path : path.getParent();
            put(roots, new WorldRoot(gameDir, path, LocationKind.LAUNCHER_CONFIG, label));
            return true;
        }
        if (PathUtils.isDirectory(path.resolve("saves"))) {
            addGameDir(roots, path, LocationKind.LAUNCHER_CONFIG, label);
            return true;
        }
        return false;
    }

    /** 先按 UTF-8 解码,出现替换字符时改用 GBK(PCL.ini 常见编码)。 */
    private static String decodeLenient(byte[] bytes) {
        String utf8 = new String(bytes, StandardCharsets.UTF_8);
        if (utf8.indexOf('\uFFFD') < 0) {
            return utf8;
        }
        try {
            return new String(bytes, Charset.forName("GBK"));
        } catch (RuntimeException e) {
            return utf8;
        }
    }

    private static void put(Map<String, WorldRoot> roots, WorldRoot root) {
        String key = root.dedupeKey().toLowerCase(Locale.ROOT);
        if (!roots.containsKey(key)) {
            roots.put(key, root);
        }
    }
}
