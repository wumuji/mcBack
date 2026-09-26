package com.mcback.service;

/**
 * 备份/导出流程中的业务异常。
 *
 * <p>与「原存档的安全性」直接相关:任何一步失败都只影响这次备份,不会碰源目录。</p>
 */
public class BackupException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public BackupException(String message) {
        super(message);
    }

    public BackupException(String message, Throwable cause) {
        super(message, cause);
    }
}
