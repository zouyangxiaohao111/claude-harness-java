package com.nexusai.wechatlog.locate;

import java.nio.file.Path;
import java.util.List;

/**
 * 一个微信账号的数据目录视图。
 *
 * @param xwechatFilesRoot {@code xwechat_files} 根目录
 * @param accountDir       账号目录（含 {@code db_storage/}）
 * @param wxid             账号 wxid（目录名去掉尾部 {@code _<hash>}）
 * @param version          识别出的微信大版本：4 或 3
 */
public record WeChatAccount(Path xwechatFilesRoot, Path accountDir, String wxid, int version) {

    /** 该账号的 {@code db_storage} 目录。v4 才有。 */
    public Path dbStorageDir() {
        return accountDir.resolve("db_storage");
    }

    /** v3 时期的消息库目录（{@code Message/}）。 */
    public Path legacyMessageDir() {
        return accountDir.resolve("Message");
    }

    /** 该账号下所有加密的数据库文件（v4）。 */
    public List<Path> encryptedDatabases() {
        return WeChatDataLocator.listEncryptedDatabases(accountDir);
    }

    @Override
    public String toString() {
        return "WeChatAccount{wxid=" + wxid + ", version=" + version
                + ", dir=" + accountDir + "}";
    }
}
