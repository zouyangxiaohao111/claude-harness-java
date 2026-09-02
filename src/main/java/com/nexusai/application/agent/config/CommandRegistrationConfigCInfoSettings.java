package com.nexusai.application.agent.config;

import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.SessionAgentStateRegistry;
import com.nexusai.application.agent.UserInputDispatcher;
import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.application.agent.api.Grove;
import com.nexusai.application.agent.command.ReleaseNotesCommand;
import com.nexusai.application.agent.security.SecurityReviewPrompt;
import com.nexusai.application.agent.skill.BundledSkillDefinition;
import com.nexusai.application.agent.skill.BundledSkills;
import com.nexusai.application.agent.skill.ParseSkillFrontmatter;
import com.nexusai.application.agent.skill.PromptBlock;
import com.nexusai.common.RequestContext;
import com.nexusai.model.command.Command;
import com.nexusai.model.command.PromptFnContext;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;

/**
 * 命令注册配置 · 组 C：信息/设置 命令接线（release-notes / security-review / privacy-settings / think-back）。
 *
 * <p><b>WHY（延续 {@link CommandRegistrationConfig} 的 GAP 探查）</b>：4 个命令在 Java 侧
 * 「已实现、未接线」或「无注册面」——{@link SecurityReviewPrompt}（security-review prompt 载体）无
 * 生产调用方；release-notes / privacy-settings / think-back 无任何 Java 类，按 CC 命令契约新建接线。
 *
 * <h2>接线通道（按 CC 命令类型）</h2>
 * <ul>
 *   <li><b>prompt 型</b>（CC security-review.ts type='prompt'，createMovedToPluginCommand.ts:31）→ 包装成
 *       {@link BundledSkillDefinition} 注册进 {@link BundledSkills}——经 SkillRegistry.getAllCommands
 *       合并进 web GET /api/command + getModelInvocableCommands（loadedFrom=BUNDLED 放行）+ 模型经
 *       SkillTool 调用时生成 prompt（用户 /name 有 prompt）。对齐 CC commands.ts:568-579 过滤语义。</li>
 *   <li><b>local / local-jsx 型</b>（release-notes type='local'；privacy-settings/think-back type='local-jsx'）→
 *       ① 元数据 Command（type='local'/'local-jsx'）注册进 {@link BundledSkills}（对齐 CC getCommands
 *       合并清单），② {@link UserInputDispatcher#registerSlashCommand} 注册执行 handler（前端 /name →
 *       后端直接执行）。</li>
 * </ul>
 *
 * <p><b>isEnabled 门控（对齐 CC types/command.ts:214-215 isEnabled?.() ?? true）</b>：
 * privacy-settings（isConsumerSubscriber，auth.ts:1846-1851——web 无 claude.ai 订阅模型 → 默认 false，
 * env/system 可开）/ think-back（statsig 'tengu_thinkback'，thinkback/index.ts:8-10——Java 无 statsig →
 * 默认 false，env/system 可开）。security-review / release-notes 无 gate 默认 true。
 *
 * <p><b>受控差异（fail loud 登记）</b>：
 * <ul>
 *   <li>security-review：CC executeShellCommandsInPrompt 把 {@code !`cmd`} 替换为 git 输出
 *       （security-review.ts:215-234）——Java 端 shell 执行未接线，prompt 保留字面 git 命令占位
 *       （模型可经自身 Bash 工具执行，allowedTools 已注入 Bash(git ...)，同 CommandRegistrationConfig
 *       commit 受控差异）。frontmatter 经 {@link ParseSkillFrontmatter#parseFrontmatterStatic} 剥离
 *       （CC parseFrontmatter）。</li>
 *   <li>release-notes：CC 500ms 拉取 GitHub changelog + 本地缓存（release-notes.ts:23-32）——Java web 不
 *       主动外网拉取（隐私红线），改读本地项目 CHANGELOG.md；同回落链（无 → changelog 链接）。</li>
 *   <li>privacy-settings：CC Grove 面板（privacy-settings.tsx PrivacySettingsDialog）——Java web 无
 *       React 渲染，handler 读 {@link Grove} grove_enabled 状态披露；FALLBACK_MESSAGE 对齐
 *       privacy-settings.tsx:6。门控 isConsumerSubscriber 默认关（web 无订阅模型）。</li>
 *   <li>think-back：CC thinkback.tsx Year-in-Review 大面板（61KB JSX）——Java web 无 React 渲染，handler
 *       回放会话 {@link AgentState#messages()} 中 assistant 消息的 thinking 块（{@link ChatMessageDto#reasoning()}）。
 *       门控 statsig tengu_thinkback 默认关（Java 无 statsig）。</li>
 * </ul>
 */
