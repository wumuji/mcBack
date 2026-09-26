package com.mcback.util;

import com.mcback.testfx.WorldFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 世界复制测试:内容一致、结构一致、临时目录清理。 */
class WorldCopierTest {

    @TempDir
    Path tempDir;

    @Test
    void copiesWholeTreeIncludingLockFile() throws IOException {
        Path saves = tempDir.resolve("saves");
        Files.createDirectories(saves);
        Path world = WorldFixtures.moddedWorld(saves, "世界", "世界");
        Path dest = tempDir.resolve("copy");

        WorldCopier.CopyResult result = WorldCopier.copy(world, dest, ProgressListener.NOOP);

        assertTrue(result.complete(), "普通文件都应复制成功,失败列表: " + result.failedFiles()
                + " 警告: " + result.warnings());
        assertEquals(0, result.failedFiles().size());
        assertTrue(result.copiedFiles() > 0);
        assertTrue(Files.isRegularFile(dest.resolve("level.dat")));
        assertTrue(Files.isDirectory(dest.resolve("region")));
        assertTrue(Files.isDirectory(dest.resolve("DIM1")));
        assertArrayEquals(Files.readAllBytes(world.resolve("region").resolve("r.0.0.mca")),
                Files.readAllBytes(dest.resolve("region").resolve("r.0.0.mca")));
        assertEquals(result.copiedFiles(), countFiles(dest));
    }

    @Test
    void refusesMissingSourceDirectory() {
        try {
            WorldCopier.copy(tempDir.resolve("nope"), tempDir.resolve("dest"), ProgressListener.NOOP);
            org.junit.jupiter.api.Assertions.fail("应当抛出异常");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("源目录不存在"));
        }
    }

    @Test
    void deletesTreeRecursively() throws IOException {
        Path dir = tempDir.resolve("to-delete");
        Files.createDirectories(dir.resolve("nested"));
        Files.writeString(dir.resolve("nested").resolve("a.txt"), "x");

        assertTrue(WorldCopier.deleteRecursively(dir));
        assertFalse(Files.exists(dir));
    }

    @Test
    void commitRenameMovesFileAtomically() throws IOException {
        Path temp = tempDir.resolve("part.zip.tmp");
        Path target = tempDir.resolve("final.zip");
        Files.writeString(temp, "content");

        WorldCopier.commitRename(temp, target);

        assertFalse(Files.exists(temp));
        assertEquals("content", Files.readString(target));
    }

    private static int countFiles(Path dir) throws IOException {
        return ZipUtils.collectFiles(dir).size();
    }
}
