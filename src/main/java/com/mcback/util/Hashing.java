package com.mcback.util;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 文件哈希(可选完整性校验)。
 *
 * <p>默认不做整包 SHA-256:GB 级备份要多读一遍磁盘。只有用户在设置里打开
 * 「完整校验」后才会计算,并写进备份清单,便于日后核对。</p>
 *
 * <p>计算过程是流式的,固定 64KB 缓冲区,与文件大小无关的内存占用。</p>
 */
public final class Hashing {

    private static final int BUFFER_SIZE = 64 * 1024;

    private Hashing() {
    }

    /** 计算文件的 SHA-256,返回小写十六进制字符串。 */
    public static String sha256(Path file) throws IOException {
        MessageDigest digest = newDigest();
        byte[] buffer = new byte[BUFFER_SIZE];
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file), BUFFER_SIZE)) {
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** 计算文件哈希;失败时返回空字符串(调用方按「未校验」处理)。 */
    public static String sha256Quietly(Path file) {
        try {
            return sha256(file);
        } catch (IOException | RuntimeException e) {
            Log.warn("计算 SHA-256 失败: %s (%s)", file, e.getMessage());
            return "";
        }
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("运行时不支持 SHA-256", e);
        }
    }
}
