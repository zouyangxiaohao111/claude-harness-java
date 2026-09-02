package com.nexusai.application.agent.permission.hook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.skill.SkillRegistry;
import com.nexusai.application.agent.subagent.FrontmatterHooks;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.tool.impl.SkillToolImpl;
import com.nexusai.model.command.Command;
import com.nexusai.model.command.CommandSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [Session H12] RegisterSkillHooks 真实实现 + FrontmatterHooks 作用域修正 · 对齐 CC
 * {@code Open-ClaudeCode/src/utils/hooks/registerSkillHooks.ts:20-64} +
 * {@code registerFrontmatterHooks.ts:18-67}.
 *
 * <p>WHY (规则九 · 测试验证意图): skill 的 frontmatter hooks 必须按 session 注册成
 * 临时 session hook (非 GLOBAL), 否则 skill 加载后 hook 会跨会话泄漏、每个 turn 重复触发
 * skill 副作用. 对齐点:
 * <ul>
 *   <li>{@code once:true} one-shot 语义 — hook 首次成功执行后自动 removeSessionHook
 *       (CC registerSkillHooks.ts:36-43)</li>
 *   <li>skillRoot 透传 — skill 目录透传到底层 matcher (CC hooks.ts:890/908 CLAUDE_PLUGIN_ROOT)</li>
 *   <li>frontmatter {@code isAgent=true} + {@code Stop} → {@code SubagentStop} 事件转换
 *       (CC registerFrontmatterHooks.ts:40-45)</li>
 *   <li>frontmatter 作用域修正 — 4 参 register 带 sessionId, 非 3 参 GLOBAL
 *       (CC registerFrontmatterHooks.ts:13 "session ID for agents")</li>
 * </ul>
 */
@DisplayName("[H12] RegisterSkillHooks + FrontmatterHooks 作用域对齐 CC")
class SkillHooksTest {

    private final HookRegistry hookRegistry = new HookRegistry();
    private final RegisterSkillHooks registerSkillHooks = new RegisterSkillHooks(hookRegistry);

    /** WHY: 测试便捷构造 CommandHook (字段除 command/once 外无关紧要). */
    private static CommandHook cmdHook(String command, boolean once) {
        return new CommandHook(command, null, null, null, null, once, null, null);
    }

    // ════════════════════════════════════════════════════════════════════
    // 1-3. registerForSkill 正向
    // ════════════════════════════════════════════════════════════════════

    /**
     * WHY: registerForSkill (CC L20-64) 遍历 HOOK_EVENTS → matchers → hooks 注册 session hook.
     * 若注册后 getSessionHooks 查不到, 则 skill hook 配置了但永不执行 = 注册链路断裂.
     */
    @Test
    @DisplayName("registerForSkill 遍历 event→matcher→hook 注册, 返回注册数 >0")
    void registerForSkill_registersHooksAndReturnsCount() {
        RegisterSkillHooks.SkillHookMatcher matcher =
                new RegisterSkillHooks.SkillHookMatcher("*", List.of(cmdHook("echo a", false), cmdHook("echo b", false)));
        RegisterSkillHooks.SkillHooksSettings settings = new RegisterSkillHooks.SkillHooksSettings(
                Map.of(HookEventType.USER_PROMPT_SUBMIT, List.of(matcher)));

        int count = registerSkillHooks.registerForSkill("sess-1", settings, "skill-a", "/skills/a");

        assertThat(count).isEqualTo(2);
        Map<HookEventType, List<SessionHookStore.SessionDerivedHookMatcher>> hooks =
                hookRegistry.getSessionHooks("sess-1", HookEventType.USER_PROMPT_SUBMIT);
        assertThat(hooks).containsKey(HookEventType.USER_PROMPT_SUBMIT);
        assertThat(hooks.get(HookEventType.USER_PROMPT_SUBMIT)).hasSize(1);
        assertThat(hooks.get(HookEventType.USER_PROMPT_SUBMIT).get(0).hooks()).hasSize(2);
    }

