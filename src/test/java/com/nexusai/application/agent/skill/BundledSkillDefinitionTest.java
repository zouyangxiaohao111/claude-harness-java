package com.nexusai.application.agent.skill;

import com.nexusai.model.command.Command;
import com.nexusai.model.command.CommandSource;
import com.nexusai.model.command.PromptFnContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1-4 统一 BundledSkillDefinition 测试（RED→GREEN）· 对齐 CC bundledSkills.ts:15-41 + :75-98。
 *
 * <p><b>WHY</b>（CLAUDE.md 规则 9 · 测试验证意图）:
 * <ol>
 *   <li><b>字段全集必须=15</b>——改造前 Java 散布 6 个 Registrar 的碎片 record（7/4/7/7/5/4 字段）
 *       缺失 aliases/model/isEnabled/hooks/agent/context/files，是字段丢失根因（E8/E9 复用
 *       BatchSkillRegistrar.BundledSkillDef 导致 allowedTools 丢失）。断言 record 组件数=15 即锁死
 *       CC BundledSkillDefinition 契约；若有人往 record 塞/删字段此断言必红。</li>
 *   <li><b>toCommand() 全字段映射</b>——keybindings userInvocable=false → Command.getUserInvocable()==false
 *       且 isHidden=!(userInvocable??true)=true（CC bundledSkills.ts:86/:95）；progressMessage='running'
 *       （:96）；source=BUNDLED（:88 同 loadedFrom 'bundled'）。这是 CC registerBundledSkill 的等价物，
 *       旧 Bootstrapper.command() 只映射 4 字段（id/name/description/context），必然丢字段。</li>
 *   <li><b>各 Registrar 返回统一类型且 CC 强制字段透传</b>——Schedule allowedTools=[RemoteTrigger,
 *       AskUserQuestion]（CC scheduleRemoteAgents.ts:335，修 E8）、UpdateConfig allowedTools=[Read]
 *       （CC updateConfig.ts:450，修 E9）、Loop/Remember isEnabled 非 null（CC loop.ts:83/remember.ts:71，
 *       修 E10/E11）、ClaudeApi userInvocable=true（CC claudeApi.ts:188）、Verify files=SKILL_FILES
 *       非空（CC verify.ts:21，保 P1-3 解压端到端）。</li>
 * </ol>
 */
class BundledSkillDefinitionTest {

    // ── record 字段全集 = 15（编译期 API + 运行时组件数双断言）──

    @Test
    @DisplayName("BundledSkillDefinition record 组件数=15（对齐 CC bundledSkills.ts:15-41）")
    void recordHasExactlyFifteenComponents() {
        assertThat(BundledSkillDefinition.class.getRecordComponents())
            .hasSize(15)
            .extracting(java.lang.reflect.RecordComponent::getName)
            .containsExactly(
                "name", "description", "aliases", "whenToUse", "argumentHint", "allowedTools",
                "model", "disableModelInvocation", "userInvocable", "isEnabled", "hooks",
                "context", "agent", "files", "getPromptForCommand");
    }

    // ── toCommand() 全字段映射（对齐 CC registerBundledSkill bundledSkills.ts:75-98）──

