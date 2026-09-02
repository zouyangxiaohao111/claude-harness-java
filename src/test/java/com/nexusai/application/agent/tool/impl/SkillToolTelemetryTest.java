package com.nexusai.application.agent.tool.impl;

import com.nexusai.application.agent.LlmAgentLoop;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.skill.SkillRegistry;
import com.nexusai.application.agent.telemetry.Telemetry;
import com.nexusai.application.agent.tool.ContentBlockParam;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.model.command.Command;
import com.nexusai.model.command.CommandSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

/**
 * [P3-4] SkillTool 遥测对齐 CC 测试 · 对齐 CC SkillTool.ts:
 *   searchHint (:333) / T5 tengu_skill_tool_slash_prefix (:366-372) /
 *   T1 tengu_skill_tool_invocation inline (:675-726) + fork (:152-203) /
 *   was_discovered 供给链消费 (:139-146 / :661-668)。
 *
 * <p>规则九 (验证意图): 遥测是 CC 分析数据源（BQ 查询 skill 调用量 / 斜杠前缀习惯 /
 * 发现态命中率）。若 T5/T1 埋点缺失或字段漂移，web 侧无对应遥测，无法观测技能使用
 * 分布与 nested-skill 触发链路。每项断言体现"该字段为何存在"（CC 行号逐字段标注）。
 *
 * <p>RED 依据: 实施前 SkillToolImpl 无 searchHint() 方法、无 telemetry 字段、
 * validateInput/doExecute 零遥测点 —— 本测试全部转 GREEN 即行为已对齐。
 */