    /**
     * WHY: once:true one-shot 语义 (CC registerSkillHooks.ts:36-43) — 注册时必须带上
     * onHookSuccess 回调, 首次成功执行后自动 removeSessionHook. 若 once:true 却不带回调,
     * skill 副作用会在每个 turn 重复触发 = 语义错位.
     */
    @Test
    @DisplayName("once:true → 注册 onHookSuccess 回调, 首次成功后自动移除 hook")
    void registerForSkill_onceTrue_registersOnHookSuccessThatRemovesHook() {
        CommandHook onceHook = cmdHook("echo once", true);
        RegisterSkillHooks.SkillHooksSettings settings = new RegisterSkillHooks.SkillHooksSettings(
                Map.of(HookEventType.USER_PROMPT_SUBMIT,
                        List.of(new RegisterSkillHooks.SkillHookMatcher("*", List.of(onceHook)))));

        registerSkillHooks.registerForSkill("sess-1", settings, "skill-a", "/skills/a");

        // 首次执行入口取到完整 entry (含 onHookSuccess)
        Optional<SessionHookStore.SessionHookEntry> entry =
                hookRegistry.getSessionHookCallback("sess-1", HookEventType.USER_PROMPT_SUBMIT, "*", onceHook);
        assertThat(entry).isPresent();
        assertThat(entry.get().onHookSuccess()).isNotNull();

        // 模拟 hook 首次成功执行 → onHookSuccess 触发 → removeSessionHook
        entry.get().onHookSuccess().onSuccess(onceHook, AggregatedHookResult.proceed());

        assertThat(hookRegistry.getSessionHooks("sess-1", HookEventType.USER_PROMPT_SUBMIT)).isEmpty();
    }

    /**
     * WHY: 非 once hook 必须注册 <b>null</b> onHookSuccess (CC registerSkillHooks.ts:36
     * {@code hook.once ? callback : undefined}). 若非 once 也带回调, 会导致 hook 首次执行后
     * 被误移除 — 与 once 语义混淆.
     */
    @Test
    @DisplayName("非 once hook → onHookSuccess 为 null (不自动移除)")
    void registerForSkill_notOnce_onHookSuccessIsNull() {
        CommandHook persistentHook = cmdHook("echo p", false);
        RegisterSkillHooks.SkillHooksSettings settings = new RegisterSkillHooks.SkillHooksSettings(
                Map.of(HookEventType.USER_PROMPT_SUBMIT,
                        List.of(new RegisterSkillHooks.SkillHookMatcher("*", List.of(persistentHook)))));

        registerSkillHooks.registerForSkill("sess-1", settings, "skill-a", "/skills/a");

        Optional<SessionHookStore.SessionHookEntry> entry =
                hookRegistry.getSessionHookCallback("sess-1", HookEventType.USER_PROMPT_SUBMIT, "*", persistentHook);
        assertThat(entry).isPresent();
        assertThat(entry.get().onHookSuccess()).isNull();
    }