@Configuration
public class CommandRegistrationConfigCInfoSettings {

    private static final Logger log = LoggerFactory.getLogger(CommandRegistrationConfigCInfoSettings.class);

    /** CC original: FALLBACK_MESSAGE（privacy-settings.tsx:6）。 */
    private static final String PRIVACY_FALLBACK_MESSAGE =
        "Review and manage your privacy settings at https://claude.ai/settings/data-privacy-controls";

    // ════════════════════════════════════════════════════════════════════════
    // 1. Bundled 命令注册（prompt + local/local-jsx 元数据）· 对齐 CC getCommands 合并清单
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 副作用 @Bean：注册 1 个 prompt 命令 + 3 个 local/local-jsx 命令元数据进 {@link BundledSkills}。
     *
     * <p>返回标记 record 而非 void：Spring 不注册 void 返回值 bean，标记 record 使注册副作用
     * 在上下文刷新时必然执行（同 {@link CommandRegistrationConfig#commandBundledRegistration} 模式）。
     */
    @Bean
    public CommandBundledRegistrationC commandBundledRegistrationC() {
        registerSecurityReviewPrompt();   // CC commands/security-review.ts（prompt）
        registerReleaseNotesMetadata();   // CC commands/release-notes/index.ts（local）
        registerPrivacySettingsMetadata();// CC commands/privacy-settings/index.ts（local-jsx，isConsumerSubscriber 门控）
        registerThinkBackMetadata();      // CC commands/thinkback/index.ts（local-jsx，tengu_thinkback 门控）
        if (log.isDebugEnabled()) {
            log.debug("[CommandRegistrationConfigCInfoSettings] bundled 命令注册完成：prompt 1 + local 元数据 3（BundledSkills 现 {} 条）",
                BundledSkills.count());
        }
        return new CommandBundledRegistrationC();
    }

    /** 副作用 bean 标记 record · 使 {@link #commandBundledRegistrationC} 的注册副作用在 context refresh 时执行。 */
    public record CommandBundledRegistrationC() {}

    /**
     * /security-review · CC commands/security-review.ts:198-243 + createMovedToPluginCommand.ts
     * （type='prompt'，:31）。description / progressMessage 对齐 :200-202。
     *
     * <p>promptFn → {@link SecurityReviewPrompt#getPrompt()} 经
     * {@link ParseSkillFrontmatter#parseFrontmatterStatic} 剥离 frontmatter（CC :207 parseFrontmatter →
     * content）；allowedTools = 10 项（git 5 + 只读 5）。
     * shell 执行（executeShellCommandsInPrompt）未接线 → 保留字面 git 命令占位（受控差异）。
     */
    private void registerSecurityReviewPrompt() {
        SecurityReviewPrompt srp = new SecurityReviewPrompt();
        String body = ParseSkillFrontmatter.parseFrontmatterStatic(srp.getPrompt(), null).content();
        registerPromptSkillC("security-review",
            "Complete a security review of the pending changes on the current branch",
            SecurityReviewPrompt.ALLOWED_TOOLS, "analyzing code changes for security risks",
            (args, ctx) -> List.of(PromptBlock.text(body)));
    }

    /** /release-notes · CC commands/release-notes/index.ts:3-9（type='local'，无 isEnabled gate）。 */
    private void registerReleaseNotesMetadata() {
        registerLocalMetadataC("release-notes", "local", "View release notes", null, null, false, false, null);
    }

    /** /privacy-settings · CC commands/privacy-settings/index.ts:4-14（type='local-jsx'，isEnabled=isConsumerSubscriber）。 */
    private void registerPrivacySettingsMetadata() {
        registerLocalMetadataC("privacy-settings", "local-jsx", "View and update your privacy settings", null,
            CommandRegistrationConfigCInfoSettings::isConsumerSubscriber, false, false, null);
    }

    /**
     * /think-back · CC commands/thinkback/index.ts:4-11（type='local-jsx'，name='think-back'，
     * isEnabled=checkStatsigFeatureGate_CACHED_MAY_BE_STALE('tengu_thinkback')）。
     */
    private void registerThinkBackMetadata() {
        registerLocalMetadataC("think-back", "local-jsx", "Your 2025 Claude Code Year in Review", null,
            CommandRegistrationConfigCInfoSettings::thinkBackFeatureEnabled, false, false, null);
    }

    /** prompt 命令统一注册 · BundledSkillDefinition → BundledSkills（source=BUNDLED + loadedFrom=BUNDLED）。 */
    private void registerPromptSkillC(String name, String description, List<String> allowedTools,
                                      String progressMessage,
                                      BiFunction<String, PromptFnContext, List<PromptBlock>> promptFn) {
        BundledSkillDefinition def = new BundledSkillDefinition(
            name, description,
            null,        // aliases
            null,        // whenToUse
            null,        // argumentHint
            allowedTools,
            null,        // model
            null,        // disableModelInvocation
            true,        // userInvocable
            null,        // isEnabled（无 gate → 恒启用）
            null,        // hooks
            null,        // context
            null,        // agent
            null,        // files
            promptFn);
        Command command = def.toCommand();
        if (progressMessage != null) {
            command.setProgressMessage(progressMessage);
        }
        BundledSkills.register(command);
        log.info("[CommandRegistrationConfigCInfoSettings] registered prompt command '{}'（对齐 CC commands/security-review.ts）",
            name);
    }

    /** local/local-jsx 命令元数据统一注册 · Command(type) → BundledSkills（source=BUNDLED + loadedFrom=BUNDLED）。 */
    private void registerLocalMetadataC(String name, String type, String description,
                                        String argumentHint, BooleanSupplier isEnabled,
                                        boolean isHidden, boolean immediate, List<String> aliases) {
        Command command = new Command();
        command.setName(name);
        command.setType(type);
        command.setDescription(description);
        if (argumentHint != null) {
            command.setArgumentHint(argumentHint);
        }
        command.setIsEnabled(isEnabled);
        command.setIsHidden(isHidden);
        command.setImmediate(immediate);
        if (aliases != null) {
            command.setAliases(aliases);
        }
        BundledSkills.register(command);
        log.info("[CommandRegistrationConfigCInfoSettings] registered {} command metadata '{}' (type={}, enabled={}, hidden={})",
            type, name, type, isEnabled != null ? isEnabled.getAsBoolean() : true, isHidden);
    }

    // ════════════════════════════════════════════════════════════════════════
    // 2. Local 命令执行 handler 注册（UserInputDispatcher registerSlashCommand）
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 副作用 @Bean：把 3 个 local/local-jsx 命令的<b>执行 handler</b> 注册进 {@link UserInputDispatcher}。
     *
     * <p>前端 /name 输入 → UserInputDispatcher.dispatch → 命名 handler 执行（同 CommandRegistrationConfig
     * advisor/cost 模式）。SessionAgentStateRegistry 为 @Component bean；plain JUnit 缺省 null → handler
     * 内空安全回退。
     */
    @Bean
    public CommandLocalSlashRegistrationC commandLocalSlashRegistrationC(
            UserInputDispatcher dispatcher,
            @Autowired(required = false) SessionAgentStateRegistry sessionAgentStateRegistry) {
        if (dispatcher == null) {
            log.warn("[CommandRegistrationConfigCInfoSettings] UserInputDispatcher 未注入，local 命令执行 handler 注册跳过");
            return new CommandLocalSlashRegistrationC();
        }
        registerReleaseNotesHandler(dispatcher);
        registerPrivacySettingsHandler(dispatcher);
        registerThinkBackHandler(dispatcher, sessionAgentStateRegistry);
        if (log.isDebugEnabled()) {
            log.debug("[CommandRegistrationConfigCInfoSettings] local 命令执行 handler 注册完成：release-notes/privacy-settings/think-back");
        }
        return new CommandLocalSlashRegistrationC();
    }

    /** 副作用 bean 标记 record · 使 {@link #commandLocalSlashRegistrationC} 的注册副作用在 context refresh 时执行。 */
    public record CommandLocalSlashRegistrationC() {}

    /**
     * /release-notes handler · CC commands/release-notes/release-notes.ts:19-50 call。
     *
     * <p>{@link ReleaseNotesCommand#call(String)} 读会话 cwd（CC getCwd()）下 CHANGELOG.md → 解析格式化；
     * 缺失 → 回落 changelog 链接。受控差异：CC 外网拉取改为本地文件读（见类 JavaDoc）。
     */
    private void registerReleaseNotesHandler(UserInputDispatcher dispatcher) {
        dispatcher.registerSlashCommandResult("release-notes", args -> {
            String cwd = CwdResolution.getCwd(RequestContext.sessionId());
            if (cwd == null || cwd.isBlank()) {
                cwd = System.getProperty("user.dir", ".");
            }
            String changelogPath = Path.of(cwd, "CHANGELOG.md").toString();
            ReleaseNotesCommand.CommandResult result = new ReleaseNotesCommand().call(changelogPath);
            log.info("[CommandRegistrationConfigCInfoSettings] /release-notes 执行完成:\n{}", result.value());
            return UserInputDispatcher.LocalCommandResult.text(result.value());
        });
        log.info("[CommandRegistrationConfigCInfoSettings] /release-notes 已注册为生产 slash command result handler（对齐 CC commands/release-notes/index.ts + release-notes.ts call，text 结果回传 <local-command-stdout>）");
    }

    /**
     * /privacy-settings handler · CC commands/privacy-settings/privacy-settings.tsx:7-57 call。
     *
     * <p>门控 isConsumerSubscriber（auth.ts:1846-1851，web 无 claude.ai 订阅 → 默认 false，env 可开）。
     * 开启后读 {@link Grove}：isQualifiedForGrove → getGroveSettings 披露 grove_enabled（"Help improve
     * Claude" 状态）；未合格/API 失败 → FALLBACK_MESSAGE（privacy-settings.tsx:6）。Java web 无
     * PrivacySettingsDialog React 渲染 → 状态披露（受控差异）。
     */
    private void registerPrivacySettingsHandler(UserInputDispatcher dispatcher) {
        dispatcher.registerSlashCommand("privacy-settings", args -> {
            if (!isConsumerSubscriber()) {
                log.warn("[CommandRegistrationConfigCInfoSettings] /privacy-settings 被 isConsumerSubscriber 门控关闭"
                    + "（web 无 claude.ai 订阅模型，默认 false；对齐 CC privacy-settings/index.ts:8-10）");
                return;
            }
            Grove grove = new Grove();
            if (!grove.isQualifiedForGrove()) {
                log.info("[CommandRegistrationConfigCInfoSettings] /privacy-settings → {}（未合格 Grove，对齐 privacy-settings.tsx:8-11）",
                    PRIVACY_FALLBACK_MESSAGE);
                return;
            }
            Grove.ApiResult<Grove.AccountSettings> settings = grove.getGroveSettings();
            if (settings instanceof Grove.ApiSuccess<Grove.AccountSettings> success) {
                Boolean enabled = success.data().groveEnabled();
                log.info("[CommandRegistrationConfigCInfoSettings] /privacy-settings 执行完成: \"Help improve Claude\" set to {}（对齐 privacy-settings.tsx:39-40）",
                    Boolean.TRUE.equals(enabled) ? "true" : "false");
            } else {
                log.info("[CommandRegistrationConfigCInfoSettings] /privacy-settings → {}（Grove settings API 不可用，对齐 privacy-settings.tsx:14-18）",
                    PRIVACY_FALLBACK_MESSAGE);
            }
        });
        log.info("[CommandRegistrationConfigCInfoSettings] /privacy-settings 已注册为生产 slash command（对齐 CC commands/privacy-settings/index.ts + privacy-settings.tsx call）");
    }

    /**
     * /think-back handler · CC commands/thinkback/thinkback.tsx call（local-jsx）。
     *
     * <p>门控 statsig 'tengu_thinkback'（thinkback/index.ts:8-10，Java 无 statsig → 默认 false，env 可开）。
     * 开启后回放会话 {@link AgentState#messages()} 中 assistant 消息的 thinking 块
     * （{@link ChatMessageDto#reasoning()}，CC thinkback.tsx 读取历史 thinking 的 Java 投影——受控差异：
     * web 无 Year-in-Review React 面板，改为思考块文本回放）。
     */
    private void registerThinkBackHandler(UserInputDispatcher dispatcher, SessionAgentStateRegistry registry) {
        dispatcher.registerSlashCommand("think-back", args -> {
            if (!thinkBackFeatureEnabled()) {
                log.warn("[CommandRegistrationConfigCInfoSettings] /think-back 被 tengu_thinkback 门控关闭"
                    + "（Java 无 statsig，默认 false；对齐 CC thinkback/index.ts:8-10）");
                return;
            }
            String sessionId = RequestContext.sessionId();
            if (registry == null || sessionId == null || sessionId.isBlank()) {
                log.warn("[CommandRegistrationConfigCInfoSettings] /think-back 无会话上下文或 SessionAgentStateRegistry 未注入");
                return;
            }
            AgentState state = registry.get(sessionId);
            if (state == null) {
                log.warn("[CommandRegistrationConfigCInfoSettings] /think-back 会话 {} 无 AgentState（无进行中循环）", sessionId);
                return;
            }
            List<String> thinkingBlocks = state.messages().stream()
                .filter(m -> m.role() == Role.assistant)
                .filter(m -> m.reasoning() != null && !m.reasoning().isBlank())
                .map(ChatMessageDto::reasoning)
                .toList();
            if (thinkingBlocks.isEmpty()) {
                log.info("[CommandRegistrationConfigCInfoSettings] /think-back 会话 {} 无 thinking 块可回放", sessionId);
                return;
            }
            log.info("[CommandRegistrationConfigCInfoSettings] /think-back 思考块回放完成: 共 {} 个 thinking 块", thinkingBlocks.size());
            for (int i = 0; i < thinkingBlocks.size(); i++) {
                log.info("[CommandRegistrationConfigCInfoSettings] Thinking #{}/{}:\n{}",
                    i + 1, thinkingBlocks.size(), thinkingBlocks.get(i));
            }
        });
        log.info("[CommandRegistrationConfigCInfoSettings] /think-back 已注册为生产 slash command（对齐 CC commands/thinkback/index.ts + thinkback.tsx call）");
    }

    // ════════════════════════════════════════════════════════════════════════
    // 生产 env 工具方法
    // ════════════════════════════════════════════════════════════════════════

    /**
     * isConsumerSubscriber 门控 · CC original: isConsumerSubscriber（auth.ts:1846-1851，
     * isClaudeAISubscriber() && subscriptionType != null && isConsumerPlan()）。Java web 无 claude.ai
     * 订阅模型（同 CommandRegistrationConfig /cost isClaudeAISubscriber=false）→ 默认 false；
     * env/system 属性可开（NEXUSAI_FEATURE_PRIVACY_SETTINGS / nexusai.feature.privacy-settings，truthy）。
     */
    private static boolean isConsumerSubscriber() {
        return featureEnabled("nexusai.feature.privacy-settings", "NEXUSAI_FEATURE_PRIVACY_SETTINGS");
    }

    /**
     * think-back 门控 · CC original: checkStatsigFeatureGate_CACHED_MAY_BE_STALE('tengu_thinkback')
     * （thinkback/index.ts:8-10）。Java 无 statsig → 默认 false；env/system 属性可开
     * （NEXUSAI_FEATURE_THINK_BACK / nexusai.feature.think-back，truthy）。
     */
    private static boolean thinkBackFeatureEnabled() {
        return featureEnabled("nexusai.feature.think-back", "NEXUSAI_FEATURE_THINK_BACK");
    }

    /** 特性开关统一判定 · system property 优先，env 兜底，缺省 false（同 CommandRegistrationConfig ultrareviewFeature 模式）。 */
    private static boolean featureEnabled(String sysPropKey, String envKey) {
        String sysProp = System.getProperty(sysPropKey);
        if (sysProp != null && !sysProp.isBlank()) {
            return Boolean.parseBoolean(sysProp);
        }
        String envVal = System.getenv(envKey);
        if (envVal != null && !envVal.isBlank()) {
            return Boolean.parseBoolean(envVal);
        }
        return false;
    }
}
