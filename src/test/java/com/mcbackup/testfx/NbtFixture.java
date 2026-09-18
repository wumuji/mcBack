package com.mcbackup.testfx;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;

/**
 * 测试用 NBT 数据生成器。
 *
 * <p>测试全部使用自己构造的字节流,不依赖用户机器上的真实存档,
 * 因此可以在任何环境重复运行。</p>
 */
public final class NbtFixture {

    private NbtFixture() {
    }

    /** 生成一个标准 level.dat(结构与 Minecraft 的 Data 复合标签一致)。 */
    public static byte[] levelDat(String levelName, String versionName, int dataVersion,
                                  int gameType, int difficulty, boolean hardcore, long dayTime)
            throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(raw);
        out.writeByte(10);
        writeName(out, "");
        {
            out.writeByte(10);
            writeName(out, "Data");
            tagString(out, "LevelName", levelName);
            tagInt(out, "DataVersion", dataVersion);
            tagInt(out, "GameType", gameType);
            tagByte(out, "Difficulty", (byte) difficulty);
            tagByte(out, "Hardcore", (byte) (hardcore ? 1 : 0));
            tagLong(out, "DayTime", dayTime);
            out.writeByte(10);
            writeName(out, "Version");
            tagString(out, "Name", versionName);
            tagInt(out, "Id", dataVersion);
            out.writeByte(0);
            out.writeByte(0);
        }
        out.writeByte(0);
        out.flush();
        return gzip(raw.toByteArray());
    }

    /** 根复合标签里只有一个 LevelName(用于测试缺少 Data 的回退路径)。 */
    public static byte[] levelDatWithoutData(String levelName) throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(raw);
        out.writeByte(10);
        writeName(out, "");
        tagString(out, "LevelName", levelName);
        out.writeByte(0);
        out.flush();
        return gzip(raw.toByteArray());
    }

    /** 声明了一个超大 Byte_Array 的 NBT(用于验证元素上限保护)。 */
    public static byte[] oversizedArray(int declaredLength) throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(raw);
        out.writeByte(10);
        writeName(out, "");
        out.writeByte(7);
        writeName(out, "Huge");
        out.writeInt(declaredLength);
        out.write(new byte[16]);
        out.writeByte(0);
        out.flush();
        return gzip(raw.toByteArray());
    }

    /** 层层嵌套的复合标签(用于验证深度上限保护)。 */
    public static byte[] deeplyNested(int depth) throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(raw);
        out.writeByte(10);
        writeName(out, "");
        for (int i = 0; i < depth; i++) {
            out.writeByte(10);
            writeName(out, "l" + i);
        }
        for (int i = 0; i <= depth; i++) {
            out.writeByte(0);
        }
        out.flush();
        return gzip(raw.toByteArray());
    }

    /** 压缩炸弹:少量字节解压后非常大。 */
    public static byte[] compressionBomb(int inflatedBytes) throws IOException {
        return gzip(new byte[inflatedBytes]);
    }

    public static byte[] gzip(byte[] data) throws IOException {
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) {
            gzip.write(data);
        }
        return compressed.toByteArray();
    }

    private static void writeName(DataOutputStream out, String name) throws IOException {
        byte[] bytes = name.getBytes(StandardCharsets.UTF_8);
        out.writeShort(bytes.length);
        out.write(bytes);
    }

    private static void tagString(DataOutputStream out, String name, String value) throws IOException {
        out.writeByte(8);
        writeName(out, name);
        writeName(out, value);
    }

    private static void tagInt(DataOutputStream out, String name, int value) throws IOException {
        out.writeByte(3);
        writeName(out, name);
        out.writeInt(value);
    }

    private static void tagLong(DataOutputStream out, String name, long value) throws IOException {
        out.writeByte(4);
        writeName(out, name);
        out.writeLong(value);
    }

    private static void tagByte(DataOutputStream out, String name, byte value) throws IOException {
        out.writeByte(1);
        writeName(out, name);
        out.writeByte(value);
    }
}