    /**
     * WHY: [?-3 / R2I-DEC-17 多事件验证 · ALIGN-VERIFY-1 补 E3] 同一 once hook 注册于多个事件时，
     * CC 按<b>事件维度</b> removeSessionHook（sessionHooks.ts:225-249 只删 {@code store.hooks[event]}
     * 内 isHookEqual 命中项，事件空则删 key）—— 事件 A 首次成功只移除事件 A，事件 B 仍保留并各自
     * once 一次；Java SessionHookStore.removeSessionHook（:238-268）逐事件删除 + 空则删 key，语义
     * 同构。若实现误为"全局移除"，同 hook 在 Stop 事件上会注册了却永不执行（静默失效）。
     */
    @Test
    @DisplayName("once hook 多事件：事件 A 成功仅移除事件 A，事件 B 保留并各自 once（CC 按事件维度移除）")
    void registerForSkill_onceHookOnMultipleEvents_removesPerEvent() {
        CommandHook onceHook = cmdHook("echo once", true);
        RegisterSkillHooks.SkillHookMatcher matcher =
                new RegisterSkillHooks.SkillHookMatcher("*", List.of(onceHook));
        RegisterSkillHooks.SkillHooksSettings settings = new RegisterSkillHooks.SkillHooksSettings(
                Map.of(HookEventType.USER_PROMPT_SUBMIT, List.of(matcher),
                        HookEventType.STOP, List.of(matcher)));

        registerSkillHooks.registerForSkill("sess-1", settings, "skill-a", "/skills/a");

        // 两事件均已注册，且各自带 once onHookSuccess 回调（CC registerSkillHooks.ts:36-43 按事件闭包）
        Optional<SessionHookStore.SessionHookEntry> entryA =
                hookRegistry.getSessionHookCallback("sess-1", HookEventType.USER_PROMPT_SUBMIT, "*", onceHook);
        Optional<SessionHookStore.SessionHookEntry> entryB =
                hookRegistry.getSessionHookCallback("sess-1", HookEventType.STOP, "*", onceHook);
        assertThat(entryA).isPresent();
        assertThat(entryB).isPresent();
        assertThat(entryA.get().onHookSuccess()).isNotNull();
        assertThat(entryB.get().onHookSuccess()).isNotNull();

        // 事件 A 首次成功 → 仅移除事件 A（CC sessionHooks.ts:225-249 按事件维度删除）
        entryA.get().onHookSuccess().onSuccess(onceHook, AggregatedHookResult.proceed());

        assertThat(hookRegistry.getSessionHooks("sess-1", HookEventType.USER_PROMPT_SUBMIT)).isEmpty();
        assertThat(hookRegistry.getSessionHooks("sess-1", HookEventType.STOP)).isNotEmpty();

        // 事件 B 随后成功 → 事件 B 亦移除（各事件独立 once 一次）
        entryB.get().onHookSuccess().onSuccess(onceHook, AggregatedHookResult.proceed());
        assertThat(hookRegistry.getSessionHooks("sess-1", HookEventType.STOP)).isEmpty();
    }

    /**
     * WHY: skillRoot 透传 (CC registerSkillHooks.ts:52 + hooks.ts:890/908) — skill 根目录
     * 必须透传到底层 matcher, 供 hook 执行时注入 CLAUDE_PLUGIN_ROOT env. 若 skillRoot 丢失,
     * skill-scoped hook 无法区分是哪个 skill 的 (分组失效).
     */
    @Test
    @DisplayName("skillRoot 透传到底层 matcher")
    void registerForSkill_skillRoot_passthrough() {
        RegisterSkillHooks.SkillHooksSettings settings = new RegisterSkillHooks.SkillHooksSettings(
                Map.of(HookEventType.USER_PROMPT_SUBMIT,
                        List.of(new RegisterSkillHooks.SkillHookMatcher("*", List.of(cmdHook("echo a", false))))));

        registerSkillHooks.registerForSkill("sess-1", settings, "skill-a", "/skills/a");

        Map<HookEventType, List<SessionHookStore.SessionDerivedHookMatcher>> hooks =
                hookRegistry.getSessionHooks("sess-1", HookEventType.USER_PROMPT_SUBMIT);
        assertThat(hooks.get(HookEventType.USER_PROMPT_SUBMIT).get(0).skillRoot()).isEqualTo("/skills/a");
    }

    // ════════════════════════════════════════════════════════════════════
    // 3.5 [H12 v2 Gap1] registerSkillHooks(Command) 生产接线路径
    // ════════════════════════════════════════════════════════════════════