    @Test
    @DisplayName("toCommand() 映射 keybindings userInvocable=false → userInvocable=false + isHidden=true + progressMessage='running' + source=BUNDLED")
    void toCommandMapsKeybindingsLikeDefinition() {
        BundledSkillDefinition def = new BundledSkillDefinition(
            "keybindings-help",
            "List all available keybindings.",
            null,
            "When the user asks about keybindings.",
            null,
            List.of("Read"),
            null,
            null,
            false,   // userInvocable (CC keybindings.ts:298)
            null,
            null,
            null,
            null,
            null,
            (args, cwd) -> List.of(PromptBlock.text("tables")));

        Command command = def.toCommand();

        assertThat(command.getId()).isEqualTo("bundled-keybindings-help");
        assertThat(command.getName()).isEqualTo("keybindings-help");
        assertThat(command.getDescription()).isEqualTo("List all available keybindings.");
        assertThat(command.getSource()).as("CC bundledSkills.ts:88 source:'bundled'").isEqualTo(CommandSource.BUNDLED);
        assertThat(command.getBuiltin())
            .as("P3-9 01-1 / DEL-03：bundled 不再设 builtin 字段（CC registerBundledSkill 无 builtin，bundledSkills.ts:75-98）——「builtin 性」由 source==BUNDLED 表达")
            .isFalse();
        assertThat(command.getWhenToUse()).isEqualTo("When the user asks about keybindings.");
        assertThat(command.getAllowedTools()).containsExactly("Read");
        assertThat(command.getUserInvocable())
            .as("CC :86 userInvocable 显式 false 必须透传，不得被 Command 构造默认 true 兜底")
            .isFalse();
        assertThat(command.getIsHidden())
            .as("CC :95 isHidden = !(userInvocable ?? true) = !false = true")
            .isTrue();
        assertThat(command.getProgressMessage()).as("CC :96 progressMessage='running'").isEqualTo("running");
        assertThat(command.getContext())
            .as("context 未提供 → 默认 'inline'（Command.java:136 构造默认，CC :92 无缺省）")
            .isEqualTo("inline");
        assertThat(command.getDisableModelInvocation())
            .as("CC :85 disableModelInvocation ?? false")
            .isFalse();
        assertThat(command.getEnabled()).as("isEnabled 未提供 → 默认 true").isTrue();
    }

    @Test
    @DisplayName("toCommand() 缺省映射：userInvocable=null → true + isHidden=false；allowedTools=null → []；isEnabled 求值落 enabled")
    void toCommandAppliesDefaultsAndIsEnabled() {
        // isEnabled=null → enabled 保持默认 true；allowedTools=null → List.of()
        BundledSkillDefinition noIsEnabled = new BundledSkillDefinition(
            "s", "d", null, null, null, null, null, null, null, null,
            null, null, null, null,
            (args, cwd) -> List.of(PromptBlock.text("x")));
        Command c1 = noIsEnabled.toCommand();
        assertThat(c1.getUserInvocable()).as("CC :86 userInvocable ?? true").isTrue();
        assertThat(c1.getIsHidden()).as("CC :95 isHidden = !(true) = false").isFalse();
        assertThat(c1.getAllowedTools()).as("CC :81 allowedTools ?? []").isEmpty();
        assertThat(c1.getEnabled()).isTrue();

        // isEnabled=()->false → Command.enabled=false（CC :94 isEnabled 求值）
        BundledSkillDefinition disabled = new BundledSkillDefinition(
            "loop", "d", null, null, null, null, null, null, null,
            () -> false,
            null, null, null, null,
            (args, cwd) -> List.of(PromptBlock.text("x")));
        assertThat(disabled.toCommand().getEnabled()).isFalse();

        // isEnabled=()->true → Command.enabled=true
        BundledSkillDefinition enabledDef = new BundledSkillDefinition(
            "loop", "d", null, null, null, null, null, null, null,
            () -> true,
            null, null, null, null,
            (args, cwd) -> List.of(PromptBlock.text("x")));
        assertThat(enabledDef.toCommand().getEnabled()).isTrue();

        // hooks / agent / aliases 透传
        BundledSkillDefinition rich = new BundledSkillDefinition(
            "rich", "d", List.of("r1"), null, null, null, "claude-sonnet-4-6", null, true,
            null, "{\"PreToolUse\":[]}", "fork", "general-purpose", null,
            (args, cwd) -> List.of(PromptBlock.text("x")));
        Command cr = rich.toCommand();
        assertThat(cr.getAliases()).containsExactly("r1");
        assertThat(cr.getModel()).isEqualTo("claude-sonnet-4-6");
        assertThat(cr.getHooks()).isEqualTo("{\"PreToolUse\":[]}");
        assertThat(cr.getContext()).isEqualTo("fork");
        assertThat(cr.getAgent()).isEqualTo("general-purpose");
    }

    // ── 各 Registrar 返回统一类型 + CC 强制字段透传 ──

