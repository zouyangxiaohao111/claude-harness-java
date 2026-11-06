package com.nexusai.kb.app.config;

import java.io.InputStream;
import java.nio.file.Path;

/**
 * 知识库文件存储抽象（批次 W8）。
 *
 * <p><b>用户 2026-11-05 裁定</b>：「文件服务跟其他的一样，保留接口兼容；
 * 如果有 minio 那直接无缝接入，没有就本地」。
 *
 * <p>⇒ 接口只有三个动作（写 / 读 / 删），本地与对象存储的实现差异封装在实现类里。
 * ⛔ 不要在接口里加「列举」「签名 URL」之类只有对象存储才有的能力 ——
 * 那会让本地实现被迫抛异常，用户第一次点就先撞墙。
 *
 * <p><b>⚠️ 与既有代码的关系</b>：本仓当前入库直接落
 * {@code nexusai.knowledge.storage-dir}（见 {@code KnowledgeController#storageDir}）。
 * 本接口是它的<b>抽象化</b>，不是替换 —— {@link LocalFileStorage} 就是落在同一个根目录，
 * 所以已入库的文件仍然可读，不需要迁移。
 */
public interface KbFileStorage {

    /** provider 名（local / minio / cos）。 */
    String provider();

    /** 本机此刻能否真正读写（配了凭据 + 有实现）。 */
    boolean available();

    /**
     * 写入一个文件。
     *
     * @param key         相对键（如 {@code kbId/knowledgeId/文件名}）
     * @param in          内容
     * @param size        字节数（对象存储需要；未知传 -1）
     * @return 实际落地的路径或对象键
     */
    String write(String key, InputStream in, long size);

    /**
     * 读取一个文件。
     *
     * @param key 相对键
     * @return 内容流
     */
    InputStream read(String key);

    /**
     * 删除一个文件（不存在时静默成功 —— 删除是幂等操作）。
     *
     * @param key 相对键
     */
    void delete(String key);

    /** 该键在本地文件系统上的路径（仅本地实现有意义；对象存储返回 null）。 */
    default Path localPath(String key) {
        return null;
    }
}
