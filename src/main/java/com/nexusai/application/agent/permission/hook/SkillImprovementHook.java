package com.nexusai.application.agent.permission.hook;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.QuerySource;
import com.nexusai.application.agent.SessionAgentStateRegistry;
import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.application.agent.hook.PostSamplingContext;
import com.nexusai.application.agent.hook.PostSamplingHookRegistry;
import com.nexusai.application.agent.skill.MarkdownConfigLoader;
import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.application.agent.skill.SkillRegistry;
import com.nexusai.application.agent.skill.SkillsLoader;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.telemetry.Telemetry;
import com.nexusai.common.RequestContext;
import com.nexusai.eventbus.ws.SkillImprovementSuggestionEvent;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ModelConfigResolver;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.model.command.Command;
import com.nexusai.repository.settings.entity.SettingsRecord;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Skill Improvement Hook · 对齐 CC {@code Open-ClaudeCode/src/utils/hooks/skillImprovement.ts} (267 行).
 *
 * <p><b>[Session H12] 语义纠正</b>: 旧实现是内存 {@code ConcurrentHashMap} 增删查 (suggest/apply/get),
 * 与 CC <b>LLM 检测器</b> 语义完全错位. 现对齐 CC 真源:
 * <ul>
 *   <li><b>TURN_BATCH_SIZE=5 节流</b> (skillImprovement.ts:31): 每 ≥5 条 user 消息才跑一次分类器</li>
 *   <li><b>shouldRun 门控</b> (L75-92): querySource===repl_main_thread + project skill 存在 +
 *       距上次分析 ≥5 条 user 消息</li>
 *   <li><b>buildMessages</b> (L94-127): skill_definition + recent_messages 构造分类器 prompt,
 *       要求输出 &lt;updates&gt; JSON</li>
 *   <li><b>parseResponse</b> (L134-144): extractTag(content,'updates') + jsonParse</li>
 *   <li><b>logResult</b> (L146-167): success 且 updates&gt;0 → tengu_skill_improvement_detected
 *       telemetry + setAppState 写 skillImprovement.suggestion</li>
 *   <li><b>getModel</b> (L169): getSmallFastModel 惰性加载</li>
 *   <li><b>applySkillImprovement</b> (L188-267): 读 .nexusai/skills/&lt;name&gt;/SKILL.md（nexusai
 *       项目级技能目录，决策 D1/D6）→ 侧信道 LLM 重写 → 提取 &lt;updated_file&gt; → 写回</li>
 * </ul>
 *
 * <p><b>Java idiom 适配</b>:
 * <ul>
 *   <li>CC {@code getInvokedSkillsForAgent(null)} 找 project skill → Java 用 {@link ProjectSkillProvider}
 *       函数式接口注入 [IMP-HOOKS-S8 CCJ-HOOKS-T8-04]: 测试可打桩; 生产接 invoked 语义
 *       findProjectSkill (SessionAgentStateRegistry + 项目级 baseDir 判定, 每次调用求值)</li>
 *   <li>CC {@code queryModelWithoutStreaming} 全局函数 → Java 用
 *       {@link SkillImprovementModelQuery} 注入 (systemPrompt, prompt, options → 响应文本;
 *       生产用 LlmProvider.chatWithOptions; systemPrompt 每次调用显式传入, options 携带 CC 查询
 *       选项 thinkingConfig disabled / temperature 0 / querySource / abort signal —
 *       对齐 skillImprovement.ts:236-249, 检测器与 applier 各用各的)</li>
 *   <li>CC {@code logEvent} 全局函数 → Java {@link Telemetry#recordEvent}</li>
 * </ul>
 *
 * @see ApiQueryHookHelper
 * @see PostSamplingHookRegistry
 * @since Session H12
 */
@Component
public class SkillImprovementHook {

    private static final Logger log = LoggerFactory.getLogger(SkillImprovementHook.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** CC original: TURN_BATCH_SIZE (skillImprovement.ts:31) — 每 N 条 user 消息分析一次. */
    public static final int TURN_BATCH_SIZE = 5;

    /** CC original: systemPrompt (skillImprovement.ts:129-130) — 检测器分类器 prompt. */
    static final String SYSTEM_PROMPT =
            "You detect user preferences and process improvements during skill execution. "
                    + "Flag anything the user asks for that should be remembered for next time.";

    /**
     * CC original: applySkillImprovement systemPrompt (skillImprovement.ts:233-235) —
     * applier 侧信道 LLM 改写 skill 文件时收到的 <b>applier 专属</b> 系统提示词
     * (区别于检测器 SYSTEM_PROMPT; CC SystemPrompt.from 单元素数组即发送该字符串, :234 原文).
     */
    static final String APPLIER_SYSTEM_PROMPT =
            "You edit skill definition files to incorporate user preferences. "
                    + "Output only the updated file content.";

    /**
     * SkillUpdate · CC original: {@code SkillUpdate = { section, change, reason }} (skillImprovement.ts:33-37).
     *
     * @param section CC original: section — 要修改的 step/section 或 'new step'
     * @param change  CC original: change — 要添加/修改的内容
     * @param reason  CC original: reason — 哪条 user 消息触发的
     */
    public record SkillUpdate(String section, String change, String reason) {}

    /**
     * ProjectSkill · Java 等价 CC {@code getInvokedSkillsForAgent(null)} 中
     * {@code skillPath.startsWith('projectSettings:')} 的 skill (skillImprovement.ts:58-66).
     *
     * @param skillName CC original: skillName
     * @param content   CC original: content — skill 定义正文
     */
    public record ProjectSkill(String skillName, String content) {}

    /**
     * ProjectSkill 提供器 · Java 等价 CC {@code getInvokedSkillsForAgent(null)}
     * (skillImprovement.ts:58-66 / state.ts:1530-1541).
     *
     * <p>[IMP-HOOKS-S8 CCJ-HOOKS-T8-04] 签名带 {@link ApiQueryHookHelper.ApiQueryHookContext}:
     * CC findProjectSkill 每次调用动态求值 (无参数闭包, 读进程内全局 STATE) — Java 端
     * invokedSkills 按会话分散 (SessionAgentStateRegistry), 需 ctx 携带 sessionId/cwd 定位
     * 会话主 AgentState 与项目目录; 测试打桩可忽略 ctx.
     */
    @FunctionalInterface
    public interface ProjectSkillProvider {
        Optional<ProjectSkill> find(ApiQueryHookHelper.ApiQueryHookContext ctx);
    }

    private final Telemetry telemetry;
    /**
     * 侧信道 LLM 查询 (systemPrompt, prompt, options) → 响应文本 · CC original:
     * {@code queryModelWithoutStreaming} (skillImprovement.ts:233 / claude.ts:709-723) —
     * systemPrompt 每次调用显式传入, 检测器 (SYSTEM_PROMPT) 与 applier (APPLIER_SYSTEM_PROMPT)
     * 各用各的; options 携带 CC 查询选项 (thinkingConfig disabled / temperature 0 /
     * querySource / abort signal, skillImprovement.ts:236-249).
     */
    private final SkillImprovementModelQuery modelQuery;

    /**
     * 侧信道 LLM 查询执行器 · Java 等价 CC {@code queryModelWithoutStreaming}
     * (claude.ts:709-723, 签名含 messages/systemPrompt/thinkingConfig/tools/signal/options).
     *
     * <p>P2-16 升级: 旧 {@code BiFunction<String,String,String>} 2 参 (systemPrompt, prompt)
     * 无法表达 CC 的查询选项 — 现 3 参, options 对齐 skillImprovement.ts:236-249 的
     * thinkingConfig / temperatureOverride / querySource / signal.
     */
    @FunctionalInterface
    public interface SkillImprovementModelQuery {
        /**
         * @param systemPrompt 系统提示词 (检测器 SYSTEM_PROMPT / applier APPLIER_SYSTEM_PROMPT)
         * @param userPrompt   用户消息内容
         * @param options      CC 查询选项 (thinkingConfig/temperature/querySource/abortController)
         * @return 模型响应文本
         */
        String query(String systemPrompt, String userPrompt, LlmProvider.ChatRequestOptions options);
    }
    private final ProjectSkillProvider projectSkillProvider;
    private final BiConsumer<String, List<SkillUpdate>> appStateWriter;
    /**
     * [IMP-HOOKS-S8 CCJ-HOOKS-T8-05] SKILL.md 路径基准 · CC original: {@code getCwd()}
     * (skillImprovement.ts:198 + cwd.ts:19-32, 每次 apply 调用动态求值, 含 cwdOverrideStorage
     * 异步本地覆盖) — Java 用 {@link Supplier}<Path> 调用时求值, 替代旧构造期冻结字段
     * {@code Path.of("").toAbsolutePath()} (所有会话/调用共用同一基准, 语义漂移).
     */
    private final Supplier<Path> baseDirSupplier;
    /** [P1-13] session-keyed suggestion store · CC original: AppState.skillImprovement.suggestion
     *  (skillImprovement.ts:160-165) — 检测器写入, REST 决策端点消费, 可空 (未接线时跳过 store 写). */
    private final SkillImprovementSuggestionStore suggestionStore;
    /**
     * [P3-6] 注册门控 · CC original: {@code feature('SKILL_IMPROVEMENT') && getFeatureValue_CACHED_MAY_BE_STALE('tengu_copper_panda', false)}
     * (skillImprovement.ts:176-179) — 双门控皆真才 registerPostSamplingHook; Java 折叠为单配置项
     * {@code nexusai.skill.improvement-enabled} 默认 false → 检测器默认不注册 (对齐 CC 生产 bundle 双门控 DCE).
     */
    private final boolean improvementEnabled;

    /**
     * [IMP-WF6-DC-01] STOMP 推送模板 · 检测器生成 suggestion 时经 WebSocket/STOMP 推"建议事件"
     * （用户拍板：前端自动弹 skill improvement survey）。字段注入 + required=false：
     * 生产 Spring 注入；测试直接构造（new SkillImprovementHook(...)）→ null → 跳过推送。
     */
    @Autowired(required = false)
    private SimpMessagingTemplate wsTemplate;

    /**
     * Spring 构造 · 生产 wiring: LlmProvider 侧信道查询 + Telemetry + 真实 project skill.
     *
     * <p><b>[Session H12 v2 Gap3 修复]</b>: 旧构造 {@code providerFactory.getProvider(null)}
     * 恒走 mock LLM + {@code Optional::empty} project skill (shouldRun 恒 false) + no-op
     * appStateWriter (suggestion 丢弃) — 整个 LLM 检测器生产运行时死代码. 现修复:
     * <ul>
     *   <li><b>真实 provider</b>: 经 {@link ModelConfigResolver} 按 {@code getSmallFastModel()}
     *       模型名精确匹配 (DEC-RV-19, 对齐 CC skillImprovement.ts:241 model: getSmallFastModel()
     *       → 全局 Anthropic client), 无则显式 warn + skip (不落 mock, 对齐
     *       ModelConfigResolver 契约 warn+skip + CC 无 mock)</li>
     *   <li><b>真实 project skill</b> [IMP-HOOKS-S8 CCJ-HOOKS-T8-04]: 对齐 CC
     *       skillImprovement.ts:58-66 — {@code getInvokedSkillsForAgent(null)} 中
     *       {@code skillPath.startsWith('projectSettings:')} 的 skill. Java 端 invokedSkills
     *       按会话存于 {@link AgentState} (SessionAgentStateRegistry), 项目级判定用
     *       {@code Command.getBaseDir()} ∈ {@link MarkdownConfigLoader#getProjectDirsUpToHome}
     *       ∪ {@code <additionalDir>/.claude/skills}（[P2-18] {@link SkillRegistry#getAdditionalDirectories}）
     *       近似 CC projectSettings 源标签 (Java SkillsLoader 全折叠为 USER, 前缀判定不可用);
     *       content 用 invoked 记录的渲染后全文 (CC processSlashCommand.tsx:884). 每次调用
     *       新鲜求值, 删除旧 memoize/配置名/USER 回退路径.</li>
     *   <li><b>appStateWriter</b>: {@link #logResult} 现优先走 {@code ctx.toolUseContext().setAppState}
     *       (CC skillImprovement.ts:160-165), 此注入 writer 仅作 ctx 为 null 时回退 (测试路径)</li>
     * </ul>
     *
     * @param providerFactory   LLM provider 工厂 (真实/ mock 分发)
     * @param telemetry         遥测上报
     * @param modelConfigResolver modelName → (config, providerType) 解析 (可 null → warn+skip 不落 mock)
     * @param skillRegistry     invoked skill 名 → Command 解析 (可 null → 恒 empty, 诚实降级)
     * @param sessionAgentStateRegistry 会话主 AgentState 注册表 (可 null → 恒 empty, 诚实降级)
     * @param suggestionStore   [P1-13] session-keyed suggestion store (可 null → 跳过 store 写)
     * @param improvementEnabled [P3-6] 注册门控 · CC original: {@code feature('SKILL_IMPROVEMENT') && getFeatureValue_CACHED_MAY_BE_STALE('tengu_copper_panda', false)}
     *                           (skillImprovement.ts:176-179) — 默认 false 不注册检测器; true 才注册
     */
    @Autowired
    public SkillImprovementHook(
            LlmProviderFactory providerFactory,
            Telemetry telemetry,
            @Autowired(required = false) ModelConfigResolver modelConfigResolver,
            @Autowired(required = false) SkillRegistry skillRegistry,
            @Autowired(required = false) SessionAgentStateRegistry sessionAgentStateRegistry,
            @Autowired(required = false) SkillImprovementSuggestionStore suggestionStore,
            @Value("${nexusai.skill.improvement-enabled:false}") boolean improvementEnabled) {
        this(
                buildModelQuery(providerFactory, modelConfigResolver),
                ctx -> findProjectSkill(skillRegistry, sessionAgentStateRegistry, ctx),
                telemetry,
                (skillName, updates) -> {},
                // baseDirSupplier 调用时求值（对齐 CC skillImprovement.ts:198 getCwd()，cwd.ts:19-32）：
                //   经 CwdResolution.getCwd(RequestContext.sessionId()) 取会话当前 cwd（含 override ??
                //   sessionCwd ?? boundProject ?? user.dir 兜底链），替代旧 Path.of("") 冻结 JVM user.dir
                //   （所有会话共用同一基准的语义漂移）。
                () -> Path.of(CwdResolution.getCwd(RequestContext.sessionId())),
                suggestionStore,
                improvementEnabled);
    }

    /**
     * 测试/注入构造 · 对齐 CC 的三条外部依赖 (skill 提供器 / LLM 查询 / telemetry).
     * 5 参签名保持 (store 可空 — 既有 SkillImprovementHookTest 免迁移).
     * [P3-6] 测试构造默认 {@code improvementEnabled=false} (与生产默认一致, 避免门控默认值被测试构造静默掩盖).
     */
    public SkillImprovementHook(
            SkillImprovementModelQuery modelQuery,
            Supplier<Optional<ProjectSkill>> projectSkillProvider,
            Telemetry telemetry,
            BiConsumer<String, List<SkillUpdate>> appStateWriter,
            Path baseDir) {
        this(modelQuery, projectSkillProvider, telemetry, appStateWriter, baseDir, null, false);
    }

    /**
     * 全参注入构造 · 7 参: 5 参 + suggestion store (P1-13 apply 半环接线) + improvementEnabled (P3-6 注册门控).
     *
     * @param suggestionStore session-keyed suggestion store; 可 null → writeSuggestion 跳过 store 写
     * @param improvementEnabled [P3-6] 注册门控 · CC original: {@code feature('SKILL_IMPROVEMENT') && getFeatureValue_CACHED_MAY_BE_STALE('tengu_copper_panda', false)}
     *                           (skillImprovement.ts:176-179) — 仅 true 时 initSkillImprovement 注册检测器
     */
    public SkillImprovementHook(
            SkillImprovementModelQuery modelQuery,
            Supplier<Optional<ProjectSkill>> projectSkillProvider,
            Telemetry telemetry,
            BiConsumer<String, List<SkillUpdate>> appStateWriter,
            Path baseDir,
            SkillImprovementSuggestionStore suggestionStore,
            boolean improvementEnabled) {
        this(
                modelQuery,
                ctx -> projectSkillProvider.get(),
                telemetry,
                appStateWriter,
                () -> baseDir,
                suggestionStore,
                improvementEnabled);
    }

    /**
     * 全参注入构造 (package-private) · 生产/测试共用底座: ctx 感知 {@link ProjectSkillProvider} +
     * 调用时求值 {@link Supplier}<Path> baseDir.
     *
     * <p>[IMP-HOOKS-S8 CCJ-HOOKS-T8-04/05] 生产构造直传 {@code ctx -> findProjectSkill(...)}
     * (invoked + 项目级判定, 每次调用求值); 公开测试构造把 {@code Supplier<Optional<ProjectSkill>>}
     * 打桩包装为忽略 ctx 的 provider, Path baseDir 包装为调用时求值 supplier.
     */
    SkillImprovementHook(
            SkillImprovementModelQuery modelQuery,
            ProjectSkillProvider projectSkillProvider,
            Telemetry telemetry,
            BiConsumer<String, List<SkillUpdate>> appStateWriter,
            Supplier<Path> baseDirSupplier,
            SkillImprovementSuggestionStore suggestionStore,
            boolean improvementEnabled) {
        this.modelQuery = modelQuery;
        this.projectSkillProvider = projectSkillProvider;
        this.telemetry = telemetry;
        this.appStateWriter = appStateWriter;
        this.baseDirSupplier = baseDirSupplier;
        this.suggestionStore = suggestionStore;
        this.improvementEnabled = improvementEnabled;
    }

    /**
     * [IMP-WF6-DC-01] 测试 seam · 注入 STOMP 模板（生产经 {@code @Autowired(required=false)} 字段
     * 注入；测试构造后经本方法打桩，验证 suggestion 事件推送）。
     *
     * @param wsTemplate STOMP 模板（null = 跳过推送，与未注入等价）
     */
    void setWsTemplate(SimpMessagingTemplate wsTemplate) {
        this.wsTemplate = wsTemplate;
    }


    /**
     * createSkillImprovementHook · 对齐 CC skillImprovement.ts:68-173 — 一个 ApiQueryHook 配置,
     * 通过 {@link ApiQueryHookHelper#createApiQueryHook} 泛型工厂构造.
     *
     * @return 可直接 register 进 {@link PostSamplingHookRegistry} 的 hook
     */
    public PostSamplingHookRegistry.PostSamplingHook createSkillImprovementHook() {
        AtomicInteger lastAnalyzedCount = new AtomicInteger(0);
        AtomicInteger lastAnalyzedIndex = new AtomicInteger(0);
        ApiQueryHookHelper.ApiQueryHookConfig<List<SkillUpdate>> config =
                new ApiQueryHookHelper.ApiQueryHookConfig<>(
                        "skill_improvement",
                        ctx -> shouldRun(ctx, lastAnalyzedCount),
                        ctx -> buildMessages(ctx, lastAnalyzedIndex),
                        SYSTEM_PROMPT,
                        false,   // CC L132 useTools:false — 分类器不调工具
                        (content, ctx) -> parseResponse(content),
                        (result, ctx) -> logResult(result, ctx),
                        ctx -> getSmallFastModel());
        return ApiQueryHookHelper.createApiQueryHook(
                config,
                // 透传 ApiQueryHookHelper 解析出的 systemPrompt (检测器 = config.systemPrompt = SYSTEM_PROMPT,
                // 对齐 CC apiQueryHookHelper.ts:73-75; applier 不走此 executor, 直传 APPLIER_SYSTEM_PROMPT)
                // [CCJ-EXEC-03] 检测器直接用 helper 内建选项（helper 内按 CC :85-108 构建
                //   thinkingConfig disabled + temperature 0 + querySource=config.name + abortController
                //   + mcpTools [] + isNonInteractiveSession；旧 detectQueryOptions 已删除，语义等同）
                (systemPrompt, userMessage, model, useTools, options) ->
                        // [D P1-7] LlmRawResponse 4 参形态 (requestId 未知 → null)
                        new LlmProvider.LlmRawResponse(
                                modelQuery.query(systemPrompt, userMessage, options),
                                null, null, null));
    }

    /**
     * 生产注册入口 · 对齐 CC backgroundHousekeeping.ts:33 {@code initSkillImprovement()}
     * → {@code registerPostSamplingHook(createSkillImprovementHook())}.
     *
     * <p><b>[Session H12 v2 Gap2 修复]</b>: 此前 {@code createSkillImprovementHook} 从未
     * register 进 {@link PostSamplingHookRegistry} — LlmAgentLoop:3017 虽已调 executeAll,
     * 但 HOOKS 列表恒空 → 整个 skill improvement LLM 检测器生产运行时完全不执行.
     * 现 Spring bean 装配完成后注册一次 (对齐 CC 启动时 initSkillImprovement).
     *
     * <p><b>[P3-6] 注册门控</b> · CC original:
     * {@code if (feature('SKILL_IMPROVEMENT') && getFeatureValue_CACHED_MAY_BE_STALE('tengu_copper_panda', false)) { registerPostSamplingHook(createSkillImprovementHook()) }}
     * (skillImprovement.ts:176-179) — 双门控皆真才注册. Java 无 GrowthBook 等价, 折叠为单配置项
     * {@code nexusai.skill.improvement-enabled} 默认 false → 默认不注册检测器 (对齐 CC 生产 bundle
     * 双门控 DCE: feature 编译期 false + GrowthBook 默认 false); true 时启用注册 (远程 kill-switch 语义).
     * {@code applySkillImprovement} 为独立导出函数不受此门控影响 (CC skillImprovement.ts:188,
     * 生产 bundle 保留 querySource:'skill_improvement_apply' 字符串).
     */
    @PostConstruct
    public void initSkillImprovement() {
        if (!improvementEnabled) {
            if (log.isDebugEnabled()) {
                log.debug("Skill improvement 检测器未注册: nexusai.skill.improvement-enabled=false (对齐 CC feature('SKILL_IMPROVEMENT') && GrowthBook tengu_copper_panda 双门控, skillImprovement.ts:176-179)");
            }
            return;
        }
        PostSamplingHookRegistry.register(createSkillImprovementHook());
        log.info("Skill improvement LLM 检测器已注册进 PostSamplingHookRegistry (对齐 CC initSkillImprovement)");
    }

    /**
     * shouldRun 门控 · 对齐 CC L75-92 (查询式版本, 供 createSkillImprovementHook 使用).
     */
    boolean shouldRun(ApiQueryHookHelper.ApiQueryHookContext ctx, AtomicInteger lastAnalyzedCount) {
        if (ctx.querySource() != QuerySource.REPL_MAIN_THREAD) {
            return false;
        }
        // [IMP-HOOKS-S8 CCJ-HOOKS-T8-04] provider 带 ctx 每次调用新鲜求值 (对齐 CC shouldRun 内
        //   findProjectSkill() 每轮动态求值, skillImprovement.ts:80-82)
        Optional<ProjectSkill> skill = projectSkillProvider.find(ctx);
        if (skill.isEmpty()) {
            return false;
        }
        long userCount = ctx.messages().stream().filter(m -> m.role() == Role.user).count();
        if (userCount - lastAnalyzedCount.get() < TURN_BATCH_SIZE) {
            return false;
        }
        lastAnalyzedCount.set((int) userCount);
        return true;
    }

    /**
     * shouldRun 门控 (测试直接断言用, 与查询式版本同逻辑, 不写回 state).
     */
    boolean shouldRun(PostSamplingContext ctx, int lastAnalyzedCount) {
        if (ctx.querySource() != QuerySource.REPL_MAIN_THREAD) {
            return false;
        }
        // [IMP-HOOKS-S8] PostSamplingContext 测试重载: 经既有 ApiQueryHookContext.from 转换复用
        //   同一 ctx 感知 provider (CC REPLHookContext 等价)
        Optional<ProjectSkill> skill = projectSkillProvider.find(ApiQueryHookHelper.ApiQueryHookContext.from(ctx));
        if (skill.isEmpty()) {
            return false;
        }
        long userCount = ctx.messages().stream().filter(m -> m.role() == Role.user).count();
        return userCount - lastAnalyzedCount >= TURN_BATCH_SIZE;
    }

    /**
     * buildMessages · 对齐 CC L94-127 — 只分析距上次检查以来的新消息 (skill 定义提供足够上下文).
     */
    String buildMessages(ApiQueryHookHelper.ApiQueryHookContext ctx, AtomicInteger lastAnalyzedIndex) {
        ProjectSkill skill = projectSkillProvider.find(ctx).orElseThrow();
        List<ChatMessageDto> newMessages = ctx.messages().stream()
                .skip(lastAnalyzedIndex.get())
                .toList();
        lastAnalyzedIndex.set(ctx.messages().size());
        return buildClassifierPrompt(skill.content(), formatRecentMessages(newMessages));
    }

    /** buildMessages (测试直接断言用) — 全量消息. */
    String buildMessages(PostSamplingContext ctx) {
        ProjectSkill skill = projectSkillProvider
                .find(ApiQueryHookHelper.ApiQueryHookContext.from(ctx))
                .orElseThrow();
        return buildClassifierPrompt(skill.content(), formatRecentMessages(ctx.messages()));
    }

    /**
     * parseResponse · 对齐 CC L134-144 — extractTag(content,'updates') + jsonParse.
     */
    List<SkillUpdate> parseResponse(String content) {
        String updatesStr = extractTag(content, "updates");
        if (updatesStr == null || updatesStr.isBlank()) {
            return List.of();
        }
        try {
            return JSON.readValue(updatesStr, new TypeReference<List<SkillUpdate>>() {});
        } catch (Exception e) {
            log.warn("Skill improvement parse <updates> JSON 失败, 按无更新处理: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * logResult · 对齐 CC L146-167 — 仅 success 且 updates&gt;0 时上报 telemetry + 写 suggestion.
     *
     * <p><b>[Session H12 v2 Gap3 修复]</b>: CC L160-165 把 suggestion 写进
     * {@code context.toolUseContext.setAppState(...)} — Java 端现优先走
     * {@code ctx.toolUseContext().setAppState()} (生产路径, suggestion 不再丢弃);
     * ctx 为 null / toolUseContext 为 null 时回退注入的 {@code appStateWriter} (测试路径).
     */
    void logResult(ApiQueryHookHelper.ApiQueryResult<List<SkillUpdate>> result,
                   ApiQueryHookHelper.ApiQueryHookContext ctx) {
        if (result instanceof ApiQueryHookHelper.ApiQuerySuccess<List<SkillUpdate>> s
                && !s.result().isEmpty()) {
            String skillName = projectSkillProvider.find(ctx)
                    .map(ProjectSkill::skillName).orElse("unknown");
            telemetry.recordEvent("tengu_skill_improvement_detected", Map.of(
                    "updateCount", s.result().size(),
                    "uuid", s.uuid(),
                    // CC original: _PROTO_skill_name (skillImprovement.ts:156-157)
                    "_PROTO_skill_name", skillName));
            writeSuggestion(ctx, skillName, s.result());
            if (log.isDebugEnabled()) {
                log.debug("Skill improvement 检测到 {} 条更新: skill={}", s.result().size(), skillName);
            }
        }
    }

    /** 写 suggestion · CC skillImprovement.ts:160-165 setAppState({skillImprovement:{suggestion}}). */
    private void writeSuggestion(ApiQueryHookHelper.ApiQueryHookContext ctx,
                                 String skillName, List<SkillUpdate> updates) {
        // [session-id-short] ctx.toolUseContext().sessionId() 已 String（short）
        String sessionId = (ctx != null && ctx.toolUseContext() != null)
                ? ctx.toolUseContext().sessionId()
                : null;
        if (ctx != null && ctx.toolUseContext() != null
                && ctx.toolUseContext().setAppState() != null) {
            ctx.toolUseContext().setAppState().accept(prev -> {
                Map<String, Object> next = new LinkedHashMap<>(prev);
                Map<String, Object> skillImprovement = new LinkedHashMap<>();
                Map<String, Object> suggestion = new LinkedHashMap<>();
                suggestion.put("skillName", skillName);
                suggestion.put("updates", updates);
                skillImprovement.put("suggestion", suggestion);
                next.put("skillImprovement", skillImprovement);
                return next;
            });
        } else {
            // ctx 为 null (测试/无 TUC 路径) → 回退注入 writer
            appStateWriter.accept(skillName, updates);
        }
        // [P1-13] 双分支均追加 store 写 (CC original: AppState.skillImprovement.suggestion,
        // skillImprovement.ts:160-165) — REST 决策端点经 store 读取消费, 打通 apply 半环.
        writeSuggestionToStore(sessionId, skillName, updates);
        // [IMP-WF6-DC-01] 生成 suggestion 即经 WebSocket/STOMP 推"建议事件"（前端自动弹 survey）·
        // 用户拍板 WF6-DC-01（复用 useChatSocket session-level topic /topic/sessions/{sess-xxx}）。
        pushSuggestionEvent(sessionId, skillName, updates.size());
    }

    /**
     * [P1-13] suggestion 写入 session-keyed store · CC original:
     * {@code setAppState({skillImprovement:{suggestion:{skillName, updates}}})}
     * (skillImprovement.ts:160-165 / useSkillImprovementSurvey.ts:14-17).
     *
     * <p>WHY: Java 端 {@code appStateRef} 在 LlmAgentLoop 实例字段 (REST 层不可达),
     * 故检测器产物经本 store 暴露给 {@code SkillImprovementController} 消费.
     * sessionId 取 {@code ctx.toolUseContext().sessionId()}; store 未接线 / 无 session
     * 上下文 (ctx==null 测试路径) → 跳过 store 写, 不破坏既有测试.
     *
     * @param sessionId suggestion 归属会话（short；writeSuggestion 已解析的 toolUseContext().sessionId()）
     */
    private void writeSuggestionToStore(String sessionId,
                                        String skillName, List<SkillUpdate> updates) {
        if (suggestionStore == null || sessionId == null) {
            return;
        }
        suggestionStore.put(sessionId,
                new SkillImprovementSuggestionStore.PendingSuggestion(skillName, updates));
        if (log.isDebugEnabled()) {
            log.debug("Skill improvement suggestion 已写入 store: session={} skill={} updates={}",
                    sessionId, skillName, updates.size());
        }
    }

    /**
     * [IMP-WF6-DC-01] 经 WebSocket/STOMP 推"建议事件" · 对齐 CC 前端响应式读
     * {@code AppState.skillImprovement.suggestion}（useSkillImprovementSurvey.ts:26）的效果 —
     * Java web 端无响应式 AppState 通道，改经 STOMP 推轻量信号让前端自动弹 survey；完整内容
     * 仍走 store + REST peek/remove/apply（用户拍板 IMP-WF6-DC-01）。
     *
     * <p>topic: {@code /topic/sessions/{sess-xxx}}（session-level，前端 useChatSocket.ts:42 已订阅，
     * 与 session_backgrounded 同通道）。[session-id-short] sessionId 已 short 恒等直拼 topic 键；
     * wsTemplate 未注入（测试/无 WS 上下文）→ 跳过推送。
     */
    private void pushSuggestionEvent(String sessionId, String skillName, int updateCount) {
        if (wsTemplate == null) {
            return;
        }
        if (sessionId == null) {
            if (log.isDebugEnabled()) {
                log.debug("Skill improvement suggestion 推送跳过: sessionId=null");
            }
            return;
        }
        String topic = "/topic/sessions/" + sessionId;
        wsTemplate.convertAndSend(topic,
                new SkillImprovementSuggestionEvent(sessionId, skillName, updateCount));
        log.info("Skill improvement suggestion 已经 WebSocket 推送: topic={} skill={} updateCount={}",
                topic, skillName, updateCount);
    }

    /**
     * applySkillImprovement · 对齐 CC L188-267 — 读 SKILL.md → 侧信道 LLM 重写 → 提取
     * &lt;updated_file&gt; → 写回. Fire-and-forget (不阻塞主对话).
     *
     * <p>[P3-7] 语义对齐: extractTag 前整段响应先 trim (CC skillImprovement.ts:252 —
     * 整段响应先 trim 再 extractTag), 写回 <b>不</b> trim (CC :263
     * {@code fs.writeFile(filePath, updatedContent, 'utf-8')}) — 标签内首尾空白是 LLM
     * 输出的原文, Java 不得改字节.
     *
     * <p>[IMP-HOOKS-S8 CCJ-HOOKS-T8-07] 异步异常可观测: CC 侧 apply 为 async fire-and-forget,
     * 查询 reject 产生 unhandled rejection (控制台可观测, useSkillImprovementSurvey.ts:73);
     * Java 旧实现 {@code CompletableFuture.runAsync(() -> doApplySkillImprovement(...))} 无观察方
     * → 异常 (含 CancellationException) 静默吞掉. 现 runAsync lambda 内 try/catch(Exception)
     * → log.error 记录侧信道查询异常 (CancellationException 是 RuntimeException 子类, 覆盖;
     * Error 不捕获 — 对齐 CC reject 同样不捕获致命错误), future 恒正常完成.
     *
     * <p>[IMP-HOOKS-S8 CCJ-HOOKS-T8-05] 路径基准每次 apply 调用时经
     * {@link #baseDirSupplier} 求值 (CC getCwd() 动态语义), 非构造期冻结.
     *
     * @param skillName skill 名 (相对 cwd/{@link NexusaiPaths#getProjectDirName()}/skills/&lt;name&gt;/SKILL.md，默认 .nexusai)
     * @param updates   SkillUpdate 列表
     * @return 完成信号 (测试用 join; 异常已内部捕获, 恒正常完成)
     */
    public CompletableFuture<Void> applySkillImprovement(String skillName, List<SkillUpdate> updates) {
        return CompletableFuture.runAsync(() -> {
            try {
                doApplySkillImprovement(skillName, updates);
            } catch (Exception e) {
                // [IMP-HOOKS-S8 CCJ-HOOKS-T8-07] 侧信道查询异常可观测 (对齐 CC unhandled rejection)
                log.error("Skill improvement apply 异步执行异常: skill={} err={}", skillName,
                        e.getMessage(), e);
            }
        });
    }


    private void doApplySkillImprovement(String skillName, List<SkillUpdate> updates) {
        // [P1-13] 空名守卫 · CC original: if (!skillName) return (skillImprovement.ts:192).
        // WHY: REST 决策端点暴露后, null/blank skillName 直接走 resolve 会 NPE / 产生空目录路径,
        // 镜像 CC 先判空再读文件 (防 REST 暴露后的 NPE/路径穿越; 与 P2-17 同守卫).
        if (skillName == null || skillName.isBlank()) {
            if (log.isDebugEnabled()) {
                log.debug("Skill improvement apply: skillName 为空, 静默跳过改写 (对齐 CC skillImprovement.ts:192)");
            }
            return;
        }
        // [IMP-HOOKS-S8 CCJ-HOOKS-T8-05] 调用时求值: baseDirSupplier.get() 每次 apply 读取当前
        //   基准 (CC getCwd(), skillImprovement.ts:198), 替代旧构造期冻结 Path 字段
        // 决策 D1/D6：技能改进是【写入】操作 → 直接写 nexusai 自有目录（.nexusai/skills），
        //   对齐 CC skillImprovement.ts:198 join(getCwd(), '.claude', 'skills', ...) 等价替换目录名，
        //   不回落 .claude（nexusai 自治；读侧才需要 .claude 回落兼容未导入过渡期）
        Path baseDir = baseDirSupplier.get();
        // 决策 D1/D6 全动态：项目级 nexusai 目录名 = NexusaiPaths.getProjectDirName()（.{appName}）
        Path filePath = baseDir.resolve(NexusaiPaths.getProjectDirName()).resolve("skills")
                .resolve(skillName).resolve("SKILL.md");

        String currentContent;
        try {
            currentContent = Files.readString(filePath);
        } catch (IOException e) {
            // CC L201-208: read catch → logError + return (不写回)
            log.warn("读取 skill 文件失败, 跳过改进: {} err={}", filePath, e.getMessage());
            return;
        }

        String updateList = updates.stream()
                .map(u -> "- " + u.section() + ": " + u.change())
                .collect(Collectors.joining("\n"));

        if (log.isDebugEnabled()) {
            log.debug("Skill improvement apply 侧信道 LLM 改写: skill={} systemPrompt={} 更新数={} options={}",
                    skillName, APPLIER_SYSTEM_PROMPT, updates.size(), applyQueryOptions().querySource());
        }
        // applier 专属 systemPrompt (CC skillImprovement.ts:233-235), 非检测器 SYSTEM_PROMPT;
        // [P2-16] options 对齐 CC skillImprovement.ts:236-249 (thinkingConfig disabled / temperature 0 /
        //   querySource='skill_improvement_apply' / 每次新建 AbortController, :238)
        String response = modelQuery.query(APPLIER_SYSTEM_PROMPT,
                "You are editing a skill definition file. Apply the following improvements to the skill.\n\n"
                        + "<current_skill_file>\n" + currentContent + "\n</current_skill_file>\n\n"
                        + "<improvements>\n" + updateList + "\n</improvements>\n\n"
                        + "Rules:\n"
                        + "- Integrate the improvements naturally into the existing structure\n"
                        + "- Preserve frontmatter (--- block) exactly as-is\n"
                        + "- Preserve the overall format and style\n"
                        + "- Do not remove existing content unless an improvement explicitly replaces it\n"
                        + "- Output the complete updated file inside <updated_file> tags",
                applyQueryOptions());

        // [P3-7] 对齐 CC skillImprovement.ts:252 (整段响应先 trim 再 extractTag) — extractTag 全局 regex
        // 本就跳过标签外空白, 此步补齐 CC 步骤顺序 (对结果行为中性).
        String updatedContent = extractTag(response.trim(), "updated_file");
        if (updatedContent == null) {
            // CC L254-260: 无 updated_file 标签 → logError + return (不写回, 保护原文件)
            log.warn("Skill improvement apply: 响应无 <updated_file> 标签, 不写回: skill={}", skillName);
            return;
        }
        try {
            // [P3-7] 对齐 CC skillImprovement.ts:263 fs.writeFile(updatedContent) 不 trim —
            // 标签内首尾空白是 LLM 输出原文, 保留字节 (旧写回曾破坏原文).
            Files.writeString(filePath, updatedContent);
            if (log.isDebugEnabled()) {
                log.debug("Skill improvement 已写回: {}", filePath);
            }
        } catch (IOException e) {
            log.error("Skill improvement 写回失败: {} err={}", filePath, e.getMessage());
        }
    }

    // [W6-1] 小快/弱档模型来源 · static volatile Supplier（同 TeamMemoryHttpClient.baseUrlSource
    //   W4-1 模式）：默认 null（未注入 ModelConfigResolver）→ getSmallFastModel() 回落默认链。
    //   Spring 侧 {@link #setModelConfigResolver} 安装 DB settings 读取（fastModelId/weakModelId）。

    /** [W6-1] 小快档模型来源 · settings.fastModelId（V1 列，models.id/全名 → models.name 反查）。 */
    static volatile Supplier<String> smallFastModelSource = () -> null;
    /** [W6-1] 弱档(haiku)模型来源 · settings.weakModelId（V25 列，同 static holder 模式）。 */
    static volatile Supplier<String> weakModelSource = () -> null;

    /**
     * getSmallFastModel · 对齐 CC skillImprovement.ts:169 getSmallFastModel
     * ({@code process.env.ANTHROPIC_SMALL_FAST_MODEL || getDefaultHaikuModel()}).
     *
     * <p>[W6-1] env 路删除（用户拍板彻底删除 env）→ DB settings 档位字段承载：
     * {@code settings.fastModelId}（CC ANTHROPIC_SMALL_FAST_MODEL，model.ts:36-38）非空 →
     * {@code settings.weakModelId}（CC ANTHROPIC_DEFAULT_HAIKU_MODEL，model.ts:132-134）非空 →
     * 默认 firstParty haiku45 字面值 'claude-haiku-4-5-20251001' (configs.ts:31)。两档字段均经
     * {@link ModelConfigResolver#settingsTierModelName} 反查为 DB models.name（裸名）；未注入 /
     * 未配置 / 未命中 → null → 回落下一档。
     */
    public static String getSmallFastModel() {
        return getSmallFastModel(smallFastModelSource.get(), weakModelSource.get());
    }

    /**
     * 两参重载 · 纯函数测试 seam（同 shouldRun(PostSamplingContext,int) 测试重载 idiom）。
     * [W6-1] 语义改为「settings 值经参数注入」：两个参数是 DB settings 档位模型值（非 env）。
     *
     * @param smallFastModelValue settings.fastModelId 反查后的 models.name（CC original:
     *                            {@code ANTHROPIC_SMALL_FAST_MODEL}，model.ts:36-38）
     * @param defaultHaikuValue   settings.weakModelId 反查后的 models.name（CC original:
     *                            {@code ANTHROPIC_DEFAULT_HAIKU_MODEL}，model.ts:132-134）
     * @return 解析后模型名: smallFastModelValue 非 blank → 它; 否则 defaultHaikuValue 非 blank → 它;
     *         否则默认 firstParty haiku45 = 'claude-haiku-4-5-20251001' (configs.ts:31)
     */
    static String getSmallFastModel(String smallFastModelValue, String defaultHaikuValue) {
        if (smallFastModelValue != null && !smallFastModelValue.isBlank()) {
            return smallFastModelValue;
        }
        if (defaultHaikuValue != null && !defaultHaikuValue.isBlank()) {
            return defaultHaikuValue;
        }
        return "claude-haiku-4-5-20251001";
    }

    /**
     * [W6-1] 安装小快/弱档模型 DB 来源 · 注入 {@link ModelConfigResolver}（内含 SettingsMapper，
     * 读 settings 单例行 id=1）后将 {@link #smallFastModelSource}/{@link #weakModelSource} 切换为
     * DB settings.fastModelId/weakModelId 反查。{@code @Autowired(required=false)}：测试/孤立运行
     * 不注入 → 保持默认 null（回落 haiku45 字面量）。同 TeamMemoryHttpClient#setProviderMapper
     * 的 W4-1 注入风格；生产构造 {@code buildModelQuery} 另注入同一 resolver（无冲突）。
     */
    @Autowired(required = false)
    public void setModelConfigResolver(ModelConfigResolver modelConfigResolver) {
        if (modelConfigResolver != null) {
            smallFastModelSource = () -> modelConfigResolver.settingsTierModelName(SettingsRecord::getFastModelName);
            weakModelSource = () -> modelConfigResolver.settingsTierModelName(SettingsRecord::getWeakModelName);
            log.info("SkillImprovementHook: ANTHROPIC_SMALL_FAST_MODEL/ANTHROPIC_DEFAULT_HAIKU_MODEL env 路删除，"
                + "档位模型改读 DB settings.fast_model_name/weak_model_name（[W6-1][FN2] 字段改名）");
        }
    }

    /**
     * applier 侧信道查询选项 · 对齐 CC skillImprovement.ts:236-249:
     * <ul>
     *   <li>{@code thinkingConfig:{type:'disabled'}} (:236)</li>
     *   <li>{@code tools: []} (:237) — 空工具数组 = 不调工具 (Java 端 tools=null 等价)</li>
     *   <li>{@code signal: createAbortController().signal} (:238) — 每次 apply 新建未 abort 的 controller</li>
     *   <li>{@code temperatureOverride: 0} (:245)</li>
     *   <li>{@code querySource: 'skill_improvement_apply'} (:247)</li>
     *   <li>{@code model: getSmallFastModel()} (:241) — 生产接线由 buildModelQuery 传入</li>
     * </ul>
     */
    static LlmProvider.ChatRequestOptions applyQueryOptions() {
        return new LlmProvider.ChatRequestOptions(
                List.of(), null, null,
                LlmProvider.ChatRequestOptions.ThinkingConfig.disabled(),
                0d,
                "skill_improvement_apply",
                new AbortController(),
                null);   // [IMP-M-P1-2] maxTokens — CC skillImprovement.ts 未设 max_tokens
    }


    // ════════════════════════════════════════════════════════════════════
    // [H12 v2 Gap3] 生产 wiring 辅助
    // ════════════════════════════════════════════════════════════════════

    /**
     * 构造侧信道 LLM 查询函数 · 惰性按模型名解析 provider 配置 (对齐 CC skillImprovement.ts:241).
     *
     * <p>WHY (Gap3): 旧实现 {@code providerFactory.getProvider(null)} config==null →
     * MockLlmProvider (LLM 检测走 mock 无意义). 现解析真实 provider 配置, 有则接线真实 LLM.
     *
     * <p>[DEC-RV-19] WHY (config 对齐 CC): CC 用 {@code getSmallFastModel()} 模型名 + 全局
     * Anthropic client — 模型名只是同一 client 的请求参数 (queryModelWithoutStreaming →
     * getAnthropicClient, claude.ts:709 / client.ts:88), 模型名与 config 天然一致.
     * Java 旧实现 resolveDefaultProviderConfig 取"任意首个 enabled provider" 且 1 参
     * {@code getProvider(cfg)} 恒走 openai_sdk → 多 provider 时可能"Claude 模型 + OpenAI 配置"
     * 错配. 现经 {@link ModelConfigResolver} 按 {@code getSmallFastModel()} 精确匹配 →
     * 模型与 config 恒同 provider, 2 参 {@code getProvider(config, providerType)} 路由
     * (type=anthropic → AnthropicSdkProvider, 顺带修复 1 参 openai_sdk 恒错).
     * 解析失败 → warn + skip (返回 ""), 不落 mock (对齐 ModelConfigResolver 契约 warn+skip
     * + CC 无 mock: 查询要么成功要么抛错).
     */
    static SkillImprovementModelQuery buildModelQuery(
            LlmProviderFactory providerFactory, ModelConfigResolver modelConfigResolver) {
        if (providerFactory == null) {
            return (systemPrompt, prompt, options) -> "";
        }
        AtomicReference<ProviderConfig> configRef = new AtomicReference<>();
        AtomicReference<LlmProvider> providerRef = new AtomicReference<>();
        // systemPrompt 按调用透传 (对齐 CC queryModelWithoutStreaming 每次调用独立传 systemPrompt,
        // skillImprovement.ts:233) — 不再硬编码检测器 SYSTEM_PROMPT, 检测器/applier 各用各的;
        // [P2-16] options 透传 CC 查询选项 → chatWithOptions (替代 chatWithRaw 无 options 契约)
        return (systemPrompt, prompt, options) -> {
            if (configRef.get() == null) {
                ModelConfigResolver.ResolvedModel resolved = resolveSkillImprovementConfig(modelConfigResolver);
                if (resolved == null) {
                    // warn 已在 resolveSkillImprovementConfig 内打印; 不缓存 null → 下次查询重试 (自愈)
                    return "";
                }
                configRef.set(resolved.config());
                providerRef.set(providerFactory.getProvider(resolved.config(), resolved.providerType()));
                log.info("Skill improvement LLM 检测已接线真实 provider (baseUrl={}, type={})",
                        resolved.config().baseUrl(), resolved.providerType());
            }
            // [P2-16] abort 预检 · 对齐 CC claude.ts:744-745 (signal.aborted → APIUserAbortError).
            if (options != null && options.abortController() != null
                    && options.abortController().isCancelled()) {
                throw new java.util.concurrent.CancellationException(
                    "Skill improvement 侧信道 LLM 查询已 abort (CC claude.ts:744-745)");
            }
            // options 映射: tools=null (CC skillImprovement.ts:237 tools:[] 空数组 = 不调工具),
            // thinkingConfig/temperature/querySource/abortController 原样透传 (CC :236/:245/:247/:238)
            LlmProvider.ChatRequestOptions chatOptions = new LlmProvider.ChatRequestOptions(
                    List.of(), null, null,
                    options != null ? options.thinkingConfig() : null,
                    options != null ? options.temperature() : null,
                    options != null ? options.querySource() : null,
                    options != null ? options.abortController() : null,
                    options != null ? options.maxTokens() : null);   // [IMP-M-P1-2] maxTokens 透传
            String content = providerRef.get().chatWithOptions(
                    configRef.get(), getSmallFastModel(), systemPrompt, prompt, chatOptions);
            if (log.isDebugEnabled()) {
                log.debug("Skill improvement 侧信道 LLM 查询完成: querySource={} contentLen={}",
                        chatOptions.querySource(), content == null ? 0 : content.length());
            }
            return content == null ? "" : content;
        };
    }

    /**
     * 按模型名解析 skill improvement 侧信道 provider 配置 · 对齐 CC skillImprovement.ts:241
     * {@code model: getSmallFastModel()} (模型名 → 全局 Anthropic client, 模型与 config 天然一致).
     * <p>[DEC-RV-19] 替代旧 resolveDefaultProviderConfig (任意首个 enabled provider + 1 参
     * getProvider 恒 openai_sdk 双重错位): 经 {@link ModelConfigResolver#resolve} 按
     * {@code getSmallFastModel()} 精确匹配 → 模型与 config 恒同 provider (type=anthropic →
     * AnthropicSdkProvider), 单/多 provider 均正确.
     *
     * @param modelConfigResolver 可为 null (未注入/单测) → null
     * @return 解析成功 → (config, providerType); 失败 → null (warn+skip, 不落 mock)
     */
    private static ModelConfigResolver.ResolvedModel resolveSkillImprovementConfig(
            ModelConfigResolver modelConfigResolver) {
        if (modelConfigResolver == null) {
            log.warn("Skill improvement ModelConfigResolver 未注入, 跳过侧信道 LLM 查询 (warn+skip 不落 mock)");
            return null;
        }
        return modelConfigResolver.resolve(getSmallFastModel());
    }

    /**
     * 查找 project skill · [IMP-HOOKS-S8 CCJ-HOOKS-T8-04] 对齐 CC skillImprovement.ts:58-66
     * {@code findProjectSkill()} — {@code getInvokedSkillsForAgent(null)} 中首个
     * {@code skillPath.startsWith('projectSettings:')} 的 skill:
     * <ol>
     *   <li><b>上下文守卫</b>: ctx/toolUseContext/sessionId 任一缺失 (T7 D8 toolUseContext 可空)
     *       → {@link Optional#empty()} (诚实降级, shouldRun false, 无异常)</li>
     *   <li><b>invoked 源</b>: {@code state.getInvokedSkillsForAgent(null)} (agentId===null 过滤,
     *       state.ts:1530-1541) 按 invokedAt 升序 (AgentState.lastInvokedAt 单调递增,
     *       :846-851 — 近似 CC Map 插入序「首个调用者胜」)</li>
     *   <li><b>项目级判定</b>: skillName 经 {@link SkillRegistry#findCommand} 解析,
     *       {@code Command.getBaseDir()} 归一化后 ∈ {@link MarkdownConfigLoader#getProjectDirsUpToHome}
     *       ("skills", cwd) 集合 (或等于其中某目录) ∪ {@code <additionalDir>/.claude/skills}
     *       (每个 --add-dir 附加目录, {@link SkillRegistry#getAdditionalDirectories}) → 项目技能.
     *       该判定近似 CC {@code 'projectSettings:'} 源标签: Java SkillsLoader 把 user/project/managed
     *       全折叠为 CommandSource.USER (SkillsLoader.java:323-345), 前缀判定不可用; [P2-18] CC 的
     *       additionalDir (--add-dir) 技能同为 projectSettings 源 (loadSkillsDir.ts:699-708),
     *       Java 经 {@link SkillsLoader#getAdditionalDirectories} 公开 API 读取该子集并纳入判定
     *       (WF6-02 △-1 additionalDir 子集排除修复, EV-WF6-SI-006);
     *       user-home 技能 baseDir 不在集合内 (getProjectDirsUpToHome 到 home 即停) → 正确排除</li>
     *   <li><b>content</b>: invoked 记录的<b>渲染后</b>全文 {@code InvokedSkillInfo.content()}
     *       (CC processSlashCommand.tsx:884 getPromptForCommand 输出), 非 Command.getContent()
     *       原始定义</li>
     *   <li><b>每次求值</b>: shouldRun/buildMessages/logResult 各调一次, 无 memoize/registry
     *       回退 (旧进程级一次性缓存 + 配置名 + USER 回退路径已删除)</li>
     * </ol>
     *
     * @param skillRegistry              invoked skill 名 → Command 解析 (可 null → empty)
     * @param sessionAgentStateRegistry  会话主 AgentState 注册表 (可 null → empty)
     * @param ctx                        检测器 hook 上下文 (可 null → empty)
     */
    private static Optional<ProjectSkill> findProjectSkill(
            SkillRegistry skillRegistry,
            SessionAgentStateRegistry sessionAgentStateRegistry,
            ApiQueryHookHelper.ApiQueryHookContext ctx) {
        if (ctx == null || ctx.toolUseContext() == null || ctx.toolUseContext().sessionId() == null) {
            if (log.isDebugEnabled()) {
                log.debug("Skill improvement 查找 project skill: 上下文缺失 (ctx/toolUseContext/sessionId), "
                        + "诚实降级返回 empty (T7 D8 toolUseContext 可空)");
            }
            return Optional.empty();
        }
        if (sessionAgentStateRegistry == null || skillRegistry == null) {
            return Optional.empty();
        }
        AgentState state = sessionAgentStateRegistry.get(ctx.toolUseContext().sessionId());
        if (state == null) {
            if (log.isDebugEnabled()) {
                log.debug("Skill improvement 查找 project skill: 会话 {} 未注册主 AgentState, 返回 empty",
                        ctx.toolUseContext().sessionId());
            }
            return Optional.empty();
        }
        List<AgentState.InvokedSkillInfo> invoked = state.getInvokedSkillsForAgent(null).values().stream()
                .sorted(Comparator.comparingLong(AgentState.InvokedSkillInfo::invokedAt))
                .toList();
        if (invoked.isEmpty()) {
            return Optional.empty();
        }
        Path cwd = ctx.toolUseContext().effectiveCwd();
        if (cwd == null) {
            return Optional.empty();
        }
        List<Path> projectDirs = new ArrayList<>(
                MarkdownConfigLoader.getProjectDirsUpToHome("skills", cwd.toString())
                        .stream()
                        .map(dir -> Path.of(dir).toAbsolutePath().normalize())
                        .toList());
        // [P2-18] additionalDir（--add-dir）子集纳入项目级判定 · CC original: loadSkillsDir.ts:699-708
        //   {@code additionalDirs.map(dir => loadSkillsFromSkillsDir(join(dir, '.claude', 'skills'), 'projectSettings'))}
        //   — additional dir 技能以 projectSettings 源加载, findProjectSkill (skillImprovement.ts:58-66
        //   {@code skillPath.startsWith('projectSettings:')}) 必须命中该子集 (WF6-02 △-1 修复, EV-WF6-SI-006).
        for (String additionalDir : skillRegistry.getAdditionalDirectories()) {
            if (additionalDir == null || additionalDir.isBlank()) {
                continue;
            }
            // 决策 D1/D6：additionalDir 技能目录 nexusai 优先 + claude 回落（技能可能从 .nexusai 加载）
            Path additionalNexusaiSkills = Path.of(additionalDir, NexusaiPaths.getProjectDirName(), "skills")
                    .toAbsolutePath().normalize();
            Path additionalSkillsDir = Path.of(additionalDir, ".claude", "skills")
                    .toAbsolutePath().normalize();
            projectDirs.add(additionalNexusaiSkills);
            projectDirs.add(additionalSkillsDir);
        }
        for (AgentState.InvokedSkillInfo info : invoked) {
            Command cmd = skillRegistry.findCommand(info.skillName());
            if (cmd == null || cmd.getBaseDir() == null || cmd.getBaseDir().isBlank()) {
                continue;
            }
            Path baseDir = Path.of(cmd.getBaseDir()).toAbsolutePath().normalize();
            boolean inProject = projectDirs.stream()
                    .anyMatch(dir -> baseDir.equals(dir) || baseDir.startsWith(dir));
            if (inProject) {
                if (log.isDebugEnabled()) {
                    log.debug("Skill improvement 命中 project skill: name={} baseDir={} (invoked, "
                                    + "projectDirs={})",
                            info.skillName(), baseDir, projectDirs);
                }
                return Optional.of(new ProjectSkill(info.skillName(), info.content()));
            }
            if (log.isDebugEnabled()) {
                log.debug("Skill improvement 跳过非项目级 invoked skill: name={} baseDir={}",
                        info.skillName(), baseDir);
            }
        }
        return Optional.empty();
    }

    // ════════════════════════════════════════════════════════════════════
    // 内部辅助 · 对齐 CC skillImprovement.ts:39-66
    // ════════════════════════════════════════════════════════════════════

    /** formatRecentMessages · 对齐 CC L39-56 — user/assistant 消息, 单条截断 500 字符. */
    private static String formatRecentMessages(List<ChatMessageDto> messages) {
        return messages.stream()
                .filter(m -> m.role() == Role.user || m.role() == Role.assistant)
                .map(m -> {
                    String role = m.role() == Role.user ? "User" : "Assistant";
                    String content = m.content() == null ? "" : m.content();
                    String truncated = content.length() > 500 ? content.substring(0, 500) : content;
                    return role + ": " + truncated;
                })
                .collect(Collectors.joining("\n\n"));
    }

    /** buildClassifierPrompt · 对齐 CC L101-126 分类器 prompt. */
    private static String buildClassifierPrompt(String skillDefinition, String recentMessages) {
        return "You are analyzing a conversation where a user is executing a skill (a repeatable process).\n"
                + "Your job: identify if the user's recent messages contain preferences, requests, or corrections "
                + "that should be permanently added to the skill definition for future runs.\n\n"
                + "<skill_definition>\n" + skillDefinition + "\n</skill_definition>\n\n"
                + "<recent_messages>\n" + recentMessages + "\n</recent_messages>\n\n"
                + "Look for:\n"
                + "- Requests to add, change, or remove steps: \"can you also ask me X\", \"please do Y too\", \"don't do Z\"\n"
                + "- Preferences about how steps should work: \"ask me about energy levels\", \"note the time\", \"use a casual tone\"\n"
                + "- Corrections: \"no, do X instead\", \"always use Y\", \"make sure to...\"\n\n"
                + "Ignore:\n"
                + "- Routine conversation that doesn't generalize (one-time answers, chitchat)\n"
                + "- Things the skill already does\n\n"
                + "Output a JSON array inside <updates> tags. Each item: "
                + "{\"section\": \"which step/section to modify or 'new step'\", \"change\": \"what to add/modify\", "
                + "\"reason\": \"which user message prompted this\"}.\n"
                + "Output <updates>[]</updates> if no updates are needed.";
    }

    /**
     * extractTag · 对齐 CC utils/messages.ts:633-687 extractTag — 提取 &lt;tag&gt;...&lt;/tag&gt; 内容.
     *
     * <p>[P3-7] 重写对齐 CC messages.ts:633-687 (替换旧简单非贪婪 regex + 共享 Pattern 常量):
     * <ol>
     *   <li>Pattern.quote(tagName) 按 tagName per-call 构造 regex (对齐 CC escapeRegExp :638,
     *       stringUtils.ts:9-11), 无共享全局 pattern</li>
     *   <li>三 regex (完整匹配 / openingTag / closingTag) 均带 CASE_INSENSITIVE (对齐 CC 'gi' :638)</li>
     *   <li>遍历完整 regex 匹配, beforeMatch = html.substring(lastIndex, match.start) (:661),
     *       分别用 opening/closing 计数 depth (:655-676), 仅 {@code depth==0 && !content.isEmpty()}
     *       返回 content (:679-681)</li>
     *   <li>空内容 ({@code <tag></tag>}) 跳过、lastIndex 前进继续找后续非空 (:683)</li>
     *   <li>无 depth-0 命中返回 null (:686)</li>
     * </ol>
     */
    static String extractTag(String html, String tagName) {
        if (html == null || html.isBlank() || tagName == null || tagName.isBlank()) {
            return null;
        }
        // CC original: escapeRegExp(tagName) (messages.ts:638) — Pattern.quote 等价全字面转义
        String escapedTag = Pattern.quote(tagName);
        // CC original: `<${escapedTag}(?:\s+[^>]*)?>([\s\S]*?)<\/${escapedTag}>` + 'gi' (messages.ts:645-650)
        Matcher matcher = Pattern.compile(
                "<" + escapedTag + "(?:\\s+[^>]*)?>([\\s\\S]*?)<\\/" + escapedTag + ">",
                Pattern.CASE_INSENSITIVE).matcher(html);
        // CC original: openingTag / closingTag 计数 regex (messages.ts:655-656)
        Pattern openingTag = Pattern.compile(
                "<" + escapedTag + "(?:\\s+[^>]*?)?>", Pattern.CASE_INSENSITIVE);
        Pattern closingTag = Pattern.compile(
                "<\\/" + escapedTag + ">", Pattern.CASE_INSENSITIVE);

        int lastIndex = 0;
        while (matcher.find()) {
            String content = matcher.group(1);
            // CC original: beforeMatch = html.slice(lastIndex, match.index) (messages.ts:661)
            String beforeMatch = html.substring(lastIndex, matcher.start());

            int depth = 0;
            // CC original: 计数 opening tags → depth++ (messages.ts:666-670)
            Matcher opening = openingTag.matcher(beforeMatch);
            while (opening.find()) {
                depth++;
            }
            // CC original: 计数 closing tags → depth-- (messages.ts:672-676)
            Matcher closing = closingTag.matcher(beforeMatch);
            while (closing.find()) {
                depth--;
            }

            // CC original: depth === 0 && content (truthy) → return content (messages.ts:679-681)
            if (depth == 0 && !content.isEmpty()) {
                return content;
            }
            // CC original: lastIndex = match.index + match[0].length (messages.ts:683)
            lastIndex = matcher.end();
        }
        return null;
    }
}
