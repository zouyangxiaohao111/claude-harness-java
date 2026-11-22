package com.nexusai.wechatlog.crypto;

/**
 * SQLCipher 解密过程中的错误。
 *
 * <p>独立于其它异常类型，便于上层把「密钥不对」与「文件读不到」区分开 ——
 * 前者需要重新取密钥（要提权），后者只是路径问题，用户体验差别很大。
 */
public class SqlCipherException extends RuntimeException {

    public SqlCipherException(String message) {
        super(message);
    }

    public SqlCipherException(String message, Throwable cause) {
        super(message, cause);
    }
}
