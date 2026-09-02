package com.nexusai.application.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.application.agent.memory.AutoMemPaths;
import com.nexusai.application.agent.skill.ClaudePaths;
import com.nexusai.application.agent.skill.NexusaiPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 内容替换持久化 · 镜像 CC sessionStorage.ts recordContentReplacement + insertContentReplacement.
 *
 * <h2>L1 / L2 契约（对齐 CC，必须保留）</h2>
 * <ul>
 *   <li><b>L1 行为</b>：跨 session 恢复 (/resume) 时，重新应用 content replacements，
 *       保证 prompt cache 稳定 + 工具结果决策不会变</li>
 *   <li><b>L2 契约</b>：
 *     <ul>
 *       <li>JSONL 文件格式：每行一个 entry，含 type='content-replacement'</li>
 *       <li>字段：{@code {type, sessionId, agentId, replacements: [{toolUseId, replacement}]}}</li>
 *       <li>append-only 写（CC 行为：永远追加，不在文件中删除）</li>
 *       <li>启动时反序列化已有的 entries</li>
 *     </ul>
 *   </li>
 *   <li><b>L3 实现</b>：Java NIO + Jackson JSON（CC 使用 Node fs + JSONL parse）</li>
 * </ul>
 *
 * <h2>CC 关键路径对齐</h2>
 * <pre>{@code
 * // sessionStorage.ts:1113-1130
 * async insertContentReplacement(replacements, agentId) {
 *   const entry = {
 *     type: 'content-replacement',
 *     sessionId: getSessionId(),
 *     agentId,
 *     replacements,
 *   }
 *   await this.trackWrite(async () => {
 *     ...
 *     await appendFile(this.#sessionFile, jsonStringify(entry) + '\n')
 *   })
 * }
 *
 * // sessionStorage.ts:1494-1499
 * export async function recordContentReplacement(replacements, agentId) {
 *   await getProject().insertContentReplacement(replacements, agentId)
 * }
 * }</pre>
 *
 * <h2>Java 端行为</h2>
 * <ul>
 *   <li>{@link #writeContentReplacement}：append 一行 JSONL 到 session.jsonl</li>
 * </ul>
 *
 * @see <a href="https://github.com/.../Open-Claude-code/blob/main/src/utils/sessionStorage.ts">CC sessionStorage.ts</a>
 */
public final class SessionStorage {

    private static final Logger log = LoggerFactory.getLogger(SessionStorage.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** session JSONL 文件名. */
    public static final String SESSION_FILE = "session.jsonl";

    /** subagent sidechain 子目录. 对齐 CC sessionStorage.ts:255-257 */
    public static final String SUBAGENTS_SUBDIR = "subagents";

    /** subagent sidechain 文件名前缀. 对齐 CC sessionStorage.ts:257 `agent-${agentId}.jsonl` */
    public static final String AGENT_FILE_PREFIX = "agent-";

    /** subagent sidechain 文件扩展名. */
    public static final String AGENT_FILE_EXT = ".jsonl";

    private SessionStorage() { /* utility class */ }

    // ════════════════════════════════════════════════════════════════════════
    // config-home 派生二基 · [S2] transcript 锚点迁 config-home（对齐 CC sessionStorage.ts:199-206）
    //   getProjectsDir()    = join(getClaudeConfigHomeDir(), 'projects')
    //   getProjectDir(cwd)  = join(getProjectsDir(), sanitizePath(cwd))
    //   sessionProjectDir(sessionId) = getProjectDir(getOriginalCwdLayer(sessionId))
    //   [决策 D1（nexusai 复刻版 .claude 改造）] getClaudeConfigHomeDir → nexusai 自有根
    //     {user.home}/.{appName}（弃 ~/.claude；claude projects 不再回落——transcript 完全隔离，2026-08-30 拍板）。
    // ════════════════════════════════════════════════════════════════════════

    /**
     * config-home projects 根 · 对齐 CC getProjectsDir()（sessionStorage.ts:199-200）。
     *
     * <p>迁移语义（transcript-reloc-wiring-complete）：transcript 从项目目录 {workspaceDir} 迁到
     * 用户级 config-home，供 automemory 自动记忆 / 跨会话内容查询 / AI 借用 Read 工具直接读取。
     *
     * <p><b>决策 D1（nexusai 复刻版 .claude 改造）</b>：写根统一切 nexusai 自有根
     * {@code {user.home}/.{appName}}（appName=spring.application.name，默认 nexusai，见
     * {@link NexusaiPaths#getAppConfigHomeDir()}），弃用 {@link ClaudePaths#getClaudeConfigHomeDir()}
     * （~/.claude）。claude projects 不再回落（2026-08-30 拍板：transcript 完全隔离，仅 nexusai）。
     *
     * @return {@code {user.home}/.{appName}/projects}
     */
    public static Path getProjectsDir() {
        // D1：写根切 nexusai（弃 ClaudePaths ~/.claude；claude projects 不再回落——transcript 完全隔离）
        return NexusaiPaths.getAppConfigHomePath().resolve("projects");
    }

    /**
     * 项目 slug 目录 · 对齐 CC getProjectDir(projectDir)（sessionStoragePortable.ts:323-329）：
     * {@code join(getProjectsDir(), sanitizePath(projectDir))}；projectRoot null → ""（sanitize 空串）。
     *
     * @param projectRoot 项目根（boundProject/originalCwd 层；null → 空串 → projects 根自身）
     * @return {@code {configHome}/projects/{sanitizePath(projectRoot)}}
     */
    public static Path getProjectDir(Path projectRoot) {
        return getProjectsDir().resolve(
            AutoMemPaths.sanitizePath(projectRoot == null ? "" : projectRoot.toString()));
    }

    /**
     * 按会话解析项目 slug 目录 · projectRoot 用稳定锚（boundProject/originalCwd 层，勿用 sessionCwd）：
     * {@code CwdResolution.getOriginalCwdLayer(sessionId)} 恒非 null（user.dir 兜底）。
     *
     * @param sessionId 会话 ID（null → 回落 user.dir 兜底层）
     * @return {@code {configHome}/projects/{sanitizePath(originalCwdLayer(sessionId))}}
     */
    public static Path sessionProjectDir(String sessionId) {
        return getProjectDir(Path.of(CwdResolution.getOriginalCwdLayer(sessionId)));
    }

    // ════════════════════════════════════════════════════════════════════════
    // transcript 路径三 seam · [cron-durable-session-fire] 已删 per-task 虚拟会话键 override，
    // 三 seam 回到纯 sessionId 解析（transcript 键 = 真实会话 UUID / 创建会话 UUID / null）。
    //   - 创建会话存活 DURABLE fire → RunRequest.sessionId=创建会话 UUID → 归创建会话文件；
    //   - 创建会话已关 DURABLE fire → RunRequest.sessionId=null → 本 seam 返回 null → 不写 transcript；
    //   - SESSION/普通路径 → 真实会话 UUID（零改动）。
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 获取 session 文件路径: {configHome}/projects/{slug}/{sessionId}/session.jsonl
     * <p>对齐 CC: getSessionFile() = join(getSessionDir(), sessionId + '.jsonl')
     * （S2 迁移：锚点从 workspaceDir 迁 config-home，seam 内部做 config-home 派生）
     */
    public static Path getSessionFile(Path workspaceDir, String sessionId) {
        if (workspaceDir == null || sessionId == null) {
            return null;
        }
        return getProjectDir(workspaceDir).resolve(sessionId).resolve(SESSION_FILE);
    }

    /**
     * 获取 subagent sidechain 文件路径: {configHome}/projects/{slug}/{sessionId}/subagents/agent-{agentId}.jsonl
     * <p>对齐 CC sessionStorage.ts:247-258 getAgentTranscriptPath(agentId):
     * <pre>
     * const base = join(projectDir, sessionId, 'subagents')
     * return join(base, `agent-${agentId}.jsonl`)
     * </pre>
     * R28-3.7 §1.3 修复: subagent records 必须写到 sidechain file (AgentTool resume 用),
     * 主线程 records 写到 session.jsonl (/resume 用).
     *
     * <p><b>[S2 迁移]</b>：锚点从 workspaceDir 迁 config-home（seam 内部做 config-home 派生）。
     * 注意：调用方传的 workspaceDir 必须是<b>稳定项目根</b>（boundProject/originalCwd 层），
     * 勿用 sessionCwd（会随 cd 漂移）。{@link com.nexusai.application.agent.subagent.AgentTranscript#getTranscriptPath}
     * 走同一 config-home 根（R1 统一 resolveSessionDir），双根分裂消除。
     *
     * @param workspaceDir 工作区根目录（项目根 / boundProject 层）
     * @param sessionId    会话 ID
     * @param agentId      Agent ID（必填，subagent 才有 sidechain）
     * @return per-agent sidechain JSONL 文件路径
     */
    public static Path getAgentTranscriptPath(Path workspaceDir, String sessionId, String agentId) {
        if (workspaceDir == null || sessionId == null || agentId == null) {
            return null;
        }
        return getProjectDir(workspaceDir).resolve(sessionId).resolve(SUBAGENTS_SUBDIR)
            .resolve(AGENT_FILE_PREFIX + agentId + AGENT_FILE_EXT);
    }

    /**
     * 写入一条 content replacement 记录（CC insertContentReplacement 镜像）.
     * <p>append-only: 文件已存在则追加，否则创建.
     *
     * <p><b>R28-3.7 §1.3 修复</b>：subagent records (agentId != null) 写入
     * sidechain 文件 {@code {workspaceDir}/{sessionId}/subagents/agent-{agentId}.jsonl}
     * 供 AgentTool resume 使用；主线程 records (agentId == null) 写入 session.jsonl
     * 供 /resume 使用。对齐 CC sessionStorage.ts:1200-1207.
     *
     * @param workspaceDir  工作区根目录
     * @param sessionId     会话 ID
     * @param agentId       Agent ID（null = 主线程，写 session.jsonl；非 null = subagent 写 sidechain）
     * @param toolUseId     工具调用 ID
     * @param replacement   已持久化的 preview 文本
     */
    public static void writeContentReplacement(Path workspaceDir, String sessionId,
                                                String agentId, String toolUseId,
                                                String replacement) {
        if (workspaceDir == null || sessionId == null || toolUseId == null || replacement == null) {
            return;
        }
        // §1.3: agentId != null → 写 sidechain file; 否则 → 写 session.jsonl
        Path file = (agentId != null)
            ? getAgentTranscriptPath(workspaceDir, sessionId, agentId)
            : getSessionFile(workspaceDir, sessionId);
        if (file == null) {
            log.warn("SessionStorage writeContentReplacement: could not resolve file path");
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            ObjectNode entry = JSON.createObjectNode();
            entry.put("type", "content-replacement");
            entry.put("sessionId", sessionId);
            if (agentId != null) entry.put("agentId", agentId);
            ArrayNode replacements = entry.putArray("replacements");
            ObjectNode rec = replacements.addObject();
            rec.put("toolUseId", toolUseId);
            rec.put("replacement", replacement);
            // append-only
            Files.writeString(file,
                JSON.writeValueAsString(entry) + "\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            log.debug("SessionStorage wrote content replacement: toolUseId={} sessionId={} agentId={} file={}",
                toolUseId, sessionId, agentId == null ? "main" : agentId, file);
        } catch (IOException e) {
            log.warn("SessionStorage writeContentReplacement failed: {}", e.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // [IMP-08] 压缩后全局状态 · reAppendSessionMetadata / getTranscriptPath（REQ-30）
    // ════════════════════════════════════════════════════════════════════════

    /** 尾窗口大小 · 对齐 CC sessionStoragePortable.ts:17 LITE_READ_BUF_SIZE = 65536 */
    public static final long LITE_READ_BUF_SIZE = 65536L;

    /**
     * 获取 transcript 路径 · 对齐 CC utils/sessionStorage.ts:202-205 getTranscriptPath:
     * <pre>
     * const projectDir = getSessionProjectDir() ?? getProjectDir(getOriginalCwd())
     * return join(projectDir, `${getSessionId()}.jsonl`)
     * </pre>
     * 路径形态为<b>扁平</b> {@code {configHome}/projects/{slug}/{sessionId}.jsonl}
     * （S2 迁移：锚点从 workspaceDir 迁 config-home，seam 内部做 config-home 派生；
     * 旧 {@link #getSessionFile} 是嵌套 session.jsonl，仅用于 content-replacement sidecar）。
     *
     * @param workspaceDir 工作区根目录（项目根 / boundProject 层，勿用 sessionCwd）
     * @param sessionId    会话 ID
     * @return transcript 路径 {@code {configHome}/projects/{slug}/{sessionId}.jsonl}；入参为 null 时 null
     */
    public static Path getTranscriptPath(Path workspaceDir, String sessionId) {
        if (workspaceDir == null || sessionId == null) {
            return null;
        }
        return getProjectDir(workspaceDir).resolve(sessionId + ".jsonl");
    }

    /**
     * 解析已存在的 transcript 文件 · <b>D3 读兼容</b>（决策 D1/D3：nexusai 复刻版 .claude 改造）。
     *
     * <p>写入根为 nexusai（{@code {user.home}/.{appName}/projects/{slug}/{sessionId}.jsonl}，
     * 见 {@link #getProjectsDir()}）。<b>用户拍板（2026-08-30）</b>：transcript 完全隔离——
     * resume 只支持 nexusai 自己写的会话，不读 claude {@code ~/.claude/projects}（前端 list 也是
     * nexusai 的 DB/文件，无 claude 兼容），故本方法仅查 nexusai 文件、缺失返回 {@code null}。
     *
     * <p><b>签名与 {@link #getTranscriptPath} 对齐</b>（同 {@code workspaceDir + sessionId}）：
     * transcript <b>读消费方</b>应调用本方法（已存在才返回，缺失不产出空串），而非直接
     * {@link #getTranscriptPath}（后者是纯 nexusai 写路径推导，不校验存在性）。
     *
     * <p>对齐 CC 读路径语义：CC 读 transcript 即 {@code join(getProjectsDir(), sessionId + '.jsonl')}
     * （sessionStorage.ts:202-205 getTranscriptPath 的消费侧），本方法在其上做 nexusai 存在性解析。
     *
     * @param workspaceDir 工作区根目录（项目根 / boundProject 层，勿用 sessionCwd）
     * @param sessionId    会话 ID
     * @return 已存在的 nexusai transcript 文件路径；不存在 → {@code null}
     */
    public static Path resolveExistingTranscript(Path workspaceDir, String sessionId) {
        if (workspaceDir == null || sessionId == null) {
            return null;
        }
        // 用户拍板（2026-08-30）：transcript 完全隔离——resume 只支持 nexusai 自己写的会话，
        //   不读 claude ~/.claude/projects（前端 list 也是 nexusai 的 DB/文件，无 claude 兼容）。
        Path projectDir = getProjectDir(workspaceDir);
        Path nexusaiFile = projectDir.resolve(sessionId + ".jsonl");
        if (Files.isRegularFile(nexusaiFile)) {
            return nexusaiFile;
        }
        if (log.isDebugEnabled()) {
            log.debug("SessionStorage resolveExistingTranscript: nexusai 无 transcript: path={}", nexusaiFile);
        }
        return null;
    }

    /**
     * re-append session 元数据到 transcript 尾 · 对齐 CC utils/sessionStorage.ts:721-829
     * {@code reAppendSessionMetadata(skipTitleRefresh=false)}。
     *
     * <p><b>WHY</b>: 压缩后消息数剧增，会把 custom-title / tag 等元数据挤出 64KB 尾窗口，
     * 导致 --resume 展示回退到自动生成标题（CC compact.ts:705-708 在压缩成功路径调用本方法）。
     *
     * <p>CC 行为（sessionStorage.ts:721-829）:
     * <ol>
     *   <li>append 前读尾窗口（LITE_READ_BUF_SIZE=64KB），吸收外部写者（SDK renameSession/
     *       tagSession）更新鲜的 custom-title/tag —— external-writer safety（:730-761）</li>
     *   <li>无条件重 append（即使值已在尾窗口内也不跳过，压缩后会话增长会把它推出窗口）</li>
     *   <li>append 顺序（:788-829）: last-prompt → custom-title → tag → agent-name →
     *       agent-color → agent-setting → mode → worktree-state → pr-link</li>
     * </ol>
     *
     * <p><b>Java 化差异</b>: CC 元数据来自 SessionStorage 实例缓存字段；Java 端元数据在
     * DB（SessionRecord.title 等），无 JSONL 元数据子系统（探查 07 ?1），故本方法接收
     * {@link SessionMetadata} 参数化元数据。生产调用点由 IMP-04/07/10 在压缩成功路径接入。
     *
     * @param workspaceDir 工作区根目录
     * @param sessionId    会话 ID
     * @param metadata     session 元数据（CC 字段全集；null 字段不 append）
     */
    public static void reAppendSessionMetadata(Path workspaceDir, String sessionId,
                                                SessionMetadata metadata) {
        if (workspaceDir == null || sessionId == null) {
            return;
        }
        Path transcript = getTranscriptPath(workspaceDir, sessionId);
        if (transcript == null) {
            return;
        }
        // 外部写者刷新: 读尾窗口，吸收更新鲜 custom-title/tag（CC sessionStorage.ts:729-766）
        String tail = readFileTail(transcript);
        String customTitle = absorbTailField(tail, "custom-title", "customTitle",
            metadata == null ? null : metadata.customTitle());
        String tag = absorbTailField(tail, "tag", "tag",
            metadata == null ? null : metadata.tag());
        try {
            Files.createDirectories(transcript.getParent());
            int appended = 0;
            // CC 顺序（sessionStorage.ts:788-829）: last-prompt 先写, 让 title/tag 更靠 EOF
            if (metadata != null && metadata.lastPrompt() != null) {
                ObjectNode entry = JSON.createObjectNode();
                entry.put("type", "last-prompt");
                entry.put("lastPrompt", metadata.lastPrompt());
                entry.put("sessionId", sessionId);
                appendEntry(transcript, entry);
                appended++;
            }
            if (customTitle != null) {
                ObjectNode entry = JSON.createObjectNode();
                entry.put("type", "custom-title");
                entry.put("customTitle", customTitle);
                entry.put("sessionId", sessionId);
                appendEntry(transcript, entry);
                appended++;
            }
            if (tag != null) {
                ObjectNode entry = JSON.createObjectNode();
                entry.put("type", "tag");
                entry.put("tag", tag);
                entry.put("sessionId", sessionId);
                appendEntry(transcript, entry);
                appended++;
            }
            if (metadata != null && metadata.agentName() != null) {
                appendStrEntry(transcript, "agent-name", "agentName", metadata.agentName(), sessionId);
                appended++;
            }
            if (metadata != null && metadata.agentColor() != null) {
                appendStrEntry(transcript, "agent-color", "agentColor", metadata.agentColor(), sessionId);
                appended++;
            }
            if (metadata != null && metadata.agentSetting() != null) {
                appendStrEntry(transcript, "agent-setting", "agentSetting", metadata.agentSetting(), sessionId);
                appended++;
            }
            if (metadata != null && metadata.mode() != null) {
                appendStrEntry(transcript, "mode", "mode", metadata.mode(), sessionId);
                appended++;
            }
            // worktree-state: JSON 字段名对齐 CC 为 worktreeSession（sessionStorage.ts:813）
            if (metadata != null && metadata.worktreeState() != null) {
                appendStrEntry(transcript, "worktree-state", "worktreeSession", metadata.worktreeState(), sessionId);
                appended++;
            }
            if (metadata != null && metadata.prNumber() != null && metadata.prUrl() != null
                    && metadata.prRepository() != null) {
                ObjectNode entry = JSON.createObjectNode();
                entry.put("type", "pr-link");
                entry.put("sessionId", sessionId);
                entry.put("prNumber", metadata.prNumber());
                entry.put("prUrl", metadata.prUrl());
                entry.put("prRepository", metadata.prRepository());
                entry.put("timestamp", java.time.OffsetDateTime.now().toString());
                appendEntry(transcript, entry);
                appended++;
            }
            log.info("SessionStorage reAppendSessionMetadata: sessionId={} appended={} customTitle={} "
                    + "tag={} transcript={}", sessionId, appended,
                customTitle != null, tag != null, transcript);
        } catch (IOException e) {
            log.warn("SessionStorage reAppendSessionMetadata failed: {}", e.getMessage());
        }
    }

    /**
     * Session 元数据 · 对齐 CC sessionStorage.ts reAppendSessionMetadata 的字段全集
     * （custom-title / tag / last-prompt / agent-name / agent-color / agent-setting / mode /
     * worktree-state / pr-link）。null 字段不 append（等价 CC {@code if (this.currentX)} 门控）。
     *
     * @param lastPrompt    上一条 user prompt（CC last-prompt entry）
     * @param customTitle   用户自定义标题（CC custom-title entry，字段 customTitle）
     * @param tag           会话 tag（CC tag entry，字段 tag）
     * @param agentName     agent 名（CC agent-name entry）
     * @param agentColor    agent 颜色（CC agent-color entry）
     * @param agentSetting  agent 配置（CC agent-setting entry）
     * @param mode          模式（CC mode entry）
     * @param worktreeState worktree 状态（CC worktree-state entry，JSON 字段 worktreeSession）
     * @param prNumber      PR 号（CC pr-link entry）
     * @param prUrl         PR URL（CC pr-link entry）
     * @param prRepository  PR 仓库（CC pr-link entry）
     */
    public record SessionMetadata(
        String lastPrompt,
        String customTitle,
        String tag,
        String agentName,
        String agentColor,
        String agentSetting,
        String mode,
        String worktreeState,
        String prNumber,
        String prUrl,
        String prRepository) {}

    // ── reAppendSessionMetadata helpers ──

    private static void appendStrEntry(Path transcript, String type, String field,
                                       String value, String sessionId) throws IOException {
        ObjectNode entry = JSON.createObjectNode();
        entry.put("type", type);
        entry.put(field, value);
        entry.put("sessionId", sessionId);
        appendEntry(transcript, entry);
    }

    private static void appendEntry(Path transcript, ObjectNode entry) throws IOException {
        Files.writeString(transcript, JSON.writeValueAsString(entry) + "\n",
            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /**
     * 追加一条 reasoning-duration entry 到扁平 transcript 尾 · <b>净新增</b>（非 CC 对齐——CC 无
     * reasoning 计时字段，自验 sessionStorage.ts:1706/2209-2252 的 turn_duration 是
     * system+subtype='turn_duration'+messageCount，非推理耗时）。
     *
     * <p><b>WHY</b>：后端测推理耗时（用户拍板 2026-08-24）双轨之一（DB messages 表 + transcript
     * 文件）。扁平 transcript（{@link #getTranscriptPath} 返回 {@code {configHome}/projects/{slug}/
     * {sessionId}.jsonl}）当前只含元数据/worktree-state entry（无消息级 entry），故新增独立
     * entry 类型 {@code 'reasoning-duration'} 而非给消息加字段。
     *
     * <p>Entry 形状（JSONL 一行）：{@code {type:'reasoning-duration', sessionId, messageId,
     * reasoningDurationMs, timestamp: Instant.now 毫秒}}。append-only + JSONL 兼容；现有 tail 窗口
     * 读者（absorbTailField 按 type 读取）对未知 type 忽略，向后兼容。
     *
     * <p><b>⚠️ workspaceDir 必须传【原始项目根】</b>（Path.of(CwdResolution.getOriginalCwdLayer(
     * sessionId))），不能传 sessionProjectDir(sessionId)——getTranscriptPath 内部再
     * getProjectDir(workspaceDir) 一次，传已派生 project dir 会双重包裹成
     * {@code {configHome}/projects/{configHome}/projects/{slug}}（AgentColorCommand:195 /
     * CompactConversation:997 均传原始项目根）。
     *
     * <p>best-effort：任一副属（workspaceDir/sessionId/messageId/durationMs null）或写失败 → no-op
     * （仅 log.warn 中文日志），不阻断主流程（对齐 reAppendSessionMetadata 容错风格）。
     *
     * @param workspaceDir        原始项目根（Path.of(CwdResolution.getOriginalCwdLayer(sessionId))）
     * @param sessionId           会话 ID（DB 键 "sess-xxx"）
     * @param messageId           产生该推理的 assistant 消息 id
     * @param reasoningDurationMs 推理耗时 ms；null → no-op（无 reasoning 不记录）
     */
    public static void appendReasoningDuration(Path workspaceDir, String sessionId,
                                               String messageId, Long reasoningDurationMs) {
        if (workspaceDir == null || sessionId == null || messageId == null || reasoningDurationMs == null) {
            return;
        }
        Path transcript = getTranscriptPath(workspaceDir, sessionId);
        if (transcript == null) {
            return;
        }
        try {
            Files.createDirectories(transcript.getParent());
            ObjectNode entry = JSON.createObjectNode();
            entry.put("type", "reasoning-duration");
            entry.put("sessionId", sessionId);
            entry.put("messageId", messageId);
            entry.put("reasoningDurationMs", reasoningDurationMs);
            entry.put("timestamp", Instant.now().toEpochMilli());
            appendEntry(transcript, entry);
            if (log.isDebugEnabled()) {
                log.debug("SessionStorage appendReasoningDuration: sessionId={} messageId={} durationMs={}",
                    sessionId, messageId, reasoningDurationMs);
            }
        } catch (IOException e) {
            log.warn("SessionStorage appendReasoningDuration 失败（不阻断主流程）: sessionId={} messageId={} err={}",
                sessionId, messageId, e.getMessage());
        }
    }

    /** 读尾窗口（LITE_READ_BUF_SIZE 64KB）· 对齐 CC readFileTailSync（sessionStorage.ts:2592-2609） */
    private static String readFileTail(Path file) {
        try {
            if (!Files.isRegularFile(file)) {
                return "";
            }
            long size = Files.size(file);
            if (size == 0) {
                return "";
            }
            long offset = Math.max(0, size - LITE_READ_BUF_SIZE);
            long len = size - offset;
            byte[] buf = Files.readAllBytes(file);
            return new String(buf, (int) offset, (int) len, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    /**
     * 吸收尾窗口内更新鲜的 type 字段 · 对齐 CC sessionStorage.ts:739-765
     * （tail 里 custom-title/tag 为外部写者权威；空串 → 清缓存 → 不 append）。
     */
    private static String absorbTailField(String tail, String type, String field, String fallback) {
        String tailValue = extractLastJsonStringField(tail, type, field);
        if (tailValue != null) {
            // `!== undefined` 区分"无匹配"与"空串匹配"（CC :745-748）: 空串 → null（不 append）
            return tailValue.isEmpty() ? null : tailValue;
        }
        return fallback;
    }

    /** 从尾窗口找最后一条 type 匹配的 entry 并提取字段值；无匹配返回 null。 */
    private static String extractLastJsonStringField(String tail, String type, String field) {
        if (tail == null || tail.isEmpty()) {
            return null;
        }
        String result = null;
        for (String line : tail.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || !trimmed.startsWith("{")) {
                continue;
            }
            try {
                JsonNode node = JSON.readTree(trimmed);
                if (!type.equals(node.path("type").asText())) {
                    continue;
                }
                JsonNode value = node.get(field);
                if (value != null && !value.isNull()) {
                    result = value.asText();
                }
            } catch (IOException ignored) {
                // 单行解析失败跳过（对齐 CC readFileTailSync 返回 '' 兜底）
            }
        }
        return result;
    }

    // ════════════════════════════════════════════════════════════════════════
    // [WF-2] worktree-state transcript 持久化 · writeWorktreeState / readWorktreeState
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 写 worktree-state 到 transcript · 对齐 CC sessionStorage.ts:2889-2916 {@code saveWorktreeState}.
     *
     * <p>CC {@code saveWorktreeState(worktreeSession)} 把 {@code project.currentSessionWorktree}
     * 落盘为 transcript JSONL 的一条 entry（{@code type='worktree-state'}），供 --resume 经
     * sessionRestore.ts:332-366 {@code restoreWorktreeForResume} 读回后 cd 进 worktree。
     *
     * <p>CC entry 形状（types/logs.ts:162-166 {@code WorktreeStateEntry}）:
     * <pre>{@code
     * { type: 'worktree-state', sessionId: UUID, worktreeSession: PersistedWorktreeSession | null }
     * }</pre>
     * {@code worktreeSession = null} 表示"退出 worktree"（exit 写 null，CC
     * {@code ExitWorktreeTool.ts:142 saveWorktreeState(null)}），resume 据此不回 cd。
     *
     * <p><b>Java 化差异</b>: CC 是 append-only（{@code appendEntryToFile(sessionFile, ...)}，
     * 且仅当 {@code sessionFile} 已存在时 eager 写）；Java 端无条件 append（对齐
     * {@link #appendEntry} 的 CREATE+APPEND 语义，等价 CC 首次 materialize 时兜底）。
     *
     * @param workspaceDir   工作区根目录（会话 projectRoot，transcript 所在目录）
     * @param sessionId      会话 ID（transcript 文件名 {@code {sessionId}.jsonl} 的 UUID 串）
     * @param worktreeSession worktree 会话 JSON 对象（null = 退出 worktree，写 JSON null）
     */
    public static void writeWorktreeState(Path workspaceDir, String sessionId,
                                          JsonNode worktreeSession) {
        if (workspaceDir == null || sessionId == null) {
            return;
        }
        Path transcript = getTranscriptPath(workspaceDir, sessionId);
        if (transcript == null) {
            return;
        }
        try {
            Files.createDirectories(transcript.getParent());
            ObjectNode entry = JSON.createObjectNode();
            entry.put("type", "worktree-state");
            entry.put("sessionId", sessionId);
            if (worktreeSession == null) {
                // CC saveWorktreeState(null) → worktreeSession: null（exit 清空）
                entry.putNull("worktreeSession");
            } else {
                entry.set("worktreeSession", worktreeSession);
            }
            appendEntry(transcript, entry);
            log.info("SessionStorage writeWorktreeState: sessionId={} worktreeSession={} transcript={}",
                sessionId, worktreeSession == null ? "null" : worktreeSession, transcript);
        } catch (IOException e) {
            log.warn("SessionStorage writeWorktreeState failed: {}", e.getMessage());
        }
    }

    /**
     * 从 transcript 读回 worktreeSession · 对齐 CC restoreSessionMetadata 读 transcript
     * （sessionStorage.ts:3605 {@code worktreeStates.set(entry.sessionId, entry.worktreeSession)}，
     * last-wins：最后一条 worktree-state entry 决定当前状态，exit 写 null 覆盖 enter）。
     *
     * <p>读取语义（对齐 CC sessionRestore.ts:332-366 restoreWorktreeForResume 的消费方契约）:
     * <ul>
     *   <li>最后一条 {@code worktree-state} entry 的 {@code worktreeSession} 为 JSON null
     *       （退出过）→ 返回 {@code null}（不恢复 worktree）</li>
     *   <li>无任何 {@code worktree-state} entry → 返回 {@code null}（从未进入 worktree）</li>
     *   <li>否则返回最后一条的非 null {@code worktreeSession} JSON 对象</li>
     * </ul>
     *
     * @param workspaceDir 工作区根目录
     * @param sessionId    会话 ID
     * @return 最后一条 worktreeSession JSON 对象；null = 退出/未进入 worktree 或读失败
     */
    public static JsonNode readWorktreeState(Path workspaceDir, String sessionId) {
        if (workspaceDir == null || sessionId == null) {
            return null;
        }
        Path transcript = getTranscriptPath(workspaceDir, sessionId);
        if (transcript == null || !Files.isRegularFile(transcript)) {
            return null;
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(transcript, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("SessionStorage readWorktreeState failed: {}", e.getMessage());
            return null;
        }
        JsonNode lastWorktreeSession = null;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.charAt(0) != '{') {
                continue;
            }
            try {
                JsonNode node = JSON.readTree(trimmed);
                if (!"worktree-state".equals(node.path("type").asText())
                        || !sessionId.equals(node.path("sessionId").asText())) {
                    continue;
                }
                JsonNode ws = node.get("worktreeSession");
                // last-wins：exit 写 null → 覆盖为 null（退出）；enter 写对象 → 覆盖为对象
                lastWorktreeSession = (ws == null || ws.isNull()) ? null : ws;
            } catch (IOException ignored) {
                // 单行解析失败跳过（对齐 CC parseJSONL 跳过脏行）
            }
        }
        return lastWorktreeSession;
    }

    // ════════════════════════════════════════════════════════════════════════
    // [R3] 会话删除 · deleteSessionFiles（双通道同步 · 对齐 CC cleanupTaskOutput/cleanupOldSessionFiles）
    //   DB 删行 + 文件侧清理同步：删 {projectDir(slug)}/{sessionId}.jsonl（transcript）+
    //   {projectDir(slug)}/{sessionId}/（session 目录：session.jsonl/subagents/tool-results）。
    //   双键都试：transcript 主文件按派生 UUID（state.sessionId()）写，session 目录按
    //   DB 原始键（"sess-xxx"）写 —— delete 时两个键形态的产物都尝试清理。
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 删除会话全部文件侧产物 · 对齐 CC cleanupTaskOutput + cleanupOldSessionFiles 的会话目录清理语义
     * （双通道同步：DB 删行 + 文件侧清理，删除后文件侧不再残留 transcript/sidecar）。
     *
     * <p>删除范围（config-home 项目 slug 目录内）：
     * <ul>
     *   <li>扁平 transcript {@code {configHome}/projects/{slug}/{sessionId}.jsonl}</li>
     *   <li>会话目录 {@code {configHome}/projects/{slug}/{sessionId}/}（session.jsonl / subagents /
     *       tool-results）</li>
     * </ul>
     *
     * <p><b>[session-id-short] 双键尝试（阶段 1）</b>：新 transcript 文件名按 short（{@code state.sessionId()}
     * = sess-xxx）写；阶段 1 保留第二键 {@code SessionKeys.canonicalUuid(sessionId)} 派生 UUID 分支
     * 以清理<b>存量</b>派生 UUID 命名的旧 transcript 文件（阶段 2 transcript 改名迁移完成后删第二键）。
     *
     * <p><b>best-effort</b>：文件不存在 / 删除失败 → log.warn 不抛（fail-loud 但不断 DB 删行）。
     *
     * @param workspaceDir 工作区根目录（项目根 / boundProject 层，勿用 sessionCwd）
     * @param sessionId    会话 ID（short，如 "sess-xxx"）
     */
    public static void deleteSessionFiles(Path workspaceDir, String sessionId) {
        if (workspaceDir == null || sessionId == null || sessionId.isBlank()) {
            return;
        }
        Path slugDir = getProjectDir(workspaceDir);
        // 双键形态（阶段1 兼容）：short 直键 + 存量派生 UUID 串（去重；阶段2 删第二键）
        List<String> keys = new ArrayList<>();
        keys.add(sessionId);
        String canonical = com.nexusai.common.SessionKeys.canonicalUuid(sessionId).toString();
        if (!canonical.equals(sessionId)) {
            keys.add(canonical);
        }
        for (String key : keys) {
            // 1) 扁平 transcript: {slug}/{key}.jsonl
            Path transcript = getTranscriptPath(workspaceDir, key);
            if (transcript != null) {
                deleteQuietly(transcript);
            }
            // 2) 会话目录: {slug}/{key}/（session.jsonl/subagents/tool-results 递归删）
            Path sessionDir = slugDir.resolve(key);
            if (Files.isDirectory(sessionDir)) {
                deleteRecursively(sessionDir);
            }
        }
        if (log.isInfoEnabled()) {
            log.info("SessionStorage deleteSessionFiles: sessionId={} 双键={} slugDir={} 文件侧清理完成"
                    + "（R3 双通道同步 · 对齐 CC cleanupTaskOutput/cleanupOldSessionFiles）",
                sessionId, keys, slugDir);
        }
    }

    /** 单文件 best-effort 删除 · 不存在/失败 → warn 不抛。 */
    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("SessionStorage deleteSessionFiles: 删除文件失败（best-effort）: {} err={}",
                file, e.getMessage());
        }
    }

    /** 递归删除目录（walk 倒序删文件再删目录）· 对齐 CC cleanupTaskOutput 会话目录清理。 */
    private static void deleteRecursively(Path dir) {
        try (Stream<Path> paths = Files.walk(dir)) {
            List<Path> sorted = paths.sorted(Comparator.reverseOrder()).toList();
            for (Path p : sorted) {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    log.warn("SessionStorage deleteSessionFiles: 递归删除失败（best-effort）: {} err={}",
                        p, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("SessionStorage deleteSessionFiles: 目录遍历失败（best-effort）: {} err={}",
                dir, e.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // [transcript-retention] 旧 transcript 留存清理 · 对齐 CC cleanup.ts:155-258
    //   cleanupOldSessionFiles()：遍历 {configHome}/projects/** 各项目目录，根下 .jsonl/.cast
    //   按 mtime < cutoff 删；会话目录 tool-results/(工具子目录/)文件按 mtime 删；空目录逐级
    //   tryRmdir 回收（toolDir → toolResultsDir → sessionDir → projectDir）。
    //   只删文件侧，不碰 DB messages/sessions（双通道铁律，DB 权威保留）。
    // ════════════════════════════════════════════════════════════════════════

    /** transcript 扩展名 · 对齐 CC cleanup.ts:183 entry.name.endsWith('.jsonl') */
    private static final String EXT_JSONL = ".jsonl";
    /** 录制扩展名 · 对齐 CC cleanup.ts:183 entry.name.endsWith('.cast') */
    private static final String EXT_CAST = ".cast";

    /**
     * 清理结果 · 对齐 CC CleanupResult{messages, errors}（cleanup.ts:33-36）。
     *
     * @param messages 删除的 session 文件数（CC message 计数语义 = 被删文件数）
     * @param errors   清理过程错误数（单文件失败计数，best-effort 继续）
     */
    public record CleanupResult(long messages, long errors) {}

    /**
     * cutoff 计算 · 对齐 CC getCutoffDate()（cleanup.ts:23-31）：
     * {@code now − cleanupPeriodDays×24h}；cleanupPeriodDays=0 → cutoff=now → 既有文件
     * （mtime&lt;now）全删，"=0 启动删既有"由数学自然达成，无需特判分支。
     *
     * @param cleanupPeriodDays 留存天数（对齐 CC {@code settings.cleanupPeriodDays ?? 30}）
     * @return 保留时间线（mtime &gt;= 返回值 保留，mtime &lt; 返回值 删除）
     */
    public static Instant getCutoffDate(int cleanupPeriodDays) {
        return Instant.now().minus(Duration.ofDays(cleanupPeriodDays));
    }

    /**
     * 清旧 session 文件 · 对齐 CC cleanupOldSessionFiles()（cleanup.ts:155-258）。
     *
     * <p>遍历 nexusai projects 写根（{@link #getProjectsDir()}）下各项目目录：
     * <ol>
     *   <li>项目目录根下 .jsonl/.cast 扁平 transcript 按 mtime &lt; cutoff 删（:182-194）</li>
     *   <li>会话目录下 tool-results/ 根文件 + tool-results/{toolDir}/ 内文件按 mtime 删（:195-251）</li>
     *   <li>空目录逐级 tryRmdir 回收：toolDir → toolResultsDir → sessionDir → projectDir（:246-254）</li>
     * </ol>
     *
     * <p><b>单根清理</b>（用户指正 2026-08-30）：D3「~/.claude 仅只读回落」不适用于删除侧——
     * 只删 nexusai 动态写根（~/.{appName}/projects），claude 根保持只读不碰。
     *
     * <p><b>双通道铁律</b>：只删文件侧（projects 根），不碰 DB messages/sessions
     * （DB 权威保留）。best-effort：单文件失败计 errors 继续（对齐 CC {@code catch { result.errors++ }}）。
     *
     * @param cutoff 保留时间线（mtime &gt;= cutoff 保留）
     * @return 删除数与错误数
     */
    public static CleanupResult cleanupOldSessionFiles(Instant cutoff) {
        long messages = 0;
        long errors = 0;
        // 用户指正（2026-08-30）：D3「~/.claude 仅只读回落」不适用于删除侧——
        // 清理只删 nexusai 动态写根（~/.{appName}/projects），claude 根保持只读不碰。
        CleanupResult partial = cleanupProjectsRoot(getProjectsDir(), cutoff);
        messages = partial.messages();
        errors = partial.errors();
        if (log.isInfoEnabled()) {
            log.info("SessionStorage cleanupOldSessionFiles: nexusai 写根清理完成"
                    + " 删除 {} 文件 错误 {} cutoff={}", messages, errors, cutoff);
        }
        return new CleanupResult(messages, errors);
    }

    /**
     * 清理单个 projects 根下旧 session 文件 · 对齐 CC cleanupOldSessionFiles()（cleanup.ts:155-258）。
     *
     * <p>遍历 {@code {projectsRoot}/**} 各项目目录：
     * <ol>
     *   <li>项目目录根下 .jsonl/.cast 扁平 transcript 按 mtime &lt; cutoff 删（:182-194）</li>
     *   <li>会话目录下 tool-results/ 根文件 + tool-results/{toolDir}/ 内文件按 mtime 删（:195-251）</li>
     *   <li>空目录逐级 tryRmdir 回收：toolDir → toolResultsDir → sessionDir → projectDir（:246-254）</li>
     * </ol>
     *
     * <p><b>双通道铁律</b>：只删文件侧（projects 根），不碰 DB messages/sessions
     * （DB 权威保留）。best-effort：单文件失败计 errors 继续（对齐 CC {@code catch { result.errors++ }}）。
     *
     * @param projectsDir 待清理的 projects 根（nexusai 写根）
     * @param cutoff      保留时间线（mtime &gt;= cutoff 保留）
     * @return 删除数与错误数
     */
    private static CleanupResult cleanupProjectsRoot(Path projectsDir, Instant cutoff) {
        long messages = 0;
        long errors = 0;
        // 对齐 CC :161-166：readdir(projectsDir) 失败（不存在/不可读）→ 返回空结果
        List<Path> projectDirs;
        try {
            projectDirs = listEntries(projectsDir);
        } catch (IOException e) {
            log.warn("SessionStorage cleanupOldSessionFiles: 读 projectsDir 失败（跳过清理）: {} err={}",
                projectsDir, e.getMessage());
            return new CleanupResult(0, 0);
        }
        for (Path projectDir : projectDirs) {
            if (!Files.isDirectory(projectDir)) {
                continue; // 对齐 CC :169 非目录跳过
            }
            // 对齐 CC :173-179：每项目目录一次 readdir，分区为文件与会话目录
            List<Path> entries;
            try {
                entries = listEntries(projectDir);
            } catch (IOException e) {
                errors++;
                continue;
            }
            for (Path entry : entries) {
                if (Files.isRegularFile(entry)) {
                    // 项目根下扁平 transcript（.jsonl/.cast）· 对齐 CC :182-194
                    String name = entry.getFileName().toString();
                    if (!name.endsWith(EXT_JSONL) && !name.endsWith(EXT_CAST)) {
                        continue;
                    }
                    try {
                        if (unlinkIfOld(entry, cutoff)) {
                            messages++;
                        }
                    } catch (IOException e) {
                        errors++; // 对齐 CC :192-194 catch { result.errors++ }
                    }
                } else if (Files.isDirectory(entry)) {
                    // 会话目录 · 对齐 CC :195-251：清 tool-results 后逐级 tryRmdir
                    Path sessionDir = entry;
                    Path toolResultsDir = sessionDir.resolve(ToolResultStorage.TOOL_RESULTS_SUBDIR);
                    List<Path> toolDirs;
                    try {
                        toolDirs = listEntries(toolResultsDir);
                    } catch (IOException e) {
                        // 对齐 CC :200-205：无 tool-results 目录 → 仍尝试回收空会话目录
                        tryRmdir(sessionDir);
                        continue;
                    }
                    for (Path toolEntry : toolDirs) {
                        if (Files.isRegularFile(toolEntry)) {
                            // tool-results 根下文件（无扩展名过滤）· 对齐 CC :208-221
                            try {
                                if (unlinkIfOld(toolEntry, cutoff)) {
                                    messages++;
                                }
                            } catch (IOException e) {
                                errors++; // 对齐 CC :219-221 catch { result.errors++ }
                            }
                        } else if (Files.isDirectory(toolEntry)) {
                            // tool-results/{toolDir}/* · 对齐 CC :222-247
                            Path toolDirPath = toolEntry;
                            List<Path> toolFiles;
                            try {
                                toolFiles = listEntries(toolDirPath);
                            } catch (IOException e) {
                                continue;
                            }
                            for (Path tf : toolFiles) {
                                if (!Files.isRegularFile(tf)) {
                                    continue; // 对齐 CC :231 只删文件
                                }
                                try {
                                    if (unlinkIfOld(tf, cutoff)) {
                                        messages++;
                                    }
                                } catch (IOException e) {
                                    errors++; // 对齐 CC :240-242 catch { result.errors++ }
                                }
                            }
                            tryRmdir(toolDirPath);
                        }
                    }
                    tryRmdir(toolResultsDir);
                    tryRmdir(sessionDir);
                }
            }
            tryRmdir(projectDir);
        }
        return new CleanupResult(messages, errors);
    }

    /** 读目录条目（完整路径列表）· readdir 失败抛 IOException（调用方捕获计 errors / 跳过）。 */
    private static List<Path> listEntries(Path dir) throws IOException {
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.toList();
        }
    }

    /**
     * stat.mtime &lt; cutoff → 删，返回是否删了 · 对齐 CC unlinkIfOld（cleanup.ts:134-145）。
     * stat/unlink 失败抛 IOException（调用方 catch 计 errors，对齐 CC {@code catch { result.errors++ }}）。
     */
    private static boolean unlinkIfOld(Path file, Instant cutoff) throws IOException {
        Instant mtime = Files.getLastModifiedTime(file).toInstant();
        if (mtime.isBefore(cutoff)) {
            Files.delete(file);
            return true;
        }
        return false;
    }

    /** 空目录回收 · 非空/不存在忽略（对齐 CC tryRmdir cleanup.ts:147-153）。 */
    private static void tryRmdir(Path dir) {
        try {
            Files.delete(dir);
        } catch (IOException ignored) {
            // 非空 / 不存在 → 忽略（对齐 CC tryRmdir catch{} 静默）
        }
    }
}
