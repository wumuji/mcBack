package com.mcbackup.util;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/**
 * 最小可用的 NBT 读取器(零依赖),足以读取 {@code level.dat}。
 *
 * <p>安全约束(防止损坏文件或恶意文件导致内存暴涨):</p>
 * <ul>
 *   <li>文件本身不超过 {@link #MAX_FILE_BYTES};</li>
 *   <li>解压后总输出不超过 {@link #MAX_INFLATED_BYTES}(防压缩炸弹);</li>
 *   <li>嵌套深度不超过 {@link #MAX_DEPTH};</li>
 *   <li>单个数组元素数不超过 {@link #MAX_ARRAY_ELEMENTS};</li>
 *   <li>整棵树解析的元素总数不超过 {@link #MAX_TOTAL_ELEMENTS}。</li>
 * </ul>
 *
 * <p>数值类型统一映射为 {@link Long}(整形)与 {@link Double}(浮点),
 * 字符串为 {@link String},列表为 {@link List},复合标签为 {@link LinkedHashMap},
 * {@code TAG_Byte_Array} 为 {@code byte[]},{@code TAG_Int_Array}/{@code TAG_Long_Array}
 * 为 {@code int[]}/{@code long[]}。</p>
 */
public final class NbtReader {

    public static final long MAX_FILE_BYTES = 8L * 1024 * 1024;
    public static final long MAX_INFLATED_BYTES = 32L * 1024 * 1024;
    public static final int MAX_DEPTH = 64;
    public static final int MAX_ARRAY_ELEMENTS = 1 << 20;
    public static final long MAX_TOTAL_ELEMENTS = 2_000_000L;

    private static final int TAG_END = 0;
    private static final int TAG_BYTE = 1;
    private static final int TAG_SHORT = 2;
    private static final int TAG_INT = 3;
    private static final int TAG_LONG = 4;
    private static final int TAG_FLOAT = 5;
    private static final int TAG_DOUBLE = 6;
    private static final int TAG_BYTE_ARRAY = 7;
    private static final int TAG_STRING = 8;
    private static final int TAG_LIST = 9;
    private static final int TAG_COMPOUND = 10;
    private static final int TAG_INT_ARRAY = 11;
    private static final int TAG_LONG_ARRAY = 12;

    /** NBT 读取失败(文件损坏、格式不符、超出安全上限)。 */
    public static class NbtException extends IOException {
        private static final long serialVersionUID = 1L;

        public NbtException(String message) {
            super(message);
        }
    }

    private NbtReader() {
    }

    /**
     * 读取 NBT 文件并返回根复合标签。
     *
     * <p>自动识别 gzip / zlib / 未压缩三种形式(Minecraft 的 level.dat 是 gzip)。</p>
     */
    public static Map<String, Object> readFile(Path file) throws IOException {
        long size;
        try {
            size = Files.size(file);
        } catch (IOException e) {
            throw new NbtException("无法读取文件大小: " + e.getMessage());
        }
        if (size <= 0) {
            throw new NbtException("文件为空");
        }
        if (size > MAX_FILE_BYTES) {
            throw new NbtException("文件过大(" + size + " 字节),不是预期的 level.dat");
        }

        try (InputStream raw = new PushbackInputStream(
                new BufferedInputStream(Files.newInputStream(file), 8192), 2)) {
            PushbackInputStream pushback = (PushbackInputStream) raw;
            byte[] magic = new byte[2];
            int read = pushback.read(magic);
            if (read > 0) {
                pushback.unread(magic, 0, read);
            }
            InputStream decompressed = wrapCompression(pushback, magic, read);
            CountingInputStream counting = new CountingInputStream(decompressed, MAX_INFLATED_BYTES);
            try (DataInputStream in = new DataInputStream(new BufferedInputStream(counting, 8192))) {
                return readRoot(in);
            }
        }
    }

    private static InputStream wrapCompression(PushbackInputStream in, byte[] magic, int read) throws IOException {
        if (read >= 2 && (magic[0] & 0xFF) == 0x1F && (magic[1] & 0xFF) == 0x8B) {
            return new GZIPInputStream(in);
        }
        if (read >= 2 && (magic[0] & 0xFF) == 0x78) {
            int second = magic[1] & 0xFF;
            if (second == 0x01 || second == 0x5E || second == 0x9C || second == 0xDA) {
                return new InflaterInputStream(in, new Inflater());
            }
        }
        return in;
    }

