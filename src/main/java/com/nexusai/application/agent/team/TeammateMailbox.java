package com.nexusai.application.agent.team;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.tasks.TaskLock;
import com.nexusai.application.agent.tasks.TaskService;
import com.nexusai.application.agent.tasks.TaskSystemConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 文件级 teammate mailbox · 对齐 CC {@code utils/teammateMailbox.ts}（S12 落地）。
 *
 * <p>本类承载 CC teammateMailbox.ts 的 <b>TeammateMessage 磁盘契约</b>：
 * 信封 {@code {from, text, timestamp, read, color?, summary?}}（teammateMailbox.ts:43-50），
 * inbox 文件为 {@code TeammateMessage[]}（jsonStringify 2 空格缩进），供 CC 生态消费侧
 * （attachments.ts:3532 getTeammateMailboxAttachments → readUnreadMessages :3590）跨进程读取。
 * 这是 TaskUpdate owner 变更通知（TaskUpdateTool.ts:277-298）的写入目标。
 * <p><b>唯一文件操作层（S6 统一）</b>：本类是 Java 侧 teammate mailbox 的唯一文件操作实现，
 * 对齐 CC teammateMailbox.ts 全部 8 个文件操作函数（getInboxPath/ensureInboxDir/readMailbox/
 * readUnreadMessages/writeToMailbox/markMessageAsReadByIndex/markMessagesAsRead/clearMailbox/
 * markMessagesAsReadByPredicate，+ 已读操作族）。原 infra/util/FileBackedTeammateMailbox
 * （磁盘 schema = TeamMessage 8 字段，偏离 CC 信封）已删除 —— S6 闭环 S12-progress.md §6-②
 * 登记的「未来统一」残留（S12 concern S6-5：FileBacked 不含 CC 信封字段 from/text/color，
 * 无法表达 CC 跨进程消息形状）。W8-03 已删 {@code TeamMessageBus}/{@code TeamMessage}
 * （DEL-31/32，CC 无内存总线）；工具链（SendMessageTool/TeamCreateTool/TeamDeleteTool）改投本类
 * 文件层 + {@code TeamHelpers} 文件层。
 *
 * <p>实现要点（全部 grep 自验 CC teammateMailbox.ts，不信注释）：
 * <ul>
 *   <li>路径：{@code {configHome}/teams/{safeTeam}/inboxes/{safeAgent}.json}
 *       —— getInboxPath :56-66 + envUtils.ts:16-18 getTeamsDir；
 *       Java configHome = {@link TaskSystemConfig#getClaudeConfigHomeDir()}
 *       （决策 D1：nexusai.task.config-dir sysprop → nexusai 自有根 {user.home}/.{appName}，
 *       弃 CLAUDE_CONFIG_DIR env 与 ~/.claude，对齐 envUtils.ts:7-14 镜像）；</li>
 *   <li>写入流程（writeToMailbox :134-192）：ensureInboxDir（mkdir recursive，失败传播）
 *       → 原子建文件 {@code '[]'} flag wx（EEXIST 忽略，其他错误 log 后 return）
 *       → 锁 {@code {inbox}.json.lock}（:163，Java 复用 {@link TaskLock} 文件级锁）
 *       → 锁内 re-read → push {@code {...message, read:false}} → jsonStringify(messages, null, 2) 写回
 *       → 锁相错误/IO 错误 logError 不抛（:184-187）；</li>
 *   <li>readMailbox :84-108：ENOENT → {@code []}，其他错误 logError → {@code []}；</li>
 *   <li>task_assignment 消息体（:953-960 TaskAssignmentMessage）与 isTaskAssignment 解析器（:965-977）。</li>
 * </ul>
 *
 * <p>静态工具类：CC teammateMailbox.ts 为模块级函数、无实例状态（路径每次由 env 解析），
 * Java 静态方法即忠实表达（对齐 TaskSystemConfig 静态代理风格）；不注册 Spring Bean。
 */
public final class TeammateMailbox {

    private static final Logger log = LoggerFactory.getLogger(TeammateMailbox.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    /** task_assignment 消息 type 常量 · 对齐 CC teammateMailbox.ts:954 */
    public static final String TYPE_TASK_ASSIGNMENT = "task_assignment";

    /** 默认 team 名 · 对齐 CC teammateMailbox.ts:58 getInboxPath teamName || getTeamName() || 'default' */
    private static final String DEFAULT_TEAM = "default";

    private TeammateMailbox() {
    }

    /**
     * 队友消息信封 · 对齐 CC teammateMailbox.ts:43-50 TeammateMessage。
     *
     * <p>CC 定义：{@code from/text/timestamp/read} 必填 + {@code color?/summary?} 可选；
     * 可选键 undefined 时 JSON.stringify 省略（Java null 经
     * {@link JsonInclude#NON_NULL} 省略，磁盘形状一致）。
     *
     * @param from      CC original: from (teammateMailbox.ts:44) — 发送方 agent 名
     * @param text      CC original: text (teammateMailbox.ts:45) — 消息文本（task_assignment JSON 串）
     * @param timestamp CC original: timestamp (teammateMailbox.ts:46) — ISO-8601（new Date().toISOString()）
     * @param read      CC original: read (teammateMailbox.ts:47) — 已读标记；writeToMailbox 强制 false
     * @param color     CC original: color (teammateMailbox.ts:48) — 发送方颜色（可省略）
     * @param summary   CC original: summary (teammateMailbox.ts:49) — UI 预览摘要（可省略）
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TeammateMessage(String from, String text, String timestamp,
                                  boolean read, String color, String summary) {

        /**
         * 便捷构造 · 未读 + 无摘要（对齐 CC writeToMailbox push {...message, read:false} 的写入形态）。
         */
        public static TeammateMessage of(String from, String text, String timestamp, String color) {
            return new TeammateMessage(from, text, timestamp, false, color, null);
        }
    }

    /**
     * 任务分配消息 · 对齐 CC teammateMailbox.ts:953-960 TaskAssignmentMessage。
     *
     * <p>CC 定义：{@code type: 'task_assignment'} + taskId/subject/description/assignedBy/timestamp；
     * 作为 {@link TeammateMessage#text()} 的 JSON 文本承载（TaskUpdateTool.ts:280-287 构造）。
     */
    public record TaskAssignmentMessage(String type, String taskId, String subject,
                                        String description, String assignedBy, String timestamp) {
    }

    /**
     * teams 根目录 · 对齐 CC envUtils.ts:16-18 {@code getTeamsDir() = join(getClaudeConfigHomeDir(), 'teams')}。
     *
     * <p><b>决策 D1（nexusai 复刻版 .claude 改造，2026-08-30）</b>：configHome 经
     * {@link TaskSystemConfig#getClaudeConfigHomeDir()} 派生为 nexusai 自有根
     * {@code {user.home}/.{appName}}（弃 CLAUDE_CONFIG_DIR/~/.claude）。存量
     * {@code ~/.claude/teams} 迁移提示见 {@link TaskSystemConfig#getClaudeConfigHomeDir()}。
     */
    public static Path getTeamsDir() {
        return TaskSystemConfig.getClaudeConfigHomeDir().resolve("teams");
    }

    /**
     * 构造 inbox 文件路径 · 对齐 CC teammateMailbox.ts:56-66 getInboxPath：
     * {@code team = teamName || getTeamName() || 'default'}；team/agent 名经
     * sanitizePathComponent（tasks.ts:217-219）防路径穿越。
     *
     * <p>[team-cc-align fixPlan3 复盘 2026-08-25] CC 真源 teammateMailbox.ts:59-60 inbox 目录
     * team 名用 <b>sanitizePathComponent</b>（非 sanitizeName）——CC 自身 config 目录
     * （teamHelpers.ts:116 sanitizeName）与 inbox 目录（sanitizePathComponent）就是不一致的；
     * Java 原样（sanitizePathComponent）已对齐 CC，探查误读的 fixPlan3 不实施（保留原样）。
     */
    public static Path getInboxPath(String agentName, String teamName) {
        String team = resolveTeamName(teamName);
        String safeTeam = TaskService.sanitizePathComponent(team);
        String safeAgentName = TaskService.sanitizePathComponent(agentName);
        Path inboxDir = getTeamsDir().resolve(safeTeam).resolve("inboxes");
        Path fullPath = inboxDir.resolve(safeAgentName + ".json");
        if (log.isDebugEnabled()) {
            log.debug("[TeammateMailbox] getInboxPath: agent={}, team={}, fullPath={}", agentName, team, fullPath);
        }
        return fullPath;
    }

    /**
     * 解析 team 名 · 对齐 CC teammateMailbox.ts:58/:72 {@code teamName || getTeamName() || 'default'}。
     */
    private static String resolveTeamName(String teamName) {
        if (teamName != null && !teamName.isBlank()) {
            return teamName;
        }
        String contextTeam = TaskSystemConfig.getTeamName();
        if (contextTeam != null && !contextTeam.isBlank()) {
            return contextTeam;
        }
        return DEFAULT_TEAM;
    }

    /**
     * 确保 inbox 目录存在 · 对齐 CC teammateMailbox.ts:71-77 ensureInboxDir：
     * {@code mkdir(inboxDir, {recursive:true})}。mkdir 失败 <b>传播</b>（CC 无 catch，
     * writeToMailbox 随之 reject；Java 包装为未受检异常，不静默吞错）。
     */
    public static void ensureInboxDir(String teamName) {
        // [team-cc-align fixPlan3 复盘 2026-08-25] CC teammateMailbox.ts:71-77 同用
        // sanitizePathComponent；Java 原样对齐 CC，fixPlan3 不实施。
        String safeTeam = TaskService.sanitizePathComponent(resolveTeamName(teamName));
        Path inboxDir = getTeamsDir().resolve(safeTeam).resolve("inboxes");
        try {
            Files.createDirectories(inboxDir);
            if (log.isDebugEnabled()) {
                log.debug("[TeammateMailbox] 确保 inbox 目录存在: {}", inboxDir);
            }
        } catch (IOException e) {
            throw new IllegalStateException("创建 teammate inbox 目录失败: " + inboxDir, e);
        }
    }

    /**
     * 读取 inbox 全部消息 · 对齐 CC teammateMailbox.ts:84-108 readMailbox：
     * ENOENT → {@code []}（:100-102）；其他异常（含 JSON 解析失败）logError 后 {@code []}（:104-106）。
     */
    public static List<TeammateMessage> readMailbox(String agentName, String teamName) {
        Path inboxPath = getInboxPath(agentName, teamName);
        try {
            String content = Files.readString(inboxPath);
            List<TeammateMessage> messages = JSON.readValue(content, new TypeReference<List<TeammateMessage>>() {});
            if (log.isDebugEnabled()) {
                log.debug("[TeammateMailbox] readMailbox: 读到 {} 条消息, path={}", messages.size(), inboxPath);
            }
            return messages;
        } catch (NoSuchFileException e) {
            if (log.isDebugEnabled()) {
                log.debug("[TeammateMailbox] readMailbox: 文件不存在, 返回空列表, path={}", inboxPath);
            }
            return List.of();
        } catch (Exception e) {
            log.error("[TeammateMailbox] readMailbox 失败, 返回空列表: agent={}, path={}", agentName, inboxPath, e);
            return List.of();
        }
    }

    /**
     * 只读未读消息 · 对齐 CC teammateMailbox.ts:115-125 readUnreadMessages：
     * {@code readMailbox().filter(m => !m.read)}（消费侧 attachments.ts:3590 经此读收件箱）。
     */
    public static List<TeammateMessage> readUnreadMessages(String agentName, String teamName) {
        return readMailbox(agentName, teamName).stream()
            .filter(m -> !m.read())
            .toList();
    }

    /**
     * 写一条消息到 inbox · 对齐 CC teammateMailbox.ts:134-192 writeToMailbox。
     *
     * <p>完整流程（grep 自验 CC 源码）：
     * <ol>
     *   <li>ensureInboxDir（:139，mkdir 失败传播）</li>
     *   <li>getInboxPath（:141）</li>
     *   <li>原子建文件 {@code '[]'} flag 'wx'（:150-161）：EEXIST 忽略；其他错误 logError 后 <b>return</b>（不写）</li>
     *   <li>锁 {@code {inbox}.lock}（:165-169，LOCK_OPTIONS retries 10/min 5ms/max 100ms，teammateMailbox.ts:35-41；
     *       Java 复用 {@link TaskLock#withFileLockAndReturn} 的 NIO 文件锁 + 指数退避，锁文件同为
     *       {@code {inbox}.json.lock}）</li>
     *   <li>锁内 re-read 最新消息（:171）→ push {@code {...message, read:false}}（:173-176）
     *       → jsonStringify(messages, null, 2) 写回（:179-180）</li>
     *   <li>锁/IO 错误 logError 不抛（:184-187）</li>
     * </ol>
     *
     * @param recipientName 收件 agent 名（非 UUID）
     * @param message       待写消息（read 字段被强制覆盖为 false）
     * @param teamName      team 名（TaskUpdate 传 taskListId 按任务列表分箱；空则按 CC 回退链）
     */
    public static void writeToMailbox(String recipientName, TeammateMessage message, String teamName) {
        ensureInboxDir(teamName);

        Path inboxPath = getInboxPath(recipientName, teamName);
        if (log.isDebugEnabled()) {
            log.debug("[TeammateMailbox] writeToMailbox: recipient={}, from={}, path={}",
                recipientName, message.from(), inboxPath);
        }

        // 对齐 CC writeFile(inboxPath, '[]', {flag:'wx'})（:150）：原子创建，已存在则 EEXIST
        try {
            Files.writeString(inboxPath, "[]", StandardOpenOption.CREATE_NEW);
            if (log.isDebugEnabled()) {
                log.debug("[TeammateMailbox] writeToMailbox: 创建新 inbox 文件, path={}", inboxPath);
            }
        } catch (FileAlreadyExistsException e) {
            // EEXIST：inbox 文件已存在，正常继续（CC :157-159 忽略）
        } catch (IOException e) {
            // CC :160-161：非 EEXIST 错误 logError 后 return（不写消息）
            log.error("[TeammateMailbox] writeToMailbox: 创建 inbox 文件异常, recipient={}, path={}",
                recipientName, inboxPath, e);
            return;
        }

        try {
            // 锁内 re-read → push read:false → 2 空格缩进 JSON 写回（CC :165-181）
            TaskLock.withFileLockAndReturn(inboxPath, () -> {
                List<TeammateMessage> messages = new ArrayList<>(readMailbox(recipientName, teamName));
                // 对齐 CC :173-176 {...message, read:false}
                messages.add(new TeammateMessage(message.from(), message.text(), message.timestamp(),
                    false, message.color(), message.summary()));
                try {
                    writeMessages(inboxPath, messages);
                } catch (IOException e) {
                    // Supplier 不能抛受检异常；包装为未受检，由下方 catch(Exception) 统一 logError 不抛（CC :184-187）
                    throw new UncheckedIOException("teammate inbox 写回失败: " + inboxPath, e);
                }
                return null;
            });
        } catch (Exception e) {
            // CC :184-187：锁失败/IO 失败 logError，不抛
            log.error("[TeammateMailbox] writeToMailbox 失败: recipient={}, from={}, path={}",
                recipientName, message.from(), inboxPath, e);
        }
    }

    /**
     * 构造 task_assignment 消息文本 · 对齐 CC TaskUpdateTool.ts:280-287：
     * {@code JSON.stringify({type:'task_assignment', taskId, subject, description, assignedBy, timestamp})}
     * （紧凑 JSON；subject/description 为 null 时省略键，对齐 JSON.stringify 省略 undefined）。
     *
     * @param taskId      CC original: taskId (TaskUpdateTool.ts:281)
     * @param subject     CC original: existingTask.subject (TaskUpdateTool.ts:282) — 更新前任务标题
     * @param description CC original: existingTask.description (TaskUpdateTool.ts:283) — 更新前任务描述
     * @param assignedBy  CC original: assignedBy (TaskUpdateTool.ts:284) — getAgentName() || 'team-lead'
     * @param timestamp   CC original: timestamp (TaskUpdateTool.ts:285) — new Date().toISOString()
     * @return task_assignment JSON 文本（作为 TeammateMessage.text）
     */
    public static String taskAssignmentJson(String taskId, String subject, String description,
                                            String assignedBy, String timestamp) {
        Map<String, Object> assignment = new LinkedHashMap<>();
        assignment.put("type", TYPE_TASK_ASSIGNMENT);
        assignment.put("taskId", taskId);
        if (subject != null) {
            assignment.put("subject", subject);
        }
        if (description != null) {
            assignment.put("description", description);
        }
        assignment.put("assignedBy", assignedBy);
        assignment.put("timestamp", timestamp);
        try {
            return JSON.writeValueAsString(assignment);
        } catch (IOException e) {
            // 纯内存 Map 序列化，不可达（保留签名）
            throw new IllegalStateException("task_assignment JSON 序列化失败", e);
        }
    }

    /**
     * 解析消息文本是否为 task_assignment · 对齐 CC teammateMailbox.ts:965-977 isTaskAssignment：
     * {@code jsonParse(messageText) && parsed.type === 'task_assignment'} → 消息；非 JSON /
     * 非该 type → null（:971-976 catch）。
     *
     * @param messageText 消息文本（TeammateMessage.text）
     * @return 解析出的任务分配消息；非 task_assignment 返回 null
     */
    public static TaskAssignmentMessage isTaskAssignment(String messageText) {
        if (messageText == null) {
            return null;
        }
        try {
            JsonNode parsed = JSON.readTree(messageText);
            if (parsed != null && parsed.isObject()
                && TYPE_TASK_ASSIGNMENT.equals(parsed.path("type").asText())) {
                return new TaskAssignmentMessage(
                    parsed.path("type").asText(),
                    textOrNull(parsed, "taskId"),
                    textOrNull(parsed, "subject"),
                    textOrNull(parsed, "description"),
                    textOrNull(parsed, "assignedBy"),
                    textOrNull(parsed, "timestamp"));
            }
        } catch (Exception e) {
            // 非 JSON → null（CC :971-976 catch）
        }
        return null;
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    /**
     * 当前 ISO-8601 时间戳 · 对齐 CC {@code new Date().toISOString()}（UTC、毫秒精度 3 位小数）。
     */
    public static String isoNow() {
        return DateTimeFormatter.ISO_INSTANT.format(Instant.now().truncatedTo(ChronoUnit.MILLIS));
    }

    /**
     * 按索引标记单条消息已读 · 对齐 CC teammateMailbox.ts:201-271 markMessageAsReadByIndex。
     *
     * <p>锁内 re-read（:225）→ 越界静默 return（:230-235）→ 消息缺失/已读静默 return（:237-243）
     * → 标 read:true 写回（:245-247）；inbox 文件不存在静默 return（:253 ENOENT）。
     * 与 {@link #writeToMailbox} 共用同一锁文件 {@code {inbox}.json.lock}（TaskLock 文件级锁），
     * 跨写入方互斥成立。
     *
     * @param agentName    收件 agent 名（非 UUID）
     * @param teamName     team 名（空则按 CC 回退链）
     * @param messageIndex 消息索引（越界/已读/缺失静默返回）
     */
    public static void markMessageAsReadByIndex(String agentName, String teamName, int messageIndex) {
        Path inboxPath = getInboxPath(agentName, teamName);
        if (!Files.exists(inboxPath)) {
            // 对齐 CC :253 ENOENT return：inbox 文件不存在不抛异常
            return;
        }
        try {
            TaskLock.withFileLockAndReturn(inboxPath, () -> {
                // 锁内 re-read 最新状态（对齐 CC :225）
                List<TeammateMessage> messages = new ArrayList<>(readMailbox(agentName, teamName));
                if (messageIndex < 0 || messageIndex >= messages.size()) {
                    // 对齐 CC :230-235 越界 return（teammate 轮询时索引可能因并发已变，正常情况不崩）
                    if (log.isDebugEnabled()) {
                        log.debug("[TeammateMailbox] markMessageAsReadByIndex: 索引越界, 静默返回, agent={}, index={}, size={}",
                            agentName, messageIndex, messages.size());
                    }
                    return null;
                }
                TeammateMessage target = messages.get(messageIndex);
                if (target == null || target.read()) {
                    // 对齐 CC :237-243 消息缺失/已读 return（不重复写、不报错）
                    return null;
                }
                // 对齐 CC :245-247 messages[index] = {...message, read:true}
                messages.set(messageIndex, new TeammateMessage(target.from(), target.text(), target.timestamp(),
                    true, target.color(), target.summary()));
                try {
                    writeMessages(inboxPath, messages);
                } catch (IOException e) {
                    // Supplier 不能抛受检异常；包装为未受检，由下方 catch(Exception) 统一 logError 不抛（CC :256-259）
                    throw new UncheckedIOException("teammate inbox 标已读写回失败: " + inboxPath, e);
                }
                if (log.isDebugEnabled()) {
                    log.debug("[TeammateMailbox] markMessageAsReadByIndex: 已标记消息已读, agent={}, index={}",
                        agentName, messageIndex);
                }
                return null;
            });
        } catch (Exception e) {
            // CC :256-259：锁失败/IO 失败 logError，不抛
            log.error("[TeammateMailbox] markMessageAsReadByIndex 失败: agent={}, index={}, path={}",
                agentName, messageIndex, inboxPath, e);
        }
    }

    /**
     * 标记 inbox 全部消息已读 · 对齐 CC teammateMailbox.ts:279-342 markMessagesAsRead。
     *
     * <p>锁内 re-read（:302）→ 空列表 return（:305-310）→ 全部标 read:true 写回（:318-320）；
     * inbox 文件不存在静默 return（:326 ENOENT）。
     *
     * @param agentName 收件 agent 名（非 UUID）
     * @param teamName  team 名（空则按 CC 回退链）
     */
    public static void markMessagesAsRead(String agentName, String teamName) {
        Path inboxPath = getInboxPath(agentName, teamName);
        if (!Files.exists(inboxPath)) {
            // 对齐 CC :326 ENOENT return：inbox 文件不存在不抛异常
            return;
        }
        try {
            TaskLock.withFileLockAndReturn(inboxPath, () -> {
                List<TeammateMessage> messages = new ArrayList<>(readMailbox(agentName, teamName));
                if (messages.isEmpty()) {
                    // 对齐 CC :305-310 空列表 return
                    if (log.isDebugEnabled()) {
                        log.debug("[TeammateMailbox] markMessagesAsRead: inbox 无消息, 静默返回, agent={}", agentName);
                    }
                    return null;
                }
                // 对齐 CC :318-320 全部标 read:true（record 不可变，新建副本）
                List<TeammateMessage> updated = messages.stream()
                    .map(m -> new TeammateMessage(m.from(), m.text(), m.timestamp(),
                        true, m.color(), m.summary()))
                    .toList();
                try {
                    writeMessages(inboxPath, updated);
                } catch (IOException e) {
                    throw new UncheckedIOException("teammate inbox 全量标已读写回失败: " + inboxPath, e);
                }
                if (log.isDebugEnabled()) {
                    log.debug("[TeammateMailbox] markMessagesAsRead: 已标记全部消息已读, agent={}, count={}",
                        agentName, messages.size());
                }
                return null;
            });
        } catch (Exception e) {
            // CC :333-335：锁失败/IO 失败 logError，不抛
            log.error("[TeammateMailbox] markMessagesAsRead 失败: agent={}, path={}", agentName, inboxPath, e);
        }
    }

    /**
     * 清空 inbox（删除全部消息）· 对齐 CC teammateMailbox.ts:349-368 clearMailbox。
     *
     * <p>{@code writeFile(inboxPath, '[]', {flag:'r+'})}（:358）：Java 等价
     * {@link StandardOpenOption#WRITE}（无 CREATE）——文件不存在抛 {@link NoSuchFileException}
     * 后静默 return（:362），<b>不误创建</b> inbox 文件；其他 IOException logError（:365-367）。
     * CC 此函数不加文件锁（单次 truncate 写，无 read-modify-write 竞态），Java 同样不加。
     *
     * @param agentName 收件 agent 名（非 UUID）
     * @param teamName  team 名（空则按 CC 回退链）
     */
    public static void clearMailbox(String agentName, String teamName) {
        Path inboxPath = getInboxPath(agentName, teamName);
        try {
            Files.writeString(inboxPath, "[]", StandardOpenOption.WRITE);
            if (log.isDebugEnabled()) {
                log.debug("[TeammateMailbox] clearMailbox: 已清空 inbox, agent={}, path={}", agentName, inboxPath);
            }
        } catch (NoSuchFileException e) {
            // 对齐 CC :362 ENOENT return：文件不存在不误创建（r+ 语义）
            if (log.isDebugEnabled()) {
                log.debug("[TeammateMailbox] clearMailbox: inbox 文件不存在, 静默返回, path={}", inboxPath);
            }
        } catch (IOException e) {
            log.error("[TeammateMailbox] clearMailbox 失败: agent={}, path={}", agentName, inboxPath, e);
        }
    }

    /**
     * 按谓词选择性标记已读 · 对齐 CC teammateMailbox.ts:1101-1142 markMessagesAsReadByPredicate
     * （参数顺序 agentName/predicate/teamName 同 CC :1101-1105）。
     *
     * <p>锁内 re-read（:1114）→ 空列表 return（:1118）→
     * {@code map(m -> !m.read() && predicate.test(m) ? {...m, read:true} : m)}（:1122-1124）写回；
     * inbox 文件不存在静默 return（:1129 ENOENT）。
     *
     * @param agentName 收件 agent 名（非 UUID）
     * @param predicate 谓词：命中且未读的消息被标已读，其余保持原样
     * @param teamName  team 名（空则按 CC 回退链）
     */
    public static void markMessagesAsReadByPredicate(String agentName,
                                                     Predicate<TeammateMessage> predicate, String teamName) {
        Path inboxPath = getInboxPath(agentName, teamName);
        if (!Files.exists(inboxPath)) {
            // 对齐 CC :1129 ENOENT return：inbox 文件不存在不抛异常
            return;
        }
        try {
            TaskLock.withFileLockAndReturn(inboxPath, () -> {
                List<TeammateMessage> messages = new ArrayList<>(readMailbox(agentName, teamName));
                if (messages.isEmpty()) {
                    // 对齐 CC :1118 空列表 return
                    return null;
                }
                List<TeammateMessage> updated = messages.stream()
                    .map(m -> !m.read() && predicate.test(m)
                        ? new TeammateMessage(m.from(), m.text(), m.timestamp(),
                            true, m.color(), m.summary())
                        : m)
                    .toList();
                try {
                    writeMessages(inboxPath, updated);
                } catch (IOException e) {
                    throw new UncheckedIOException("teammate inbox 谓词标已读写回失败: " + inboxPath, e);
                }
                if (log.isDebugEnabled()) {
                    log.debug("[TeammateMailbox] markMessagesAsReadByPredicate: 谓词命中消息已标已读, agent={}, total={}",
                        agentName, messages.size());
                }
                return null;
            });
        } catch (Exception e) {
            // CC :1136-1140：锁失败/IO 失败 logError，不抛
            log.error("[TeammateMailbox] markMessagesAsReadByPredicate 失败: agent={}, path={}",
                agentName, inboxPath, e);
        }
    }

    /**
     * 渲染 teammate mailbox 消息为 XML · 对齐 CC teammateMailbox.ts:638-654
     * formatTeammateMessages（{@code TEAMMATE_MESSAGE_TAG='teammate-message'}，constants/xml.ts:52）：
     * 逐条 {@code <teammate-message teammate_id="{from}"[ color="{color}"][ summary="{summary}"]>\n{text}\n</teammate-message>}，
     * 多条 {@code \n\n} join；color/summary 非空才写属性（CC {@code m.color ? ... : ''}）。
     *
     * <p><b>Batch2 B1 消费方</b>：{@code AgentLoopContext.maybeInjectTeammateMailbox} 把 leader inbox
     * 未读 teammate 消息经本方法渲染为 meta user message 注入 LLM（对齐 CC messages.ts:3847-3857）。
     * 注意 tag 为 {@code teammate-message}（连字符），与 {@code AutonomousAgentLoop.formatAsTeammateMessage}
     * 一致（CC TEAMMATE_MESSAGE_TAG 真源，constants/xml.ts:52）。
     *
     * @param messages 待渲染消息列表（可空/含 null → 空串）
     * @return XML 文本；空列表 → ""
     */
    public static String formatTeammateMessages(List<TeammateMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        List<String> rendered = new ArrayList<>(messages.size());
        for (TeammateMessage m : messages) {
            if (m == null) {
                continue;
            }
            String colorAttr = (m.color() != null && !m.color().isBlank())
                ? " color=\"" + m.color() + "\"" : "";
            String summaryAttr = (m.summary() != null && !m.summary().isBlank())
                ? " summary=\"" + m.summary() + "\"" : "";
            String text = m.text() != null ? m.text() : "";
            rendered.add("<teammate-message teammate_id=\"" + m.from() + "\""
                + colorAttr + summaryAttr + ">\n" + text + "\n</teammate-message>");
        }
        return String.join("\n\n", rendered);
    }

    private static void writeMessages(Path inboxPath, List<TeammateMessage> messages) throws IOException {
        // 对齐 CC jsonStringify(messages, null, 2)（:180）：2 空格缩进（Jackson 默认 pretty printer）
        String json = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(messages);
        Files.writeString(inboxPath, json, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    // ════════════════════════════════════════════════════════════════════════
    // 协议字段层 P1-P9 · 对齐 CC teammateMailbox.ts:394-1095
    //
    // 结构化消息作为 TeammateMessage.text 的 JSON 文本承载，经 mailbox 在 leader 与
    // teammate 间跨进程传递。字段命名规则（grep 自验 CC 源码）：
    //   - permission_request / permission_response 用 snake_case（对齐 SDK can_use_tool）；
    //   - 其余消息用 camelCase（CC 类型定义直接 camelCase）。
    // 本层提供 typed record + create* 构造器 + is* 解析器 + isStructuredProtocolMessage
    // 路由判定（useInboxPoller 消费侧据此分流，attachments.ts 不得先行吞掉）。
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 紧凑 JSON 序列化 · 对齐 CC {@code jsonStringify(v)}（无缩进）。
     * create* 构造出的 record 经此序列化为 TeammateMessage.text。
     */
    public static String toCompactJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (IOException e) {
            // 纯内存对象序列化，不可达（保留签名）
            throw new IllegalStateException("协议消息 JSON 序列化失败", e);
        }
    }

    // ── P-8 idle_notification ────────────────────────────────────────────────

    /**
     * Idle 通知消息 · 对齐 CC teammateMailbox.ts:394-405 IdleNotificationMessage。
     * worker 经 Stop hook 转 idle 时通知 leader（inProcessRunner.ts:569-589）。
     *
     * @param type           CC original: type (teammateMailbox.ts:395) — 恒 'idle_notification'
     * @param from           CC original: from (:396) — 发送方 agent 名
     * @param timestamp      CC original: timestamp (:397) — ISO-8601
     * @param idleReason     CC original: idleReason (:399) — 'available'|'interrupted'|'failed'
     * @param summary        CC original: summary (:401) — 本轮最后 DM 摘要
     * @param completedTaskId CC original: completedTaskId (:402) — 完成任务 ID
     * @param completedStatus CC original: completedStatus (:403) — 'resolved'|'blocked'|'failed'
     * @param failureReason  CC original: failureReason (:404) — 失败原因
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record IdleNotificationMessage(String type, String from, String timestamp,
                                          String idleReason, String summary, String completedTaskId,
                                          String completedStatus, String failureReason) {
    }

    /**
     * 构造 idle 通知消息 · 对齐 CC teammateMailbox.ts:410-430 createIdleNotification。
     * timestamp 取当前 ISO-8601；可选字段 null 省略键（JSON.stringify 省略 undefined）。
     */
    public static IdleNotificationMessage createIdleNotification(String agentId, String idleReason,
            String summary, String completedTaskId, String completedStatus, String failureReason) {
        return new IdleNotificationMessage("idle_notification", agentId, isoNow(),
            idleReason, summary, completedTaskId, completedStatus, failureReason);
    }

    /** 解析消息文本是否为 idle 通知 · 对齐 CC teammateMailbox.ts:435-447 isIdleNotification。 */
    public static IdleNotificationMessage isIdleNotification(String messageText) {
        if (messageText == null) {
            return null;
        }
        try {
            JsonNode p = JSON.readTree(messageText);
            if (p != null && p.isObject() && "idle_notification".equals(p.path("type").asText())) {
                return new IdleNotificationMessage(
                    p.path("type").asText(),
                    textOrNull(p, "from"),
                    textOrNull(p, "timestamp"),
                    textOrNull(p, "idleReason"),
                    textOrNull(p, "summary"),
                    textOrNull(p, "completedTaskId"),
                    textOrNull(p, "completedStatus"),
                    textOrNull(p, "failureReason"));
            }
        } catch (Exception e) {
            // 非 JSON / 非 idle_notification → null（CC :443-446 catch）
        }
        return null;
    }

    // ── P-7 permission_request / permission_response（snake_case）──────────────

    /**
     * 权限请求消息（worker → leader）· 对齐 CC teammateMailbox.ts:453-462 PermissionRequestMessage。
     * 字段名对齐 SDK can_use_tool（snake_case）。
     *
     * @param type                  CC original: type (:454) — 恒 'permission_request'
     * @param requestId             CC original: request_id (:455)
     * @param agentId               CC original: agent_id (:456) — worker agent 名
     * @param toolName              CC original: tool_name (:457)
     * @param toolUseId             CC original: tool_use_id (:458)
     * @param description           CC original: description (:459)
     * @param input                 CC original: input (:460) — 序列化 tool input
     * @param permissionSuggestions CC original: permission_suggestions (:461)
     */
    public record PermissionRequestMessage(
            @JsonProperty("type") String type,
            @JsonProperty("request_id") String requestId,
            @JsonProperty("agent_id") String agentId,
            @JsonProperty("tool_name") String toolName,
            @JsonProperty("tool_use_id") String toolUseId,
            @JsonProperty("description") String description,
            @JsonProperty("input") Map<String, Object> input,
            @JsonProperty("permission_suggestions") List<Object> permissionSuggestions) {
    }

    /**
     * 权限响应成功负载 · 对齐 CC teammateMailbox.ts:470-477 response 字段。
     *
     * @param updatedInput     CC original: updated_input (:476)
     * @param permissionUpdates CC original: permission_updates (:477)
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PermissionResponsePayload(
            @JsonProperty("updated_input") Map<String, Object> updatedInput,
            @JsonProperty("permission_updates") List<Object> permissionUpdates) {
    }

    /**
     * 权限响应消息（leader → worker）· 对齐 CC teammateMailbox.ts:468-483 PermissionResponseMessage
     * （ControlResponseSchema / ControlErrorResponseSchema 形状的并集）。
     *
     * @param type      CC original: type (:470/:479) — 恒 'permission_response'
     * @param requestId CC original: request_id (:471/:480)
     * @param subtype   CC original: subtype (:472/:481) — 'success' | 'error'
     * @param error     CC original: error (:482) — subtype=error 时的拒绝原因
     * @param response  CC original: response (:473-477) — subtype=success 时的负载
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PermissionResponseMessage(
            @JsonProperty("type") String type,
            @JsonProperty("request_id") String requestId,
            @JsonProperty("subtype") String subtype,
            @JsonProperty("error") String error,
            @JsonProperty("response") PermissionResponsePayload response) {
    }

    /** 构造权限请求消息 · 对齐 CC teammateMailbox.ts:488-507 createPermissionRequestMessage。 */
    public static PermissionRequestMessage createPermissionRequestMessage(String requestId, String agentId,
            String toolName, String toolUseId, String description, Map<String, Object> input,
            List<Object> permissionSuggestions) {
        return new PermissionRequestMessage("permission_request", requestId, agentId, toolName,
            toolUseId, description, input, permissionSuggestions == null ? List.of() : permissionSuggestions);
    }

    /** 构造权限响应消息 · 对齐 CC teammateMailbox.ts:512-536 createPermissionResponseMessage。 */
    public static PermissionResponseMessage createPermissionResponseMessage(String requestId, String subtype,
            String error, Map<String, Object> updatedInput, List<Object> permissionUpdates) {
        if ("error".equals(subtype)) {
            return new PermissionResponseMessage("permission_response", requestId, "error",
                error == null ? "Permission denied" : error, null);
        }
        return new PermissionResponseMessage("permission_response", requestId, "success", null,
            new PermissionResponsePayload(updatedInput, permissionUpdates));
    }

    /** 解析权限请求消息 · 对齐 CC teammateMailbox.ts:541-553 isPermissionRequest。 */
    public static PermissionRequestMessage isPermissionRequest(String messageText) {
        if (messageText == null) {
            return null;
        }
        try {
            JsonNode p = JSON.readTree(messageText);
            if (p != null && p.isObject() && "permission_request".equals(p.path("type").asText())) {
                return new PermissionRequestMessage(
                    p.path("type").asText(),
                    textOrNull(p, "request_id"),
                    textOrNull(p, "agent_id"),
                    textOrNull(p, "tool_name"),
                    textOrNull(p, "tool_use_id"),
                    textOrNull(p, "description"),
                    mapOrNull(p, "input"),
                    listOrNull(p, "permission_suggestions"));
            }
        } catch (Exception e) {
            // 非 JSON / 非 permission_request → null
        }
        return null;
    }

    /** 解析权限响应消息 · 对齐 CC teammateMailbox.ts:558-570 isPermissionResponse。 */
    public static PermissionResponseMessage isPermissionResponse(String messageText) {
        if (messageText == null) {
            return null;
        }
        try {
            JsonNode p = JSON.readTree(messageText);
            if (p != null && p.isObject() && "permission_response".equals(p.path("type").asText())) {
                JsonNode resp = p.path("response");
                PermissionResponsePayload payload = resp.isObject()
                    ? new PermissionResponsePayload(mapOrNull(resp, "updated_input"), listOrNull(resp, "permission_updates"))
                    : null;
                return new PermissionResponseMessage(
                    p.path("type").asText(),
                    textOrNull(p, "request_id"),
                    textOrNull(p, "subtype"),
                    textOrNull(p, "error"),
                    payload);
            }
        } catch (Exception e) {
            // 非 JSON / 非 permission_response → null
        }
        return null;
    }

    // ── P-1 / P-2 sandbox_permission_request / response ───────────────────────

    /** Sandbox 主机模式 · 对齐 CC teammateMailbox.ts:587-589 hostPattern。 */
    public record SandboxHostPattern(String host) {
    }

    /**
     * Sandbox 权限请求消息（worker → leader）· 对齐 CC teammateMailbox.ts:576-592。
     * sandbox 运行时检测到非允许 host 的网络访问时触发。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SandboxPermissionRequestMessage(String type, String requestId, String workerId,
            String workerName, String workerColor, SandboxHostPattern hostPattern, long createdAt) {
    }

    /** Sandbox 权限响应消息（leader → worker）· 对齐 CC teammateMailbox.ts:597-607。 */
    public record SandboxPermissionResponseMessage(String type, String requestId, String host,
            boolean allow, String timestamp) {
    }

    /** 构造 sandbox 权限请求 · 对齐 CC teammateMailbox.ts:612-628（createdAt=Date.now()）。 */
    public static SandboxPermissionRequestMessage createSandboxPermissionRequestMessage(String requestId,
            String workerId, String workerName, String workerColor, String host) {
        return new SandboxPermissionRequestMessage("sandbox_permission_request", requestId, workerId,
            workerName, workerColor, new SandboxHostPattern(host), System.currentTimeMillis());
    }

    /** 构造 sandbox 权限响应 · 对齐 CC teammateMailbox.ts:633-645（timestamp=new Date().toISOString()）。 */
    public static SandboxPermissionResponseMessage createSandboxPermissionResponseMessage(String requestId,
            String host, boolean allow) {
        return new SandboxPermissionResponseMessage("sandbox_permission_response", requestId, host, allow, isoNow());
    }

    /** 解析 sandbox 权限请求 · 对齐 CC teammateMailbox.ts:650-662。 */
    public static SandboxPermissionRequestMessage isSandboxPermissionRequest(String messageText) {
        if (messageText == null) {
            return null;
        }
        try {
            JsonNode p = JSON.readTree(messageText);
            if (p != null && p.isObject() && "sandbox_permission_request".equals(p.path("type").asText())) {
                JsonNode hp = p.path("hostPattern");
                return new SandboxPermissionRequestMessage(
                    p.path("type").asText(),
                    textOrNull(p, "requestId"),
                    textOrNull(p, "workerId"),
                    textOrNull(p, "workerName"),
                    textOrNull(p, "workerColor"),
                    new SandboxHostPattern(hp.isObject() ? textOrNull(hp, "host") : null),
                    p.path("createdAt").asLong());
            }
        } catch (Exception e) {
            // 非 JSON / 非 sandbox_permission_request → null
        }
        return null;
    }

    /** 解析 sandbox 权限响应 · 对齐 CC teammateMailbox.ts:667-679。 */
    public static SandboxPermissionResponseMessage isSandboxPermissionResponse(String messageText) {
        if (messageText == null) {
            return null;
        }
        try {
            JsonNode p = JSON.readTree(messageText);
            if (p != null && p.isObject() && "sandbox_permission_response".equals(p.path("type").asText())) {
                return new SandboxPermissionResponseMessage(
                    p.path("type").asText(),
                    textOrNull(p, "requestId"),
                    textOrNull(p, "host"),
                    p.path("allow").asBoolean(),
                    textOrNull(p, "timestamp"));
            }
        } catch (Exception e) {
            // 非 JSON / 非 sandbox_permission_response → null
        }
        return null;
    }

    // ── P-3 / P-4 plan_approval_request / response ────────────────────────────

    /** 计划审批请求消息（teammate → leader）· 对齐 CC teammateMailbox.ts:684-697。 */
    public record PlanApprovalRequestMessage(String type, String from, String timestamp,
            String planFilePath, String planContent, String requestId) {
    }

    /** 计划审批响应消息（leader → teammate）· 对齐 CC teammateMailbox.ts:702-715。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PlanApprovalResponseMessage(String type, String requestId, boolean approved,
            String feedback, String timestamp, String permissionMode) {
    }

    /** 解析计划审批请求 · 对齐 CC teammateMailbox.ts:885-897。 */
    public static PlanApprovalRequestMessage isPlanApprovalRequest(String messageText) {
        if (messageText == null) {
            return null;
        }
        try {
            JsonNode p = JSON.readTree(messageText);
            if (p != null && p.isObject() && "plan_approval_request".equals(p.path("type").asText())) {
                // 对齐 CC PlanApprovalRequestMessageSchema.safeParse（teammateMailbox.ts:684-693）：
                // from/timestamp/planFilePath/planContent/requestId 全为 z.string() 必填，
                // 缺任一字段 → safeParse 失败 → null（Java 端 textOrNull 返回 null 即拒绝）。
                String from = textOrNull(p, "from");
                String timestamp = textOrNull(p, "timestamp");
                String planFilePath = textOrNull(p, "planFilePath");
                String planContent = textOrNull(p, "planContent");
                String requestId = textOrNull(p, "requestId");
                if (from == null || timestamp == null || planFilePath == null
                        || planContent == null || requestId == null) {
                    return null;
                }
                return new PlanApprovalRequestMessage(
                    p.path("type").asText(), from, timestamp, planFilePath, planContent, requestId);
            }
        } catch (Exception e) {
            // 非 JSON → null
        }
        return null;
    }

    /** 解析计划审批响应 · 对齐 CC teammateMailbox.ts:936-948。 */
    public static PlanApprovalResponseMessage isPlanApprovalResponse(String messageText) {
        if (messageText == null) {
            return null;
        }
        try {
            JsonNode p = JSON.readTree(messageText);
            if (p != null && p.isObject() && "plan_approval_response".equals(p.path("type").asText())) {
                // 对齐 CC PlanApprovalResponseMessageSchema.safeParse（teammateMailbox.ts:702-711）：
                // requestId/timestamp 为 z.string() 必填，approved 为 z.boolean() 必填；
                // feedback 可选，permissionMode 为 PermissionModeSchema().optional()（可选枚举，非法 → null）。
                String requestId = textOrNull(p, "requestId");
                JsonNode approvedNode = p.get("approved");
                String timestamp = textOrNull(p, "timestamp");
                if (requestId == null || timestamp == null
                        || approvedNode == null || !approvedNode.isBoolean()) {
                    return null;
                }
                String permissionMode = textOrNull(p, "permissionMode");
                if (permissionMode != null && !isLegalPermissionMode(permissionMode)) {
                    return null;
                }
                return new PlanApprovalResponseMessage(
                    p.path("type").asText(), requestId, approvedNode.asBoolean(),
                    textOrNull(p, "feedback"), timestamp, permissionMode);
            }
        } catch (Exception e) {
            // 非 JSON → null
        }
        return null;
    }

    // ── shutdown_request / shutdown_approved / shutdown_rejected（P-5）──────────

    /** shutdown 请求消息（leader → teammate）· 对齐 CC teammateMailbox.ts:720-732。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ShutdownRequestMessage(String type, String requestId, String from,
            String reason, String timestamp) {
    }

    /** shutdown 批准消息（teammate → leader）· 对齐 CC teammateMailbox.ts:737-750。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ShutdownApprovedMessage(String type, String requestId, String from,
            String timestamp, String paneId, String backendType) {
    }

    /** shutdown 拒绝消息（teammate → leader）· 对齐 CC teammateMailbox.ts:755-767。 */
    public record ShutdownRejectedMessage(String type, String requestId, String from,
            String reason, String timestamp) {
    }

    /** 构造 shutdown 请求 · 对齐 CC teammateMailbox.ts:772-784。 */
    public static ShutdownRequestMessage createShutdownRequestMessage(String requestId, String from, String reason) {
        return new ShutdownRequestMessage("shutdown_request", requestId, from, reason, isoNow());
    }

    /** 构造 shutdown 批准 · 对齐 CC teammateMailbox.ts:789-803。 */
    public static ShutdownApprovedMessage createShutdownApprovedMessage(String requestId, String from,
            String paneId, String backendType) {
        return new ShutdownApprovedMessage("shutdown_approved", requestId, from, isoNow(), paneId, backendType);
    }

    /** 构造 shutdown 拒绝 · 对齐 CC teammateMailbox.ts:808-820。 */
    public static ShutdownRejectedMessage createShutdownRejectedMessage(String requestId, String from, String reason) {
        return new ShutdownRejectedMessage("shutdown_rejected", requestId, from, reason, isoNow());
    }

    /** 解析 shutdown 请求 · 对齐 CC teammateMailbox.ts:868-880。 */
    public static ShutdownRequestMessage isShutdownRequest(String messageText) {
        if (messageText == null) {
            return null;
        }
        try {
            JsonNode p = JSON.readTree(messageText);
            if (p != null && p.isObject() && "shutdown_request".equals(p.path("type").asText())) {
                // 对齐 CC ShutdownRequestMessageSchema.safeParse（teammateMailbox.ts:720-728）：
                // requestId/from/timestamp 必填，reason 可选（z.string().optional()）。
                String requestId = textOrNull(p, "requestId");
                String from = textOrNull(p, "from");
                String timestamp = textOrNull(p, "timestamp");
                if (requestId == null || from == null || timestamp == null) {
                    return null;
                }
                return new ShutdownRequestMessage(
                    p.path("type").asText(), requestId, from, textOrNull(p, "reason"), timestamp);
            }
        } catch (Exception e) {
            // 非 JSON → null
        }
        return null;
    }

    /** 解析 shutdown 批准 · 对齐 CC teammateMailbox.ts:902-914。 */
    public static ShutdownApprovedMessage isShutdownApproved(String messageText) {
        if (messageText == null) {
            return null;
        }
        try {
            JsonNode p = JSON.readTree(messageText);
            if (p != null && p.isObject() && "shutdown_approved".equals(p.path("type").asText())) {
                // 对齐 CC ShutdownApprovedMessageSchema.safeParse（teammateMailbox.ts:737-746）：
                // requestId/from/timestamp 必填，paneId/backendType 可选。
                String requestId = textOrNull(p, "requestId");
                String from = textOrNull(p, "from");
                String timestamp = textOrNull(p, "timestamp");
                if (requestId == null || from == null || timestamp == null) {
                    return null;
                }
                return new ShutdownApprovedMessage(
                    p.path("type").asText(), requestId, from, timestamp,
                    textOrNull(p, "paneId"), textOrNull(p, "backendType"));
            }
        } catch (Exception e) {
            // 非 JSON → null
        }
        return null;
    }

    /** 解析 shutdown 拒绝 · 对齐 CC teammateMailbox.ts:919-931。 */
    public static ShutdownRejectedMessage isShutdownRejected(String messageText) {
        if (messageText == null) {
            return null;
        }
        try {
            JsonNode p = JSON.readTree(messageText);
            if (p != null && p.isObject() && "shutdown_rejected".equals(p.path("type").asText())) {
                // 对齐 CC ShutdownRejectedMessageSchema.safeParse（teammateMailbox.ts:755-763）：
                // requestId/from/reason/timestamp 全为 z.string() 必填。
                String requestId = textOrNull(p, "requestId");
                String from = textOrNull(p, "from");
                String reason = textOrNull(p, "reason");
                String timestamp = textOrNull(p, "timestamp");
                if (requestId == null || from == null || reason == null || timestamp == null) {
                    return null;
                }
                return new ShutdownRejectedMessage(
                    p.path("type").asText(), requestId, from, reason, timestamp);
            }
        } catch (Exception e) {
            // 非 JSON → null
        }
        return null;
    }

    // ── P-6 mode_set_request ─────────────────────────────────────────────────

    /** mode 设置请求消息（leader → teammate）· 对齐 CC teammateMailbox.ts:1019-1029。 */
    public record ModeSetRequestMessage(String type, String mode, String from) {
    }

    /** 构造 mode 设置请求 · 对齐 CC teammateMailbox.ts:1034-1043。 */
    public static ModeSetRequestMessage createModeSetRequestMessage(String mode, String from) {
        return new ModeSetRequestMessage("mode_set_request", mode, from);
    }

    /** 解析 mode 设置请求 · 对齐 CC teammateMailbox.ts:1048-1062。 */
    public static ModeSetRequestMessage isModeSetRequest(String messageText) {
        if (messageText == null) {
            return null;
        }
        try {
            JsonNode p = JSON.readTree(messageText);
            if (p != null && p.isObject() && "mode_set_request".equals(p.path("type").asText())) {
                // 对齐 CC ModeSetRequestMessageSchema.safeParse（teammateMailbox.ts:1019-1025）：
                // mode 为 PermissionModeSchema() 枚举（coreSchemas.ts:337-348 五值），
                // from 为 z.string() 必填；mode 非法/缺失、from 缺失 → safeParse 失败 → null。
                String mode = textOrNull(p, "mode");
                String from = textOrNull(p, "from");
                if (mode == null || from == null || !isLegalPermissionMode(mode)) {
                    return null;
                }
                return new ModeSetRequestMessage(p.path("type").asText(), mode, from);
            }
        } catch (Exception e) {
            // 非 JSON → null
        }
        return null;
    }

    // ── team_permission_update ────────────────────────────────────────────────

    /** team 权限规则 · 对齐 CC teammateMailbox.ts:985-990 rules 元素。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TeamPermissionRule(String toolName, String ruleContent) {
    }

    /** team 权限更新负载 · 对齐 CC teammateMailbox.ts:984-991 permissionUpdate。 */
    public record TeamPermissionUpdate(String type, List<TeamPermissionRule> rules,
            String behavior, String destination) {
    }

    /** team 权限更新消息（leader → teammates 广播）· 对齐 CC teammateMailbox.ts:983-996。 */
    public record TeamPermissionUpdateMessage(String type, TeamPermissionUpdate permissionUpdate,
            String directoryPath, String toolName) {
    }

    /** 解析 team 权限更新消息 · 对齐 CC teammateMailbox.ts:1001-1013。 */
    public static TeamPermissionUpdateMessage isTeamPermissionUpdate(String messageText) {
        if (messageText == null) {
            return null;
        }
        try {
            JsonNode p = JSON.readTree(messageText);
            if (p != null && p.isObject() && "team_permission_update".equals(p.path("type").asText())) {
                JsonNode pu = p.path("permissionUpdate");
                TeamPermissionUpdate update = pu.isObject()
                    ? new TeamPermissionUpdate(
                        textOrNull(pu, "type"),
                        ruleList(pu),
                        textOrNull(pu, "behavior"),
                        textOrNull(pu, "destination"))
                    : null;
                return new TeamPermissionUpdateMessage(
                    p.path("type").asText(),
                    update,
                    textOrNull(p, "directoryPath"),
                    textOrNull(p, "toolName"));
            }
        } catch (Exception e) {
            // 非 JSON → null
        }
        return null;
    }

    // ── P-9 isStructuredProtocolMessage ──────────────────────────────────────

    /**
     * 判定消息文本是否为「结构化协议消息」· 对齐 CC teammateMailbox.ts:1073-1095。
     *
     * <p>这些 type 在 useInboxPoller 有专门 handler 路由到 workerPermissions /
     * workerSandboxPermissions 等队列；若 getTeammateMailboxAttachments 先行消费会把它们
     * 当作原始 LLM 上下文打包成 attachment，导致永远到不了对应 handler。
     * <p>CC 精确 10 种 type（grep 自验 :1080-1091）：permission_request / permission_response /
     * sandbox_permission_request / sandbox_permission_response / shutdown_request /
     * shutdown_approved / team_permission_update / mode_set_request / plan_approval_request /
     * plan_approval_response。注意 <b>不含</b> shutdown_rejected / idle_notification / task_assignment
     * （前两者由 leader 消费为原始内容，task_assignment 另有 isTaskAssignment 处理）。
     */
    public static boolean isStructuredProtocolMessage(String messageText) {
        if (messageText == null) {
            return false;
        }
        try {
            JsonNode p = JSON.readTree(messageText);
            if (p == null || !p.isObject() || !p.has("type")) {
                return false;
            }
            String type = p.path("type").asText();
            return "permission_request".equals(type)
                || "permission_response".equals(type)
                || "sandbox_permission_request".equals(type)
                || "sandbox_permission_response".equals(type)
                || "shutdown_request".equals(type)
                || "shutdown_approved".equals(type)
                || "team_permission_update".equals(type)
                || "mode_set_request".equals(type)
                || "plan_approval_request".equals(type)
                || "plan_approval_response".equals(type);
        } catch (Exception e) {
            return false;
        }
    }

    // ── 解析辅助 ──────────────────────────────────────────────────────────────

    /** 读取对象字段为 Map（缺省/null/非对象 → null）。 */
    private static Map<String, Object> mapOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isObject()) {
            return null;
        }
        return JSON.convertValue(value, new TypeReference<Map<String, Object>>() {});
    }

    /** 读取数组字段为 List（缺省/null/非数组 → null）。 */
    private static List<Object> listOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isArray()) {
            return null;
        }
        return JSON.convertValue(value, new TypeReference<List<Object>>() {});
    }

    /** CC PermissionModeSchema 合法枚举值（coreSchemas.ts:337-348）：default/acceptEdits/bypassPermissions/plan/dontAsk。 */
    private static final Set<String> LEGAL_PERMISSION_MODES = Set.of(
        "default", "acceptEdits", "bypassPermissions", "plan", "dontAsk");

    /** mode 是否为 CC PermissionModeSchema 合法枚举值。 */
    private static boolean isLegalPermissionMode(String mode) {
        return LEGAL_PERMISSION_MODES.contains(mode);
    }

    /** 解析 permissionUpdate.rules 数组（CC teammateMailbox.ts:986-990）。 */
    private static List<TeamPermissionRule> ruleList(JsonNode permissionUpdate) {
        JsonNode rules = permissionUpdate.get("rules");
        if (rules == null || !rules.isArray()) {
            return List.of();
        }
        List<TeamPermissionRule> result = new ArrayList<>();
        for (JsonNode r : rules) {
            result.add(new TeamPermissionRule(textOrNull(r, "toolName"), textOrNull(r, "ruleContent")));
        }
        return result;
    }

}