    @Test
    @DisplayName("ScheduleRemoteAgents register() 补 allowedTools=[RemoteTrigger,AskUserQuestion]（修 E8，CC scheduleRemoteAgents.ts:335）")
    void scheduleRegisterCarriesAllowedTools() {
        BundledSkillDefinition def = new ScheduleRemoteAgentsSkillRegistrar(
            () -> false, () -> false, () -> null, List::of,
            name -> new ScheduleRemoteAgentsSkillRegistrar.EnvironmentResource(name, "default", "cloud"),
            () -> "UTC", List::of, () -> null).register();

        assertThat(def.name()).isEqualTo("schedule");
        assertThat(def.allowedTools())
            .as("E8：旧架构复用 BatchSkillRegistrar.BundledSkillDef 丢失 allowedTools；统一类型必须补 CC :335 两工具")
            .containsExactly("RemoteTrigger", "AskUserQuestion");
        assertThat(def.userInvocable()).isTrue();
        assertThat(def.isEnabled())
            .as("CC scheduleRemoteAgents.ts:332-333 isEnabled 双开关必须随统一类型承载，禁止传 null 静默丢失")
            .isNotNull();
        assertThat(def.isEnabled().getAsBoolean())
            .as("isEnabled = featureEnabled.getAsBoolean() && policyAllowed.getAsBoolean()（本测试注入 false&&false）")
            .isFalse();
    }

    @Test
    @DisplayName("UpdateConfig register() 补 allowedTools=[Read]（修 E9，CC updateConfig.ts:450）")
    void updateConfigRegisterCarriesAllowedTools() {
        BundledSkillDefinition def = new UpdateConfigSkillRegistrar(() -> "{}").register();

        assertThat(def.name()).isEqualTo("update-config");
        assertThat(def.allowedTools()).as("E9：CC updateConfig.ts:450 allowedTools: ['Read']")
            .containsExactly("Read");
        assertThat(def.userInvocable()).isTrue();
    }

    @Test
    @DisplayName("Loop register() 携带 whenToUse/argumentHint + isEnabled=isKairosCronEnabled（修 E10/E11，CC loop.ts:79-83）")
    void loopRegisterCarriesIsEnabledAndWhenToUse() {
        BundledSkillDefinition[] holder = new BundledSkillDefinition[1];
        new LoopSkillRegistrar().register(def -> holder[0] = def, () -> true);

        assertThat(holder[0]).isNotNull();
        assertThat(holder[0].name()).isEqualTo("loop");
        assertThat(holder[0].whenToUse())
            .as("CC loop.ts:80 whenToUse 必须携带（旧 sink 5 参签名丢弃）")
            .isNotBlank();
        assertThat(holder[0].argumentHint()).isEqualTo("[interval] <prompt>");
        assertThat(holder[0].isEnabled())
            .as("CC loop.ts:83 isEnabled: isKairosCronEnabled")
            .isNotNull();
        assertThat(holder[0].isEnabled().getAsBoolean()).isTrue();
    }

    @Test
    @DisplayName("Remember register() 携带 isEnabled=isAutoMemoryEnabled + ant 早返（修 E10/E11，CC remember.ts:71）")
    void rememberRegisterCarriesIsEnabled() {
        BundledSkillDefinition[] holder = new BundledSkillDefinition[1];
        boolean registered = new RememberSkillRegistrar().register(def -> holder[0] = def, true, () -> true);

        assertThat(registered).isTrue();
        assertThat(holder[0].name()).isEqualTo("remember");
        assertThat(holder[0].whenToUse()).as("CC remember.ts:68 whenToUse").isNotBlank();
        assertThat(holder[0].isEnabled()).as("CC remember.ts:71 isEnabled: () => isAutoMemoryEnabled()").isNotNull();
        assertThat(holder[0].isEnabled().getAsBoolean()).isTrue();
    }

