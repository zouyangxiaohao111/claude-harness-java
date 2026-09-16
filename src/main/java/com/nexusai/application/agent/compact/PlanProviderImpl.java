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
 *   <tr><td>{@link #copyPlanForResume(String, String)}</td><td>copyPlanForResume</td><td>plans.ts:164-231</td></tr>
 *   <tr><td>{@link #copyPlanForFork(String, String)}</td><td>copyPlanForFork</td><td>plans.ts:239-264</td></tr>
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

    /** [批 6] 「无会话」告警一次性开关（`AgentLoopContext:2712` 每 tool 轮构造 ⇒ 逐次打印会淹没日志）。 */
    private static final java.util.concurrent.atomic.AtomicBoolean NO_SESSION_WARNED =
        new java.util.concurrent.atomic.AtomicBoolean(false);

    /** 本会话 ID（slug 源，CC getSessionId() 等价）· [session-id-short] short 形态 sess-xxx。
     *  <p>[批 6] <b>无会话时为 null</b>（见 {@link #noSession}）—— 不再是随机兜底值。 */
    private final String sessionId;

    /**
     * <b>[批 6 2026-09-14 · 用户裁定 #4] 「无会话」显式降级标志</b>。
     *
     * <p>原实现 {@code sessionId == null ⇒ "sess-" + UUID.substring(0,8)} 现造随机 slug。该随机
     * slug 使 {@code getPlanFilePath} 的<b>读回路永远失效</b>（写入与读取各得一个不同随机 slug），
     * 且让假键流出本类。实测本分支<b>在生产可达</b>：生产无 {@code PlanProvider} bean
     * （{@code ToolRegistrationConfig:237 @Autowired(required=false)}）⇒ 5 个
     * {@code new PlanProviderImpl(...)} 调用点全是活路径，其中 4 处实参可为 null
     * （{@code PostCompactAttachmentRestorer:953 ctx.getSessionId()} ·
     * {@code CommandRegistrationConfig28:445 dispatcher 形参（契约明示可 null）} ·
     * {@code AgentLoopContext:2712 state.sessionId()（AgentState 无非空不变量）} ·
     * {@code SessionMemoryService:1771}）；仅 {@code ExitPlanModeTool:357} 因
     * {@code ToolUseContext.sessionId} 非空不变量而确定非空。
     *
     * <p>⛔ <b>不值 (a) 抛</b>（用户裁定）：{@code AgentLoopContext:2712} 在<b>每 tool 轮</b>的
     * 消息组装里跑，抛会炸主循环。⇒ (b) 显式降级：本 provider 一律「无 plan」
     * （{@code getPlanFilePath}/{@code getPlan} → null，不落盘）+ 构造期 ≥WARN；
     * 调用方按既有 null-provider / null-path 守卫跳过
     * （{@code PostCompactAttachmentRestorer:912-916}、{@code ExitPlanModeTool} 的
     * {@code filePath != null} 守卫、{@code shouldExcludeFromPostCompactRestore} 的
     * {@code planFilePath != null} 检查均已存在）。
     */
    private final boolean noSession;

    /** plans 目录（构造时确定 + mkdir，CC getPlansDirectory memoize 等价）。 */
    private final String plansDirectory;

    /**
     * 生产构造（默认 plans 目录）· 对齐 CC getPlansDirectory 默认分支
     * {@code join(getClaudeConfigHomeDir(), 'plans')}（plans.ts:100）。
     *
     * @param sessionId 当前会话 ID（short）。<b>[批 6]</b> null/空白 ⇒ 显式「无会话」降级
     *                  （见 {@link #noSession}）：不再现造随机 slug；本 provider 一律返回
     *                  「无 plan」+ 构造期 ≥WARN。CC 侧 {@code getSessionId()} 恒非 null
     *                  （进程级单例，{@code bootstrap/state.ts:326}）⇒ 本降级是本仓多会话
     *                  Web 架构下「确实拿不到会话」的显式出口，非对齐偏差。
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
        // [批 6 · 用户裁定 #4] 缺会话 ⇒ 显式「无会话」降级（⛔ 不现造 "sess-"+UUID 随机 slug）
        boolean absent = sessionId == null || sessionId.isBlank();
        this.noSession = absent;
        this.sessionId = absent ? null : sessionId;
        if (absent && NO_SESSION_WARNED.compareAndSet(false, true)) {
            log.warn("[PlanProviderImpl] 构造时无会话 ID ⇒ 本 provider 一律「无 plan」显式降级"
                + "（getPlanFilePath/getPlan → null，不落盘；⛔ 不再现造 \"sess-\"+UUID 随机 slug）。"
                + "调用方按既有 null-provider/null-path 守卫跳过。命中本告警请查是否有漏传 sessionId"
                + "（本告警仅打印一次）");
        }
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
        // [批 6] 无会话 ⇒ 无 slug 可派生（旧实现给随机 slug，使读/写回路必然错配）⇒ null。
        //   消费方已全部 null 安全（PostCompactAttachmentRestorer:482 的 planFilePath != null；
        //   planModeAttachment:684 的 null → ""；ExitPlanModeTool 的 filePath != null 守卫）。
        if (noSession) {
            if (log.isDebugEnabled()) {
                log.debug("[PlanProviderImpl] getPlanFilePath: 无会话 ⇒ 返回 null（agentId={}）", agentId);
            }
            return null;
        }
        String slug = sessionId;
        if (agentId == null) {
            return Path.of(plansDirectory, slug + ".md").toString();
        }
        return Path.of(plansDirectory, slug + "-agent-" + agentId + ".md").toString();
    }

    @Override
    public String getPlan(UUID agentId) {
        String path = getPlanFilePath(agentId);
        if (path == null) {
            // [批 6] 无会话：不读盘（也避免 Path.of(null) NPE）⇒ 与 ENOENT 同语义「无 plan」
            return null;
        }
        Path filePath = Path.of(path);
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
        // [批 6] 无会话 ⇒ 显式降级「不落盘」（与 getPlanFilePath/getPlan → null 同一契约）
        if (noSession) {
            log.warn("[PlanProviderImpl] {}: 无会话 ⇒ 跳过 plan 复制（不落盘；⛔ 不伪造 slug）", opName);
            return false;
        }
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
