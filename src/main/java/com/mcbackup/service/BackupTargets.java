package com.mcbackup.service;

import com.mcbackup.model.MinecraftWorld;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 自动备份的「要备份哪些世界」选择逻辑。
 *
 * <p>用世界目录的绝对路径作为标识(而不是世界名):同名世界、同名文件夹在不同版本/实例下
 * 都可能有,只有路径是唯一的。路径在比较时统一小写,避免 Windows 大小写差异导致选择丢失。</p>
 */
public final class BackupTargets {

    private BackupTargets() {
    }

    /** 规范化标识:绝对路径 + 规范化 + 小写。 */
    public static String key(Path worldDir) {
        return worldDir.toAbsolutePath().normalize().toString().toLowerCase(Locale.ROOT);
    }

    public static String key(MinecraftWorld world) {
        return key(world.worldDir());
    }

    /** 去重、去空、统一大小写。 */
    public static List<String> normalize(Collection<String> raw) {
        Set<String> result = new LinkedHashSet<>();
        if (raw != null) {
            for (String item : raw) {
                if (item == null || item.isBlank()) {
                    continue;
                }
                String text = item.strip();
                try {
                    text = key(Path.of(text));
                } catch (RuntimeException e) {
                    text = text.toLowerCase(Locale.ROOT);
                }
                result.add(text);
            }
        }
        return new ArrayList<>(result);
    }

    public static boolean isSelected(MinecraftWorld world, Collection<String> targets) {
        return targets != null && targets.contains(key(world));
    }

    /** 只保留被勾选的世界。 */
    public static List<MinecraftWorld> filter(List<MinecraftWorld> worlds, Collection<String> targets) {
        List<MinecraftWorld> selected = new ArrayList<>();
        if (worlds == null) {
            return selected;
        }
        for (MinecraftWorld world : worlds) {
            if (isSelected(world, targets)) {
                selected.add(world);
            }
        }
        return selected;
    }

    /**
     * 已勾选但本次扫描没找到的世界数量。
     *
     * <p>典型场景:换启动器、世界被移走或改名。UI 会把它作为「已失效」提示出来,
     * 但不会自动清除勾选,等世界回来就恢复。</p>
     */
    public static int missingCount(List<MinecraftWorld> worlds, Collection<String> targets) {
        if (targets == null || targets.isEmpty()) {
            return 0;
        }
        Set<String> present = new LinkedHashSet<>();
        if (worlds != null) {
            for (MinecraftWorld world : worlds) {
                present.add(key(world));
            }
        }
        int missing = 0;
        for (String target : targets) {
            if (!present.contains(target)) {
                missing++;
            }
        }
        return missing;
    }
}