    @Test
    @DisplayName("ClaudeApi register() userInvocable=true 显式透传 + allowedTools 4 工具（CC claudeApi.ts:187-188）")
    void claudeApiRegisterCarriesUserInvocable() {
        ClaudeApiSkillRegistrar.SkillContent content = new ClaudeApiSkillRegistrar.SkillContent() {
            public Map<String, String> SKILL_FILES() { return Map.of(); }
            public String SKILL_PROMPT() { return "# Claude API"; }
            public Map<String, String> SKILL_MODEL_VARS() { return Map.of(); }
        };
        BundledSkillDefinition def = new ClaudeApiSkillRegistrar(List::of, () -> content).register();

        assertThat(def.name()).isEqualTo("claude-api");
        assertThat(def.userInvocable())
            .as("CC claudeApi.ts:188 userInvocable 显式 true（不再靠 Command 构造默认兜底）")
            .isTrue();
        assertThat(def.allowedTools()).containsExactly("Read", "Grep", "Glob", "WebFetch");
    }

    @Test
    @DisplayName("Verify register() files=SKILL_FILES 非空（保 P1-3 解压端到端，CC verify.ts:21）")
    void verifyRegisterCarriesFiles() {
        BundledSkillDefinition[] holder = new BundledSkillDefinition[1];
        boolean registered = new VerifySkillRegistrar().register(
            def -> holder[0] = def, () -> true, "Verify description", "# Verify body",
            VerifySkillContent.SKILL_FILES);

        assertThat(registered).isTrue();
        assertThat(holder[0].name()).isEqualTo("verify");
        assertThat(holder[0].files())
            .as("verify 注册必须携带 SKILL_FILES（CC verify.ts:21 files: SKILL_FILES），否则 P1-3 解压端到端不成立")
            .isNotEmpty();
    }

    @Test
    @DisplayName("verify prompt body 剥离 frontmatter（CC verify.ts:5 parseFrontmatter→SKILL_BODY；回归防 Bootstrapper 误传原始 SKILL_MD）")
    void verifySkillPromptBodyStripsFrontmatter() {
        // WHY: CC verify.ts:5 const {frontmatter, content: SKILL_BODY} = parseFrontmatter(SKILL_MD)
        // — SKILL_BODY 剥离 '---\ndescription:...\n---' 头，模型 prompt 不可见 '---'。上一轮
        // Bootstrapper:202 误传含 frontmatter 的原始 SKILL_MD，prompt 以 '---' 开头违反
        // VerifySkillRegistrar javadoc:49 契约；此测试锁定剥离行为，防止回归。
        String body = new ParseSkillFrontmatter().extractBody(VerifySkillContent.SKILL_MD);
        assertThat(body).as("CC verify.ts:5 SKILL_BODY 不得以 '---' 开头").doesNotStartWith("---");
        assertThat(body).as("frontmatter description 行不得残留").doesNotContain("description:");

        BundledSkillDefinition[] holder = new BundledSkillDefinition[1];
        new VerifySkillRegistrar().register(
            def -> holder[0] = def, () -> true, VerifySkillRegistrar.FALLBACK_DESCRIPTION,
            body, VerifySkillContent.SKILL_FILES);
        assertThat(holder[0]).as("ant 用户应注册 verify").isNotNull();
        List<PromptBlock> blocks = holder[0].getPromptForCommand().apply(
            "", PromptFnContext.of("", List.of(), null));
        assertThat(blocks).isNotEmpty();
        assertThat(blocks.get(0).text())
            .as("CC verify.ts:23 SKILL_BODY.trimStart() — 模型 prompt 首块不得以 '---' 开头")
            .doesNotStartWith("---");
    }

    @Test
    @DisplayName("Batch/Debug register() 返回统一类型且 disableModelInvocation 透传（CC batch.ts:109 / debug.ts:23）")
    void batchAndDebugReturnUnifiedDefinition() {
        BundledSkillDefinition batch = new BatchSkillRegistrar(() -> false).register();
        assertThat(batch.name()).isEqualTo("batch");
        assertThat(batch.userInvocable()).isTrue();
        assertThat(batch.disableModelInvocation()).as("CC batch.ts:109 disableModelInvocation: true").isTrue();

        BundledSkillDefinition debug = new DebugSkillRegistrar(
            () -> false, () -> "/tmp/debug.log", () -> true,
            (path, offset, size) -> "", path -> { throw new java.nio.file.NoSuchFileException(path); },
            Long::toString, source -> "/default/" + source + ".json").register();
        assertThat(debug.name()).isEqualTo("debug");
        assertThat(debug.allowedTools()).containsExactly("Read", "Grep", "Glob");
        assertThat(debug.disableModelInvocation()).as("CC debug.ts:23 disableModelInvocation: true").isTrue();
        assertThat(debug.userInvocable()).isTrue();
    }