    /**
     * WHY: [H12 v2 Gap1] 生产接线 — 此前 registerForSkill 生产零调用方, skill frontmatter
     * hooks 运行时永不注册. registerSkillHooks(sessionId, Command) 是 SkillToolImpl 的接线
     * 入口 (对齐 CC processSlashCommand.tsx:877): 解析 {@link Command#getHooks()} JSON
     * (event→matcher→hooks) 并按 once/skillRoot 语义注册. 若 JSON 解析不工作, skill 的
     * frontmatter hooks 即便配置了也不会执行.
     */
    @Test
    @DisplayName("registerSkillHooks(Command) 解析 hooks JSON 并注册 (once + skillRoot)")
    void registerForSkill_fromCommand_parsesHooksJsonAndRegisters() {
        Command skill = new Command();
        skill.setName("skill-a");
        skill.setBaseDir("/skills/a");
        skill.setHooks("{\"UserPromptSubmit\":[{\"matcher\":\"*\",\"hooks\":["
                + "{\"type\":\"command\",\"command\":\"echo once\",\"once\":true}]}]}");

        int count = registerSkillHooks.registerSkillHooks("sess-1", skill);

        assertThat(count).isEqualTo(1);
        Map<HookEventType, List<SessionHookStore.SessionDerivedHookMatcher>> hooks =
                hookRegistry.getSessionHooks("sess-1", HookEventType.USER_PROMPT_SUBMIT);
        assertThat(hooks).containsKey(HookEventType.USER_PROMPT_SUBMIT);
        // once:true → onHookSuccess 回调已注册 (首次成功后自动移除)
        assertThat(hooks.get(HookEventType.USER_PROMPT_SUBMIT).get(0).skillRoot()).isEqualTo("/skills/a");
        Optional<SessionHookStore.SessionHookEntry> entry = hookRegistry.getSessionHookCallback(
                "sess-1", HookEventType.USER_PROMPT_SUBMIT, "*",
                new CommandHook("echo once", null, null, null, null, true, null, null));
        assertThat(entry).isPresent();
        assertThat(entry.get().onHookSuccess()).isNotNull();
    }

    /**
     * WHY: [H12 v2 Gap1] 非法 hooks JSON → 不抛异常、不伪造注册 (fail-loud 到日志).
     * 若解析失败也硬注册空 hooks, 会掩盖配置错误.
     */
    @Test
    @DisplayName("registerSkillHooks(Command) 非法 hooks JSON → 0 注册 (不抛)")
    void registerForSkill_fromCommand_badHooksJson_returnsZero() {
        Command skill = new Command();
        skill.setName("skill-b");
        skill.setHooks("not-json{{{");

        int count = registerSkillHooks.registerSkillHooks("sess-1", skill);

        assertThat(count).isZero();
        assertThat(hookRegistry.getSessionHooks("sess-1", HookEventType.USER_PROMPT_SUBMIT)).isEmpty();
    }

