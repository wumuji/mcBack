package com.mcbackup.util;

import com.mcbackup.testfx.WorldFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 文件工具测试:大小统计、变化指纹、session.lock 探测。 */
class FileUtilsTest {

    @TempDir
    Path tempDir;

    @Test
    void sumsDirectorySizeRecursively() throws IOException {
        Path dir = tempDir.resolve("data");
        Files.createDirectories(dir.resolve("nested"));
        Files.write(dir.resolve("a.bin"), new byte[1000]);
        Files.write(dir.resolve("nested").resolve("b.bin"), new byte[500]);

        assertEquals(1500, FileUtils.directorySize(dir));
    }

    @Test
    void formatsHumanReadableSizes() {
        assertEquals("512 B", FileUtils.humanSize(512));
        assertEquals("1 KB", FileUtils.humanSize(1024));
        assertEquals("1.0 MB", FileUtils.humanSize(1024 * 1024));
        assertEquals("423 MB", FileUtils.humanSize(423L * 1024 * 1024));
        assertEquals("11.5 MB", FileUtils.humanSize((long) (11.5 * 1024 * 1024)));
        assertEquals("—", FileUtils.humanSize(-1));
    }

    @Test
    void worldChangeStampReactsToLevelDatChange() throws IOException {
        Path saves = tempDir.resolve("saves");
        Files.createDirectories(saves);
        Path world = WorldFixtures.vanillaWorld(saves, "世界", "世界");

        long before = FileUtils.worldChangeStamp(world);
        assertTrue(before > 0);

        // 触摸 level.dat 后指纹必须变化(Minecraft 保存世界时会更新它)
        Files.setLastModifiedTime(world.resolve("level.dat"),
                FileTime.fromMillis(System.currentTimeMillis() + 5000));

        long after = FileUtils.worldChangeStamp(world);
        assertTrue(after > before, "指纹应随 level.dat 修改时间变化");
    }

    @Test
    void detectsFreeAndLockedSessionFile() throws IOException {
        Path saves = tempDir.resolve("saves");
        Files.createDirectories(saves);
        Path world = WorldFixtures.vanillaWorld(saves, "世界", "世界");
        Path lockFile = world.resolve("session.lock");

        assertEquals(FileUtils.LockProbe.FREE, FileUtils.probeSessionLock(world));

        // 模拟 Minecraft 持有独占锁
        try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.WRITE);
             FileLock lock = channel.lock()) {
            assertEquals(FileUtils.LockProbe.LOCKED, FileUtils.probeSessionLock(world));
            assertTrue(lock.isValid());
        }
        assertEquals(FileUtils.LockProbe.FREE, FileUtils.probeSessionLock(world));
    }

    @Test
    void reportsMissingSessionFileAsMissing() throws IOException {
        Path world = tempDir.resolve("empty-world");
        Files.createDirectories(world);
        assertEquals(FileUtils.LockProbe.MISSING, FileUtils.probeSessionLock(world));
    }
}
