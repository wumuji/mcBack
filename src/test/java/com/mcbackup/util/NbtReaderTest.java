package com.mcbackup.util;

import com.mcbackup.testfx.NbtFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** NBT 读取测试,重点在「损坏或恶意文件不能拖垮程序」。 */
class NbtReaderTest {

    @TempDir
    Path tempDir;

    @Test
    void readsLevelData() throws IOException {
        Path file = tempDir.resolve("level.dat");
        Files.write(file, NbtFixture.levelDat("生存世界", "1.21.1", 3955, 0, 2, false, 24000L * 42));

        Map<String, Object> root = NbtReader.readFile(file);

        assertTrue(root.containsKey("Data"));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) root.get("Data");
        assertEquals("生存世界", data.get("LevelName"));
        assertEquals(3955L, data.get("DataVersion"));
        assertEquals(24000L * 42, data.get("DayTime"));
        assertNotNull(data.get("Version"));
    }

    @Test
    void rejectsTruncatedFile() throws IOException {
        byte[] full = NbtFixture.levelDat("世界", "1.21.1", 3955, 0, 2, false, 1000L);
        Path file = tempDir.resolve("truncated.dat");
        Files.write(file, Arrays.copyOf(full, full.length / 2));

        assertThrows(IOException.class, () -> NbtReader.readFile(file));
    }

    @Test
    void rejectsNonNbtContent() throws IOException {
        Path file = tempDir.resolve("random.dat");
        Files.write(file, new byte[]{'n', 'o', 't', 'n', 'b', 't'});

        assertThrows(IOException.class, () -> NbtReader.readFile(file));
    }

    @Test
    void rejectsOversizedArrayDeclaration() throws IOException {
        Path file = tempDir.resolve("oversized.dat");
        Files.write(file, NbtFixture.oversizedArray(NbtReader.MAX_ARRAY_ELEMENTS + 1));

        NbtReader.NbtException error = assertThrows(NbtReader.NbtException.class, () -> NbtReader.readFile(file));
        assertTrue(error.getMessage().contains("元素过多"), "应命中元素上限保护: " + error.getMessage());
    }

    @Test
    void rejectsTooDeepNesting() throws IOException {
        Path file = tempDir.resolve("deep.dat");
        Files.write(file, NbtFixture.deeplyNested(NbtReader.MAX_DEPTH + 8));

        NbtReader.NbtException error = assertThrows(NbtReader.NbtException.class, () -> NbtReader.readFile(file));
        assertTrue(error.getMessage().contains("嵌套层级"), "应命中深度上限保护: " + error.getMessage());
    }

    @Test
    void rejectsFileThatIsTooLarge() throws IOException {
        Path file = tempDir.resolve("huge.dat");
        Files.write(file, new byte[(int) NbtReader.MAX_FILE_BYTES + 1024]);

        NbtReader.NbtException error = assertThrows(NbtReader.NbtException.class, () -> NbtReader.readFile(file));
        assertTrue(error.getMessage().contains("文件过大"), "应命中文件大小上限: " + error.getMessage());
    }

    @Test
    void handlesCompressionBombWithoutHanging() throws IOException {
        Path file = tempDir.resolve("bomb.dat");
        Files.write(file, NbtFixture.compressionBomb(64 * 1024 * 1024));

        long started = System.currentTimeMillis();
        assertThrows(IOException.class, () -> NbtReader.readFile(file));
        long elapsed = System.currentTimeMillis() - started;
        assertTrue(elapsed < 5000, "应当在很短时间内失败,实际耗时 " + elapsed + " ms");
    }
}
