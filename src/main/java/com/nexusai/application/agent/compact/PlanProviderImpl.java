package com.nexusai.application.agent.compact;

import com.nexusai.application.agent.attachment.AttachmentMessageDto;
import com.nexusai.application.agent.skill.NexusaiPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.UUID;

/**
 * PlanProvider 磁盘生产实现 · 对齐 CC {@code plans.ts} 读盘 + 写盘契约
 * （Open-ClaudeCode/src/utils/plans.ts:119-264）。
 *
 * <h2>CC 对应（行号标注）</h2>
 * <table>
 *   <tr><th>Java 方法</th><th>CC original</th><th>行号</th></tr>
 *   <tr><td>{@link #getPlanFilePath(UUID)}</td><td>getPlanFilePath(agentId)</td><td>plans.ts:119-129</td></tr>
 *   <tr><td>{@link #getPlan(UUID)}</td><td>getPlan(agentId)</td><td>plans.ts:135-144</td></tr>
 *   <tr><td>{@link #copyPlanForResume(UUID, String)}</td><td>copyPlanForResume</td><td>plans.ts:164-231</td></tr>
 *   <tr><td>{@link #copyPlanForFork(UUID, String)}</td><td>copyPlanForFork</td><td>plans.ts:239-264</td></tr>
 *   <tr><td>{@link #createPlanAttachmentIfNeeded(UUID)}</td><td>createPlanAttachmentIfNeeded</td><td>compact.ts:1470-1486</td></tr>
 * </table>
 *
 * <p><b>plans 目录（CC getPlansDirectory plans.ts:79-111）</b>: 默认
 * {@code join(getClaudeConfigHomeDir(), 'plans')}。Java 无 settings.plansDirectory 配置源
 * （CC plans.ts:84-97 的 settings 相对 cwd + 路径穿越校验无 Java 等价物）→ 只走默认分支，
 * 复用 {@link NexusaiPaths#getAppConfigHomeDir()}（决策 D1：写根 nexusai 自有根
 * {@code {user.home}/.{appName}}，弃 ~/.claude；CC 读兼容仅 D3 transcript 域，plans 无读回落）。
 * mkdirSync 等价在构造时执行（createDirectories 幂等，memoize 语义由「每会话构造一次」保证）。
 *
 * <p><b>slug（concern D3 · 拍板记录 2026-08-13）</b>: 本实现采用 <b>sessionId-as-slug</b>
 * （以 {@code sessionId} 作 slug，非 CC generateWordSlug 词对）。理由：稳定唯一、零新增词库依赖、
 * 读/写/注入契约（{@code {plansDir}/{slug}.md} / {@code {slug}-agent-{agentId}.md} 路径拼接）
 * 等价。故 {@code getPlanFilePath} 无需 CC 的 getPlanSlug 缓存/碰撞重试（plans.ts:32-49）。
 * 文件名语义偏离 CC「人类可读词对」（plans.ts:39-45 词库 + existsSync 冲突重试 10 次），
 * 若后续拍板改 word slug，需同步引入 generateWordSlug 等价词库 + 10 次冲突重试。
 *
 * <p><b>数据流日志</b>: 读成功 / ENOENT / 非 ENOENT 错误 / 目录创建失败 / 复制结果，
 * 均按 CLAUDE.md 规范（slf4j + 中文 + isDebugEnabled 包裹 debug）。
 */
public class PlanProviderImpl implements PlanProvider {

    private static final Logger log = LoggerFactory.getLogger(PlanProviderImpl.class);

    /** 本会话 ID（slug 源，CC getSessionId() 等价）· [session-id-short] short 形态 sess-xxx。 */
    private final String sessionId;

    /** plans 目录（构造时确定 + mkdir，CC getPlansDirectory memoize 等价）。 */
    private final String plansDirectory;

    /**
     * 生产构造（默认 plans 目录）· 对齐 CC getPlansDirectory 默认分支
     * {@code join(getClaudeConfigHomeDir(), 'plans')}（plans.ts:100）。
     *
     * @param sessionId 当前会话 ID（short；null → 随机兜底 short，CC getSessionId 恒非 null）
     */
    public PlanProviderImpl(String sessionId) {
        this(sessionId, null);
    }

    /**
     * 可注入 plans 目录构造（测试覆写 plans 目录）· 对齐 CC settings.plansDirectory 的可替换性
     * （plans.ts:84-97）。目录为 null/blank → 默认 {@link #defaultPlansDirectory()}。
     *
     * @param sessionId      当前会话 ID（short）
     * @param plansDirectory plans 目录覆写（null → 默认 ClaudeConfigHomeDir/plans）
     */
    public PlanProviderImpl(String sessionId, String plansDirectory) {
        this.sessionId = sessionId != null ? sessionId : "sess-" + UUID.randomUUID().toString().substring(0, 8);
        this.plansDirectory = plansDirectory != null && !plansDirectory.isBlank()
            ? plansDirectory
            : defaultPlansDirectory();
        ensureDirectory();
    }

