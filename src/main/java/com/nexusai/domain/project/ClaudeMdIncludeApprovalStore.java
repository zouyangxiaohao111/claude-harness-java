package com.nexusai.domain.project;

import com.nexusai.repository.project.entity.ClaudeMdIncludeApprovalRecord;
import com.nexusai.repository.project.mapper.ClaudeMdIncludeApprovalMapper;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * CLAUDE.md 外部 {@code @import} 审批态持久化服务（对齐 CC project config，Java 用 DB）。
 *
 * <p><b>WHY</b>：CC 真源 {@code config.hasClaudeMdExternalIncludesApproved} /
 * {@code hasClaudeMdExternalIncludesWarningShown}（config.ts:115-116）宿主是 <b>project config</b>
 * —— 落盘 ⇒ <b>重启后不重复弹审批</b>。Java 侧 {@code ClaudeMdController} 原实现是纯内存
 * {@code ConcurrentHashMap}（进程重启即丢 ⇒ 每次重启都重复弹一次审批）。本类落
 * {@code claude_md_include_approval} 表（V74）补上跨重启事实源。
 *
 * <p>⭐ <b>键归一单点</b>：本类<b>全部</b>读写入口先过
 * {@link ProjectService#normalizePathKey(String)}（同一包，故可直接用 package-private 实现 ——
 * 全仓<b>只有这一份</b>键归一，⛔ 不另写第二份）。
 * <p><b>WHY 必须归一（否则静默失效）</b>：控制器写侧的键来自
 * {@code AutoMemPaths.findCanonicalGitRoot(...)} → {@code Path.toString()}，Windows 上产
 * <b>反斜杠</b>（实测 {@code D:\code\ai_project\nexusai}）；而同一目录在别处可能以正斜杠出现。
 * 不归一则「写侧 {@code D:\x\y}、读侧 {@code D:/x/y}」落成<b>两行</b>/查不到 ⇒
 * <b>「批准了但不加载」</b>。归一同时折叠大小写（Windows 大小写不敏感）。
 *
 * <p><b>null-safe</b>：读不存在的键返回 {@code null}（无行 ≠ 未审批）；键归一为空串则读写均静默 no-op
 * （⛔ 不写空键行，否则所有解析不出的项目共用一行 = 跨项目串值）。
 *
 * <p><b>失败语义</b>：本类不吞异常 —— 写失败由调用方 best-effort 降级为纯内存（对齐
 * {@code McpNeedsAuthCache} 对 {@code McpNeedsAuthCacheStore} 的处理），读失败由调用方 fail-open
 * 按 CC 缺省 {@code false}（config.ts:146 / :147）。
 */
@Service
public class ClaudeMdIncludeApprovalStore {

    private static final Logger log = LoggerFactory.getLogger(ClaudeMdIncludeApprovalStore.class);

    @Autowired private ClaudeMdIncludeApprovalMapper claudeMdIncludeApprovalMapper;

    /**
     * 项目键下的两份审批态（同一行两列）· CC original:
     * {@code hasClaudeMdExternalIncludesApproved} / {@code hasClaudeMdExternalIncludesWarningShown}
     * （config.ts:115 / :116）。
     *
     * @param approved     外部 {@code @import} 是否获准
     * @param warningShown 审批警告是否已示（CC Dialog onDone 批准/拒绝<b>均</b>置位，config.ts:123-131）
     */
    public record State(boolean approved, boolean warningShown) {}

    /**
     * 读取项目键下的审批态；<b>无行 → null</b>（⛔ 与「有行但 approved=0」严格区分：前者 = CC 缺省
     * false，后者 = 用户明确拒绝过，二者对 WarningShown 的含义不同）。
     *
     * <p>两列同属一行 ⇒ 一次主键查询取回（⛔ 不拆两次查询：那是同一行的两次往返）。
     */
    public State read(String projectKey) {
        String key = ProjectService.normalizePathKey(projectKey);
        if (key.isEmpty()) {
            return null;
        }
        ClaudeMdIncludeApprovalRecord r = claudeMdIncludeApprovalMapper.selectOneById(key);
        if (r == null) {
            return null;
        }
        State state = new State(isTrue(r.getExternalIncludesApproved()),
            isTrue(r.getExternalIncludesWarningShown()));
        if (log.isDebugEnabled()) {
            log.debug("[ClaudeMdIncludeApprovalStore] read 项目键={} 命中 approved={} warningShown={}",
                key, state.approved(), state.warningShown());
        }
        return state;
    }

    /**
     * 保存项目键下的审批态（<b>一次 upsert 写两列</b>，幂等；键归一后落库）。
     *
     * <p>⚠️ <b>insert 分支必须显式填 {@code createdAt}/{@code updatedAt}</b>：MyBatis-Flex
     * {@code insert(entity)} <b>会把 null 字段一并写入</b>（不同于 {@code update(entity)} 默认忽略
     * null），NULL 会覆盖 DDL 的 {@code DEFAULT (datetime('now'))}（先例
     * {@code McpNeedsAuthCacheStore:59} 同一注释）。
     * <p>update 分支只设 {@code updatedAt}：{@code update(entity)} 忽略 null ⇒
     * {@code created_at} 不被覆盖（首次落库时间保持）。
     */
    public void save(String projectKey, boolean approved, boolean warningShown) {
        String key = ProjectService.normalizePathKey(projectKey);
        if (key.isEmpty()) {
            log.warn("[ClaudeMdIncludeApprovalStore] save 项目键归一后为空 ⇒ 不落库"
                + "（⛔ 不写空键行：会让所有解析不出的项目共用一行 = 跨项目串值）: rawKey={}", projectKey);
            return;
        }
        String now = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        ClaudeMdIncludeApprovalRecord r = new ClaudeMdIncludeApprovalRecord();
        r.setConfigKey(key);
        r.setExternalIncludesApproved(approved ? 1 : 0);
        r.setExternalIncludesWarningShown(warningShown ? 1 : 0);
        if (claudeMdIncludeApprovalMapper.selectOneById(key) != null) {
            r.setUpdatedAt(now);
            claudeMdIncludeApprovalMapper.update(r);
        } else {
            // 显式填时间戳，见方法 javadoc（insert 会带 NULL 覆盖 DB DEFAULT）
            r.setCreatedAt(now);
            r.setUpdatedAt(now);
            claudeMdIncludeApprovalMapper.insert(r);
        }
        if (log.isDebugEnabled()) {
            log.debug("[ClaudeMdIncludeApprovalStore] save 项目键={} approved={} warningShown={}（upsert 两列）",
                key, approved, warningShown);
        }
    }

    /** 列值 → boolean（0/1；null 视为 false —— NOT NULL 列理论上不出现）。 */
    private static boolean isTrue(Integer v) {
        return v != null && v != 0;
    }
}
