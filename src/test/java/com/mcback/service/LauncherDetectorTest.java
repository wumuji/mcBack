package com.mcback.service;

import com.mcback.model.LocationKind;
import com.mcback.testfx.WorldFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 启动器目录检测测试。
 *
 * <p>用假的 Environment 指向临时目录,构造出「官方默认 + 版本隔离 + Prism 实例 + 盘符根 +
 * 启动器配置」的真实布局,验证每一类都能被发现,并且重复目录只保留一次。</p>
 */
class LauncherDetectorTest {

    @TempDir
    Path tempDir;

    private record FakeEnv(Path roaming, Path home, List<Path> drives, List<Path> configs)
            implements LauncherDetector.Environment {

        @Override
        public Path roamingAppData() {
            return roaming;
        }

        @Override
        public Path userHome() {
            return home;
        }

        @Override
        public List<Path> driveRoots() {
            return drives;
        }

        @Override
        public List<Path> configFiles() {
            return configs;
        }
    }

    @Test
    void discoversOfficialVersionIsolatedAndInstanceLayouts() throws IOException {
        Path roaming = tempDir.resolve("roaming");
        Path home = tempDir.resolve("home");
        Path drive = tempDir.resolve("driveE");

        Path officialSaves = roaming.resolve(".minecraft").resolve("saves");
        Files.createDirectories(officialSaves);
        WorldFixtures.vanillaWorld(officialSaves, "官方世界", "官方世界");

        Path isolatedSaves = roaming.resolve(".minecraft").resolve("versions").resolve("1.21.1").resolve("saves");
        Files.createDirectories(isolatedSaves);
        WorldFixtures.vanillaWorld(isolatedSaves, "隔离世界", "隔离世界");

        Path prismSaves = roaming.resolve("PrismLauncher").resolve("instances").resolve("inst1")
                .resolve(".minecraft").resolve("saves");
        Files.createDirectories(prismSaves);
        WorldFixtures.vanillaWorld(prismSaves, "Prism 世界", "Prism 世界");

        Path curseForgeSaves = home.resolve("curseforge").resolve("minecraft").resolve("Instances")
                .resolve("cf1").resolve("saves");
        Files.createDirectories(curseForgeSaves);
        WorldFixtures.vanillaWorld(curseForgeSaves, "CF 世界", "CF 世界");

        Path driveSaves = drive.resolve(".minecraft").resolve("saves");
        Files.createDirectories(driveSaves);
        WorldFixtures.vanillaWorld(driveSaves, "盘符世界", "盘符世界");

        // 启动器配置里指向的自定义位置
        Path customGameDir = tempDir.resolve("custom").resolve("localmc");
        Path customSaves = customGameDir.resolve("saves");
        Files.createDirectories(customSaves);
        WorldFixtures.vanillaWorld(customSaves, "配置世界", "配置世界");
        Path pclIni = roaming.resolve("PCL").resolve("PCL.ini");
        Files.createDirectories(pclIni.getParent());
        Files.writeString(pclIni, "LaunchFolder=\"" + customGameDir + "\"\r\n", StandardCharsets.UTF_8);

        LauncherDetector detector = new LauncherDetector(
                new FakeEnv(roaming, home, List.of(drive), List.of(pclIni)));
        List<WorldRoot> roots = detector.discover();

        assertTrue(contains(roots, officialSaves), "应发现官方默认 saves");
        assertTrue(contains(roots, isolatedSaves), "应发现版本隔离 saves");
        assertTrue(contains(roots, prismSaves), "应发现 Prism 实例 saves");
        assertTrue(contains(roots, curseForgeSaves), "应发现 CurseForge 实例 saves");
        assertTrue(contains(roots, driveSaves), "应发现盘符根 .minecraft");
        assertTrue(contains(roots, customSaves), "应发现启动器配置里的自定义位置");

        assertEquals(LocationKind.VERSION_ISOLATED, kindOf(roots, isolatedSaves));
        assertTrue(labelOf(roots, isolatedSaves).contains("版本隔离"));
        assertEquals(LocationKind.PRISM, kindOf(roots, prismSaves));
        assertEquals(LocationKind.LAUNCHER_CONFIG, kindOf(roots, customSaves));

        // 分组名:版本隔离目录用版本名,实例用实例名,官方默认归到一个固定分组
        assertEquals("1.21.1", groupOf(roots, isolatedSaves));
        assertEquals("inst1", groupOf(roots, prismSaves));
        assertEquals("官方 .minecraft", groupOf(roots, officialSaves));

        // 同一 saves 目录不允许重复出现
        assertEquals(roots.size(), roots.stream().map(WorldRoot::dedupeKey).distinct().count());
    }

    @Test
    void ignoresMissingDirectories() {
        Path roaming = tempDir.resolve("empty-roaming");
        LauncherDetector detector = new LauncherDetector(
                new FakeEnv(roaming, tempDir.resolve("home"), List.of(), List.of()));

        assertTrue(detector.discover().isEmpty());
    }

    @Test
    void toleratesBrokenLauncherConfig() throws IOException {
        Path roaming = tempDir.resolve("roaming2");
        Path config = roaming.resolve("PCL").resolve("PCL.ini");
        Files.createDirectories(config.getParent());
        Files.writeString(config, "这不是 ini,里面只有乱码 \u0000\u0001 和半截路径 D:\\not-exist",
                StandardCharsets.UTF_8);

        LauncherDetector detector = new LauncherDetector(
                new FakeEnv(roaming, tempDir.resolve("home"), List.of(), List.of(config)));

        assertFalse(detector.discover().stream().anyMatch(root -> root.kind() == LocationKind.LAUNCHER_CONFIG));
    }

    private static boolean contains(List<WorldRoot> roots, Path savesDir) {
        String expected = savesDir.toAbsolutePath().normalize().toString();
        return roots.stream().anyMatch(root -> root.savesDir().toAbsolutePath().normalize().toString().equals(expected));
    }

    private static LocationKind kindOf(List<WorldRoot> roots, Path savesDir) {
        String expected = savesDir.toAbsolutePath().normalize().toString();
        return roots.stream()
                .filter(root -> root.savesDir().toAbsolutePath().normalize().toString().equals(expected))
                .findFirst()
                .map(WorldRoot::kind)
                .orElseThrow();
    }

    private static String labelOf(List<WorldRoot> roots, Path savesDir) {
        String expected = savesDir.toAbsolutePath().normalize().toString();
        return roots.stream()
                .filter(root -> root.savesDir().toAbsolutePath().normalize().toString().equals(expected))
                .findFirst()
                .map(WorldRoot::label)
                .orElseThrow();
    }

    private static String groupOf(List<WorldRoot> roots, Path savesDir) {
        String expected = savesDir.toAbsolutePath().normalize().toString();
        return roots.stream()
                .filter(root -> root.savesDir().toAbsolutePath().normalize().toString().equals(expected))
                .findFirst()
                .map(WorldRoot::groupName)
                .orElseThrow();
    }
}