    /**
     * WHY: [H12 v2 Gap1] SkillToolImpl 生产接线端到端 — 技能执行 (inline 展开) 时 frontmatter
     * hooks 必须注册进 session store. 此前 SkillToolImpl 无 RegisterSkillHooks 引用, skill
     * 一旦执行, 其 frontmatter hooks 也不会在运行时生效.
     */
    @Test
    @DisplayName("SkillToolImpl 执行技能 → frontmatter hooks 注册 (生产接线)")
    void skillToolImpl_executeSkill_registersFrontmatterHooks(@TempDir Path tempDir) throws Exception {
        // 构造含 hooks frontmatter 的 SKILL.md (单行 JSON hooks, YAML 解析器按标量字符串保留)
        Path skillsRoot = tempDir.resolve("skills");
        Files.createDirectories(skillsRoot.resolve("skill-a"));
        Files.writeString(skillsRoot.resolve("skill-a").resolve("SKILL.md"),
                "---\nname: skill-a\nhooks: {\"UserPromptSubmit\":[{\"matcher\":\"*\","
                        + "\"hooks\":[{\"type\":\"command\",\"command\":\"echo a\"}]}]}\n---\n"
                        + "Skill body content\n");

        SkillRegistry registry = new SkillRegistry(skillsRoot.toString());
        HookRegistry registry2 = new HookRegistry();
        RegisterSkillHooks rsh = new RegisterSkillHooks(registry2);
        SkillToolImpl tool = new SkillToolImpl(registry);
        tool.setRegisterSkillHooks(rsh);

        ObjectMapper mapper = new ObjectMapper();
        ToolUseBlock block = new ToolUseBlock("tb-1", "Skill", mapper.readTree("{\"skill\":\"skill-a\"}"));
        String sessionId = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        tool.execute(block, ToolUseContext.of(UUID.randomUUID(), sessionId));

        Map<HookEventType, List<SessionHookStore.SessionDerivedHookMatcher>> hooks =
                registry2.getSessionHooks(sessionId.toString(), HookEventType.USER_PROMPT_SUBMIT);
        assertThat(hooks).containsKey(HookEventType.USER_PROMPT_SUBMIT);
        assertThat(hooks.get(HookEventType.USER_PROMPT_SUBMIT).get(0).hooks()).hasSize(1);
    }

    // ════════════════════════════════════════════════════════════════════
    // 3.6 [ALIGN-HOOKS-1 △-8] 注册权限门控 (CC processSlashCommand.tsx:874-875)
    // ════════════════════════════════════════════════════════════════════

    /**
     * WHY: [ALIGN-HOOKS-1 △-8] 注册权限门控 (CC processSlashCommand.tsx:874-875) —
     * {@code hooksAllowedForThisSkill = !isRestrictedToPluginOnly('hooks') || isSourceAdminTrusted(source)}.
     * policy strictPluginOnlyCustomization 锁 hooks 面 (此处 true 锁全部面) 且来源非 admin-trusted
     * (user/mcp) → 技能 frontmatter hooks 不得注册. 若无门控, managed policy 锁定后用户自定义
     * skill 的 hooks 仍注入 session = 权限旁路 (与 runAgent.ts agent frontmatter 门控同语义).
     * 技能本身仍正常执行 (仅 hooks 注册被门控).
     */
    @Test
    @DisplayName("policy 锁 hooks 面 + user 来源 → frontmatter hooks 不注册 (CC processSlashCommand.tsx:874-875)")
    void skillToolImpl_lockedHooks_userSource_skipsHooksRegistration(@TempDir Path tempDir) throws Exception {
        Path skillsRoot = tempDir.resolve("skills");
        Files.createDirectories(skillsRoot.resolve("skill-a"));
        Files.writeString(skillsRoot.resolve("skill-a").resolve("SKILL.md"),
                "---\nname: skill-a\nhooks: {\"UserPromptSubmit\":[{\"matcher\":\"*\","
                        + "\"hooks\":[{\"type\":\"command\",\"command\":\"echo a\"}]}]}\n---\n"
                        + "Skill body content\n");

        SkillRegistry registry = new SkillRegistry(skillsRoot.toString());
        HookRegistry registry2 = new HookRegistry();
        RegisterSkillHooks rsh = new RegisterSkillHooks(registry2);
        SkillToolImpl tool = new SkillToolImpl(registry);
        tool.setRegisterSkillHooks(rsh);
        // policy=true 锁全部 customization 面 (CC pluginOnlyPolicy.ts:19-20)
        tool.setPluginOnlySettingsSupplier(() -> Map.of("strictPluginOnlyCustomization", true));

        ObjectMapper mapper = new ObjectMapper();
        ToolUseBlock block = new ToolUseBlock("tb-1", "Skill", mapper.readTree("{\"skill\":\"skill-a\"}"));
        String sessionId = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        var result = tool.execute(block, ToolUseContext.of(UUID.randomUUID(), sessionId));

        // 门控只拦 hooks 注册, 技能本身仍正常执行
        assertThat(LlmAgentLoop.isToolErrorData(result.data()))
            .as("技能正常执行 data 非错误消息（IMP-C2 后 isError 由执行路径推导）")
            .isFalse();
        assertThat(registry2.getSessionHooks(sessionId.toString(), HookEventType.USER_PROMPT_SUBMIT)).isEmpty();
    }

