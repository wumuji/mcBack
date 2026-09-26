package com.mcback.ui.components;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 图标测试:同一份绘制代码要同时供窗口、托盘与打包使用。 */
class AppIconTest {

    @TempDir
    Path tempDir;

    @Test
    void rendersOpaqueIconAtRequestedSize() {
        BufferedImage image = AppIcon.render(64);

        assertEquals(64, image.getWidth());
        assertEquals(64, image.getHeight());
        // 中心像素必须有颜色(不是全透明)
        int center = image.getRGB(32, 32);
        assertTrue((center >>> 24) > 0, "图标不应是空白的");
    }

    @Test
    void writesReadablePng() throws IOException {
        Path png = tempDir.resolve("icons").resolve("icon-256.png");

        AppIcon.writePng(png, 256);

        assertTrue(Files.isRegularFile(png));
        BufferedImage read = ImageIO.read(png.toFile());
        assertNotNull(read);
        assertEquals(256, read.getWidth());
    }

    @Test
    void writesIcoWithMultipleSizes() throws IOException {
        Path ico = tempDir.resolve("icon.ico");

        AppIcon.writeIco(ico, 16, 32, 48, 256);

        assertTrue(Files.isRegularFile(ico));
        byte[] bytes = Files.readAllBytes(ico);
        // ICONDIR: reserved=0, type=1, count=4
        assertEquals(0, bytes[0]);
        assertEquals(0, bytes[1]);
        assertEquals(1, bytes[2]);
        assertEquals(0, bytes[3]);
        assertEquals(4, bytes[4]);
        assertEquals(0, bytes[5]);
        // 目录项里每一张都必须是 PNG 载荷(魔数 89 50 4E 47)
        int offset = readIntLE(bytes, 6 + 12);
        assertEquals(0x89, bytes[offset] & 0xFF);
        assertEquals(0x50, bytes[offset + 1] & 0xFF);
        assertEquals(0x4E, bytes[offset + 2] & 0xFF);
        assertEquals(0x47, bytes[offset + 3] & 0xFF);
        assertTrue(bytes.length > 1000, "ICO 应包含实际图标数据");
    }

    private static int readIntLE(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8)
                | ((data[offset + 2] & 0xFF) << 16) | ((data[offset + 3] & 0xFF) << 24);
    }
}
