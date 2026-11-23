package com.nexusai.model.session.dto;

/** PATCH /api/v1/sessions/{id} 请求 · 全字段可选 */
public record SessionUpdateRequest(
    String title,
    /**
     * 模型 tag（自由文本 · [D5] 不再是 5 值枚举）· null = 不改动。写路径经
     * {@code ModelTags.normalize} 校验（1-8 位大写字母/数字），非法值 fail-loud 400。
     */
    String model,
    String modelName,
    String mainProjectId,
    Boolean bareMode,               // 可选 · 会话级 bare（精简）模式开关（V33 列 bare_mode）；null = 不改动
    String permissionMode,          // 可选 · 会话级权限模式覆盖（V44 列 permission_mode）；null = 不改动
    String mainThreadAgent,         // 可选 · 会话指定主线程 agent（V58 列 main_thread_agent）；null = 不改动
    /**
     * 可选 · 会话级 coordinator 模式覆盖（V75 列 coordinator_mode）· true/false = 显式设置
     * （false 是「本会话强制普通」，<b>不是</b>未设置）；null = <b>不改动</b>该列（与 bareMode /
     * permissionMode 同款 PATCH 语义）。
     */
    Boolean coordinatorMode,
    /**
     * 可选 · 会话级「引入知识库」开关（V278 列 kb_tools_enabled）· true/false = 显式设置
     * （false 是「本会话关闭」，<b>不是</b>未设置）；null = <b>不改动</b>该列。
     *
     * <p>只影响主线程<b>工具可见性</b>：打开时 4 个知识库专属工具进入默认池，
     * ⛔ 不改变 {@code LlmAgentLoop.loop()} 的执行逻辑，也不是「本轮走 RAG 链」的开关。
     */
    Boolean kbToolsEnabled,
    /**
     * 可选 · 左栏置顶标记（V283 列 pinned）· true = 置顶，false = 取消置顶（显式写 0），
     * null = <b>不改动</b>该列（与 bareMode / coordinatorMode 同款 PATCH 语义）。
     */
    Boolean pinned,
    /**
     * 可选 · 会话级「已启动应用」（V337 列 enabled_apps，JSON 数组，= schema 门）· <b>四态</b>：
     * <ul>
     *   <li>字段缺省（Java 引用 null）= <b>不改动</b>；</li>
     *   <li>显式 JSON {@code null}（NullNode）= <b>清除</b>（回到 defaultOpen 种子）；</li>
     *   <li>{@code []} = 显式全不启动；</li>
     *   <li>字符串数组 = 显式启动集（MCP server 名 / 内置 key）。</li>
     * </ul>
     * 其它形态 → 400。启动即持久化；schema 与执行侧 allowlist 都读本列。
     */
    com.fasterxml.jackson.databind.JsonNode enabledApps
) {
    /**
     * 七参兼容构造器 · 保留 V75 加列前的既有调用形态（{@code new SessionUpdateRequest(title, model,
     * modelName, mainProjectId, bareMode, permissionMode, mainThreadAgent)}）——不传 coordinatorMode
     * 即「不改动本列」。Jackson 反序列化仍走规范构造器（本构造器无注解、非 property-based creator）。
     */
    public SessionUpdateRequest(String title, String model, String modelName, String mainProjectId,
                                Boolean bareMode, String permissionMode, String mainThreadAgent) {
        this(title, model, modelName, mainProjectId, bareMode, permissionMode, mainThreadAgent,
            null, null, null, null);
    }

    /**
     * 八参兼容构造器 · 保留 V278 加列前的既有调用形态（带 coordinatorMode 的七参写法）。
     */
    public SessionUpdateRequest(String title, String model, String modelName, String mainProjectId,
                                Boolean bareMode, String permissionMode, String mainThreadAgent,
                                Boolean coordinatorMode) {
        this(title, model, modelName, mainProjectId, bareMode, permissionMode, mainThreadAgent,
            coordinatorMode, null, null, null);
    }

    /**
     * 九参兼容构造器 · 保留 V283 加列前的既有调用形态（带 kbToolsEnabled 的八参写法）。
     * pinned 不传 = 不改动该列。
     */
    public SessionUpdateRequest(String title, String model, String modelName, String mainProjectId,
                                Boolean bareMode, String permissionMode, String mainThreadAgent,
                                Boolean coordinatorMode, Boolean kbToolsEnabled) {
        this(title, model, modelName, mainProjectId, bareMode, permissionMode, mainThreadAgent,
            coordinatorMode, kbToolsEnabled, null, null);
    }

    /**
     * 十参兼容构造器 · 保留加 V337 前的既有调用形态（{@code SessionCreateTool} 两处 10 参）。
     * {@code enabledApps} 不传 = 不改动该列。
     */
    public SessionUpdateRequest(String title, String model, String modelName, String mainProjectId,
                                Boolean bareMode, String permissionMode, String mainThreadAgent,
                                Boolean coordinatorMode, Boolean kbToolsEnabled, Boolean pinned) {
        this(title, model, modelName, mainProjectId, bareMode, permissionMode, mainThreadAgent,
            coordinatorMode, kbToolsEnabled, pinned, null);
    }
}
