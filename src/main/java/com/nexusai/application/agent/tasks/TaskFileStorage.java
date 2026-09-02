package com.nexusai.application.agent.tasks;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 任务文件存储层 · 纯 IO 操作（JSON 序列化/反序列化 + 文件路径管理）
 *
 * <p>s12.5-1.1 (L3 拆分): 从 TaskService 中提取所有文件 IO 逻辑。
 * 不包含业务逻辑（锁、信号、阻塞图、认领等），仅负责读写 JSON 文件。
 *
 * <h2>存储根（对齐 CC getClaudeConfigHomeDir）</h2>
 * <p>任务存储根 = {@code configHomeDir}/tasks/{taskListId}，
 * configHomeDir 由 {@link TaskSystemConfig#getClaudeConfigHomeDir()} 派生。
 * <b>决策 D1（nexusai 复刻版 .claude 改造，2026-08-30）</b>：写根默认 nexusai 自有根
 * {@code {user.home}/.{appName}}（appName=spring.application.name，默认 nexusai），
 * 弃用 CLAUDE_CONFIG_DIR env 与 ~/.claude 默认；可经 {@code nexusai.task.config-dir} sysprop
 * 显式覆盖。对齐 CC {@code Open-ClaudeCode/src/utils/envUtils.ts:7-14} + {@code tasks.ts:221-227}。
 *
 * <p><b>迁移提示（tasks/teams 既有数据）</b>：弃 CLAUDE_CONFIG_DIR/~/.claude 后，存量
 * {@code ~/.claude/tasks}、{@code ~/.claude/teams} 不再自动读取。迁移方式二选一：
 * ① 一次性把旧目录复制到 nexusai 根；② 过渡期临时设 {@code nexusai.task.config-dir}={@code ~/.claude}
 * 读旧数据（详见 {@link TaskSystemConfig#getClaudeConfigHomeDir()}）。
 *
 * <h2>职责边界</h2>
 * <ul>
 *   <li>✅ 任务文件路径解析（getTasksDir / getTaskPath）</li>
 *   <li>✅ 原始读写（readTaskFile / readTaskFileMigrated / writeTaskFile / listTaskFiles）</li>
 *   <li>✅ 物理删除（deleteTaskFile）</li>
 *   <li>✅ 目录创建（ensureTasksDir）</li>
 *   <li>✅ 路径安全清理（sanitizePathComponent）</li>
 *   <li>❌ High water mark 管理 → {@link HighWatermark}</li>
 *   <li>❌ 锁管理 → {@link TaskLock}</li>
 *   <li>❌ 业务方法 → {@link TaskService}</li>
 * </ul>
 *
 * @see HighWatermark
 * @see TaskService
 */
class TaskFileStorage {

    static final Logger log = LoggerFactory.getLogger(TaskFileStorage.class);
    static final ObjectMapper JSON = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** 美化写盘序列化器 · 对齐 CC {@code JSON.stringify(task, null, 2)}（G5，2 空格缩进） */
    static final ObjectWriter JSON_PRETTY = JSON.writer(new CcJsonPrettyPrinter());

    static final String TASKS_DIR_NAME = "tasks";
    static final String HIGH_WATER_MARK_FILE = ".highwatermark";

    /**
     * 对齐 CC {@code JSON.stringify(task, null, 2)} 的磁盘 JSON 美化打印机。
     *
     * <p><b>WHY（G5 缩进对齐）</b>：CC 落盘用 {@code jsonStringify(task, null, 2)}
     * （= JSON.stringify 纯包装，Open-ClaudeCode/src/utils/slowOperations.ts:170-191；
     * 调用点 tasks.ts:300 createTask / tasks.ts:365 updateTaskUnsafe），2 空格缩进、
     * {@code "key": value}（冒号后单空格）、空容器 {@code []}/{@code {}}。
     * Jackson 默认 DefaultPrettyPrinter 输出 {@code "key" : value}（冒号前后各一空格）
     * 与空容器 {@code [ ]}/{@code { }}，与 CC 磁盘字节形状不一致——本类逐方法覆盖，
     * 使 {@link #writeTaskFile} 落盘字节与 CC jsonStringify 完全一致（实测与 node
     * JSON.stringify(obj, null, 2) 逐字节比对一致）。
     */
    private static final class CcJsonPrettyPrinter extends DefaultPrettyPrinter {
        CcJsonPrettyPrinter() {
            super();
            // 对齐 CC JSON.stringify space=2：对象/数组子元素均 2 空格缩进 + \n 换行
            indentArraysWith(new DefaultIndenter("  ", "\n"));
            indentObjectsWith(new DefaultIndenter("  ", "\n"));
        }

        private CcJsonPrettyPrinter(CcJsonPrettyPrinter base) {
            super(base);
        }

        @Override
        public DefaultPrettyPrinter createInstance() {
            return new CcJsonPrettyPrinter(this);
        }

        /** 对齐 CC JSON.stringify：冒号后单空格、无前导空格（Jackson 默认冒号前后各一空格） */
        @Override
        public void writeObjectFieldValueSeparator(JsonGenerator g) throws IOException {
            g.writeRaw(": ");
        }

        /** 对齐 CC JSON.stringify 空对象 {@code {}}：Jackson 默认输出 {@code { }} */
        @Override
        public void writeEndObject(JsonGenerator g, int nrOfEntries) throws IOException {
            if (!_objectIndenter.isInline()) {
                _nesting--;
            }
            if (nrOfEntries > 0) {
                _objectIndenter.writeIndentation(g, _nesting);
            }
            g.writeRaw('}');
        }

        /** 对齐 CC JSON.stringify 空数组 {@code []}：Jackson 默认输出 {@code [ ]} */
        @Override
        public void writeEndArray(JsonGenerator g, int nrOfElements) throws IOException {
            if (!_arrayIndenter.isInline()) {
                _nesting--;
            }
            if (nrOfElements > 0) {
                _arrayIndenter.writeIndentation(g, _nesting);
            }
            g.writeRaw(']');
        }
    }

    private final Path configHomeDir;

    /**
     * @param configHomeDir 配置根目录 · 对齐 CC getClaudeConfigHomeDir()
     *                      （Open-ClaudeCode/src/utils/envUtils.ts:7-14）
     *                      = 决策 D1 nexusai 自有根 {@code {user.home}/.{appName}}（默认，
     *                      appName=spring.application.name），可经 nexusai.task.config-dir
     *                      sysprop 显式覆盖。
     *                      任务存储根 = configHomeDir/tasks/{taskListId}，无 .claude 中间层。
     */
    TaskFileStorage(Path configHomeDir) {
        this.configHomeDir = configHomeDir;
    }

    // ════════════════════════════════════════════════════════════════════════
    // 路径工具方法
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 任务列表目录 · 对齐 CC tasks.ts:221-227 getTasksDir()
     *
     * <p>CC 源码：{@code join(getClaudeConfigHomeDir(), 'tasks', sanitizePathComponent(taskListId))}
     * → {configHome}/tasks/{taskListId}。configHome 为决策 D1 nexusai 自有根
     * {@code {user.home}/.{appName}}（弃 CLAUDE_CONFIG_DIR/~/.claude），无 .claude 中间层
     * （旧实现 workspaceDir/.claude/tasks 为脏代码，已删除）。
     */
    Path getTasksDir(String taskListId) {
        Path dir = configHomeDir.resolve(TASKS_DIR_NAME).resolve(sanitizePathComponent(taskListId));
        if (log.isDebugEnabled()) {
            log.debug("任务列表目录 getTasksDir({}) = {}", taskListId, dir);
        }
        return dir;
    }

    Path getTaskPath(String taskListId, String taskId) {
        return getTasksDir(taskListId).resolve(sanitizePathComponent(taskId) + ".json");
    }

    Path getHighWaterMarkPath(String taskListId) {
        return getTasksDir(taskListId).resolve(HIGH_WATER_MARK_FILE);
    }

    void ensureTasksDir(String taskListId) throws IOException {
        Path dir = getTasksDir(taskListId);
        Files.createDirectories(dir);
    }

    /**
     * 团队根目录 · 对齐 CC envUtils.ts:16-18 getTeamsDir()
     *
     * <p>CC 源码：{@code join(getClaudeConfigHomeDir(), 'teams')} → {configHome}/teams。
     * 与 {@link #getTasksDir(String)} 同根（configHome = 决策 D1 nexusai 自有根
     * {@code {user.home}/.{appName}}），团队配置 {@code config.json} 位于
     * {configHome}/teams/{sanitizeName(teamName)}/config.json
     * （对齐 CC tasks.ts:728 + teamHelpers.ts:58-68）。
     */
    Path getTeamsDir() {
        return configHomeDir.resolve("teams");
    }

    /**
     * 读取 team 配置文件（原始 JSON 节点）· 对齐 CC tasks.ts:724-753 readTeamMembers()
     *
     * <p>CC 真源（grep 自验，非注释）：
     * <pre>
     * const teamFilePath = join(teamsDir, sanitizeName(teamName), 'config.json')   // tasks.ts:728
     * const content = await readFile(teamFilePath, 'utf-8')                          // tasks.ts:730
     * const teamFile = jsonParse(content) as { leadAgentId; members }               // tasks.ts:731-733
     * </pre>
     * ENOENT → null（tasks.ts:745-747）；其他读/解析错误 → logForDebugging + null（tasks.ts:748-751）。
     * Java 侧返回 {@code Optional.empty()} 对应 CC 返回 null。
     *
     * <p>成员提取（name + agentId + agentType）由 {@link TaskService#readTeamMembers} 完成，
     * 本方法仅负责文件读取与 JSON 树解析（IO 职责边界，见类 Javadoc）。
     *
     * @param safeTeamName 已 sanitize 的 team 名（{@code TeamHelpers.sanitizeName}，CC tasks.ts:728 sanitizeName）
     * @return 解析出的 config.json JSON 树；文件不存在或解析失败 → Optional.empty（对齐 CC return null）
     */
    Optional<JsonNode> readTeamConfig(String safeTeamName) {
        Path configPath = getTeamsDir().resolve(safeTeamName).resolve("config.json");
        try {
            String content = Files.readString(configPath);
            JsonNode root = JSON.readTree(content);
            if (root == null || !root.isObject()) {
                if (log.isDebugEnabled()) {
                    log.debug("[Tasks] team config.json 顶层非 JSON 对象: {}", configPath);
                }
                return Optional.empty();
            }
            return Optional.of(root);
        } catch (NoSuchFileException e) {
            // 对齐 CC tasks.ts:745-747：ENOENT → return null（静默无日志）
            return Optional.empty();
        } catch (IOException e) {
            // 对齐 CC tasks.ts:748-751：其他读/解析错误 → logForDebugging + return null
            if (log.isDebugEnabled()) {
                log.debug("[Tasks] 读取 team 配置文件失败 {}: {}", configPath, e.getMessage());
            }
            log.warn("[Tasks] 读取 team 配置文件失败 {}: {}", configPath, e.getMessage());
            return Optional.empty();
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 原始读写（无锁，调用方需自行持有锁）
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 读取并严格校验任务文件 · 对齐 CC tasks.ts:310-349 getTask()
     *
     * <p>对齐 CC 语义（grep 实证）：
     * <ul>
     *   <li>tasks.ts:333 对原始 JSON 跑完整 schema：{@code TaskSchema().safeParse(data)}</li>
     *   <li>tasks.ts:334-339 校验失败 → logForDebugging（默认 debug 级）+ return null</li>
     *   <li>tasks.ts:341-344 ENOENT → return null 静默无日志</li>
     *   <li>tasks.ts:345-348 其他读错误 → logForDebugging + logError + return null</li>
     * </ul>
     * 彻底替换旧 Jackson 宽松反序列化（缺 status/description/blocks/blockedBy 被 Task
     * 紧凑构造默认值静默兜底，Task.java:143-147；非法状态被 IOException 吞掉、无结构化
     * 校验日志——脏代码，已删除）。严格校验实现见 {@link #parseTask(String, String)}。
     *
     * @return 校验通过的 Task；Optional.empty 对应 CC getTask return null
     */
    Optional<Task> readTaskFile(String taskListId, String taskId) {
        Path taskPath = getTaskPath(taskListId, taskId);
        if (!Files.exists(taskPath)) {
            return Optional.empty();
        }
        try {
            String content = Files.readString(taskPath);
            return parseTask(content, taskId);
        } catch (IOException e) {
            // 对齐 CC tasks.ts:341-348：ENOENT 静默 return null；其他读错误 logError + return null
            if (e instanceof NoSuchFileException) {
                return Optional.empty();
            }
            log.error("读取任务 {} 失败: {}", taskId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 读取任务并执行 ant 遗留状态迁移 · 对齐 CC tasks.ts:310-349 getTask()
     *
     * <p><b>统一读入口（FIX-G4 结构对齐）</b>：getTask 与 listTaskFiles 均经本方法读路径，
     * 消除两路读路径分叉——CC listTasks 逐 id 调 getTask（tasks.ts:454），getTask 内含
     * ant 迁移（tasks.ts:317-331）；Java 侧 getTask（TaskService.readTaskFileMigrated 委托
     * 本方法）与 listTaskFiles 共用本单一实现。
     *
     * <p>CC 真源（grep 实证 tasks.ts:317-339）：
     * <pre>
     * const content = await readFile(path, 'utf-8')                    // tasks.ts:317
     * const data = jsonParse(content) as { status?: string }           // tasks.ts:317
     * if (process.env.USER_TYPE === 'ant') {                           // tasks.ts:320
     *   if (data.status === 'open') data.status = 'pending'            // tasks.ts:321
     *   else if (data.status === 'resolved') data.status = 'completed' // tasks.ts:322
     *   else if (data.status && ['planning','implementing',
     *     'reviewing','verifying'].includes(data.status)) {            // tasks.ts:323-328
     *     data.status = 'in_progress'                                  // tasks.ts:330
     *   }
     * }
     * const parsed = TaskSchema().safeParse(data)                      // tasks.ts:333
     * </pre>
     *
     * <p>Java 映射：不存在 → Optional.empty（对齐 CC ENOENT → null，tasks.ts:341-344）；
     * 读原文 → {@code antUser=true} 时 {@link #migrateLegacyStatus(String)} 内存迁移（不落盘）
     * → 严格校验 {@link #parseTask(String, String)}。迁移作用于原始 JSON 的 status 字符串
     * 字段（tasks.ts:317 反序列化之前），Java 侧同样在 parseTask 反序列化<b>前</b>迁移。
     *
     * @param taskListId 任务列表 ID
     * @param taskId     任务 ID
     * @param antUser    USER_TYPE==='ant' 时执行遗留状态迁移（测试注入缝）
     * @return 校验通过的 Task；不存在或迁移后仍校验失败 → Optional.empty（对齐 CC return null）
     */
    Optional<Task> readTaskFileMigrated(String taskListId, String taskId, boolean antUser) {
        Path taskPath = getTaskPath(taskListId, taskId);
        if (!Files.exists(taskPath)) {
            return Optional.empty();
        }
        try {
            String content = Files.readString(taskPath);
            if (antUser) {
                content = migrateLegacyStatus(content);
            }
            return parseTask(content, taskId);
        } catch (IOException e) {
            // 对齐 CC tasks.ts:341-348：ENOENT 静默 return null；其他读错误 logError + return null
            if (e instanceof NoSuchFileException) {
                return Optional.empty();
            }
            log.error("读取任务 {} 失败: {}", taskId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * ant 遗留状态迁移（纯函数，内存不落盘）· 对齐 CC tasks.ts:319-331
     *
     * <p>CC 映射表（grep 实证 tasks.ts:321/322/326/330）：
     * <ul>
     *   <li>open → pending（tasks.ts:321）</li>
     *   <li>resolved → completed（tasks.ts:322）</li>
     *   <li>planning / implementing / reviewing / verifying → in_progress（tasks.ts:326/330）</li>
     * </ul>
     *
     * <p>读 JSON 树 → status 为 textual 且命中映射时改写 → 重序列化。仅在确有迁移时改写；
     * 非对象 / status 缺失或非字符串 / 非 6 值 → 原样返回。不写回磁盘（对齐 CC 内存迁移）。
     *
     * @param content 任务文件原始 JSON 文本
     * @return 迁移后的 JSON 文本；未发生迁移时原样返回
     * @throws JsonProcessingException JSON 无法解析（对齐 CC jsonParse 抛错 → 外层 catch 返回 null）
     */
    static String migrateLegacyStatus(String content) throws JsonProcessingException {
        JsonNode root = JSON.readTree(content);
        if (root == null || !root.isObject()) {
            return content;
        }
        JsonNode statusNode = root.get("status");
        if (statusNode == null || !statusNode.isTextual()) {
            return content;
        }
        String status = statusNode.textValue();
        String migrated = switch (status) {
            case "open" -> "pending";
            case "resolved" -> "completed";
            case "planning", "implementing", "reviewing", "verifying" -> "in_progress";
            default -> null;
        };
        if (migrated == null) {
            return content;
        }
        if (log.isDebugEnabled()) {
            log.debug("getTask ant 旧状态迁移: {} -> {}", status, migrated);
        }
        ((ObjectNode) root).put("status", migrated);
        return JSON.writeValueAsString(root);
    }

    /**
     * 写任务到磁盘 · 对齐 CC tasks.ts:300/:365 jsonStringify(task, null, 2)
     *
     * <p>2 空格缩进美化（{@link CcJsonPrettyPrinter}，G5），与 CC 磁盘字节形状一致；
     * 读回经 {@link #parseTask(String, String)} JSON 解析，天然兼容美化/紧凑两种格式。
     */
    void writeTaskFile(String taskListId, String taskId, Task task) {
        Path taskPath = getTaskPath(taskListId, taskId);
        try {
            // 对齐 CC tasks.ts:300/:365 jsonStringify(task, null, 2)：2 空格缩进美化写盘（G5）。
            String json = JSON_PRETTY.writeValueAsString(task);
            Files.writeString(taskPath, json);
            if (log.isDebugEnabled()) {
                log.debug("任务 {} 已写盘: {}", taskId, json);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to write task file: " + e.getMessage(), e);
        }
    }

    /**
     * 列出目录内所有合法任务 · 对齐 CC tasks.ts:443-456 listTasks()
     *
     * <p>对齐 CC 语义（grep 实证 tasks.ts:454-456）：listTasks 先 readdir 过滤
     * {@code endsWith('.json')}，再<b>逐 id 调 getTask</b>（tasks.ts:454）——即逐文件复用
     * 含 ant 迁移的单一读入口（getTask 内 tasks.ts:317-331）。本方法同样逐文件委托
     * {@link #readTaskFileMigrated(String, String, boolean)}，校验失败 / 读错误文件返回
     * Optional.empty 被剔除（对齐 CC {@code results.filter(t => t !== null)}）。
     *
     * <p>旧实现内联读文件 + parseTask，缺 ant 迁移——与 getTask 读路径分叉（getTask 走
     * readTaskFileMigrated 迁移，listTaskFiles 走 parseTask 不迁移），同一 ant 用户旧状态
     * 文件 getTask 可读而 listTasks 丢弃。脏代码已删除，现与 getTask 统一经
     * readTaskFileMigrated（结构对齐，消除读路径分叉）。非 ant 用户 {@code antUser=false}
     * 迁移跳过，行为与迁移前完全一致。
     *
     * @param taskListId 任务列表 ID
     * @param antUser    USER_TYPE==='ant' 时执行遗留状态迁移（对齐 CC tasks.ts:320，getTask 同参）
     */
    List<Task> listTaskFiles(String taskListId, boolean antUser) {
        Path dir = getTasksDir(taskListId);
        List<Task> tasks = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.json")) {
            for (Path file : stream) {
                String taskId = file.getFileName().toString().replace(".json", "");
                readTaskFileMigrated(taskListId, taskId, antUser).ifPresent(tasks::add);
            }
        } catch (IOException e) {
            // 目录不存在 → 空列表（对齐 CC listTasks readdir catch → []，tasks.ts:447-450）
        }
        return tasks;
    }

    // ════════════════════════════════════════════════════════════════════════
    // 严格校验器 · 对齐 CC TaskSchema + safeParse
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 解析并严格校验任务 JSON · 对齐 CC tasks.ts:333-339 TaskSchema().safeParse(data)
     *
     * <p>CC 真源（grep 实证）：
     * <ul>
     *   <li>tasks.ts:71-74 {@code TaskStatusSchema = z.enum(['pending','in_progress','completed'])}
     *       — status 严格三态，无 deleted/inprogress/done alias</li>
     *   <li>tasks.ts:76-89 TaskSchema 必填：id/subject/description 为 z.string()；
     *       blocks/blockedBy 为 z.array(z.string())；activeForm/owner 为 z.string().optional()；
     *       metadata 为 z.record(z.string(), z.unknown()).optional()</li>
     *   <li>tasks.ts:334-339 任一校验失败 → logForDebugging（默认 debug 级，debug.ts:203-209）
     *       + return null，不宽松吞错</li>
     *   <li>tasks.ts:346-347 JSON 解析失败（jsonParse 抛错，非 ENOENT）→ logForDebugging（debug 级）
     *       + logError(e)（error 级）双日志 + return null；Java 侧 parseTask 对 JSON.readTree
     *       IOException 记 log.error（对齐 tasks.ts:347 error 级），schema 校验失败仍记 debug
     *       （对齐 tasks.ts:335-337）——两类失败分级不同，防维护误统一</li>
     *   <li>tasks.ts:340 成功 → return parsed.data</li>
     * </ul>
     *
     * <p>Java ⊕ 偏差（计划 concerns#2 文档化，防 Java 自身 round-trip 断裂）：
     * <ul>
     *   <li>status 大小写：Java 磁盘现写小写（Task.java {@code @JsonValue} + toValue()
     *       对齐 CC TASK_STATUSES，utils/tasks.ts:69），本校验器<b>严格仅接受小写 3 种</b>
     *       （对齐 CC 严格 z.enum，tasks.ts:71-74 大小写敏感）——大写 PENDING/IN_PROGRESS/
     *       COMPLETED 一律拒绝（旧 Java ⊕ 大写容忍已按 DC-3 移除）；仍拒绝
     *       deleted/inprogress/done/open/resolved 等 alias（对齐 CC 严格 z.enum）</li>
     *   <li>activeForm/owner 为 null → 视为缺省（Task.java record 级
     *       {@code @JsonInclude(JsonInclude.Include.NON_NULL)} 已使磁盘写路径省略 null
     *       字段，对齐 CC jsonStringify 省略 undefined；本校验器仍宽松接受 null，
     *       兼容磁盘旧文件含 "owner":null 或缺失键的场景）</li>
     * </ul>
     *
     * <p>Java ⊕ 扩展：Task 紧凑构造 subject 非空校验（Task.java:140-142，CC z.string()
     * 接受空串）——subject 为 blank 时抛 IllegalArgumentException，按校验失败处理。
     *
     * @param content 任务文件原始 JSON 文本
     * @param taskId  任务 ID（仅用于日志）
     * @return 校验通过的 Task；任一校验失败返回 Optional.empty()（对齐 CC return null）
     */
    private static Optional<Task> parseTask(String content, String taskId) {
        JsonNode root;
        try {
            root = JSON.readTree(content);
        } catch (IOException e) {
            // 对齐 CC tasks.ts:346-347：JSON 解析失败（jsonParse 抛错，非 ENOENT）→ logForDebugging + logError(e)，
            // 其中 logError 为 error 级；Java 侧以 log.error 单条收敛（error 级覆盖 CC logError 语义）。
            // 与 schema 校验失败（下方 debugValidationFail，debug 级，对齐 tasks.ts:335-337）分级不同。
            log.error("[Tasks] 任务 {} JSON 解析失败: {}", taskId, e.getMessage());
            return Optional.empty();
        }
        if (root == null || !root.isObject()) {
            debugValidationFail(taskId, "顶层非 JSON 对象");
            return Optional.empty();
        }

        // 必填字符串：id/subject/description（对齐 tasks.ts:77-79 z.string()）
        String id = requireText(root, "id", taskId);
        String subject = requireText(root, "subject", taskId);
        String description = requireText(root, "description", taskId);
        if (id == null || subject == null || description == null) {
            return Optional.empty();
        }

        // status 严格三态（对齐 tasks.ts:71-74 z.enum + 大写 Java 遗留，拒绝 alias）
        JsonNode statusNode = root.get("status");
        if (statusNode == null || !statusNode.isTextual()) {
            debugValidationFail(taskId, "status 缺失或非字符串");
            return Optional.empty();
        }
        Task.TaskStatus status = parseStatusStrict(statusNode.textValue());
        if (status == null) {
            debugValidationFail(taskId, "status '" + statusNode.textValue()
                + "' 非法（CC 仅 pending/in_progress/completed）");
            return Optional.empty();
        }

        // 必填字符串数组：blocks/blockedBy（对齐 tasks.ts:83-84 z.array(z.string())）
        List<String> blocks = requireStringArray(root, "blocks", taskId);
        List<String> blockedBy = requireStringArray(root, "blockedBy", taskId);
        if (blocks == null || blockedBy == null) {
            return Optional.empty();
        }

        // 可选字符串：activeForm/owner（对齐 tasks.ts:80-81 z.string().optional()；
        // null 视为缺省——磁盘写路径经 Task record @JsonInclude(NON_NULL) 已省略 null，
        // 但兼容旧文件含 "owner":null 场景，此处仍宽松接受 null）
        JsonNode activeFormNode = root.get("activeForm");
        if (activeFormNode != null && !activeFormNode.isNull() && !activeFormNode.isTextual()) {
            debugValidationFail(taskId, "activeForm 非字符串");
            return Optional.empty();
        }
        JsonNode ownerNode = root.get("owner");
        if (ownerNode != null && !ownerNode.isNull() && !ownerNode.isTextual()) {
            debugValidationFail(taskId, "owner 非字符串");
            return Optional.empty();
        }
        String activeForm = activeFormNode == null || activeFormNode.isNull() ? null : activeFormNode.textValue();
        String owner = ownerNode == null || ownerNode.isNull() ? null : ownerNode.textValue();

        // 可选对象：metadata（对齐 tasks.ts:85 z.record(z.string(), z.unknown()).optional()）
        JsonNode metadataNode = root.get("metadata");
        if (metadataNode != null && !metadataNode.isNull() && !metadataNode.isObject()) {
            debugValidationFail(taskId, "metadata 非对象");
            return Optional.empty();
        }
        Map<String, Object> metadata = (metadataNode == null || metadataNode.isNull())
            ? Map.of()
            : JSON.convertValue(metadataNode, new TypeReference<Map<String, Object>>() {});

        try {
            Task task = new Task(id, subject, description, activeForm, owner, status,
                blocks, blockedBy, metadata);
            return Optional.of(task);
        } catch (IllegalArgumentException e) {
            // Task 紧凑构造 subject 非空校验（Java ⊕ 扩展 Task.java:140-142）
            debugValidationFail(taskId, "构造 Task 失败（subject 非空校验）: " + e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 严格解析 status 文本 → TaskStatus · 对齐 CC tasks.ts:71-74 z.enum
     *
     * <p>严格仅接受小写 CC 格式（pending/in_progress/completed，大小写敏感）；
     * 拒绝大写 Java 遗留 enum 名（PENDING/IN_PROGRESS/COMPLETED，DC-3 已移除旧 ⊕ 大写容忍）
     * 与 deleted/inprogress/done/open/resolved 等 alias（对齐 CC tasks.ts:71-74 严格 z.enum）。
     * <p>与 {@link Task.TaskStatus#fromString}（Task.java:109-121）语义完全一致：
     * null→null、非法→null、大小写敏感（tasks.ts:71-73 + :334-339）。
     *
     * @param value status 文本
     * @return 匹配的 TaskStatus；null 表示非法
     */
    private static Task.TaskStatus parseStatusStrict(String value) {
        return switch (value) {
            case "pending" -> Task.TaskStatus.PENDING;
            case "in_progress" -> Task.TaskStatus.IN_PROGRESS;
            case "completed" -> Task.TaskStatus.COMPLETED;
            default -> null;
        };
    }

    /** 必填字符串字段校验（对齐 CC z.string()，tasks.ts:77-79）· 失败返回 null */
    private static String requireText(JsonNode root, String field, String taskId) {
        JsonNode node = root.get(field);
        if (node == null || !node.isTextual()) {
            debugValidationFail(taskId, "字段 " + field + " 缺失或非字符串");
            return null;
        }
        return node.textValue();
    }

    /** 必填字符串数组校验（对齐 CC z.array(z.string())，tasks.ts:83-84）· 失败返回 null */
    private static List<String> requireStringArray(JsonNode root, String field, String taskId) {
        JsonNode node = root.get(field);
        if (node == null || !node.isArray()) {
            debugValidationFail(taskId, "字段 " + field + " 缺失或非数组");
            return null;
        }
        List<String> result = new ArrayList<>(node.size());
        for (JsonNode element : node) {
            if (!element.isTextual()) {
                debugValidationFail(taskId, "字段 " + field + " 含非字符串元素");
                return null;
            }
            result.add(element.textValue());
        }
        return result;
    }

    /** schema 校验失败 debug 日志 · 对齐 CC logForDebugging 默认 debug 级（debug.ts:203-209）+ CLAUDE.md 中文规范。
     *  仅覆盖 schema 校验失败（JSON 解析失败已提升为 log.error，见 {@link #parseTask}），防后续维护误用。 */
    private static void debugValidationFail(String taskId, String reason) {
        if (log.isDebugEnabled()) {
            log.debug("[Tasks] 任务 {} schema 校验失败: {}", taskId, reason);
        }
    }

    boolean deleteTaskFile(String taskListId, String taskId) {
        Path taskPath = getTaskPath(taskListId, taskId);
        try {
            return Files.deleteIfExists(taskPath);
        } catch (IOException e) {
            log.warn("Failed to delete task {}: {}", taskId, e.getMessage());
            return false;
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 路径安全清理
    // ════════════════════════════════════════════════════════════════════════

    static String sanitizePathComponent(String input) {
        return input.replaceAll("[^a-zA-Z0-9_-]", "-");
    }
}