    /** 默认 plans 目录 · CC original: join(getClaudeConfigHomeDir(), 'plans')（plans.ts:100）。
     *  决策 D1（nexusai 复刻版 .claude 改造）：写根切 nexusai 自有根 {user.home}/.{appName}。 */
    private static String defaultPlansDirectory() {
        return NexusaiPaths.getAppConfigHomePath().resolve("plans").toString();
    }

    /** mkdirSync(plansPath) 等价（plans.ts:104-108）· createDirectories 幂等，失败仅告警不抛。 */
    private void ensureDirectory() {
        try {
            Files.createDirectories(Path.of(plansDirectory));
        } catch (IOException e) {
            log.warn("[PlanProviderImpl] 创建 plans 目录失败: {}（后续读取将降级返回 null）", plansDirectory, e);
        }
    }

    @Override
    public String getPlanFilePath(UUID agentId) {
        String slug = sessionId;
        if (agentId == null) {
            return Path.of(plansDirectory, slug + ".md").toString();
        }
        return Path.of(plansDirectory, slug + "-agent-" + agentId + ".md").toString();
    }

    @Override
    public String getPlan(UUID agentId) {
        Path filePath = Path.of(getPlanFilePath(agentId));
        try {
            String content = Files.readString(filePath, StandardCharsets.UTF_8);
            if (log.isDebugEnabled()) {
                log.debug("[PlanProviderImpl] getPlan 读取成功: path={} chars={}", filePath, content.length());
            }
            return content;
        } catch (NoSuchFileException e) {
            // ENOENT → null（CC plans.ts:140 isENOENT 分支，不抛）
            if (log.isDebugEnabled()) {
                log.debug("[PlanProviderImpl] getPlan 文件不存在（ENOENT → null）: path={}", filePath);
            }
            return null;
        } catch (IOException e) {
            log.warn("[PlanProviderImpl] getPlan 读取失败（非 ENOENT → null，不抛）: path={}", filePath, e);
            return null;
        }
    }

    @Override
    public AttachmentMessageDto.PlanRef createPlanAttachmentIfNeeded(UUID agentId) {
        String planContent = getPlan(agentId);
        if (planContent == null) {
            if (log.isDebugEnabled()) {
                log.debug("[PlanProviderImpl] createPlanAttachmentIfNeeded: 无 plan 文件，返回 null（agentId={}）", agentId);
            }
            return null;
        }
        String planFilePath = getPlanFilePath(agentId);
        if (log.isDebugEnabled()) {
            log.debug("[PlanProviderImpl] createPlanAttachmentIfNeeded: 生成 plan_file_reference 引用（path={} chars={}）",
                planFilePath, planContent.length());
        }
        return new AttachmentMessageDto.PlanRef(planFilePath, planContent);
    }

    @Override
    public boolean copyPlanForResume(String targetSessionId, String sourceSlug) {
        // concern D/E 简化本地形式：读源文件成功 → 复制到目标 session 文件 → true；ENOENT → false。
        // CC recoverPlanFromMessages 恢复链（plans.ts:189-229）Java 无转录类型等价物，本期不实现。
        return copyPlanFile(targetSessionId, sourceSlug, "copyPlanForResume");
    }

    @Override
    public boolean copyPlanForFork(String targetSessionId, String sourceSlug) {
        // sessionId-as-slug 下目标新 slug 即 targetSessionId（CC plans.ts:252 getPlanSlug(targetSessionId) 等价），
        // 故与 resume 同复制语义：copyFile 源文件到目标 session 文件（防止原/分叉会话互相覆盖）。
        return copyPlanFile(targetSessionId, sourceSlug, "copyPlanForFork");
    }

    /**
     * 复制 plan 文件（copyPlanForResume / copyPlanForFork 共用）· CC copyFile 等价
     * （plans.ts:255 / 复用 writeFile 语义）。
     *
     * <p>读 {@code {sourceSlug}.md} → 写 {@code {targetSessionId}.md}。ENOENT → false 不抛
     * （CC copyPlanForFork plans.ts:258-260 isENOENT 分支）；其它 error → logError + false
     * （CC plans.ts:261-262）。
     */
    private boolean copyPlanFile(String targetSessionId, String sourceSlug, String opName) {
        if (sourceSlug == null || sourceSlug.isBlank() || targetSessionId == null) {
            if (log.isDebugEnabled()) {
                log.debug("[PlanProviderImpl] {}: sourceSlug/targetSessionId 缺失，返回 false", opName);
            }
            return false;
        }
        Path source = Path.of(plansDirectory, sourceSlug + ".md");
        Path target = Path.of(plansDirectory, targetSessionId + ".md");
        try {
            String content = Files.readString(source, StandardCharsets.UTF_8);
            Files.writeString(target, content, StandardCharsets.UTF_8);
            log.info("[PlanProviderImpl] {}: 复制 plan 文件成功 source={} target={} chars={}",
                opName, source, target, content.length());
            return true;
        } catch (NoSuchFileException e) {
            log.info("[PlanProviderImpl] {}: 源 plan 文件不存在（ENOENT → false）: {}", opName, source);
            return false;
        } catch (IOException e) {
            log.warn("[PlanProviderImpl] {}: 复制 plan 文件失败（→ false）: source={} target={}", opName, source, target, e);
            return false;
        }
    }
}
