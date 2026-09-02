package com.nexusai.domain.session;

import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.repository.session.entity.AttachmentRecord;
import com.nexusai.repository.session.mapper.AttachmentMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;

/**
 * Attachment 业务逻辑 · attachments 表（V64）= 大文件附件统一 contentId 注册中心。
 *
 * <p><b>WHY（附件双模式 + 统一附件表 contentId · 用户拍板 2026-09-02）</b>：
 * path（local-read 外部绝对路径）与 upload（multipart store 落盘）两类大文件附件全部
 * 注册本表，contentId = attachments 自增 id（全局唯一 + 持久化 DB）。模型消费与 F5 预览
 * 统一经 {@code contentId → getContent → path → 读盘}，跨 store / 重启无损。
 * ≤5MB 图片 base64 的 imagePasteIds 链路不并入本表（保持现状）。
 *
 * <p>注册来源（sourceType）：'path' = 外部绝对路径（后端直读，省 upload）；'upload' =
 * store 落盘路径（cache 目录）。
 *
 * <p><b>id 回填结论</b>：{@code @Id(keyType = KeyType.Auto)}（AttachmentRecord）——MyBatis-Flex
 * 1.10.0 对 KeyType.Auto 的 id 不进 INSERT 列清单（SQLite AUTOINCREMENT 自增分配），insert 后
 * 实体 id 经 MyBatis Jdbc3KeyGenerator 回填；若回填失败（{@code record.getId() == null}，理论兜底）
 * 则按「session_id + path + id DESC」查询取最新注册行 id（单用户桌面级 SQLite，足够安全），
 * 并 log.warn 暴露异常路径（fail loud）。
 */
@Service
public class AttachmentService {

    private static final Logger log = LoggerFactory.getLogger(AttachmentService.class);

    @Autowired private AttachmentMapper attachmentMapper;

    /**
     * 注册附件记录并返回 contentId（attachments 自增 id）。
     *
     * <p>插入非 id 全字段（path/sessionId 为 NOT NULL 必填）；id 由 SQLite AUTOINCREMENT 分配。
     * 消费/预览侧拿到返回的 contentId 后经 {@link #getContent}/{@link #getPath} 反查读盘。
     *
     * @param sessionId  所属会话 ID（DB 键；null/空 → IllegalArgumentException，禁止无主注册）
     * @param path       附件落盘/外部绝对路径（读盘唯一真源；null/空 → IllegalArgumentException）
     * @param mediaType  MIME 类型（可 null）
     * @param filename   显示名（可 null）
     * @param size       字节数（>0；未提供传 0）
     * @param sourceType 注册来源：'path'|'upload'（可 null——容忍旧调用方未标注）
     * @return 新注册附件记录的 contentId（long）
     */
    public long register(String sessionId, String path, String mediaType, String filename,
                         long size, String sourceType) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("AttachmentService.register: sessionId 不能为空");
        }
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("AttachmentService.register: path 不能为空");
        }
        AttachmentRecord rec = new AttachmentRecord();
        rec.setSessionId(sessionId);
        rec.setPath(path);
        rec.setMediaType(mediaType);
        rec.setFilename(filename);
        rec.setSize(size > 0 ? size : null);
        rec.setSourceType(sourceType);
        // created_at 必须 Java 侧落值：MyBatis-Flex insert()（非 insertSelective）把非 id 列全部写入，
        //   created_at=null 会显式传 NULL 绕过 V64 DEFAULT(datetime('now')) → NOT NULL 约束失败（实测）。
        //   对齐 MessageService created_at = OffsetDateTime.now().toString() 惯例（ISO 文本）。
        rec.setCreatedAt(OffsetDateTime.now().toString());
        attachmentMapper.insert(rec);

        Long id = rec.getId();
        if (id == null) {
            // [兜底] KeyType.Auto insert 后实体 id 未回填的环境（理论路径）→ 按会话+路径取最新注册行
            AttachmentRecord latest = attachmentMapper.selectOneByQuery(
                QueryWrapper.create()
                    .eq("session_id", sessionId)
                    .eq("path", path)
                    .orderBy("id", false));
            if (latest == null || latest.getId() == null) {
                throw new IllegalStateException(
                    "AttachmentService.register 无法取得新注册附件 id: sessionId=" + sessionId
                        + " path=" + path + "（insert 未回填且兜底查询无结果）");
            }
            id = latest.getId();
            if (log.isWarnEnabled()) {
                log.warn("[AttachmentService] insert 未回填自增 id，兜底按 session+path+id DESC 取回: "
                        + "sessionId={} path={} contentId={}", sessionId, path, id);
            }
        }
        if (log.isInfoEnabled()) {
            log.info("[AttachmentService] 注册附件: contentId={} sessionId={} path={} mediaType={} "
                    + "filename={} size={} sourceType={}", id, sessionId, path, mediaType, filename,
                rec.getSize(), sourceType);
        }
        return id;
    }

    /**
     * 按 contentId 查附件记录（统一解析 API）· 不存在 → null（调用方 fail loud 或跳过）。
     *
     * <p><b>WHY</b>：模型消费（PDF 分页 / 大图路径 / 媒体文本）与 F5 预览都走
     * {@code contentId → getContent}，杜绝各消费点自建 store 查询（跨 store 无差别）。
     *
     * @param contentId 附件表自增 id（= 出站 contentId）
     * @return 附件记录；不存在 → null
     */
    public AttachmentRecord getContent(long contentId) {
        if (contentId <= 0) {
            if (log.isDebugEnabled()) {
                log.debug("[AttachmentService] getContent 跳过非法 contentId: {}", contentId);
            }
            return null;
        }
        AttachmentRecord rec = attachmentMapper.selectOneById(contentId);
        if (rec == null && log.isDebugEnabled()) {
            log.debug("[AttachmentService] 附件记录不存在: contentId={}", contentId);
        }
        return rec;
    }

    /**
     * 按 contentId 取附件落盘/外部绝对路径 · 不存在 → null。
     *
     * @param contentId 附件表自增 id
     * @return path；附件不存在或 path 为空 → null
     */
    public String getPath(long contentId) {
        AttachmentRecord rec = getContent(contentId);
        if (rec == null) {
            return null;
        }
        String path = rec.getPath();
        if (path == null || path.isBlank()) {
            if (log.isWarnEnabled()) {
                log.warn("[AttachmentService] 附件记录 path 为空: contentId={}", contentId);
            }
            return null;
        }
        return path;
    }
}