    private static Map<String, Object> readRoot(DataInputStream in) throws IOException {
        int type = in.readUnsignedByte();
        if (type != TAG_COMPOUND) {
            throw new NbtException("根标签不是 Compound(实际 type=" + type + ")");
        }
        readName(in);
        Cursor cursor = new Cursor();
        Object root = readPayload(in, TAG_COMPOUND, 0, cursor);
        if (!(root instanceof Map<?, ?> map)) {
            throw new NbtException("根标签解析结果不是 Compound");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            result.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return result;
    }

    private static String readName(DataInputStream in) throws IOException {
        int length = in.readUnsignedShort();
        if (length == 0) {
            return "";
        }
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static Object readPayload(DataInputStream in, int type, int depth, Cursor cursor) throws IOException {
        if (depth > MAX_DEPTH) {
            throw new NbtException("嵌套层级超过上限 " + MAX_DEPTH);
        }
        switch (type) {
            case TAG_BYTE:
                return (long) in.readByte();
            case TAG_SHORT:
                return (long) in.readShort();
            case TAG_INT:
                return (long) in.readInt();
            case TAG_LONG:
                return in.readLong();
            case TAG_FLOAT:
                return (double) in.readFloat();
            case TAG_DOUBLE:
                return in.readDouble();
            case TAG_BYTE_ARRAY: {
                int length = checkLength(in.readInt(), "byte[]");
                cursor.add(length);
                byte[] data = new byte[length];
                in.readFully(data);
                return data;
            }
            case TAG_STRING:
                return readName(in);
            case TAG_LIST: {
                int elementType = in.readUnsignedByte();
                int length = checkLength(in.readInt(), "list");
                cursor.add(length);
                List<Object> list = new ArrayList<>(Math.min(length, 4096));
                for (int i = 0; i < length; i++) {
                    list.add(readPayload(in, elementType, depth + 1, cursor));
                }
                return list;
            }
            case TAG_COMPOUND: {
                Map<String, Object> compound = new LinkedHashMap<>();
                while (true) {
                    int childType = in.readUnsignedByte();
                    if (childType == TAG_END) {
                        return compound;
                    }
                    String name = readName(in);
                    cursor.add(1);
                    compound.put(name, readPayload(in, childType, depth + 1, cursor));
                }
            }
            case TAG_INT_ARRAY: {
                int length = checkLength(in.readInt(), "int[]");
                cursor.add(length);
                int[] data = new int[length];
                for (int i = 0; i < length; i++) {
                    data[i] = in.readInt();
                }
                return data;
            }
            case TAG_LONG_ARRAY: {
                int length = checkLength(in.readInt(), "long[]");
                cursor.add(length);
                long[] data = new long[length];
                for (int i = 0; i < length; i++) {
                    data[i] = in.readLong();
                }
                return data;
            }
            default:
                throw new NbtException("未知的标签类型: " + type);
        }
    }

    private static int checkLength(int length, String what) throws NbtException {
        if (length < 0) {
            throw new NbtException(what + " 长度为负: " + length);
        }
        if (length > MAX_ARRAY_ELEMENTS) {
            throw new NbtException(what + " 元素过多: " + length);
        }
        return length;
    }

    /** 累计元素数量,防止构造出超大树。 */
    private static final class Cursor {
        private long total;

        void add(long count) throws NbtException {
            total += count;
            if (total > MAX_TOTAL_ELEMENTS) {
                throw new NbtException("NBT 元素总数超过上限 " + MAX_TOTAL_ELEMENTS);
            }
        }
    }

    /** 统计解压输出,防止压缩炸弹。 */
    private static final class CountingInputStream extends FilterInputStream {
        private final long limit;
        private long count;

        CountingInputStream(InputStream in, long limit) {
            super(in);
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) {
                count(1);
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = super.read(buffer, offset, length);
            if (read > 0) {
                count(read);
            }
            return read;
        }

        @Override
        public long skip(long n) throws IOException {
            long skipped = super.skip(n);
            if (skipped > 0) {
                count(skipped);
            }
            return skipped;
        }

        private void count(long delta) throws NbtException {
            count += delta;
            if (count > limit) {
                throw new NbtException("解压输出超过上限 " + limit + " 字节");
            }
        }
    }
}