    @Test
    @DisplayName("Skillify/Simplify/Stuck 经统一 Consumer 注册（ant 早返保留）")
    void skillifySimplifyStuckRegisterViaConsumer() {
        BundledSkillDefinition[] skillify = new BundledSkillDefinition[1];
        // [拍板#9 part2] 构造器签名升级：sessionMemoryResolver (sessionId→内容) + userMessagesExtractor (消息→文本)
        new SkillifySkillRegistrar(() -> "ant", sessionId -> "", messages -> List.of(),
            def -> skillify[0] = def).register();
        assertThat(skillify[0].name()).isEqualTo("skillify");
        assertThat(skillify[0].userInvocable()).isTrue();
        assertThat(skillify[0].allowedTools()).contains("AskUserQuestion", "Bash(mkdir:*)");

        BundledSkillDefinition[] simplify = new BundledSkillDefinition[1];
        new SimplifySkillRegistrar().register(def -> simplify[0] = def);
        assertThat(simplify[0].name()).isEqualTo("simplify");
        assertThat(simplify[0].userInvocable()).isTrue();
        assertThat(simplify[0].files()).isEmpty();

        BundledSkillDefinition[] stuck = new BundledSkillDefinition[1];
        boolean stuckRegistered = new StuckSkillRegistrar().register(def -> stuck[0] = def, true);
        assertThat(stuckRegistered).isTrue();
        assertThat(stuck[0].name()).isEqualTo("stuck");
        assertThat(stuck[0].userInvocable()).isTrue();
    }

    @Test
    @DisplayName("Keybindings/NexusaiInChrome 经统一 Consumer 注册且 CC 强制字段透传（keybindings.ts:297-298 / claudeInChrome.ts:23-24）")
    void keybindingsAndNexusaiInChromeRegisterViaConsumer() {
        BundledSkillDefinition[] keybindings = new BundledSkillDefinition[1];
        new KeybindingsSkill(def -> keybindings[0] = def)
            .registerSkill();
        assertThat(keybindings[0].name()).isEqualTo("keybindings-help");
        assertThat(keybindings[0].userInvocable()).as("CC keybindings.ts:298 userInvocable: false").isFalse();
        assertThat(keybindings[0].allowedTools()).as("CC keybindings.ts:297 allowedTools: ['Read']")
            .containsExactly("Read");

        BundledSkillDefinition[] chrome = new BundledSkillDefinition[1];
        new NexusaiInChromeSkill(() -> "", () -> false, def -> chrome[0] = def)
            .registerSkill(List.of("tabs_context_mcp"));
        assertThat(chrome[0].name()).isEqualTo("nexusai-in-chrome");
        assertThat(chrome[0].userInvocable()).as("CC claudeInChrome.ts:24 userInvocable: true").isTrue();
        assertThat(chrome[0].allowedTools()).containsExactly("mcp__nexusai-in-chrome__tabs_context_mcp");
    }

    @Test
    @DisplayName("统一 PromptBlock 替代 6 处嵌套重复（P1-4 createList）")
    void unifiedPromptBlockReplacesNestedDuplicates() {
        PromptBlock block = PromptBlock.text("hello");
        assertThat(block.type()).isEqualTo("text");
        assertThat(block.text()).isEqualTo("hello");

        // lorem-ipsum handleCommand 使用统一 PromptBlock
        LoremIpsumSkill lorem = new LoremIpsumSkill(() -> true);
        assertThat(lorem.handleCommand("100")).isNotEmpty()
            .allSatisfy(b -> assertThat(b.type()).isEqualTo("text"));
    }
}