    /**
     * WHY: [ALIGN-HOOKS-1 △-8] 门控例外 (CC pluginOnlyPolicy.ts ADMIN_TRUSTED_SOURCES
     * plugin/policySettings/built-in/builtin/bundled) — policy 锁 hooks 面 (此处 array 形式
     * ['hooks']) 时 admin-trusted 来源仍注册 (admin-approved surface, CC :874
     * {@code !isRestrictedToPluginOnly('hooks') || isSourceAdminTrusted(command.source)}).
     * 若无例外, bundled/plugin skill 的 hooks 会被 policy 误伤.
     */
    @Test
    @DisplayName("policy 锁 hooks 面 + bundled 来源 → 仍注册 (admin-trusted 例外)")
    void skillToolImpl_lockedHooks_bundledSource_registersHooks(@TempDir Path tempDir) throws Exception {
        Path skillsRoot = tempDir.resolve("skills");
        Files.createDirectories(skillsRoot.resolve("skill-a"));
        Files.writeString(skillsRoot.resolve("skill-a").resolve("SKILL.md"),
                "---\nname: skill-a\nhooks: {\"UserPromptSubmit\":[{\"matcher\":\"*\","
                        + "\"hooks\":[{\"type\":\"command\",\"command\":\"echo a\"}]}]}\n---\n"
                        + "Skill body content\n");

        SkillRegistry registry = new SkillRegistry(skillsRoot.toString());
        HookRegistry registry2 = new HookRegistry();
        RegisterSkillHooks rsh = new RegisterSkillHooks(registry2);
        SkillToolImpl tool = new SkillToolImpl(registry);
        tool.setRegisterSkillHooks(rsh);
        // policy array 形式只锁 hooks 面 (CC pluginOnlyPolicy.ts:23)
        tool.setPluginOnlySettingsSupplier(() -> Map.of("strictPluginOnlyCustomization", List.of("hooks")));
        // 磁盘 skill 默认 source=USER → 改标 BUNDLED (admin-trusted, CC 'bundled' ∈ ADMIN_TRUSTED_SOURCES)
        registry.findCommand("skill-a").setSource(CommandSource.BUNDLED);

        ObjectMapper mapper = new ObjectMapper();
        ToolUseBlock block = new ToolUseBlock("tb-1", "Skill", mapper.readTree("{\"skill\":\"skill-a\"}"));
        String sessionId = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        var result = tool.execute(block, ToolUseContext.of(UUID.randomUUID(), sessionId));

        assertThat(LlmAgentLoop.isToolErrorData(result.data()))
            .as("bundled 来源技能执行 data 非错误消息（IMP-C2 后 isError 由执行路径推导）")
            .isFalse();
        Map<HookEventType, List<SessionHookStore.SessionDerivedHookMatcher>> hooks =
                registry2.getSessionHooks(sessionId.toString(), HookEventType.USER_PROMPT_SUBMIT);
        assertThat(hooks).containsKey(HookEventType.USER_PROMPT_SUBMIT);
        assertThat(hooks.get(HookEventType.USER_PROMPT_SUBMIT).get(0).hooks()).hasSize(1);
    }

