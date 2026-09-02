package com.nexusai.application.agent.tasks;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.*;

/**
 * 任务数据结构 · 对齐 CC tasks.ts:76-88 TaskSchema
 *
 * <p>CC TaskSchema 定义（tasks.ts:76-88）：
 * <pre>
 *   id: z.string()           — 任务唯一 ID（字符串，持久化层生成）
 *   subject: z.string()      — 任务标题（CC tasks.ts:78）
 *   description: z.string()  — 任务描述（CC tasks.ts:79）
 *   activeForm: z.string().optional() — 进行中表单，spinner 显示用（CC tasks.ts:80）
 *   owner: z.string().optional()      — 所有者 agent ID（CC tasks.ts:81）
 *   status: TaskStatusSchema()        — 状态枚举（CC tasks.ts:82）
 *   blocks: z.array(z.string())       — 此任务阻塞的任务 ID 列表（CC tasks.ts:83）
 *   blockedBy: z.array(z.string())    — 阻塞此任务的任务 ID 列表（CC tasks.ts:84）
 *   metadata: z.record(...).optional()— 任意元数据（CC tasks.ts:85）
 * </pre>
 *
 * <h2>磁盘 JSON 形状 · 对齐 CC jsonStringify 省略 undefined</h2>
 * <p>CC 落盘用 {@code jsonStringify(task, null, 2)}（= {@code JSON.stringify} 纯包装，
 * Open-ClaudeCode/src/utils/slowOperations.ts:170-191，grep {@code return JSON.stringify}
 * 自验），undefined 字段被省略（新建任务 owner=undefined，
 * Open-ClaudeCode/src/tools/TaskCreateTool/TaskCreateTool.ts:86）。CC 读回经
 * {@code TaskSchema().safeParse(data)}（tasks.ts:333-339），zod {@code optional} 接受
 * undefined 拒绝 null——若 Java 写出 {@code "owner":null}，CC 校验失败返回 null。
 * 故本 record 加 {@code @JsonInclude(JsonInclude.Include.NON_NULL)}（对齐仓库既有
 * ToolResult.java:55 先例），Java null ≈ CC undefined，null 字段不再写出。
 *
 * <p>注意：blocks/blockedBy 在 CC 显式传 {@code []}（TaskCreateTool.ts:87-88），NON_NULL
 * 不删空数组，磁盘仍写 {@code "blocks":[]}。description/metadata 因紧凑构造默认值非 null
 * 仍写出（空串/{}，均通过 zod 校验，属 task-store △-8 差异项范围，本项不处理）。
 * <b>activeForm 缺省不物化（OPD-TS-12）</b>：CC activeForm 为 {@code z.string().optional()}
 * 无默认值（tasks.ts:81），未提供即省略键——Java null 透传，由 {@code @JsonInclude(NON_NULL)}
 * 省略，与 CC jsonStringify 省略 undefined 一致。
 *
 * <h2>不可变性</h2>
 * <p>所有 with* 方法返回新实例，原实例不变。
 *
 * @param id          任务 ID（字符串，由持久化层生成；null 表示尚未持久化）
 * @param subject     任务标题（必填 string；空串允许、null 拒绝——CC tasks.ts:79 z.string() 无 min，OD-TC-4）
 * @param description 任务描述（默认空字符串）
 * @param activeForm  进行中的表单文本（spinner 显示用，缺省不物化=null，对齐 CC optional）
 * @param owner       所有者 agent ID（null 表示未分配）
 * @param status      状态（默认 PENDING）
 * @param blocks      此任务阻塞的任务 ID 列表（不可变）
 * @param blockedBy   阻塞此任务的任务 ID 列表（不可变）
 * @param metadata    任意元数据（不可变）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Task(
    String id,
    String subject,
    String description,
    String activeForm,
    String owner,
    TaskStatus status,
    List<String> blocks,
    List<String> blockedBy,
    Map<String, Object> metadata
) {
    /**
     * 任务状态枚举 · 对齐 CC tasks.ts:69-73 TaskStatusSchema
     *
     * <p>CC 严格三态枚举（tasks.ts:69）：
     * <pre>z.enum(['pending', 'in_progress', 'completed'])</pre>
     *
     * <p><b>大小写敏感</b>（对齐 CC z.enum 精确匹配，tasks.ts:71-73）：仅接受
     * 'pending'/'in_progress'/'completed' 三个小写值，'PENDING'/'Pending' 等一律解析失败。
     * 与磁盘读路径 {@code parseStatusStrict}（TaskFileStorage，DC-3 移除大写容忍后同样
     * 严格仅小写，两端一致对齐 CC z.enum）一致。<b>不接受 "done" alias</b>：CC 的 fromString
     * 严格使用 Zod enum 解析，不提供 alias 映射。旧代码接受 "done" → COMPLETED、
     * fromString(null) → PENDING、toLowerCase 折叠混合大小写，均属 nexusai 自加的宽松行为，
     * 已按 CC 严格化删除。
     */
    public enum TaskStatus {
        PENDING, IN_PROGRESS, COMPLETED;

        /**
         * 从字符串解析状态 · 对齐 CC tasks.ts:71-73 TaskStatusSchema 严格 z.enum 解析（大小写敏感）
         *
         * <p>CC 真源（grep 实证 src/utils/tasks.ts）：
         * <pre>
         * export const TASK_STATUSES = ['pending', 'in_progress', 'completed'] as const  // tasks.ts:69
         * export const TaskStatusSchema = lazySchema(() =>
         *   z.enum(['pending', 'in_progress', 'completed']),                              // tasks.ts:71-73
         * )
         * </pre>
         *
         * <p><b>严格 3 值 + 大小写敏感</b>：z.enum 精确匹配原始字符串，不折叠大小写——
         * 'Pending'/'PENDING'/'In_Progress' 等一律解析失败。解析失败返回 null（对齐 CC
         * safeParse 失败返回 null，tasks.ts:333-339），不抛异常、不默认 pending。
         * <b>'deleted' 不是存储态</b>：CC 仅 TaskUpdate 工具输入层接受它
         * （TaskUpdateTool.ts:35 TaskUpdateStatusSchema = TaskStatusSchema().or(z.literal('deleted'))），
         * 属删除 action，execute 走 deleteTask() 物理 unlink + 提前返回（TaskUpdateTool.ts:214-227，
         * tasks.ts:393-441），从不写入 'deleted' 存储值。
         *
         * @param value 字符串值；null 返回 null（对齐 CC safeParse(null) 失败）
         * @return 对应的 TaskStatus；值不匹配（含 null / 大小写变体 / alias）返回 null
         */
        public static TaskStatus fromString(String value) {
            if (value == null) return null;
            return switch (value) {
                case "pending" -> PENDING;
                case "in_progress" -> IN_PROGRESS;
                case "completed" -> COMPLETED;
                // 对齐 CC 严格 z.enum（tasks.ts:71-73）大小写敏感 + safeParse 失败返回 null
                // （tasks.ts:333-339）：无下划线拼写、'deleted'（删除仅限 TaskUpdate 输入层
                // action，TaskUpdateTool.ts:35）、混合/全大写、done/open/resolved 等一律返回
                // null（不抛异常——调用方按「解析失败」处理）。
                default -> null;
            };
        }

        /**
         * 尝试解析，失败返回默认值（用于兼容旧数据迁移场景）
         *
         * <p>对齐 CC safeParse 失败返回 null 语义（tasks.ts:333-339）：解析失败（含 null /
         * 大小写变体 / alias）落 defaultValue。与单参 {@link #fromString(String)} 一致，
         * 不再大小写折叠。
         *
         * @param value        字符串值
         * @param defaultValue 解析失败时的默认值
         * @return TaskStatus；解析成功直返，失败落 defaultValue
         */
        public static TaskStatus fromString(String value, TaskStatus defaultValue) {
            TaskStatus result = fromString(value);
            return result != null ? result : defaultValue;
        }

        /**
         * 转为小写字符串（对齐 CC JSON 序列化格式，utils/tasks.ts:69 TASK_STATUSES
         * = ['pending', 'in_progress', 'completed']）
         *
         * <p>{@code @JsonValue} 使 Jackson 序列化 TaskStatus 时输出小写值而非 enum name()
         * 大写（OPD-TS-05）：磁盘 JSON status 落小写，与 CC 存储态全小写一致，CC CLI
         * zod safeParse 可精确匹配读入（tasks.ts:71-73,333-339）；不再写大写 "PENDING"。
         * 读路径不受影响——parseTask 走 parseStatusStrict 手动解析（TaskFileStorage.java:319-331），
         * 保留大小写双容忍兼容存量大写磁盘文件，无任何 Jackson enum 反序列化点。
         */
        @JsonValue
        public String toValue() {
            return name().toLowerCase();
        }
    }

    /**
     * 紧凑构造函数：防御性 copy + 默认值
     *
     * <p>对齐 CC createTask() 中的默认值设定（tasks.ts:284-308）：
     * - subject 约束已放宽（OD-TC-4）：CC TaskSchema subject 为 {@code z.string()} 无 min
     *   （tasks.ts:79）——<b>空串合法</b>，仅 null 拒绝（z.string() 拒绝 null/undefined）；
     *   旧 blank-subject 拦截（D-TC-1 联动）已删除，空 subject 照常建任务。
     * - status 默认 'pending'
     * - activeForm 缺省不物化（OPD-TS-12，CC tasks.ts:81 optional 无默认值 → null 省略键）
     * - blocks/blockedBy 默认空数组
     * - metadata 默认空对象
     */
    public Task {
        if (subject == null) {
            throw new IllegalArgumentException("subject cannot be null");
        }
        if (description == null) description = "";
        // OPD-TS-12：activeForm 缺省不物化为 subject（CC tasks.ts:81 activeForm:
        // z.string().optional() 无默认值；TaskCreateTool.ts:22 optional）——null 透传，
        // 由 @JsonInclude(NON_NULL) 省略键，与 CC jsonStringify 省略 undefined 一致。
        if (status == null) status = TaskStatus.PENDING;
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
        blockedBy = blockedBy == null ? List.of() : List.copyOf(blockedBy);
        // 对齐 CC JSON.stringify 保字符串键插入序（Open-ClaudeCode/src/utils/tasks.ts:300
        // createTask 落盘 jsonStringify(task, null, 2)；slowOperations.ts:189 纯 JSON.stringify
        // 包装，ES OrdinaryOwnPropertyKeys 对普通对象字符串键按插入序输出）：
        // HashMap 不保证迭代序，Jackson 序列化 metadata 按 entrySet 迭代序输出
        // （TaskFileStorage.java:149 JSON.writeValueAsString(task)），会打乱磁盘 JSON 键序；
        // LinkedHashMap 保插入序，与 CC 对象字面量序列化语义一致。
        metadata = metadata == null ? Map.of() : new LinkedHashMap<>(metadata);
    }

    // ════════════════════════════════════════════════════════════════════════
    // 工厂方法
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 创建新任务（待处理状态，无 ID）
     *
     * @param subject     任务标题
     * @param description 任务描述
     * @return 新 Task 实例
     */
    public static Task create(String subject, String description) {
        // OPD-TS-12：activeForm 缺省不物化为 subject——末参传 null（CC TaskCreateTool.ts:22
        // optional，未提供即省略键，由 @JsonInclude(NON_NULL) 不落盘）。
        return new Task(null, subject, description, null, null, TaskStatus.PENDING,
            List.of(), List.of(), Map.of());
    }

    /**
     * 创建新任务（带 activeForm）
     */
    public static Task create(String subject, String description, String activeForm) {
        return new Task(null, subject, description, activeForm, null, TaskStatus.PENDING,
            List.of(), List.of(), Map.of());
    }

    /**
     * 创建新任务（带元数据）
     */
    public static Task create(String subject, String description, String activeForm,
                               Map<String, Object> metadata) {
        return new Task(null, subject, description, activeForm, null, TaskStatus.PENDING,
            List.of(), List.of(), metadata);
    }

    // ════════════════════════════════════════════════════════════════════════
    // with* 方法 — 不可变更新（对齐 CC 的 spread operator 模式）
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 更新状态 · 对齐 CC TaskUpdateTool.ts:267 updates.status = status
     */
    public Task withStatus(TaskStatus newStatus) {
        return new Task(id, subject, description, activeForm, owner, newStatus,
            blocks, blockedBy, metadata);
    }

    /**
     * 更新所有者 · 对齐 CC TaskUpdateTool.ts:186 updates.owner = owner
     */
    public Task withOwner(String newOwner) {
        return new Task(id, subject, description, activeForm, newOwner, status,
            blocks, blockedBy, metadata);
    }

    /**
     * 更新标题 · 对齐 CC TaskUpdateTool.ts:170 updates.subject = subject
     */
    public Task withSubject(String newSubject) {
        return new Task(id, newSubject, description, activeForm, owner, status,
            blocks, blockedBy, metadata);
    }

    /**
     * 更新描述 · 对齐 CC TaskUpdateTool.ts:174 updates.description = description
     */
    public Task withDescription(String newDescription) {
        return new Task(id, subject, newDescription, activeForm, owner, status,
            blocks, blockedBy, metadata);
    }

    /**
     * 更新 activeForm · 对齐 CC TaskUpdateTool.ts:178 updates.activeForm = activeForm
     */
    public Task withActiveForm(String newActiveForm) {
        return new Task(id, subject, description, newActiveForm, owner, status,
            blocks, blockedBy, metadata);
    }

    /**
     * 更新元数据 · 对齐 CC TaskUpdateTool.ts:209 updates.metadata = merged
     */
    public Task withMetadata(Map<String, Object> newMetadata) {
        return new Task(id, subject, description, activeForm, owner, status,
            blocks, blockedBy, newMetadata);
    }

    /**
     * 添加一个阻塞关系（单任务）· 保留向后兼容
     *
     * @param taskId 被阻塞的任务 ID
     */
    public Task withBlocks(String taskId) {
        List<String> newBlocks = new ArrayList<>(blocks);
        if (!newBlocks.contains(taskId)) {
            newBlocks.add(taskId);
        }
        return new Task(id, subject, description, activeForm, owner, status,
            newBlocks, blockedBy, metadata);
    }

    /**
     * 替换整个 blocks 列表 · 对齐 CC blockTask 的全量替换模式（tasks.ts:473-475）
     *
     * <p>CC blockTask 使用 spread 操作符全量替换：
     * <pre>blocks: [...fromTask.blocks, toTaskId]</pre>
     */
    public Task withBlocksList(List<String> newBlocks) {
        return new Task(id, subject, description, activeForm, owner, status,
            newBlocks == null ? List.of() : List.copyOf(newBlocks), blockedBy, metadata);
    }

    /**
     * 添加一个被阻塞关系（单任务）· 保留向后兼容
     */
    public Task withBlockedBy(String taskId) {
        List<String> newBlockedBy = new ArrayList<>(blockedBy);
        if (!newBlockedBy.contains(taskId)) {
            newBlockedBy.add(taskId);
        }
        return new Task(id, subject, description, activeForm, owner, status,
            blocks, newBlockedBy, metadata);
    }

    /**
     * 替换整个 blockedBy 列表 · 对齐 CC blockTask 的全量替换模式（tasks.ts:479-481）
     */
    public Task withBlockedByList(List<String> newBlockedBy) {
        return new Task(id, subject, description, activeForm, owner, status,
            blocks, newBlockedBy == null ? List.of() : List.copyOf(newBlockedBy), metadata);
    }

    /**
     * 合并元数据 · 对齐 CC TaskUpdateTool.ts:222-235 metadata merge 逻辑
     *
     * <p>CC 行为（TaskUpdateTool.ts:222-235）：
     * <pre>
     * const merged = { ...(existingTask.metadata ?? {}) }
     * for (const [key, value] of Object.entries(metadata)) {
     *   if (value === null) delete merged[key]  // null = 删除键
     *   else merged[key] = value                 // 非 null = 设置键
     * }
     * </pre>
     *
     * @param updates 要合并的元数据更新（value=null 表示删除该键）
     * @return 新 Task 实例
     */
    public Task mergeMetadata(Map<String, Object> updates) {
        if (updates == null || updates.isEmpty()) {
            return this;
        }
        // 对齐 CC TaskUpdateTool.ts:222-235 spread 合并保序：
        // LinkedHashMap 保留既有键插入序，新增键按 updates 传入序追加（与 CC
        // { ...(existingTask.metadata ?? {}) } 后逐 key put 语义一致），
        // 避免 HashMap 复制打乱磁盘 metadata 键序（TaskFileStorage.java:149 按迭代序写出）。
        Map<String, Object> merged = new LinkedHashMap<>(this.metadata);
        for (Map.Entry<String, Object> entry : updates.entrySet()) {
            if (entry.getValue() == null) {
                // CC: value === null → delete merged[key]（TaskUpdateTool.ts:226）
                merged.remove(entry.getKey());
            } else {
                // CC: value !== null → merged[key] = value（TaskUpdateTool.ts:227）
                merged.put(entry.getKey(), entry.getValue());
            }
        }
        return new Task(id, subject, description, activeForm, owner, status,
            blocks, blockedBy, merged);
    }

    // ════════════════════════════════════════════════════════════════════════
    // 查询方法
    // ════════════════════════════════════════════════════════════════════════

    /** 是否被阻塞 · 对齐 CC Task.claimTask 的 blockedBy 检查（tasks.ts:589-590） */
    @JsonIgnore
    public boolean isBlocked() {
        return !blockedBy.isEmpty();
    }

    /** 是否完成 */
    @JsonIgnore
    public boolean isCompleted() {
        return status == TaskStatus.COMPLETED;
    }

    /** 是否待处理 */
    @JsonIgnore
    public boolean isPending() {
        return status == TaskStatus.PENDING;
    }

    /** 是否进行中 */
    @JsonIgnore
    public boolean isInProgress() {
        return status == TaskStatus.IN_PROGRESS;
    }

    // ════════════════════════════════════════════════════════════════════════
    // equals / hashCode — Java record 默认基于所有字段，这里显式声明以文档化
    // 对齐 CC：任务通过 id 唯一标识（同一 taskListId 下 id 唯一）
    // ════════════════════════════════════════════════════════════════════════
}
