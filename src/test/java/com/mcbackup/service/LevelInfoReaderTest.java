package com.mcbackup.service;

import com.mcbackup.model.WorldInfo;
import com.mcbackup.testfx.NbtFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** level.dat 解析测试:成功路径与所有失败路径都不能抛出到调用方。 */
class LevelInfoReaderTest {

    @TempDir
    Path tempDir;

    @Test
    void readsAllSupportedFields() throws IOException {
        Path levelDat = tempDir.resolve("level.dat");
        Files.write(levelDat, NbtFixture.levelDat("我的生存世界", "1.21.1", 3955, 1, 3, true, 24000L * 7));

        WorldInfo info = LevelInfoReader.read(levelDat);

        assertTrue(info.available());
        assertEquals("我的生存世界", info.levelName());
        assertEquals("1.21.1", info.versionName());
        assertEquals(3955, info.dataVersion());
        assertEquals("创造", info.gameMode());
        assertEquals("困难", info.difficulty());
        assertTrue(info.hardcore());
        assertEquals(7, info.dayCount());
    }

    @Test
    void mapsGameModeAndDifficultyText() {
        assertEquals("生存", LevelInfoReader.gameModeText(0));
        assertEquals("旁观", LevelInfoReader.gameModeText(3));
        assertEquals("", LevelInfoReader.gameModeText(9));
        assertEquals("和平", LevelInfoReader.difficultyText(0));
        assertEquals("困难", LevelInfoReader.difficultyText(3));
        assertEquals("", LevelInfoReader.difficultyText(-1));
    }

    @Test
    void fallsBackWhenDataCompoundIsMissing() throws IOException {
        Path levelDat = tempDir.resolve("level-old.dat");
        Files.write(levelDat, NbtFixture.levelDatWithoutData("旧版本世界"));

        WorldInfo info = LevelInfoReader.read(levelDat);

        assertTrue(info.available());
        assertEquals("旧版本世界", info.levelName());
    }

    @Test
    void returnsUnavailableForCorruptedFile() throws IOException {
        Path levelDat = tempDir.resolve("broken.dat");
        Files.write(levelDat, new byte[]{1, 2, 3, 4, 5, 6, 7, 8});

        WorldInfo info = LevelInfoReader.read(levelDat);

        assertFalse(info.available());
        assertFalse(info.hasName());
    }

    @Test
    void returnsUnavailableWhenFileIsMissing() {
        WorldInfo info = LevelInfoReader.read(tempDir.resolve("not-there.dat"));
        assertFalse(info.available());
    }
}