    /**
     * WHY: [P3-22] managed（policySettings）源技能 hooks 注册门控例外 · CC original:
     * {@code loadSkillsDir.ts:688} managed 技能 source='policySettings'，ADMIN_TRUSTED_SOURCES
     * （pluginOnlyPolicy.ts:40-46）含 camelCase 'policySettings'。旧 {@code toCcSource(POLICY_SETTINGS)}
     * 经 {@code name().toLowerCase()} 漂移为 "policy_settings"（snake_case）∉ 集合 → hooks 面被
     * strictPluginOnlyCustomization 锁定时 managed 技能 frontmatter hooks 漏注册（WF6-01 △-1，
     * EV-WF6-SH-101）。修复后 toCcSource 特殊映射 POLICY_SETTINGS→"policySettings" → admin-trusted
     * 例外仍注册（CC processSlashCommand.tsx:874-875）。
     */
    @Test
    @DisplayName("policy 锁 hooks 面 + policySettings 来源 → 仍注册 (P3-22 camelCase 映射修复)")
    void skillToolImpl_lockedHooks_policySettingsSource_registersHooks(@TempDir Path tempDir) throws Exception {
        Path skillsRoot = tempDir.resolve("skills");
        Files.createDirectories(skillsRoot.resolve("skill-a"));
        Files.writeString(skillsRoot.resolve("skill-a").resolve("SKILL.md"),
                "---\nname: skill-a\nhooks: {\"UserPromptSubmit\":[{\"matcher\":\"*\","
                        + "\"hooks\":[{\"type\":\"command\",\"command\":\"echo a\"}]}]}\n---\n"
                        + "Skill body content\n");

        SkillRegistry registry = new SkillRegistry(skillsRoot.toString());
        HookRegistry registry2 = new HookRegistry();
        RegisterSkillHooks rsh = new RegisterSkillHooks(registry2);
        SkillToolImpl tool = new SkillToolImpl(registry);
        tool.setRegisterSkillHooks(rsh);
        // policy=true 锁全部 customization 面 (CC pluginOnlyPolicy.ts:19-20)
        tool.setPluginOnlySettingsSupplier(() -> Map.of("strictPluginOnlyCustomization", true));
        // 磁盘 skill 默认 source=USER → 改标 POLICY_SETTINGS（managed 源，CC 'policySettings' ∈ ADMIN_TRUSTED_SOURCES）
        registry.findCommand("skill-a").setSource(CommandSource.POLICY_SETTINGS);

        ObjectMapper mapper = new ObjectMapper();
        ToolUseBlock block = new ToolUseBlock("tb-1", "Skill", mapper.readTree("{\"skill\":\"skill-a\"}"));
        String sessionId = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        var result = tool.execute(block, ToolUseContext.of(UUID.randomUUID(), sessionId));

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse();
        Map<HookEventType, List<SessionHookStore.SessionDerivedHookMatcher>> hooks =
                registry2.getSessionHooks(sessionId.toString(), HookEventType.USER_PROMPT_SUBMIT);
        assertThat(hooks).containsKey(HookEventType.USER_PROMPT_SUBMIT);
        assertThat(hooks.get(HookEventType.USER_PROMPT_SUBMIT).get(0).hooks()).hasSize(1);
    }

    // ════════════════════════════════════════════════════════════════════
    // 4-5. FrontmatterHooks 作用域修正
    // ════════════════════════════════════════════════════════════════════

    /**
     * WHY: frontmatter {@code isAgent=true} + {@code Stop} → {@code SubagentStop}
     * (CC registerFrontmatterHooks.ts:40-45). subagent 完成触发 SubagentStop 而非 Stop,
     * 若不转换, agent frontmatter 的 Stop hook 永不触发 = 静默失效.
     *
     * <p>[IMPL-10] DEL-L03-04: 断言改走 SessionHookStore 公开 API (getSessionHooks),
     * 不再反射读 hookSessionScopes/hookEventFilters 旧机制.
     */
    @Test
    @DisplayName("frontmatter isAgent=true + Stop → 注册到 SubagentStop 会话 hooks")
    void frontmatter_isAgent_stopConvertsToSubagentStop() {
        // [Session S1 P1-4] FrontmatterHooks.HookMatcher/HooksSettings 内嵌 record 已删除,
        // 迁移到 permission.hook.HookMatcher (List<HookCommand>) + Map<HookEventType, List<HookMatcher>>.
        HookMatcher stopMatcher = new HookMatcher("*",
                List.<HookCommand>of(new CommandHook("echo hi", null, null, null, null, false, false, false)));
        Map<HookEventType, List<HookMatcher>> settings =
                Map.of(HookEventType.STOP, List.of(stopMatcher));

        int count = FrontmatterHooks.register(hookRegistry, "agent-1", "subagent", settings);

        assertThat(count).isEqualTo(1);
        // 注册到 SUBAGENT_STOP（agent 场景 Stop→SubagentStop 转换）· key=agentId
        assertThat(hookRegistry.getSessionHooks("agent-1", HookEventType.SUBAGENT_STOP)).isNotEmpty();
        assertThat(hookRegistry.getSessionHooks("agent-1", HookEventType.STOP)).isEmpty();
    }

