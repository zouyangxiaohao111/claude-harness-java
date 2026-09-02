package com.nexusai.application.agent.tool;

import java.util.List;

/**
 * UI system message payload · 对齐 CC toolExecution.ts 中的 appendSystemMessage 接口
 * (React 集成由 nexusai 前端负责, Java 端仅作为类型契约 + payload transport).
 *
 * <p><b>IMP-M-P0-3b 扩展</b>: 原仅 {@code (role, content)} 两字段, 无法承载 CC
 * {@code SystemMemorySavedMessage}（{@code type:'system', subtype:'memory_saved',
 * writtenPaths}）的 subtype + writtenPaths 契约 —— extractMemories.ts:490-496 经
 * {@code appendSystemMessage} 回调把 memory_saved 消息传给 UI. 现增加可选 {@code subtype}
 * + {@code writtenPaths} 字段（null/空 = 普通 role+content 消息, 后端双写不破坏既有契约）.
 * 2 参构造器保留向后兼容（既有 3 处测试调用点免迁移）。
 *
 * <p>字段映射（snake_case → camelCase + CC 原名行号）:
 * <table>
 *   <tr><th>Java 字段</th><th>CC 原名</th><th>CC 行号</th></tr>
 *   <tr><td>{@code role}</td><td>{@code type}</td><td>messages.ts:4464</td></tr>
 *   <tr><td>{@code subtype}</td><td>{@code subtype}</td><td>messages.ts:4465</td></tr>
 *   <tr><td>{@code writtenPaths}</td><td>{@code writtenPaths}</td><td>messages.ts:4466</td></tr>
 *   <tr><td>{@code verb}</td><td>{@code verb}</td><td>autoDream.ts:246</td></tr>
 * </table>
 * <p>[③ memory_saved 契约核验登记 · IMP-MV2-40]（02 D07 / OPD-MM-32）：CC createMemorySavedMessage
 * （messages.ts:4460-4471）含 timestamp/uuid/isMeta 三字段，Java record 无。核验结论：前端对接文档
 * （待前端对接.md）未登记任何 memory_saved 的 timestamp/uuid/isMeta 渲染依赖 → 登记不补字段；
 * 核心契约（type/subtype/writtenPaths）已由 ExtractMemoriesAgentTest:609-631 与序列化测试固化。
 * 若未来前端依赖这三字段 → 补字段 + 序列化测试（R32B15Stage3_3_UIRecordExtensionTest 随附用例）。
 */
public record SystemMessage(
        // WHY: 消息角色 (user/assistant/system), UI 决定气泡颜色 + avatar (CC systemMessage.role)
        String role,
        // WHY: 消息内容, UI 直接渲染 (CC systemMessage.content)
        String content,
        // CC original: subtype (messages.ts:4465) —— memory_saved / stop_hook_summary 等子类型
        String subtype,
        // CC original: writtenPaths (messages.ts:4466) —— memory_saved 消息携带的已写路径
        List<String> writtenPaths,
        // CC original: verb (autoDream.ts:246) —— memory_saved 消息的动词（'Improved' = auto-dream 完成摘要）
        String verb
) {

    public SystemMessage {
        if (writtenPaths == null) {
            writtenPaths = List.of();
        }
    }

    /**
     * 2 参兼容构造器 · 对齐 IMP-M-P0-3b 之前的 (role, content) 契约（subtype=null, writtenPaths=空, verb=null）。
     */
    public SystemMessage(String role, String content) {
        this(role, content, null, List.of(), null);
    }

    /**
     * memory_saved 工厂 · CC original: {@code createMemorySavedMessage(writtenPaths)}
     * （utils/messages.ts:4460-4471, type:'system' subtype:'memory_saved' writtenPaths）。
     *
     * <p>WHY: extractMemories.ts:490-496 在 memoryPaths.length&gt;0 时经 appendSystemMessage
     * 追加该消息 —— 前端用它渲染"已保存 N 条记忆"的系统消息. role 映射 CC type='system'.
     * verb=null（extract 语义无动词，auto-dream 的 'Improved' 走 {@link #memorySavedImproved}）。
     *
     * @param writtenPaths 排除 MEMORY.md 索引后的已写记忆文件路径（不可变拷贝）
     * @return subtype=memory_saved 的 SystemMessage
     */
    public static SystemMessage memorySaved(List<String> writtenPaths) {
        return new SystemMessage(
                "system",
                "",
                "memory_saved",
                writtenPaths == null ? List.of() : List.copyOf(writtenPaths),
                null);
    }

    /**
     * auto-dream Improved 完成消息工厂 · CC original: autoDream.ts:244-247
     * {@code appendSystemMessage({...createMemorySavedMessage(dreamState.filesTouched), verb:'Improved'})}。
     *
     * <p>WHY: fork 合并成功且 touchedPaths 非空时，主 transcript 追加 verb='Improved' 的
     * memory_saved 系统消息 —— 与 extractMemories 的"Saved N memories"同 surface
     * （autoDream.ts:236-237 注释"Inline completion summary in the main transcript"）。
     * 前端按 subtype=memory_saved + verb='Improved' 渲染 auto-dream 完成摘要。
     *
     * @param writtenPaths fork watcher 收集的 Edit/Write 已写文件路径（不可变拷贝）
     * @return subtype=memory_saved + verb='Improved' 的 SystemMessage
     */
    public static SystemMessage memorySavedImproved(List<String> writtenPaths) {
        return new SystemMessage(
                "system",
                "",
                "memory_saved",
                writtenPaths == null ? List.of() : List.copyOf(writtenPaths),
                "Improved");
    }
}