@DisplayName("[P3-4] SkillTool telemetry: searchHint / T5 slash_prefix / T1 invocation / was_discovered")
class SkillToolTelemetryTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 返回单个 Command 的注册表（避免加载磁盘/Bundled 技能）· 复用
     * SkillToolValidateInputTest.SingleCommandRegistry 模式（override getAllCommands）。
     */
    private static final class SingleCommandRegistry extends SkillRegistry {
        private final List<Command> cmds;

        SingleCommandRegistry(List<Command> cmds) {
            super(".claude/skills");
            this.cmds = cmds;
        }

        @Override
        public List<Command> getAllCommands() {
            return cmds;
        }
    }

    private static SkillRegistry registryWith(Command cmd) {
        return new SingleCommandRegistry(List.of(cmd));
    }

    /**
     * 构造无磁盘依赖的命令 · promptFn 闭包路径 (P2-8, CC getPromptForCommand):
     * doExecute 走闭包内容源, 无需 SkillContentLoader 读盘; withBaseDirPrefix 对 null baseDir
     * 原样返回 (SkillContentLoader:112-115)。source 显式注入供 sanitizedCommandName 判别。
     */
    private static Command skill(String name, CommandSource source) {
        Command cmd = new Command();
        cmd.setName(name);
        cmd.setSource(source);
        cmd.setPromptFn((args, cwd) -> List.of(
            (ContentBlockParam) new ContentBlockParam.TextBlockParam("# " + name + "\n\nbody")));
        return cmd;
    }

    /** 构造 plugin 源命令（含 pluginInfo 写侧契约：pluginManifest + repository，CC loadPluginCommands.ts:317-320）。 */
    private static Command pluginSkill(String name, String pluginManifestName, String repository) {
        Command cmd = skill(name, CommandSource.PLUGIN);
        cmd.setPluginInfo(new Command.PluginInfo(new Command.PluginManifest(pluginManifestName), repository));
        return cmd;
    }

    /** 独立计算 CC hashPluginId（pluginTelemetry.ts:48-54）期望值：sha256(name@marketplace.lowercase + salt) 前 16 位。 */
    private static String expectedPluginIdHash(String name, String marketplace) {
        String key = (marketplace != null && !marketplace.isEmpty())
                ? name + "@" + marketplace.toLowerCase()
                : name;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest((key + "claude-plugin-telemetry-v1").getBytes(StandardCharsets.UTF_8)))
                    .substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ToolUseContext ctx() {
        return ToolUseContext.of(UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8));
    }

    private static ToolUseBlock skillBlock(String skillName) {
        ObjectNode input = MAPPER.createObjectNode();
        input.put("skill", skillName);
        return new ToolUseBlock(UUID.randomUUID().toString(), "Skill", input);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Map<String, Object> capturedInvocation(org.mockito.ArgumentCaptor captor) {
        return (Map<String, Object>) captor.getValue();
    }

    private static org.mockito.ArgumentCaptor<Map<String, Object>> invocationCaptor() {
        return (org.mockito.ArgumentCaptor) org.mockito.ArgumentCaptor.forClass(Map.class);
    }

    // ══════════════════════════════════════════════════════════════════════
    // searchHint (CC SkillTool.ts:333)
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("searchHint() = 'invoke a slash-command skill' (CC SkillTool.ts:333)")
    void searchHint_matchesCC() {
        // WHY: CC buildTool searchHint 供 ToolSearch 关键词匹配（Tool.ts:378）。Java per-tool
        //   方法（per-tool 方法承载，CC Tool.ts:378 searchHint?: string），注册 metadata 传递 ——
        //   值必须逐字对齐 CC，否则未来 ToolSearch 引入时关键词不匹配。
        SkillToolImpl tool = new SkillToolImpl(registryWith(skill("commit", CommandSource.USER)));
        assertThat(tool.searchHint()).isEqualTo("invoke a slash-command skill");
    }

    // ══════════════════════════════════════════════════════════════════════
    // (e) telemetry 未注入 → null-safe 不破坏执行链
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("telemetry 未注入 (null) → validateInput 斜杠前缀 + inline 执行不 NPE (执行链不破坏)")
    void telemetryNull_noNpe() {
        // WHY: Telemetry 为 @Component 可选注入（@Autowired(required=false)）；POJO/测试可能未注入。
        //   若埋点非 null-safe，未注入时每次技能调用 NPE → 破坏既有执行链（对齐 CC logEvent
        //   best-effort：遥测失败不得影响技能执行）。
        SkillToolImpl tool = new SkillToolImpl(registryWith(skill("commit", CommandSource.USER)));
        // 不注入 telemetry（null）
        assertThatCode(() -> {
            tool.validateInput(skillBlock("/commit").input(), ctx());
            tool.execute(skillBlock("commit"), ctx());
        }).doesNotThrowAnyException();
    }

    // ══════════════════════════════════════════════════════════════════════
    // (a) T5 · 前导斜杠触发 tengu_skill_tool_slash_prefix (CC SkillTool.ts:366-372)
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("斜杠前缀输入 → recordEvent('tengu_skill_tool_slash_prefix', {}) (CC SkillTool.ts:366-369)")
    void slashPrefix_emitsSlashPrefixTelemetry() {
        // WHY: CC validateInput 捕获 hasLeadingSlash 后 logEvent('tengu_skill_tool_slash_prefix', {})
        //   —— 统计"用户带斜杠前缀调用 Skill"的习惯信号。漏发则前端无法区分斜杠/裸名调用。
        SkillToolImpl tool = new SkillToolImpl(registryWith(skill("commit", CommandSource.USER)));
        Telemetry telemetry = spy(new Telemetry());
        tool.setTelemetry(telemetry);

        var result = tool.validateInput(skillBlock("/commit").input(), ctx());

        assertThat(result.ok()).isTrue();
        verify(telemetry).recordEvent(eq("tengu_skill_tool_slash_prefix"), eq(Map.of()));
    }

    @Test
    @DisplayName("无斜杠前缀 → 不触发 tengu_skill_tool_slash_prefix（差分对照）")
    void noSlashPrefix_noSlashTelemetry() {
        // WHY: 差分证明 T5 只在前导斜杠时触发（CC :366 hasLeadingSlash 守卫），裸名不误发。
        SkillToolImpl tool = new SkillToolImpl(registryWith(skill("commit", CommandSource.USER)));
        Telemetry telemetry = spy(new Telemetry());
        tool.setTelemetry(telemetry);

        var result = tool.validateInput(skillBlock("commit").input(), ctx());

        assertThat(result.ok()).isTrue();
        verify(telemetry, never()).recordEvent(eq("tengu_skill_tool_slash_prefix"), eq(Map.of()));
    }

    // ══════════════════════════════════════════════════════════════════════
    // (b) T1 inline · execution_context='inline' (CC SkillTool.ts:675-726)
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("inline 执行 → tengu_skill_tool_invocation: context=inline, command_name=custom (USER 源)")
    void inlineExecution_emitsInvocationTelemetry() {
        // WHY: inline 遥测字段集（command_name sanitized / _PROTO_skill_name / execution_context /
        //   invocation_trigger / query_depth）是 BQ 查询技能调用分布的数据源。
        //   USER 源非 bundled/builtin → command_name='custom'（CC :658-659 sanitizedCommandName）。
        //   was_discovered 在 EXPERIMENTAL_SKILL_SEARCH flag-off 时省略（CC :661-668 spread 空对象），
        //   Java 缺省 skillSearchEnabled=false → 字段不发射（ALIGN-ST-1 △-2 对齐）。
        SkillToolImpl tool = new SkillToolImpl(registryWith(skill("inline-skill", CommandSource.USER)));
        Telemetry telemetry = spy(new Telemetry());
        tool.setTelemetry(telemetry);

        var result = tool.execute(skillBlock("inline-skill"), ctx());

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse();
        var captor = invocationCaptor();
        verify(telemetry).recordEvent(eq("tengu_skill_tool_invocation"), captor.capture());
        Map<String, Object> attrs = capturedInvocation(captor);
        assertThat(attrs)
                .containsEntry("command_name", "custom")
                .containsEntry("_PROTO_skill_name", "inline-skill")
                .containsEntry("execution_context", "inline")
                .containsEntry("invocation_trigger", "claude-proactive")
                .containsEntry("query_depth", 0)
                .doesNotContainKey("was_discovered");
    }

    @Test
    @DisplayName("inline bundled 源 → command_name=原名 (CC :654-659 isBundled)")
    void inlineBundled_commandNameOriginal() {
        // WHY: CC sanitizedCommandName 对 bundled 技能保留原名（遥测可聚合 bundled 调用量），
        //   其余归 'custom' —— bundled 判别错则 bundled 调用全部塌进 'custom' 桶。
        SkillToolImpl tool = new SkillToolImpl(registryWith(skill("bundled-skill", CommandSource.BUNDLED)));
        Telemetry telemetry = spy(new Telemetry());
        tool.setTelemetry(telemetry);

        tool.execute(skillBlock("bundled-skill"), ctx());

        var captor = invocationCaptor();
        verify(telemetry).recordEvent(eq("tengu_skill_tool_invocation"), captor.capture());
        assertThat(capturedInvocation(captor)).containsEntry("command_name", "bundled-skill");
    }

    @Test
    @DisplayName("inline builtin 源 → command_name=原名 (CC builtInCommandNames().has)")
    void inlineBuiltin_commandNameOriginal() {
        SkillToolImpl tool = new SkillToolImpl(registryWith(skill("builtin-skill", CommandSource.BUILTIN)));
        Telemetry telemetry = spy(new Telemetry());
        tool.setTelemetry(telemetry);

        tool.execute(skillBlock("builtin-skill"), ctx());

        var captor = invocationCaptor();
        verify(telemetry).recordEvent(eq("tengu_skill_tool_invocation"), captor.capture());
        assertThat(capturedInvocation(captor)).containsEntry("command_name", "builtin-skill");
    }

    // ══════════════════════════════════════════════════════════════════════
    // (c) T1 fork · execution_context='fork' (CC SkillTool.ts:152-203)
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("fork 执行 → tengu_skill_tool_invocation: context='fork', command_name=原名 (BUNDLED)")
    void forkExecution_emitsForkInvocationTelemetry() {
        // WHY: fork 遥测与 inline 同事件名但 execution_context='fork'（CC :160-161）—— BQ 需区分
        //   内联展开 vs 隔离子代理执行两种调用形态。fork 判别错则 fork 调用被统计成 inline。
        Command forkCmd = skill("fork-skill", CommandSource.BUNDLED);
        forkCmd.setContext("fork");
        SkillToolImpl tool = new SkillToolImpl(registryWith(forkCmd));
        SubagentExecutor executor = mock(SubagentExecutor.class);
        doAnswer(inv -> SubagentExecutor.SubagentResult.completed(
                "fork 完成", 0, 0L, "fork-agent-id"))
                .when(executor).executeForkedSkill(
                        anyString(), any(Command.class), anyString(),
                        any(ToolUseContext.class), any());
        tool.setSubagentExecutor(executor);
        Telemetry telemetry = spy(new Telemetry());
        tool.setTelemetry(telemetry);

        var result = tool.execute(skillBlock("fork-skill"), ctx());

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse();
        var captor = invocationCaptor();
        verify(telemetry).recordEvent(eq("tengu_skill_tool_invocation"), captor.capture());
        assertThat(capturedInvocation(captor))
                .containsEntry("execution_context", "fork")
                .containsEntry("command_name", "fork-skill");
    }

    // ══════════════════════════════════════════════════════════════════════
    // (d) was_discovered 供给链消费 (CC SkillTool.ts:139-146 / :661-668)
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("EXPERIMENTAL_SKILL_SEARCH 开 + discoveredSkillNames 含技能名 → was_discovered=true (CC :661-668)")
    void discoveredSkillNames_wasDiscoveredTrue() {
        // WHY: was_discovered 消费 ctx.discoveredSkillNames（CC QueryEngine.ts:192-197 feeds
        //   was_discovered on tengu_skill_tool_invocation）—— 标识技能是模型从发现列表选中还是
        //   静态目录命中。仅当 EXPERIMENTAL_SKILL_SEARCH 开启（isSkillSearchEnabled）时才发射
        //   该字段（CC :661-668 feature 门控）；Java skill-search 子系统范围外，结构就位待填充。
        SkillToolImpl tool = new SkillToolImpl(registryWith(skill("inline-skill", CommandSource.USER)));
        Telemetry telemetry = spy(new Telemetry());
        tool.setTelemetry(telemetry);
        tool.setSkillSearchEnabled(() -> true);   // ALIGN-ST-1 △-2：feature 门控开启
        ToolUseContext tuc = ctx();
        tuc.discoveredSkillNames().add("inline-skill");

        tool.execute(skillBlock("inline-skill"), tuc);

        var captor = invocationCaptor();
        verify(telemetry).recordEvent(eq("tengu_skill_tool_invocation"), captor.capture());
        assertThat(capturedInvocation(captor)).containsEntry("was_discovered", true);
    }

    @Test
    @DisplayName("EXPERIMENTAL_SKILL_SEARCH 开 + discoveredSkillNames 空 → was_discovered=false（差分对照）")
    void discoveredSkillNamesEmpty_wasDiscoveredFalse() {
        SkillToolImpl tool = new SkillToolImpl(registryWith(skill("inline-skill", CommandSource.USER)));
        Telemetry telemetry = spy(new Telemetry());
        tool.setTelemetry(telemetry);
        tool.setSkillSearchEnabled(() -> true);   // feature 门控开启，但发现集为空 → false

        tool.execute(skillBlock("inline-skill"), ctx());

        var captor = invocationCaptor();
        verify(telemetry).recordEvent(eq("tengu_skill_tool_invocation"), captor.capture());
        assertThat(capturedInvocation(captor)).containsEntry("was_discovered", false);
    }

    @Test
    @DisplayName("EXPERIMENTAL_SKILL_SEARCH 关（缺省）→ was_discovered 字段省略（CC feature-off spread 空对象）")
    void skillSearchDisabled_omitsWasDiscoveredEvenWhenDiscovered() {
        // WHY: CC :661-668 was_discovered 仅当 feature('EXPERIMENTAL_SKILL_SEARCH') &&
        //   isSkillSearchEnabled() 时发射；feature-off 时 spread 空对象 → 字段不存在。即便
        //   discoveredSkillNames 含技能名，flag-off 也不得发射（区分 flag 门控与数据有无）。
        SkillToolImpl tool = new SkillToolImpl(registryWith(skill("inline-skill", CommandSource.USER)));
        Telemetry telemetry = spy(new Telemetry());
        tool.setTelemetry(telemetry);
        ToolUseContext tuc = ctx();
        tuc.discoveredSkillNames().add("inline-skill");   // 数据就绪，但 flag 关

        tool.execute(skillBlock("inline-skill"), tuc);

        var captor = invocationCaptor();
        verify(telemetry).recordEvent(eq("tengu_skill_tool_invocation"), captor.capture());
        assertThat(capturedInvocation(captor)).doesNotContainKey("was_discovered");
    }

    // ══════════════════════════════════════════════════════════════════════
    // invocation_trigger / query_depth (CC SkillTool.ts:150 / :162-164 / :673 / :685-687)
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("queryTracking.depth=1 → invocation_trigger='nested-skill', query_depth=1")
    void queryDepthNested_triggerNestedSkill() {
        // WHY: CC invocation_trigger = queryDepth>0 ? 'nested-skill' : 'claude-proactive'（:162-164）
        //   —— 区分技能被嵌套技能触发 vs 主会话主动触发。Java queryTracking 经
        //   AgentLoopContext.withQueryTracking 每轮 stamp（:1053-1068），depth 链递增。
        SkillToolImpl tool = new SkillToolImpl(registryWith(skill("inline-skill", CommandSource.USER)));
        Telemetry telemetry = spy(new Telemetry());
        tool.setTelemetry(telemetry);
        ToolUseContext tuc = ctx().withQueryTracking(
                Map.of("chainId", UUID.randomUUID().toString(), "depth", 1));

        tool.execute(skillBlock("inline-skill"), tuc);

        var captor = invocationCaptor();
        verify(telemetry).recordEvent(eq("tengu_skill_tool_invocation"), captor.capture());
        assertThat(capturedInvocation(captor))
                .containsEntry("query_depth", 1)
                .containsEntry("invocation_trigger", "nested-skill");
    }

    // ══════════════════════════════════════════════════════════════════════
    // (f) plugin 字段块 + isOfficialSkill（FIX-B5 拍板#8，NEW-GAP-V-CI-1-2 回填）
    //     CC SkillTool.ts:185-202/:710-725 + :935-942 isOfficialMarketplaceSkill
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("plugin 源 + 官方 marketplace repository → command_name=原名 + plugin 字段块真实值（CC :185-202/:935-942）")
    void pluginOfficialMarketplace_keepsNameAndEmitsPluginBlock() {
        // WHY: isOfficialMarketplaceSkill（SkillTool.ts:935-942，repository 解析 marketplace ∈
        //   ALLOWED_OFFICIAL_MARKETPLACE_NAMES）→ sanitized 保留原名（:658-659）；plugin 字段块
        //   （:185-202/:710-725）发射 plugin_name/plugin_repository/plugin_scope/is_official_plugin。
        //   Java 此前恒 'custom' + 零 plugin 字段（pluginInfo 读侧零读，NEW-GAP-V-CI-1-2）——回填后
        //   BQ 可区分官方 marketplace 插件调用（skill 调用分布不再塌进 'custom' 桶）。
        Command cmd = pluginSkill("plugin-official", "anthropic-tool", "anthropic-tool@anthropic-marketplace");
        SkillToolImpl tool = new SkillToolImpl(registryWith(cmd));
        Telemetry telemetry = spy(new Telemetry());
        tool.setTelemetry(telemetry);

        tool.execute(skillBlock("plugin-official"), ctx());

        var captor = invocationCaptor();
        verify(telemetry).recordEvent(eq("tengu_skill_tool_invocation"), captor.capture());
        Map<String, Object> attrs = capturedInvocation(captor);
        assertThat(attrs)
                .containsEntry("command_name", "plugin-official")
                .containsEntry("_PROTO_plugin_name", "anthropic-tool")
                .containsEntry("_PROTO_marketplace_name", "anthropic-marketplace")
                .containsEntry("plugin_name", "anthropic-tool")
                .containsEntry("plugin_repository", "anthropic-tool@anthropic-marketplace")
                .containsEntry("plugin_scope", "official")
                .containsEntry("is_official_plugin", true);
    }

    @Test
    @DisplayName("plugin 源 + 第三方 marketplace repository → command_name=custom + redacted 'third-party' 字段块")
    void pluginThirdParty_customNameAndRedactedBlock() {
        // WHY: 第三方 marketplace（不在官方名单）→ isOfficialSkill=false → sanitized='custom'（CC :658-659）
        //   且 redacted 字段 plugin_name/plugin_repository='third-party'（CC :195-200/:718-723，twin-column
        //   privacy 模式：真实名只进 PII-tagged _PROTO_* 列）；plugin_scope='user-local'（pluginTelemetry.ts:80）。
        Command cmd = pluginSkill("plugin-third", "third-name", "third-name@some-market");
        SkillToolImpl tool = new SkillToolImpl(registryWith(cmd));
        Telemetry telemetry = spy(new Telemetry());
        tool.setTelemetry(telemetry);

        tool.execute(skillBlock("plugin-third"), ctx());

        var captor = invocationCaptor();
        verify(telemetry).recordEvent(eq("tengu_skill_tool_invocation"), captor.capture());
        Map<String, Object> attrs = capturedInvocation(captor);
        assertThat(attrs)
                .containsEntry("command_name", "custom")
                .containsEntry("_PROTO_plugin_name", "third-name")
                .containsEntry("_PROTO_marketplace_name", "some-market")
                .containsEntry("plugin_name", "third-party")
                .containsEntry("plugin_repository", "third-party")
                .containsEntry("plugin_scope", "user-local")
                .containsEntry("is_official_plugin", false);
    }

    @Test
    @DisplayName("plugin 源但无 pluginInfo → 不发射 plugin 字段块（CC spread 空对象）+ command_name=custom")
    void pluginSourceWithoutPluginInfo_omitsPluginBlock() {
        // WHY: CC :185/:710 插件块 gate 于 command.pluginInfo truthy —— 无 pluginInfo（写侧契约：仅
        //   loadPluginCommands 置值，其余 plugin 源路径可能缺失）时 spread 空对象，字段必须缺席（区分
        //   「有 pluginInfo 的第三方」与「无 pluginInfo」两种零值，避免误报 third-party）。
        Command cmd = skill("plugin-naked", CommandSource.PLUGIN);   // source=PLUGIN 但无 pluginInfo
        SkillToolImpl tool = new SkillToolImpl(registryWith(cmd));
        Telemetry telemetry = spy(new Telemetry());
        tool.setTelemetry(telemetry);

        tool.execute(skillBlock("plugin-naked"), ctx());

        var captor = invocationCaptor();
        verify(telemetry).recordEvent(eq("tengu_skill_tool_invocation"), captor.capture());
        Map<String, Object> attrs = capturedInvocation(captor);
        assertThat(attrs)
                .containsEntry("command_name", "custom")
                .doesNotContainKey("_PROTO_plugin_name")
                .doesNotContainKey("_PROTO_marketplace_name")
                .doesNotContainKey("plugin_name")
                .doesNotContainKey("plugin_repository")
                .doesNotContainKey("plugin_id_hash");
    }

    @Test
    @DisplayName("plugin_id_hash 对齐 CC sha256(name@marketplace.lowercase + salt) 前 16 位（pluginTelemetry.ts:48-54）")
    void pluginIdHash_matchesCCHashAlgorithm() {
        // WHY: hashPluginId（pluginTelemetry.ts:48-54）提供无隐私依赖的 per-plugin 聚合键
        //   （sha256(name@marketplace.lowercase + 固定盐) hex 前 16 位）；marketplace 后缀 lower-case
        //   保跨仓可复现。算法错则 distinct-count/per-plugin-trend BQ 查询全部失真。
        Command cmd = pluginSkill("plugin-hash", "hashname", "hashname@HashMarket");
        SkillToolImpl tool = new SkillToolImpl(registryWith(cmd));
        Telemetry telemetry = spy(new Telemetry());
        tool.setTelemetry(telemetry);

        tool.execute(skillBlock("plugin-hash"), ctx());

        var captor = invocationCaptor();
        verify(telemetry).recordEvent(eq("tengu_skill_tool_invocation"), captor.capture());
        Map<String, Object> attrs = capturedInvocation(captor);
        assertThat(attrs).containsEntry("plugin_id_hash", expectedPluginIdHash("hashname", "HashMarket"));
    }

    @Test
    @DisplayName("repository 无 '@'（无 marketplace）→ _PROTO_marketplace_name 省略 + plugin_scope=user-local")
    void pluginRepositoryWithoutMarketplace_omitsMarketplaceName() {
        // WHY: CC :191-194/:714-717 _PROTO_marketplace_name 仅当 marketplace 非空时 spread；
        //   parsePluginIdentifier（pluginIdentifier.ts:51-57）无 '@' → marketplace undefined → 字段省略。
        Command cmd = pluginSkill("plugin-plain", "plain-name", "plain-name");
        SkillToolImpl tool = new SkillToolImpl(registryWith(cmd));
        Telemetry telemetry = spy(new Telemetry());
        tool.setTelemetry(telemetry);

        tool.execute(skillBlock("plugin-plain"), ctx());

        var captor = invocationCaptor();
        verify(telemetry).recordEvent(eq("tengu_skill_tool_invocation"), captor.capture());
        Map<String, Object> attrs = capturedInvocation(captor);
        assertThat(attrs)
                .containsEntry("_PROTO_plugin_name", "plain-name")
                .doesNotContainKey("_PROTO_marketplace_name")
                .containsEntry("plugin_name", "third-party")
                .containsEntry("plugin_scope", "user-local")
                .containsEntry("plugin_id_hash", expectedPluginIdHash("plain-name", null));
    }
}