    /**
     * WHY: frontmatter {@code isAgent=false} (CC 默认, registerFrontmatterHooks.ts:23) + Stop
     * → Stop 保持 Stop, 不转换 SubagentStop (CC registerFrontmatterHooks.ts:40
     * {@code if (isAgent && event === 'Stop')}). 若无条件转换, skill/普通 frontmatter 的
     * Stop hook 会在 SubagentStop 事件上永不触发 (skill 不产生 SubagentStop) = 静默失效.
     */
    @Test
    @DisplayName("frontmatter isAgent=false + Stop → 保持 Stop 事件 (不转换)")
    void frontmatter_notAgent_stopStaysStop() {
        // [Session S1 P1-4] 迁移到 permission.hook.HookMatcher + Map<HookEventType, List<HookMatcher>>
        HookMatcher stopMatcher = new HookMatcher("*",
                List.<HookCommand>of(new CommandHook("echo hi", null, null, null, null, false, false, false)));
        Map<HookEventType, List<HookMatcher>> settings =
                Map.of(HookEventType.STOP, List.of(stopMatcher));

        int count = FrontmatterHooks.register(hookRegistry, "sess-x", "skill", settings, false);

        assertThat(count).isEqualTo(1);
        assertThat(hookRegistry.getSessionHooks("sess-x", HookEventType.STOP)).isNotEmpty();
        assertThat(hookRegistry.getSessionHooks("sess-x", HookEventType.SUBAGENT_STOP)).isEmpty();
    }

    /**
     * WHY: frontmatter 作用域 (CC registerFrontmatterHooks.ts:13 "session ID for agents") —
     * hooks 注册到 SessionHookStore key=agentId（addSessionHook 等价）。若注册为 GLOBAL,
     * hook 会在所有会话/agent 上触发 = 跨 agent 泄漏。
     */
    @Test
    @DisplayName("frontmatter 注册 → session hooks key=agentId（非 GLOBAL，跨 agent 隔离）")
    void frontmatter_register_scopedToAgentId() {
        // [Session S1 P1-4] 迁移到 permission.hook.HookMatcher + Map<HookEventType, List<HookMatcher>>
        HookMatcher matcher = new HookMatcher("*",
                List.<HookCommand>of(new CommandHook("echo hi", null, null, null, null, false, false, false)));
        Map<HookEventType, List<HookMatcher>> settings =
                Map.of(HookEventType.STOP, List.of(matcher));

        int count = FrontmatterHooks.register(hookRegistry, "agent-1", "subagent", settings);

        assertThat(count).isEqualTo(1);
        // key=agentId 命中（isAgent=true → STOP 已转 SUBAGENT_STOP）；其他 agent/session key 不可见（无跨 agent 泄漏）
        assertThat(hookRegistry.getSessionHooks("agent-1", HookEventType.SUBAGENT_STOP)).isNotEmpty();
        assertThat(hookRegistry.getSessionHooks("other-agent", HookEventType.SUBAGENT_STOP)).isEmpty();
    }
}
