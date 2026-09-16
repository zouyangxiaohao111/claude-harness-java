package com.nexusai.application.agent.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Controller;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T10 · 单例 bean 会话态可变字段审计闸门（机械闸门 · 第 4 类残留）。
 *
 * <p><b>WHY 存在（设计 §1.5 类③ 的盲区闭环）</b>：前序审计把「单例承载会话态」压成两条判据
 * —— ①只扫 {@code @Configuration} 类的 {@code @Bean} 返回类型；②字段名关键字
 * {@code session/agent/current/project/cwd/context}。两条都漏：
 * <ul>
 *   <li>{@code DenialTracker}（{@code @Component}）的 {@code consecutiveDenials}/{@code totalDenials}
 *       <b>不含任何关键字</b>，且它<b>不在 {@code @Bean} 面内</b> ⇒ 双漏；</li>
 *   <li>{@code ClaudeMdController}（{@code @RestController}）的
 *       {@code externalIncludesApproved}/{@code externalIncludesWarningShown} 同样双漏；</li>
 *   <li>{@code CompactWarningState.suppressed}（{@code static final AtomicBoolean}）连「名字含关键字」都不成立。</li>
 * </ul>
 *
 * <p><b>本闸门的判据（修订版）</b>：
 * <ol>
 *   <li><b>扫描面</b>：全部<b>单例 bean 类型</b>——{@code @Component} / {@code @Service} /
 *       {@code @RestController} / {@code @Controller} / {@code @Repository} / {@code @Configuration}
 *       （经编译产物反射判注解，非源码文本匹配 ⇒ 无解析器 bug 面）。</li>
 *   <li><b>判据</b>：类型上<b>非 static 且非 final</b> 的<b>声明字段</b>（{@code getDeclaredFields()}）。
 *       ⛔ 排除理由<b>只对 {@code static}</b> 成立（常量 / 进程级持有者）；{@code final} 的排除是
 *       <b>有意收窄扫描面</b>，<b>不是</b>「final 承载不了会话态」——
 *       <b>{@code final} 可变容器（{@code Map}/{@code List}/{@code AtomicBoolean}/{@code Set}）
 *       恰恰是「按会话键承载会话态」的正范式</b>。</li>
 *   <li><b>⭐ 已知结构性盲区（登记 · 有意不修）</b>：由上一条 ⇒ <b>{@code final} 可变容器不在扫描面内</b>。
 *       批 P10b 的 {@code SubagentExecutor#systemPromptContextProviderBySession}（{@code final} LRU Map、
 *       跨会话串值缺陷的<b>修复形态</b>）正是此类 ⇒ 本闸门<b>拦不住同类问题</b>。
 *       <br><b>为何不修（实测代价，2026-09-16 @ P3 基线）</b>：把 {@code final} 非 static 字段纳入候选集 ⇒
 *       候选 348 → <b>948</b>（+600），其中「会话可疑」34 → <b>122</b>（<b>+88</b> 条），而这 88 条里绝大多数
 *       是<b>正确实现</b>（如 {@code ExtractMemoriesAgent} 的 7 张 {@code …BySession} Map、
 *       {@code SessionAgentStateRegistry#sessions}），把它们当「违规」逼逐条登记，等于把<b>正范式</b>
 *       判成嫌疑 ⇒ 直接触发本类反对的「噪声闸门必被绕过」。
 *       <br>⇒ 该盲区由<b>人工评审 + 本类文档</b>兜底，而非靠扩大扫描面。</li>
 *   <li><b>两层断言</b>：
 *       <br>· <b>Tier-1（人读白名单，双向）</b>：「会话可疑」字段（类型属会话载体集 <b>或</b> 字段名含
 *         会话关键字）必须<b>逐个</b>出现在 {@link #TIER1_REGISTERED} 且白名单里不许有已消失项 ——
 *         多一处红、少一处也红。每项必须写明「为何不是会话态载体」。</li>
 *       <br>· <b>Tier-2（候选集合指纹，防漏网）</b>：候选集合（非 static 非 final ∧ 非装配字段）
 *         排序列表的 SHA-256 等于 {@link #PINNED_FINGERPRINT}。Tier-1 的关键字/类型判据天然会漏掉
 *         「名字不含关键字且类型是基础类型」的会话字段（{@code int consecutiveDenials} 正是此型）
 *         ⇒ 靠候选集合指纹兜底：<b>任何新增/删除/改名一个候选字段都会改指纹</b>。
 *         ⛔ 但候选集合<b>不含</b>装配字段 —— 「新增一个 {@code @Autowired} 依赖」是无关改动，
 *         不该把本闸门打红（噪声闸门必被绕过）。</li>
 *   <li><b>覆盖率自检</b>：扫到的单例类数 / 字段数必须为正，防空集恒绿。</li>
 * </ol>
 *
 * <p><b>⛔ 这是源码/字节码审计闸门，不是行为测试</b>：它不证明任何运行期语义，只保证
 * 「新增一个单例会话态字段」在评审期被强制显式登记（登记动作 = 改本文件的两处常量）。
 *
 * <p><b>白名单条目的裁定依据</b>：{@code s1-final.md} §2 裁定-9 的判据「<b>可观测语义 / 指标基数</b>」
 * —— 会话化的必要条件是「可观测单元随会话变化」。进程级一次性 telemetry（如
 * {@code tengu_claudemd__initial_load}）会话化会改变<b>指标基数</b>（每 JVM 一条 → 每会话一条）
 * ⇒ 明确<b>不</b>会话化，登记为「已裁定进程级」。
 */
class BeanSingletonSessionStateAuditTest {

    /**
     * Tier-1 白名单 · 「会话可疑」字段（类型属会话载体集或名字含会话关键字）的**逐项登记**。
     *
     * <p>格式 {@code FQCN#field}；为空表示当前无任何可疑字段（正常终态）。
     * ⛔ 新增条目必须在注释里写明「为何不是会话态载体」。
     */
    private static final Set<String> TIER1_REGISTERED = new TreeSet<>(Set.of(
        "com.nexusai.apis.session.TodoStatusController#SessionMapper sessionMapper", // 装配别名（字段类型未带 bean 注解故未被装配过滤剔除）；bean 创建期写一次
        "com.nexusai.application.agent.compact.AutoCompactor#SessionMemoryService sessionMemoryService", // 装配依赖；bean 创建期写一次（@Bean 方法内注入）
        "com.nexusai.application.agent.compact.AutoCompactor#boolean contextCollapseEnabled", // feature/配置位；bean 创建期经 setContextCollapseEnabled 写一次
        "com.nexusai.application.agent.compact.AutoCompactor#boolean contextCollapseModeEnabled", // feature/配置位；bean 创建期经 setContextCollapseModeEnabled 写一次
        "com.nexusai.application.agent.compact.CompactThresholdSystem#ToIntFunction modelContextWindowResolver", // 装配注入的函数（无状态）
        "com.nexusai.application.agent.mcp.HttpMcpTransport#String sessionId", // 已核：协议层 session id（MCP HTTP 会话，非用户会话）。实例由 McpTransportFactory:67 `new HttpMcpTransport(...)` 按连接创建；全仓无 `@Autowired HttpMcpTransport` 注入点 ⇒ 该 @Component 注解从不作为单例被消费 ⇒ 字段 per-instance，非会话载体
        "com.nexusai.application.agent.mcp.WorkspaceTrustState#boolean nonInteractiveSession", // 启动期配置位（进程级）
        "com.nexusai.application.agent.memory.AutoDreamConsolidator#long lastSessionScanAt", // 已核【非载体】：CC 真源 autoDream.ts:124 `let lastSessionScanAt = 0`（initAutoDream() 闭包变量）+ :145/:152 扫描节流。它节流的是 `listSessionsTouchedSince`（跨全部会话的 transcript 扫描 = **全局**操作）⇒ 进程级节流是**正确语义**（限流全局资源），名字里的 session 只是「上次扫描时刻」的误称 ⇒ 不属会话态载体，不会话化
        "com.nexusai.application.agent.memory.SessionMemoryService#boolean sessionMemoryFeatureEnabled", // feature/配置位（进程级开关，非会话态）
        "com.nexusai.application.agent.memory.SessionMemoryService#boolean smSessionMemoryEnabled", // feature/配置位（进程级开关，非会话态）
        "com.nexusai.application.agent.memory.TeamMemoryWatcher#Future currentPushFuture", // 进程级 in-flight 推送句柄（team memory 为项目级共享）
        "com.nexusai.application.agent.memory.TeamMemoryWatcher#boolean hasPendingChanges", // 进程级 in-flight 标志（同上，项目级共享）
        "com.nexusai.application.agent.permission.hook.FileChangedWatcher#String currentCwd", // 监听目录（进程级配置，非 per-session）
        "com.nexusai.application.agent.permission.hook.HooksSettings#Function sessionHooksProvider", // 装配注入的 provider 函数（字段名含 session 指回调语义）
        "com.nexusai.application.agent.permission.hook.SettingsFileChangeWatcher#String projectRootOverride", // 测试/装配 override（进程级配置）
        "com.nexusai.application.agent.skill.SkillChangeDetector#Path projectDir", // 装配注入的目录配置（进程级）
        "com.nexusai.application.agent.skill.SkillRegistry#Function cwdSupplier", // 装配注入的函数（无状态）
        "com.nexusai.application.agent.subagent.AutonomousAgentLoop#AbortControllerRef currentWorkAbortController", // per-agent 构造（生产经 new，非 Spring 单例实例）=> 字段 per-instance
        "com.nexusai.application.agent.subagent.AutonomousAgentLoop#AgentState state", // per-agent 构造（生产经 new）=> per-instance
        "com.nexusai.application.agent.subagent.AutonomousAgentLoop#String agentId", // per-agent 构造（生产经 new，调用方 setAgentId）=> per-instance
        "com.nexusai.application.agent.subagent.AutonomousAgentLoop#String agentName", // per-agent 构造（生产经 new）=> per-instance
        "com.nexusai.application.agent.subagent.AutonomousAgentLoop#SubagentExecutor subagentExecutor", // 装配依赖；per-agent 构造（生产经 new）
        "com.nexusai.application.agent.tasks.BackgroundTaskRunner#RemoteAgentTaskService remoteAgentTaskService", // 装配依赖（bean 创建期写一次）
        "com.nexusai.application.agent.tasks.SdkEventQueue#boolean nonInteractiveSession", // 启动期配置位（进程级）
        "com.nexusai.application.agent.tool.PathGuard#Function sessionWorkdirResolver", // 装配注入的函数（字段名含 session 指解析语义）
        "com.nexusai.application.agent.tool.impl.SubagentExecutor#AgentMemoryDirectory agentMemoryDirectory", // 已核：装配依赖（@Autowired 注入式构造传入）；bean 创建期写一次
        "com.nexusai.application.agent.tool.impl.SubagentExecutor#Function agentDefinitionResolver", // 装配注入的函数（无状态）
        "com.nexusai.application.agent.tool.impl.SubagentExecutor#Map additionalAgentDefinitions", // 装配依赖（bean 创建期写一次）
        "com.nexusai.application.agent.tool.impl.SubagentExecutor#SystemPromptContextProvider systemPromptContextProvider", // 已核【P10b 复核 2026-09-16】：⛔ 原写「已核：per-invocation 构造」**为假** —— 该字段确乘在**单例 bean 面**上（`ToolRegistrationConfig:682` @Bean `subagentExecutor` 无 `@Scope`），且 2 条生产路径在该单例上跑（`SkillToolImpl:1660` executeForkedSkill / `AutonomousAgentLoop:1170` executeStreaming，二者均经 `SubagentExecutor:1779` resolveSystemContextText 到达读取点）。⭐ 但该字段**仅作装配注入位**：全仓唯一 `setSystemPromptContextProvider` 调用点在测试 `SubagentG4MetricsTest:87` ⇒ 生产恒 null ⇒ 读取恒走**按会话键**的 `systemPromptContextProviderBySession`（读取方法 `SubagentExecutor:5660`：`:5664` 注入优先，未注入则 `:5678` 查按会话键缓存）。⚠️ 这条假保证正是 P10b「跨会话串值」缺陷长期存活的直接原因
        "com.nexusai.application.agent.tool.impl.SubagentExecutor#UUID agentIdOverride", // 已核：per-invocation。bean 面确被 SpawnInProcess:60 注入并透传至 AutonomousAgentLoop:288，但 setAgentIdOverride 只在 `new SubagentExecutor(...)` 实例上调用（SubagentTool:3319/:3446）⇒ 共享 bean 上该字段恒 null（不构成当前跨会话泄漏）；保留登记因为「若有人对 bean 调该 setter 即为跨代理串扰」—— 属待评审项而非已裁定非载体
        "com.nexusai.application.agent.tool.impl.SubagentExecutor#boolean deferContextModifier", // 已核：per-invocation 构造传入；bean 面无写入方
        "com.nexusai.application.agent.tool.impl.SubagentExecutor#boolean sdkAgentProgressSummariesEnabled", // feature/配置位（per-invocation 构造时传入）
        "com.nexusai.application.agent.tool.impl.SubagentTool#boolean sdkAgentProgressSummariesEnabled", // feature/配置位（bean 创建期由装配方注入）
        "com.nexusai.application.agent.tool.powershell.PowerShellPermissionChain#AgentMemoryDirectory agentMemoryDirectory" // 装配依赖（bean 创建期 @Autowired 写一次；AgentMemoryDirectory 自身是 per-project 解析器，字段本身不变）
    ));

    /**
     * 已裁定为「进程级、非会话载体」的字段（Tier-2 指纹的子集）· 显式登记理由。
     *
     * <p>这些字段在单例上<b>故意</b>进程级；任何把它们改成会话级的动作都必须改本清单（= 二次评审点）。
     */
    private static final Set<String> DOCUMENTED_PROCESS_SCOPED = new TreeSet<>(Set.of(
        "com.nexusai.application.agent.context.ClaudemdEngine#boolean hasLoggedInitialLoad" // T8 判据「可观测语义/指标基数」：tengu_claudemd__initial_load 的语义是「每 JVM 一条」==> 会话化会改变指标基数 ==> 明确不会话化
        // [P3 · 2026-09-16] 原此处的 ClaudeMdController#boolean externalIncludesApproved /
        //   externalIncludesWarningShown 两条已删除 —— 字段本体已被 T15-3（de4ed2e9）删除，
        //   审批态改按**项目根键**承载并落 DB 表 claude_md_include_approval（V74）。
        //   ⛔ 替代物 `externalIncludesApprovedByProject` / `...WarningShownByProject` 是 `final` Map
        //      ⇒ 结构性进不了本清单（见类 javadoc 的盲区登记），**不得**改写成它们。
    ));

    /**
     * 全量 (类#字段) 排序列表的 SHA-256（换行分隔）。
     *
     * <p>更新方式：跑红后从断言消息里的 {@code 全量字段清单} 复制排序后的列表重算 —— 每次改动
     * 都必须是一次<b>显式评审</b>（这正是本闸门的目的）。
     *
     * <p><b>[P3 · 2026-09-16] 本次重钉的差异 = 恰 4 行</b>（已在评审中逐条归因；旧值
     * {@code 634f8500…} 对应的字段清单在当前代码下已不存在）：
     * <ol>
     *   <li><b>删 2 行</b>（T15-3 / {@code de4ed2e9} 删字段本体）：
     *       {@code ClaudeMdController#boolean externalIncludesApproved} /
     *       {@code …#boolean externalIncludesWarningShown}；</li>
     *   <li><b>改名 2 行</b>（T15-3 接缝带会话维度）：
     *       {@code ClaudemdEngine#Supplier hasClaudeMdExternalIncludesApproved} →
     *       {@code #Function …Approved}；{@code …WarningShown} 同（{@code Supplier<Boolean>} →
     *       {@code Function<String, Boolean>}）。</li>
     * </ol>
     * ⭐ 归因方式（可复核）：把上述 4 处按「旧态」还原后重算 SHA-256，<b>逐字节等于旧值
     * {@code 634f8500…}</b> ⇒ 旧清单被唯一确定，不存在第 5 处差异（SHA-256 见证，非目测）。
     * 其余一切字段（含 P10b 新增的 {@code systemPromptContextProviderBySession}）均未进入候选集
     * —— 后者是 {@code final} 字段，见类 javadoc 的盲区登记。
     */
    private static final String PINNED_FINGERPRINT =
        "1fa10951cf1797873c7f3dbf7d2e4098fdf974e49d5cb8d41b7a31ff537dace9";

    /** 「会话可疑」判据 ①：字段类型属会话载体集（简名匹配，兼容泛型外层）。 */
    private static final Set<String> SESSION_CARRIER_TYPES = Set.of(
        "ToolUseContext", "AgentState", "SessionPushContext", "TeammateIdentity",
        "SessionAgentStateRegistry", "AutoCompactTrackingState", "MicroCompactSessionState");

    /** 「会话可疑」判据 ②：字段名含会话关键字（原设计判据，保留作为 Tier-1 过滤）。 */
    private static final List<String> SESSION_KEYWORDS = List.of(
        "session", "agent", "current", "project", "cwd", "context", "pending");

    /** 单例 bean 注解集（与扫描面一一对应；改这里 = 改扫描面）。 */
    private static final List<Class<? extends Annotation>> SINGLETON_ANNOTATIONS = List.of(
        Component.class, Service.class, RestController.class, Controller.class,
        Repository.class, Configuration.class);

    /**
     * 「装配字段」标记注解<b>简名</b>集 ⇒ 该字段是 bean 依赖注入位，非业务可变态。
     *
     * <p>按简名匹配（而非 {@code Class} 常量）以免依赖具体注解包的可用性（{@code jakarta.*} 未必在
     * 测试 classpath 上）；命中即排除。
     */
    private static final Set<String> WIRING_ANNOTATION_NAMES = Set.of(
        "Autowired", "Value", "Resource", "Inject", "Qualifier", "PersistenceContext");

    @Test
    @DisplayName("T10 闸门：全部单例 bean 上零未登记的「会话可疑」可变字段 + 全量字段指纹不变")
    void singletonBeans_haveNoUnregisteredSessionStateFields() throws Exception {
        ScanResult scan = scanSingletonBeanFields();

        // ── 覆盖率自检（防空集恒绿）──
        assertThat(scan.beanClassCount())
            .as("覆盖率自检：扫到的单例 bean 类数必须 > 0（扫描面失效 ⇒ 本闸门退化为恒绿）")
            .isGreaterThan(0);
        assertThat(scan.fields())
            .as("覆盖率自检：扫到的非 static 非 final 声明字段必须 > 0")
            .isNotEmpty();

        // ── Tier-1：会话可疑字段逐项登记（双向）──
        List<String> suspicious = scan.fields().stream().filter(BeanSingletonSessionStateAuditTest::isSessionSuspicious).toList();
        assertThat(suspicious)
            .as("Tier-1：会话可疑字段（类型属会话载体集或名字含会话关键字）必须逐个登记在 TIER1_REGISTERED"
                + "（多一处 = 新增未评审会话态；少一处 = 清单残留）%n全量字段清单:%n%s", scan.describe())
            .containsExactlyInAnyOrderElementsOf(TIER1_REGISTERED);

        // ── Tier-2：全量指纹（防「名字不含关键字 + 基础类型」的漏网）──
        String actual = fingerprint(scan.fields());
        assertThat(actual)
            .as("Tier-2：单例 bean 上的非 static 非 final 声明字段集合发生变化（新增/删除/改名）"
                + "⇒ 必须显式评审并重新钉住指纹%n扫到的单例 bean 类数=%d%n候选字段数=%d%n实际指纹=%s%n全量字段清单:%n%s", scan.beanClassCount(), scan.fields().size(), actual, scan.describe())
            .isEqualTo(PINNED_FINGERPRINT);

        // ── 已裁定进程级项必须仍存在（防「静默删掉守护对象」）──
        assertThat(scan.fields())
            .as("DOCUMENTED_PROCESS_SCOPED 登记的项必须仍存在于扫描结果中（否则为陈留清单）")
            .containsAll(DOCUMENTED_PROCESS_SCOPED);
    }

    /** 「会话可疑」= 类型属会话载体集（简名尾部匹配，兼容泛型）或字段名含会话关键字。 */
    static boolean isSessionSuspicious(String classHashField) {
        String field = classHashField.substring(classHashField.indexOf('#') + 1);
        String type = field.substring(0, field.indexOf(' '));
        String name = field.substring(field.indexOf(' ') + 1);
        if (SESSION_CARRIER_TYPES.stream().anyMatch(t -> type.equals(t) || type.endsWith("<" + t + ">"))) {
            return true;
        }
        String lower = name.toLowerCase();
        return SESSION_KEYWORDS.stream().anyMatch(lower::contains);
    }

    /** 全量字段清单的 SHA-256（换行分隔 · 已排序 · 去重）。 */
    static String fingerprint(List<String> sortedFields) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(String.join("\n", sortedFields).getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : hash) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /** 扫描结果（类数 + 字段清单 + 诊断文本）。 */
    record ScanResult(int beanClassCount, List<String> fields) {
        String describe() {
            StringBuilder sb = new StringBuilder();
            for (String f : fields) {
                sb.append("  ").append(f).append('\n');
            }
            return sb.toString();
        }
    }

    /**
     * 反射扫描：编译产物（{@code target/classes}）里全部单例 bean 类型上的
     * 非 static 非 final 声明字段。
     *
     * <p><b>两条准入形态（缺一不可）</b>：
     * <ol>
     *   <li>形态 ①：类型自身带单例 bean 注解（{@code @Component}/{@code @Service}/
     *       {@code @RestController}/{@code @Controller}/{@code @Repository}/{@code @Configuration}）；</li>
     *   <li>形态 ②：{@code @Configuration} 类里 {@code @Bean} 方法的<b>返回类型</b>。
     *       ⛔ 不可丢 —— {@code AutoCompactor}/{@code ClaudemdEngine} 正是此形态（它们<b>不带</b>
     *       任何类级注解，只经 {@code ToolRegistrationConfig} 的 {@code @Bean} 注册）；
     *       丢掉形态 ② 就复刻了原设计「只扫 @Bean 面」的镜像盲区。</li>
     * </ol>
     *
     * <p>用<b>反射</b>而非源码文本匹配：{@code Modifier.isStatic/isFinal} 是编译器裁定的唯一真值，
     * 无「正则漏缩进/漏注解/误匹配方法体局部变量/FQN 形式注解漏匹配」的解析器 bug 面
     * （本仓已多次吃过源码正则的亏；{@code LlmAgentLoop} 的 {@code @org.springframework...}
     * 注释形 FQN 写法正是源码正则的失败例）。
     */
    static ScanResult scanSingletonBeanFields() throws Exception {
        Path classesRoot = resolveClassesRoot();
        Set<Class<?>> singletonTypes = new LinkedHashSet<>();
        try (Stream<Path> walk = Files.walk(classesRoot)) {
            List<Path> classFiles = walk
                .filter(p -> p.toString().endsWith(".class"))
                .toList();
            for (Path classFile : classFiles) {
                String className = classesRoot.relativize(classFile).toString()
                    .replace('\\', '.').replace('/', '.');
                className = className.substring(0, className.length() - ".class".length());
                Class<?> type;
                try {
                    // initialize=false：不触发静态初始化（审计只读元数据）
                    type = Class.forName(className, false, BeanSingletonSessionStateAuditTest.class.getClassLoader());
                } catch (Throwable t) {
                    continue; // 无法装载（合成类 / 可选依赖缺失）→ 跳过
                }
                if (isSingletonBean(type) && !isPrototype(type)) {
                    singletonTypes.add(type);
                }
                if (type.isAnnotationPresent(Configuration.class)) {
                    for (java.lang.reflect.Method m : type.getDeclaredMethods()) {
                        if (!m.isAnnotationPresent(org.springframework.context.annotation.Bean.class)) {
                            continue;
                        }
                        Class<?> rt = m.getReturnType();
                        if (rt != null && rt != void.class && !rt.isPrimitive() && !isPrototype(rt)
                                && isRepositoryOwned(rt)) {
                            // 只审计本仓类型：@Bean 也可返回第三方类型（如 Spring 的
                            // ServletServerContainerFactoryBean）—— 那不是本仓的可改代码，
                            // 纳入会让闸门把上游库的字段当成违规。
                            // [P3 · 2026-09-16] 判据由「FQN 前缀 com.nexusai.」改为「code source
                            //   归属本仓 target/classes」：按**来源**判归属，不按**包名**判。
                            //   ⚠️ 实测（见 {@link #isRepositoryOwned}）：本仓当前 81 个 @Bean 方法上
                            //   两种判据**逐条等价（差异 0）**，候选字段集（348 条）与指纹均不变。
                            singletonTypes.add(rt);
                        }
                    }
                }
            }
        }
        List<String> fields = new ArrayList<>();
        int beanClassCount = 0;
        for (Class<?> type : singletonTypes) {
            beanClassCount++;
            for (Field f : type.getDeclaredFields()) {
                int mod = f.getModifiers();
                if (Modifier.isStatic(mod) || Modifier.isFinal(mod)) {
                    continue;
                }
                if (f.isSynthetic() || isWiringAnnotated(f)) {
                    continue;
                }
                if (isSingletonBean(f.getType()) || isPrototype(f.getType())) {
                    continue; // 字段类型本身是 bean 类型 ⇒ 装配字段（固定依赖）
                }
                String simpleType = f.getType().getSimpleName();
                fields.add(type.getName() + "#" + simpleType + " " + f.getName());
            }
        }
        List<String> sorted = new ArrayList<>(new LinkedHashSet<>(new TreeSet<>(fields)));
        return new ScanResult(beanClassCount, sorted);
    }

    /**
     * 类型是否<b>本仓编译产物</b>（code source 归属 {@code target/classes}）。
     *
     * <p>[P3 · 2026-09-16] 取代原 FQN 前缀判据 {@code rt.getName().startsWith("com.nexusai.")}：
     * 按<b>来源</b>判归属，不按<b>包名</b>判。
     *
     * <p>⚠️ <b>实测等价性（必须照实读）</b>：本仓当前 81 个 {@code @Bean} 方法上，两种判据
     * <b>逐条等价（差异 0）</b>，候选字段集（348 条）与 Tier-2 指纹完全相同。原因：JDK 类型
     * （{@code java.util.function.Function} / {@code Supplier} / {@code List} …）的
     * {@code getProtectionDomain().getCodeSource()} 在 Java 9+ 返 <b>{@code null}</b>
     * （模块化后不暴露 CodeSource）⇒ <b>新判据同样不收 JDK 类型</b>。
     * <p>⭐ 因此：{@code ToolRegistrationConfig} 的 {@code Function<String,String>}
     * {@code @Bean}（{@code claudeMdContentSupplier} / {@code sessionProjectRootResolver}）
     * <b>在本判据下依旧不进入扫描面</b>，且它<b>本来也无字段可审计</b>
     * （{@code java.util.function.Function} 声明的字段数 = 0，实测）。
     *
     * <p>两向差异实测（2026-09-16）<b>均为 0</b>：① 本仓自有但非 {@code com.nexusai.*} 包名的类型
     * = 0（本仓 {@code src/main/java} 下只有 {@code com/nexusai}）⇒ 旧判据今日**没有**可漏之物；
     * ② 占用 {@code com.nexusai.*} 包名的第三方 jar 类型 = 0 ⇒ 新判据真正防住的是这一理论误收面。
     * ⛔ <b>不得</b>据此判据说「本判据让某个 {@code Function} {@code @Bean} 进了视野」—— 删除该
     * {@code @Bean} 后本测试<b>仍绿</b>（2026-09-16 实测，见批 P3 报告）。
     */
    private static boolean isRepositoryOwned(Class<?> type) {
        try {
            java.security.CodeSource codeSource = type.getProtectionDomain().getCodeSource();
            if (codeSource == null || codeSource.getLocation() == null) {
                return false; // 无 code source（JDK 模块类 / 引导类路径）⇒ 非本仓
            }
            Path source = Paths.get(codeSource.getLocation().toURI()).toAbsolutePath().normalize();
            Path root = resolveClassesRoot().toAbsolutePath().normalize();
            return source.equals(root) || source.startsWith(root);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 字段是否带装配类注解（按简名匹配，见 {@link #WIRING_ANNOTATION_NAMES}）。 */
    private static boolean isWiringAnnotated(Field f) {
        for (Annotation a : f.getAnnotations()) {
            if (WIRING_ANNOTATION_NAMES.contains(a.annotationType().getSimpleName())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSingletonBean(Class<?> type) {
        for (Class<? extends Annotation> ann : SINGLETON_ANNOTATIONS) {
            if (type.isAnnotationPresent(ann)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 是否<b>单例</b> bean · 排除 prototype 作用域。
     *
     * <p>⛔ 必须排除：{@code LlmAgentLoop} 是 {@code @Component} <b>+</b>
     * {@code @Scope("prototype")}（LlmAgentLoop.java:198-199，注释形 FQN 写法）—— 生产经
     * {@code loopProvider.getObject()} <b>每次新实例</b>，其 {@code currentState}/{@code streamSessionId}
     * 等字段是 per-instance 而非跨会话共享。不排除会把 prototype bean 的实例字段误判为单例会话态
     * （噪声闸门必被绕过）。
     */
    private static boolean isPrototype(Class<?> type) {
        org.springframework.context.annotation.Scope scope =
            type.getAnnotation(org.springframework.context.annotation.Scope.class);
        return scope != null && "prototype".equalsIgnoreCase(scope.value());
    }

    /** 定位 {@code target/classes}：由本测试类的 code source（{@code target/test-classes}）取同级。 */
    private static Path resolveClassesRoot() throws Exception {
        URI location = BeanSingletonSessionStateAuditTest.class.getProtectionDomain()
            .getCodeSource().getLocation().toURI();
        Path testClasses = Paths.get(location);
        Path classes = testClasses.getParent().resolve("classes");
        if (!Files.isDirectory(classes)) {
            throw new IllegalStateException("找不到 target/classes（" + classes + "）—— 审计闸门需先编译主源码");
        }
        return classes;
    }
}
