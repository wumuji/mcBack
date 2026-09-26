package com.mcback.testfx;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** 测试用世界目录生成器。 */
public final class WorldFixtures {

    private WorldFixtures() {
    }

    /** 生成一个结构完整的原版世界。 */
    public static Path vanillaWorld(Path savesDir, String folderName, String levelName) throws IOException {
        return buildWorld(savesDir, folderName, levelName, false);
    }

    /** 生成一个带模组目录(DIM1 / dimensions)的世界。 */
    public static Path moddedWorld(Path savesDir, String folderName, String levelName) throws IOException {
        return buildWorld(savesDir, folderName, levelName, true);
    }

    /** 生成一个只有 level.dat 和 region 的「结构不完整」目录。 */
    public static Path incompleteWorld(Path savesDir, String folderName) throws IOException {
        Path world = savesDir.resolve(folderName);
        Files.createDirectories(world.resolve("region"));
        Files.write(world.resolve("level.dat"),
                NbtFixture.levelDat(folderName, "1.20.1", 3465, 0, 2, false, 12000L));
        Files.write(world.resolve("region").resolve("r.0.0.mca"), new byte[2048]);
        return world;
    }

    private static Path buildWorld(Path savesDir, String folderName, String levelName, boolean modded)
            throws IOException {
        Path world = savesDir.resolve(folderName);
        Files.createDirectories(world.resolve("region"));
        Files.createDirectories(world.resolve("playerdata"));
        Files.createDirectories(world.resolve("advancements"));
        Files.createDirectories(world.resolve("data"));
        Files.createDirectories(world.resolve("poi"));
        if (modded) {
            Files.createDirectories(world.resolve("DIM1"));
            Files.createDirectories(world.resolve("DIM-1"));
            Files.createDirectories(world.resolve("dimensions"));
        }
        Files.write(world.resolve("level.dat"),
                NbtFixture.levelDat(levelName, "1.21.1", 3955, 0, 2, false, 24000L * 42));
        Files.write(world.resolve("region").resolve("r.0.0.mca"), new byte[4096]);
        Files.write(world.resolve("region").resolve("r.0.1.mca"), new byte[2048]);
        Files.write(world.resolve("playerdata").resolve("00000000-0000-0000-0000-000000000000.dat"), new byte[64]);
        Files.write(world.resolve("session.lock"), new byte[]{0, 0, 0, 0});
        return world;
    }
}
