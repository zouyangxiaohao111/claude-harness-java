package com.nexusai.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>[G6-GUARD] 死引用护栏 · 删除载体后回扫的三门</b>
 *
 * <p>本类只做一件事：把「删了载体（类 / 方法），但 javadoc / 注释仍以现时态讲述它」这类
 * <b>死引用</b>变成<b>机械可判、自动列红</b>的红灯。它是零生产改动的纯扫描测试。
 *
 * <h2>门 = 可独立施工的一类死引用</h2>
 * <table border="1">
 *   <caption>三门</caption>
 *   <tr><th>门</th><th>守什么</th><th>机械性</th></tr>
 *   <tr>
 *     <td><b>门 3</b>（本类主体）</td>
 *     <td>javadoc 里的 javadoc 链接（{@code {@link X}} / {@code {@link X#y}}）目标
 *         <b>本体是否尚存</b>。链接<b>写明了形参表</b>（{@code {@link X#y(A, B)}}）时，
 *         成员存亡按<b>形参个数</b>比对，不只看名字（见 {@link #declaredCallable}）。
 *         悬空 ⇒ 断言失败并<b>逐条列出</b>（红清单）。</td>
 *     <td><b>完全机械</b>：无豁免名单、无行号、无需人工判断。</td>
 *   </tr>
 *   <tr>
 *     <td><b>门 1</b></td>
 *     <td>已删类的<b>本体文件</b>不得复活（台账驱动 + 文件系统查找）。</td>
 *     <td>机械。局限见 {@link #DELETED_CLASS_LEDGER}。</td>
 *   </tr>
 *   <tr>
 *     <td><b>门 2</b></td>
 *     <td>已删<b>方法名</b>的注释提及不得超过<b>债务台账</b>
 *         （<u>不是</u>清零门，见 {@link #gate2_deletedMethodMentions_doNotExceedDebtLedger()}）。</td>
 *     <td><b>只有计数机械</b>；「现时态 vs 过去时」<u>不可机械判定</u>。</td>
 *   </tr>
 * </table>
 *
 * <h2>WHY 必须存在（本仓具体事故，不是泛泛而谈）</h2>
 * <p>2026-09 的「删载体」批次（批 3c 删 {@code RequestContext}、批 4b-1 删
 * {@code AutoMemPaths.CURRENT_PROJECT_ROOT}、批 5a 删 {@code CacheSafeParamsHolder}）
 * 删了符号、改了调用点，<b>但没有回扫「描述旧机制的文字」</b>。收尾审查实测：
 * 已删符号的<b>注释引用</b>合计 ≥60 处，其中认为 <b>17 处是悬空的 javadoc 链接</b>。
 * <b>⚠️ 注意两个数字不要混</b>：审查的「≥60」是<b>一切</b>注释引用（含 {@code {@code}} 与裸文字），
 * 而本类实测的「60」是<b>纯悬空 javadoc 链接</b>数 —— 两者恰好都是 60，但口径完全不同。
 *
 * <p>本仓 <b>无</b> javadoc / doclint 插件（{@code backend/pom.xml} 与根 {@code pom.xml}
 * 都只有 {@code maven.compiler.source=25}），所以这 17 处<b>在构建期完全不暴露</b>；
 * 而 CC（TS 单仓）的 {@code tsc} 同样不校验 JSDoc {@code @link}
 * （实测 {@code claude-code-best/src} 18 处、{@code Open-ClaudeCode/src} 13 处，同样无人管）
 * ⇒ <b>这条护栏抄 CC 抄不到解法</b>，只能本仓自建。本类抄的是本仓自己的
 * {@code ProviderSessionIdWiringGuardTest} 手法（JUnit + 读源码断言），
 * 而<b>不是</b>任何 CC 对应物（CC 侧本项无对应物）。
 *
 * <h2>为什么门 3 必须「最先落」（P0 理由）</h2>
 * <p>它让悬空链接 <b>自动列红</b>，后续施工按<b>红清单</b>而非人工清单 ⇒ 防漏项。
 * 审查已证明「单一检索手段必漏」：只用 {@code grep 简单名} 只挖到第一簇，
 * 必须加 {@code Glob **&#47;Name*.java} 判本体存亡才挖到第二簇。本类把这件事<b>机械化</b>了
 * （见 {@link TypeIndex} 的双手段交叉），并且实测把清单从 17 扩到 60
 * —— <b>多出来的 43 条全部为真</b>（见下）。
 *
 * <h2>⛔ 绝不使用 GitNexus 做「本体存在」判定</h2>
 * <p>实测：GitNexus 索引陈旧，把<b>已删的</b> {@code CacheSafeParamsHolder.java} 报成存在
 * （本仓会话里 GitNexus hook 至今仍在报它）。本类一律走<b>真实文件系统 + 真实 classpath</b>。
 *
 * <h2>本护栏的边界（它能守什么、守不住什么）—— 必读</h2>
 * <p>本类<b>本身是「源码 / 字节码扫描型」护栏</b>，因此必须如实交代边界
 * （本仓前科：断言本身对、错的是「它守护了什么」的<b>声明</b>，共 5 次）。
 *
 * <p><b>它守得住</b>：
 * <ol>
 *   <li>javadoc 链接目标的本体存亡（<b>含多行形态</b>，见 {@link #LINK_TAG}）；</li>
 *   <li><b>同名重载被删</b>：链接写明了形参表（{@code {@link #m()}} / {@code {@link #m(A, B)}}）
 *       而目标只剩其它元数的重载 ⇒ 判悬空。这是 2026-09-15 补上的判据
 *       （此前 {@code declares(...)} 只比方法名，对这类<b>结构性失明</b>：
 *       实测把已删 0 参的 {@code {@link #agentRegistry()}} 放回去，本类 <b>仍 7/7 绿</b>）；
 *       ⛔ 链接<b>不写</b>形参表（{@code {@link #m}}）时元数不参与判定 —— 那是合法写法；</li>
 *   <li>已删类的本体文件复活；</li>
 *   <li>已删方法名提及<b>数量增长</b>。</li>
 * </ol>
 *
 * <p><b>它守不住（已知、已登记，不是遗漏）</b>：
 * <ol>
 *   <li><b>现时态 vs 过去时的语义判定</b>。「本类不再注入 ThreadLocal」与「本类注入 ThreadLocal」
 *       在机械上只差一个「不再」，而这个差别<b>不可机械判定</b>（同句在反讽 / 引用 / 否定式里
 *       语义可反过来）。门 2 因此<b>只能数数量</b>，⛔ 不声称自己清了历史债。</li>
 *   <li><b>{@code {@code X}} 与裸文字里的死引用</b>。{@code {@code}} 恰恰是 F-26 规定的
 *       <b>合法降级目标</b>（「{@link} 目标不存在时降级为 {@code} 纯文本」）⇒ 不能对
 *       {@code {@code}} 设门，否则把正确的修法判红。裸文字（无任何 tag）同理，规模 ≥60 处，
 *       只能靠门 2 的数量门压增长。</li>
 *   <li><b>元数对得上但<b>形参类型名</b>漂移</b>。链接写 {@code {@link #m(String)}}
 *       而目标只剩 {@code m(int)}（同为 1 参）时，本类<b>判不出来</b>（判绿）。
 *       WHY 刻意不修：类型名要比对就得处理泛型擦除 / 全限定与简单名混写 / 数组与变参后缀 /
 *       嵌套类名等一堆归一化，误红代价远高于收益（本仓「假红陷阱」前科：误红比漏判更贵；
 *       本类建立时源码正则判成员曾产生 2286 条假阳性）。⇒ 元数判据<b>只收敛「同名重载被删」
 *       这一类</b>（acc8 实证的那一类），⛔ 不声称覆盖全部签名漂移。</li>
 *   <li><b>新删除未登记</b>。门 1 是台账驱动的 ⇒ 只能防「已登记的类复活」，
 *       <b>不能</b>发现「有人删了一个类但没登记」。闭环方式见 {@link #DELETED_CLASS_LEDGER}。</li>
 *   <li><b>测试横幅 / {@code @DisplayName} / RED 条件里的死引用</b>（审查 F-14 那一类）。
 *       它们多数不是 javadoc，门 3 看不见；门 2 只数方法名。这一类<b>本类不覆盖</b>，
 *       属于门 3 / 门 2 之外的第三类，尚无机械守卫。</li>
 *   <li><b>跨模块</b>。只扫 {@code backend/src}。实测仓库根下 70 个
 *       {@code SessionProjectRoot*.java} 副本（68 个来自 40 个 {@code .claude/worktrees}）
 *       ⇒ 扫描根一旦被抬高，护栏会被过期副本冲垮。见 {@link #SRC_ROOT_RELATIVE_DIRS} 与
 *       {@link #gateMetaGuard_scopeIsExact_andNothingIsSilentlySkipped()}。</li>
 *   <li><b>陈旧字节码</b>。成员存亡走反射（源码正则判成员实测有 2286 条假阳性
 *       —— Lombok getter / record 访问器 / 继承成员正则全看不见，<b>不可用</b>）。
 *       反射依赖 {@code target/classes} 与源码同步；若有人删了一个<b>方法</b>却没触发重编译，
 *       本类会漏判。缓解：类型存亡一律走<b>源码索引</b>（不受影响），
 *       且本类的自检用例（{@link #gate3_reverseExperiment_realFilesystemInjectedNeedles(Path)}）
 *       证明反射路径真的能判红，不是恒绿。</li>
 * </ol>
 *
 * <p><b>本类刻意不自扫</b>（{@link #SELF_RELATIVE_PATH}）：本类的 javadoc 必须能
 * <b>谈论</b>「javadoc 链接」这件事本身（{@code {@code {@link X}}} 这类举例），
 * 自扫会把举例当链接判红，也会污染「分子 / 分母配平」自检。
 * 排除集合被<b>钉死为恰 1 个文件</b>并断言 —— 排除集一旦变大就红，
 * 所以这不是「静默跳过」。
 *
 * <p>2026-09-14 建（P0-1 / G6-GUARD）。建立时<b>实测 60 处悬空</b>
 * （{@code src/main/java} <b>47</b> 处 + {@code src/test/java} <b>13</b> 处）⇒ 门 3 <b>刻意保持红</b>，
 * 红清单即后续施工单；修完后自动转绿（本类<b>不</b> pin 死这 60 条的文件:行，
 * 避免行号漂移造成的假红，也避免「改了清单才转绿」的形式主义）。
 *
 * <p><b>2026-09-15 补元数判据 ⇒ 门 3 由「全绿」转红（65 条，红清单即施工单）</b>。
 * 此前成员存亡<b>只比方法名</b>，对「同名重载被删」结构性失明
 * （acc8 实证：把已删 0 参的 {@code {@link #agentRegistry()}} 放回 {@code SubagentTool} 的 javadoc，
 * 目标只剩 {@code agentRegistry(String)} ⇒ 本类<b>仍 7/7 绿</b>，见
 * {@link #gate3_reverseExperiment_arityMismatchIsDangling_andMatchingArityIsNot}）。
 * 补上形参个数比对后，本仓新暴露 <b>65 处元数不符</b>的悬空链接。处理方式与 60 处时完全一致：
 * <ul>
 *   <li>本类<b>刻意不 pin</b> 这 65 条的文件:行（同样避免行号漂移假红）；
 *       修法与门 3 相同 —— ① 改成现存重载名，或 ② 降级为 {@code}。数字只是当时的读数，
 *       ⛔ 不是台账；</li>
 *   <li>典型形态是「注释按<b>旧签名</b>写」：
 *       {@code OpenAiSdkProvider} 的链接列了 17 参而真实方法已 20 参（同一段 javadoc 的标题
 *       还自称「19-arg」）、{@code MemoryStorage} 链接 0 参 {@code #memoryDir()} 而真实已改
 *       为 {@code memoryDir(String)}、{@code McpServerService} 链接 {@code #start()} 而现存
 *       重载皆带参；</li>
 *   <li><b>判红口径有独立对照</b>：真实 {@code javadoc -Xdoclint:all} 对本仓这类引用
 *       报 {@code error: reference not found}（连变参简写 {@code {@link Path#toRealPath()}} /
 *       {@code {@link Files#isDirectory(Path)}} 也报，故它们被判红<b>不是</b>本类误红）。
 *       ⇒ 本门补的是「本仓无 javadoc 插件、这些错在构建期完全不暴露」的那一段，
 *       ⛔ 不是把 javadoc lint 的全部门槛搬进来（只搬元数这一条，理由见
 *       「它守不住」第 3 条）。</li>
 * </ul>
 *
 * <p><b>⚠️ 60 &gt; 收尾审查给的 17 —— 这不是本类误报，是人工枚举的结构性漏项。</b>
 * 审查的 17 条是「只 grep 那 4 个已知死符号（{@code CacheSafeParamsHolder} /
 * {@code currentSessionProjectRoot} / {@code #register} / {@code #registerAbort}）」得到的，
 * 所以<b>结构上不可能</b>发现别的死链接。本类逐条复核过（每条都手工对过源码）：
 * <ol>
 *   <li><b>审查的 17 条 100% 包含在 60 条里</b>（无一条被漏，已逐条比对）；</li>
 *   <li>另外 43 条是审查该方法学看不到的类别 —— 逐条复核结论（<b>全部为真</b>，
 *       即「javadoc 链接目标确实不存在」，非本类误报）：
 *     <ul>
 *       <li><b>已删符号</b>（复核方式：在目标类里 grep 零声明）：
 *           {@code ExtendedToolResultApplier}（已被 {@code ToolResultApplier} 取代）、
 *           {@code ToolResult#structuredOutput}（record 组件已移除）、
 *           {@code ChatService#parseSessionUuid} / {@code ChatService#replayAndPersist}
 *           （现存提及全是「原 …」过去时）、{@code FileReadEvent}、{@code ToolCallPartitioner}
 *           （本体文件不存在）、{@code AutoMemPaths#CURRENT_PROJECT_ROOT}、
 *           {@code CompactSummary#isValid}（该类有 0 处 {@code isValid}）、
 *           {@code MicroCompactor#compact}（该类只有 {@code maybeTimeBasedMicrocompact}）、
 *           {@code LlmProviderFactory#chatWithOptions}（该文件 0 处 {@code chatWithOptions}）；</li>
 *       <li><b>成员名在接收者类里不存在</b>（改名或从未存在）：
 *           {@code AbortBridge#create}（真实 API 是 {@code createAbortBridge}）、
 *           {@code AgentLoopContext#behaviors()}（该类 0 处 {@code behaviors(}）、
 *           {@code LimitResult.Denied}（该 sealed interface 的成员是
 *           {@code DeniedFileSize} / {@code DeniedEntryCount}）；</li>
 *       <li><b>接收者写错</b>（成员活在别的类里，声明不在被点名的类上）：
 *           {@code GenericHook#systemMessage} / {@code #additionalContext}
 *           （声明在 {@code AgentLoopContext}）、{@code LlmAgentLoop#loadPluginHooks()}
 *           （声明在 {@code PluginLoader}，{@code LlmAgentLoop} 只是调用方）、
 *           {@code McpConfigFileWriterTest#globalConfigFilePath} 与
 *           {@code GitRemoteResolverTest#getGithubRepo}（声明在<b>主源</b>的对应类上，
 *           测试类自己没有该成员）；</li>
 *       <li><b>名字与真实方法名漂移</b>（测试横幅写了不存在的用例名）：
 *           {@code HookOutputParserTest} 的 {@code #nonJsonOutput_degradesToProceed} /
 *           {@code #emptyStdout_degradesToProceed} / {@code #additionalContext_singleStringValue}
 *           （真实方法名是 {@code nonJsonOutput_plainTextHookSuccess} / {@code emptyStdout_hookSuccess} 等）、
 *           {@code R33H1_SsrfGuardLoopbackTest#ssrfGuardedLookup_rejectsPrivateResolution}、
 *           {@code StreamingToolExecutorHookInjectionTest#executeAsync_preToolUseHookPreventContinuationStopsTool}、
 *           {@code #buildMainUserMessage}、{@code #assembleSingleServer}、{@code #wouldOverflow}、
 *           {@code #leaderTeamNameHolder}、{@code #REAL_SETTINGS_SCHEMA}、
 *           {@code SkillCatalogBudgetTest#parseEnvBudget}、{@code #getAllCommands(String)}、
 *           {@code #unregisteredSession_fallsBackToSessionRecord}；</li>
 *       <li><b>外部 API 已被移除</b>：{@code ApplicationContextRunner#withEnvironment}
 *           （同文件 {@code :319} 注释自己写着「Spring Boot 3.5 移除了 withEnvironment」，
 *           而 {@code :32} 的链接没跟着改）；</li>
 *       <li><b>链接写法本身不合法</b>（javadoc 结构上无法解析）：{@code {@link #onIdle(int)}}
 *           指向的是<b>匿名类</b>里的方法、{@code {@link #agentListSection}} 指向的是<b>局部变量</b>、
 *           {@code {@link #interruptBehaviorCancel()}} 指向已删的旧实现。</li>
 *     </ul>
 * </ol>
 * <p>⇒ 结论：<b>「17」是「4 个已知死符号的直接引用数」，不是「悬空链接总数」</b>。
 * 这正是本护栏存在的理由（机械枚举 &gt; 人工枚举）。
 */
@DisplayName("[G6-GUARD] 死引用护栏：删除载体后回扫的三门交叉")
class DeadSymbolReferenceGuardTest {

    // ═══════════════════════════════ 常量 / 台账 ═══════════════════════════════

    /**
     * 扫描根（相对 {@code backend/}）。<b>⛔ 绝不外扩到 {@code backend/} 之上</b>：
     * 实测仓库根下 70 个 {@code SessionProjectRoot*.java}、40 个 worktree 副本
     * ⇒ 抬一格就冲垮护栏。
     */
    private static final List<String> SRC_ROOT_RELATIVE_DIRS =
        List.of("src/main/java", "src/test/java");

    /**
     * 本类自身的相对路径。<b>刻意不自扫</b>，理由见类 javadoc。
     * 排除集被断言钉死为恰 1 个文件（{@link #gateMetaGuard_scopeIsExact_andNothingIsSilentlySkipped()}）。
     */
    private static final String SELF_RELATIVE_PATH =
        "src/test/java/com/nexusai/common/DeadSymbolReferenceGuardTest.java";

    /**
     * javadoc 链接标签。{@code @link} 与 {@code @linkplain} 是同一机制，都要收。
     *
     * <p><b>⛔ 必须多行容错</b>：{@code @link} 后面可以跟<b>换行</b>（把长目标排到下一行）。
     * 实测全仓 <b>7 处</b>这种形态（如 {@code LlmAgentLoop.java:9569} 的
     * {@code @link} 后紧跟换行再接 {@code AgentState#appendMessage}）。
     * 若用同行正则（{@code [^\}\n]} 或 {@code [ \t]+}），这 7 处会被<b>静默漏掉</b>
     * —— 而其中任何一处都可能是悬空的。
     * 故用 {@code \s+}（含换行）+ 不跨 {@code }} + 长度上限 400（防缺右括号时吞掉整段）。
     */
    private static final Pattern LINK_TAG =
        Pattern.compile("\\{@link(?:plain)?\\s+([^}]{0,400}?)\\}", Pattern.DOTALL);

    /**
     * 裸链接标记（无目标）的原始形态，用于「分子 / 分母配平」自检。
     * 它<b>不是</b>链接，产量应当精确等于「裸出现数 − 已解析数」。
     *
     * <p>实测全仓 2 处，都在 {@code ProviderSessionIdWiringGuardTest} 的<b>字符串字面量</b>里
     * （断言失败信息里讲解「javadoc 里的它不算」）。
     *
     * <p>这两处也解释了为什么本类<b>不做</b>「剔除字符串字面量内的伪形态」：实测全仓
     * 该标记出现在真 Java 字符串字面量里的只有这 2 处，且都无目标（不构成链接）。
     * 反过来，粗暴的「按引号奇偶剥字符串」会<b>误伤中文 javadoc 里的引号</b>
     * （实测 6 处误报，形态如 {@code 支持"最小化"，...}）⇒ 剥字符串<b>净害无益</b>。
     * 故本类改为「分子分母精确配平」：两者不等 ⇒ 有链接没被解析到，当场红。
     */
    private static final String BARE_LINK_TAG = "{@link}";

    /**
     * <b>已删类台账</b>（简单名 → 删除提交）。门 1 断言这些类的<b>本体文件</b>不得在
     * {@code backend/src} 下复活。
     *
     * <p>⛔ <b>已知局限（写在这里，不靠口头约定）</b>：本门只能防「<b>已登记</b>的类复活」，
     * <b>不能</b>发现「有人删了一个类但没登记」。要让门 1 真正闭环，必须把「删类」与
     * 「往本台账加一行」做成<b>同一个提交里的同一个动作</b>；否则本门结构性失明。
     *
     * <p>初始两条都是本仓实测：
     * <ul>
     *   <li>{@code CacheSafeParamsHolder} —— {@code 30873b3}（批 5a 删 3 个 compact 态载体）；</li>
     *   <li>{@code RequestContext} —— {@code 2e6f887}（批 3c 删除 MDC 会话态通道）。</li>
     * </ul>
     */
    private static final Map<String, String> DELETED_CLASS_LEDGER = Map.of(
        "CacheSafeParamsHolder", "30873b3",
        "RequestContext", "2e6f887");

    /**
     * <b>已删方法名债务台账</b>（方法名 → 允许的「无豁免前缀」提及条数上限）。
     *
     * <p>⛔ <b>这不是清零门</b>。数字是 2026-09-14 建的实测值。本门只在
     * <b>数量增长</b>时红 —— 即有人<b>新写</b>了一处不带豁免前缀的死引用。
     * 它<b>不</b>声称历史债已清（那需要人工逐句判断现时态 / 过去时，机械做不到，
     * 见类 javadoc「它守不住」第 1 条）。
     *
     * <p>数法：扫 {@code backend/src} 全部源文本，某行命中 {@link #DEBT_HIT_PATTERNS}
     * 且该行所在<b>注释块</b>内不含 {@link #DEBT_EXEMPTION} ⇒ 计入。
     * <b>块级</b>而非行级（审查已证：行级会把块标题写着「已删除…旧实现」的
     * {@code MemoryStorage.java:120} 误判，块标题在上一行）。
     */
    private static final Map<String, Integer> DELETED_METHOD_DEBT_LEDGER = new LinkedHashMap<>();

    static {
        DELETED_METHOD_DEBT_LEDGER.put("currentSessionProjectRoot", 36);
        DELETED_METHOD_DEBT_LEDGER.put("registerAbort", 4);
        DELETED_METHOD_DEBT_LEDGER.put("clearAbort", 4);
        DELETED_METHOD_DEBT_LEDGER.put("currentAbort", 11);
    }

    /**
     * 门 2 的<b>词边界精确形态</b>：命中即计入债务。刻意<b>不用</b>裸 {@code grep 简单名}
     * —— 审查已证裸 grep 会把下面三个<b>活符号</b>算进来，属误伤：
     * <ol>
     *   <li>{@code currentSessionProjectRootOrNull} —— 前者是后者的<b>前缀子串</b>
     *       （不排就把大量<b>合法存活</b>引用误红）；</li>
     *   <li>{@code PromptSuggestion.java:188} 的 {@code currentAbort} 是<b>真字段</b>
     *       （接收者为 {@code this}/{@code PromptSuggestion}）；</li>
     *   <li>{@code registerAbortBubbleUp} 是<b>活符号</b>（{@code StreamingToolExecutor}），
     *       故 {@code registerAbort} 必须要求紧跟 {@code (} 才算命中。</li>
     * </ol>
     */
    private static final Map<String, Pattern> DEBT_HIT_PATTERNS = new LinkedHashMap<>();

    static {
        DEBT_HIT_PATTERNS.put("currentSessionProjectRoot",
            Pattern.compile("currentSessionProjectRoot(?!OrNull)\\b"));
        DEBT_HIT_PATTERNS.put("registerAbort",
            Pattern.compile("\\bregisterAbort\\s*\\("));
        DEBT_HIT_PATTERNS.put("clearAbort",
            Pattern.compile("\\bclearAbort\\s*\\("));
        DEBT_HIT_PATTERNS.put("currentAbort",
            Pattern.compile("(?<!this\\.)(?<!PromptSuggestion\\.)\\bcurrentAbort\\b"));
    }

    /**
     * 门 2 的豁免前缀：命中即视为「已明确标注为历史 / 否定 / 批次留痕」，不计债。
     * 在<b>块级</b>上找（向上找最近的注释块首行，再向下补足块尾）。
     */
    private static final Pattern DEBT_EXEMPTION = Pattern.compile(
        "原|旧实现|旧接线|旧 |已删|已随|不再是|不再经|不再注入|不再默认|WHY 不再|\\[批 |批 \\d|删除|已废弃|曾经");

    /**
     * <b>同名活符号豁免（方法名 → 声明它的文件）</b>：该文件整篇不计入该方法的债务。
     *
     * <p>审查点名的排除要求：「{@code currentAbort} 需排除接收者为 {@code this}/{@code PromptSuggestion}」。
     * 落成「整文件豁免」是刻意从严的<b>实现选择</b>，理由：该文件里的 {@code currentAbort}
     * <b>全部</b>是对<b>真字段</b>的读写（声明 + {@code .set}/{@code .get}/{@code .getAndSet}），
     * 逐行判接收者既脆（换行、链式调用）又难解释；整文件豁免语义清楚、且下面有
     * {@link #gate2_liveFieldExemption_isStillValid()} 兜底（字段一旦被删，豁免立刻失效并转红）。
     */
    private static final Map<String, String> LIVE_FIELD_EXEMPTION_FILES = Map.of(
        "currentAbort",
        "src/main/java/com/nexusai/application/agent/api/PromptSuggestion.java");

    /**
     * 门 3 元守卫的覆盖率基线：五种链接形态各自的实测下限。<b>只许升不许降</b>
     * —— 降 = 有人把正则收窄了。
     *
     * <p>实测（2026-09-14，{@code backend/src/main/java} + {@code src/test/java}，
     * 不含本类自身）：{@code #m} <b>4682</b>、{@code A#m} <b>2297</b>、{@code a.b.C#m} <b>242</b>、
     * {@code A} <b>2366</b>、{@code a.b.C} <b>489</b>，合计 <b>10076</b>。
     * 这里的基线刻意留了余量（只要正则被收窄到单形，差距是数量级的）。
     */
    private static final Map<String, Integer> FORM_COUNT_BASELINE = new LinkedHashMap<>();

    static {
        FORM_COUNT_BASELINE.put("MEMBER_OF_CURRENT", 4000);
        FORM_COUNT_BASELINE.put("MEMBER_OF_SIMPLE", 2000);
        FORM_COUNT_BASELINE.put("MEMBER_OF_QUALIFIED", 200);
        FORM_COUNT_BASELINE.put("TYPE_SIMPLE", 2000);
        FORM_COUNT_BASELINE.put("TYPE_QUALIFIED", 400);
    }

    /**
     * 真链接总量基线（实测 <b>10076</b> = 裸出现 10078 − 2 处无目标伪形态）。
     *
     * <p>刻意留 ~10% 余量：本类真正的「反收窄」武器是
     * {@link #gateMetaGuard_resolutionCoverageIsNotSilentlySkipped()} 里的
     * <b>分子分母精确配平</b>（裸出现数 == 已解析数 + 无目标伪形态数，<b>等式</b>而非下限），
     * 它不会因为有人合法删掉几段 javadoc 而误红，但只要漏解析一条就红。
     */
    private static final int TOTAL_LINK_BASELINE = 9000;

    // ═══════════════════════════════ 门 3 · 悬空链接 ═══════════════════════════════

    /**
     * <b>门 3：javadoc 链接目标的本体必须尚存。</b>
     *
     * <p>建立时实测 <b>17 处悬空</b>（全部在 {@code src/main/java}）。本用例刻意保持红，
     * 红清单即 F-26 的施工单；修完自动转绿。
     *
     * <p>判据 —— <b>两条独立手段交叉</b>（正是审查要求的「删一个符号后的回扫必须用两种独立
     * 检索并交叉」的机械化）：
     * <ol>
     *   <li><b>类型存亡</b>：先查<b>源码索引</b>（{@code backend/src} 下所有 .java 的
     *       package + 声明；权威、<b>不受陈旧 .class 影响</b>），再回落
     *       {@code Class.forName}（覆盖 JDK / Spring / 三方 —— 它们不是我们删的，但不该误红）。</li>
     *   <li><b>成员存亡</b>：用<b>反射</b>判（{@code getDeclared*} + 上溯父类 + 接口），
     *       而<b>不是</b>源码正则。
     *       WHY：实测源码正则在成员级别有 <b>2286 条假阳性</b>（Lombok 生成的 getter、
     *       record 访问器、继承来的成员、常量，正则全看不见）⇒ 源码正则判成员<b>不可用</b>。</li>
     * </ol>
     */
    @Test
    @DisplayName("门3：javadoc 链接不得悬空（目标本体必须尚存）—— 建立时实测 60 处，红清单即施工单")
    void gate3_noDanglingJavadocLinks() {
        ScanResult result = scanBackend();

        assertThat(result.dangling())
            .as("""

                ╔══════════════════════════════════════════════════════════════════════════╗
                ║ 门 3 红：javadoc 链接指向了已不存在的本体。                               ║
                ║ 修法（F-26 既定方向，二选一）：                                           ║
                ║   ① 目标已改名 ⇒ 改成新名字（如 currentSessionProjectRoot →               ║
                ║      currentSessionProjectRootOrNull）；                                ║
                ║   ② 目标已整个删除 ⇒ 降级为 {@code} 纯文本，⛔ 不新造符号。                ║
                ║ 下面每一条都给了 文件:行 + 原始 tag + 判定理由。                           ║
                ╚══════════════════════════════════════════════════════════════════════════╝

                悬空明细（%d 条）：
                %s""", result.dangling().size(), formatDangling(result.dangling()))
            .isEmpty();
    }

    /**
     * <b>门 3 元守卫 / 解析覆盖率自检</b>：把「本类声称解析了全部链接」变成可证伪。
     *
     * <p>审查原话：「名单驱动的清零验收<b>必须反向自证</b>」。本用例断言四件事：
     * <ol>
     *   <li>五种链接形态<b>各自计数 ≥ 基线</b>（谁把正则收窄成单形，当场红）；</li>
     *   <li>总量 ≥ {@link #TOTAL_LINK_BASELINE}；</li>
     *   <li><b>分子分母精确配平</b>：裸标记出现数 == 已解析数 + 无目标伪形态数。
     *       不等 ⇒ 有链接被静默漏掉（⛔ 本仓铁律「不许静默失效」，不许跳过）；</li>
     *   <li><b>解析不到的接收者 == 0</b>（fail loud）：某个链接的接收者既不在源码索引、
     *       也 {@code Class.forName} 不到 ⇒ 本类「不知道它存不存在」——
     *       <b>必须红</b>，不能当「外部的、跳过」处理。</li>
     * </ol>
     */
    @Test
    @DisplayName("门3 元守卫：解析覆盖率自检（形态计数 / 总量 / 分子分母配平 / 零静默跳过）")
    void gateMetaGuard_resolutionCoverageIsNotSilentlySkipped() {
        ScanResult r = scanBackend();

        assertThat(r.formCounts())
            .as("五种链接形态必须<b>全部</b>出现在结果里（多出一种 = 解析逻辑变了，也要显式处置）")
            .containsOnlyKeys(FORM_COUNT_BASELINE.keySet());
        for (Map.Entry<String, Integer> e : FORM_COUNT_BASELINE.entrySet()) {
            assertThat(r.formCounts().get(e.getKey()))
                .as("形态 [%s] 的解析计数不得低于基线 %d（低于即正则被收窄）。"
                    + "基线来源见 FORM_COUNT_BASELINE javadoc；"
                    + "清单只许升不许降 —— 降了就是漏检。", e.getKey(), e.getValue())
                .isGreaterThanOrEqualTo(e.getValue());
        }

        assertThat(r.links())
            .as("解析到的链接总量不得低于基线 %d", TOTAL_LINK_BASELINE)
            .hasSizeGreaterThanOrEqualTo(TOTAL_LINK_BASELINE);

        assertThat(r.rawOccurrences())
            .as("""
                裸标记出现数必须精确等于「已解析 + 无目标伪形态数」。
                不等 ⇒ 有链接形态本类解析不到（例如『标记后紧跟换行』实测 7 处），
                它们会被静默漏检 —— 本仓铁律「不许静默失效」，故此处 fail loud。
                修法：放宽 LINK_TAG，⛔ 不是放宽本断言。""")
            .isEqualTo(r.links().size() + r.bareTagOccurrences());

        assertThat(r.unresolvedReceivers())
            .as("接收者解析不到 = 本类不知道它存不存在 ⇒ 必须显式报告，⛔ 不得静默跳过。"
                + "若这些其实是合法存在的外部符号，请把它的解析途径补进 receiverCandidates()。")
            .isEmpty();
    }

    /**
     * <b>门 3 反向实验</b>：证明本类不是恒绿装置。
     *
     * <p>本仓已判定「纯否定断言恒绿」是零鉴别力写法（{@link #gate3_noDanglingJavadocLinks()}
     * 在「一条链接都没解析到」时也会绿）。故此处<b>在真实文件系统</b>上
     * （{@code @TempDir}，非内存假夹具）写一个 .java，内含三个 needle：
     * <ul>
     *   <li><b>悬空 needle</b> {@code NoSuchSymbolXyzQq} —— 必须被列红；</li>
     *   <li><b>存活 needle</b> {@code SessionKeys#NO_SESSION}（真类真字段，走 import + 反射解析）
     *       —— 必须<b>不</b>被列红、也<b>不</b>落进「解析不到」；</li>
     *   <li><b>多行 needle</b>（标记后换行）—— 必须被解析到（防「静默漏 7 处」复发）。</li>
     * </ul>
     * 用<b>与门 3 完全相同的扫描器</b>{@code scan(...)} 跑，所以它同时是
     * 「扫描器可用」与「两个方向都能判」的证据 —— 不存在「自检证明的是另一个扫描器」的漏洞。
     */
    @Test
    @DisplayName("门3 自检：真实文件系统注入 needle —— 悬空必红 / 存活必不误红 / 多行必被解析")
    void gate3_reverseExperiment_realFilesystemInjectedNeedles(@TempDir Path tmp) throws IOException {
        Path pkgDir = tmp.resolve("src/main/java/com/nexusai/guardprobe");
        Files.createDirectories(pkgDir);
        Files.writeString(pkgDir.resolve("GuardProbeSample.java"),
            "package com.nexusai.guardprobe;\n"
                + "\n"
                + "import com.nexusai.common.SessionKeys;\n"
                + "\n"
                + "/**\n"
                + " * 悬空 needle（本类<b>必须</b>列红它）：{" + "@link NoSuchSymbolXyzQq}\n"
                + " * 存活 needle（本类<b>不该</b>红它）：{" + "@link SessionKeys#NO_SESSION}\n"
                + " * 多行 needle（标记后换行，实测全仓 7 处此形态）：\n"
                + " * {" + "@link\n"
                + " *      SessionKeys#NO_SESSION}\n"
                + " */\n"
                + "class GuardProbeSample {\n"
                + "}\n");

        ScanResult r = scan(tmp, List.of(tmp.resolve("src/main/java")), false);

        assertThat(r.links())
            .as("3 条链接必须全部被解析到（含多行那条 —— 否则又会静默漏掉实测的 7 处形态）")
            .hasSize(3);
        assertThat(r.dangling())
            .as("注入的悬空 needle 必须被列红 ⇒ 证明本类不是恒绿装置")
            .hasSize(1);
        assertThat(r.dangling().get(0).raw())
            .as("被列红的那条必须恰好是注入的悬空 needle，且原始 tag 被完整带回（便于按红清单施工）")
            .contains("NoSuchSymbolXyzQq");
        assertThat(r.dangling().get(0).relPath())
            .as("红清单必须带真实文件路径（相对扫描根）")
            .isEqualTo("src/main/java/com/nexusai/guardprobe/GuardProbeSample.java");
        assertThat(r.unresolvedReceivers())
            .as("存活的第 2 条必须经 import + 反射解析成功，既不算悬空也不算『解析不到』")
            .isEmpty();
    }

    /**
     * <b>门 3 元数盲区反向实验（两个方向都必须成立）</b>。
     *
     * <p>WHY 单独立一条：{@link #declaredCallable} 之前<b>只比方法名</b>，对「同名重载被删」
     * <b>结构性失明</b> —— 删掉 {@code m()} 只留 {@code m(X)} 后，{@code {@link #m()}}
     * 会被那个 1 参重载解析掉 ⇒ 永不判为悬空。实测（2026-09-15，改前）：在
     * {@code SubagentTool} 的 javadoc 里放 {@code {@link #agentRegistry()}}（0 参已删、
     * 只剩 {@code agentRegistry(String)}），本类 <b>7/7 仍绿</b>。
     *
     * <p>本用例用<b>同一个扫描器</b>{@code scan(...)}在 {@code @TempDir} 合成树上钉住两个方向：
     * <ul>
     *   <li><b>必须红</b>：{@code {@link StringBuilder#append()}} —— {@code StringBuilder}
     *       只有 ≥1 参的 {@code append} 重载，0 参<b>不存在</b>；</li>
     *   <li><b>必须绿</b>：{@code {@link StringBuilder#append(String)}}（元数对得上）
     *       与 {@code {@link StringBuilder#append}}（<b>未写</b>形参表 ⇒ 元数不参与判定）。
     *       后两条是防「带括号就红」那种偷懒改法的反向闸 —— 少了它们，
     *       把门改窄成「有括号一律判死」也能过。</li>
     * </ul>
     *
     * <p>外部对照（独立于本类）：真实 {@code javadoc -Xdoclint:all} 对
     * {@code {@link StringBuilder#append()}} 报 {@code error: reference not found}，
     * 而对 {@code {@link StringBuilder#append(String)}} 无告警
     * ⇒ 本门的元数判据与 javadoc 自身的解析口径一致，不是本类自造的严格性。
     * （同一实验里 {@code {@link Path#toRealPath()}} / {@code {@link Files#isDirectory(Path)}}
     * 也报 {@code reference not found} —— 变参简写 <b>不</b>构成合法引用，
     * 故它们被判红是对的，不是误红。）
     */
    @Test
    @DisplayName("门3 自检：元数盲区 —— 同名重载被删必须红 / 元数对得上与未写形参表必须绿")
    void gate3_reverseExperiment_arityMismatchIsDangling_andMatchingArityIsNot(@TempDir Path tmp)
        throws IOException {
        Path pkgDir = tmp.resolve("src/main/java/com/nexusai/guardprobe");
        Files.createDirectories(pkgDir);
        Files.writeString(pkgDir.resolve("GuardProbeAritySample.java"),
            "package com.nexusai.guardprobe;\n"
                + "\n"
                + "/**\n"
                + " * 元数盲区 needle（本类<b>必须</b>列红）：{" + "@link StringBuilder#append()}\n"
                + " * 合法 needle（本类<b>不该</b>红）：{" + "@link StringBuilder#append(String)}\n"
                + " * 无元数 needle（本类<b>不该</b>红）：{" + "@link StringBuilder#append}\n"
                + " */\n"
                + "class GuardProbeAritySample {\n"
                + "}\n");

        ScanResult r = scan(tmp, List.of(tmp.resolve("src/main/java")), false);

        assertThat(r.links())
            .as("3 条链接必须全部被解析到 —— 元数判据不能把形参表吃掉（形参表在 toLink 里被剥掉前"
                + "必须先把个数记进 Link#arity；多参形参表里的空白也不算 label 分隔符）")
            .hasSize(3);
        assertThat(r.dangling())
            .as("""
                元数盲区 needle 必须被列红：{@link StringBuilder#append()} 的 0 参重载不存在，
                旧实现（只比方法名）会被 1 参重载解析掉 ⇒ 恒绿 ⇒ 对「同名重载被删」失明。
                实测对照：真实 javadoc -Xdoclint:all 对本条报 "reference not found"。""")
            .hasSize(1);
        assertThat(r.dangling().get(0).raw())
            .as("被列红的必须<b>恰好</b>是 0 参那条 —— 另两条同样带括号，必须保持绿"
                + "（本断言即「不许把门改窄成『带括号就红』」的反向闸）")
            .isEqualTo("StringBuilder#append()");
        assertThat(r.dangling().get(0).reason())
            .as("红清单必须说清是**元数**对不上（同名方法存在），而不是「该成员整个不存在」——"
                + "两者的修法不同（前者可改成现存重载名，后者只能降级为 {@code}）")
            .contains("元数");
        assertThat(r.unresolvedReceivers())
            .as("两条合法 needle 必须解析成功，既不算悬空也不算『解析不到』")
            .isEmpty();
    }

    /**
     * <b>门 3 范围自检</b>：扫描集合必须严格限定在 {@code backend/src}，
     * 且排除集合<b>恰为本类自己那 1 个文件</b>（排除集变大 = 有人偷偷跳过文件）。
     *
     * <p>WHY 范围：实测仓库根下 70 个 {@code SessionProjectRoot*.java} 副本，
     * 68 个来自 40 个 worktree。扫描根一旦被抬高，护栏会被几十份<b>过期的</b>副本冲垮
     * （副本里那些已删符号仍是活的 ⇒ 真悬空被判绿，或反之大量误红）。
     */
    @Test
    @DisplayName("门3 范围自检：扫描根严格限定 backend/src，排除集恰为 1 个文件（本类自身）")
    void gateMetaGuard_scopeIsExact_andNothingIsSilentlySkipped() {
        ScanResult r = scanBackend();

        assertThat(r.scannedFiles())
            .as("扫描到的源文件数必须远大于 0（否则下面的断言全是空跑 = 假绿）")
            .hasSizeGreaterThan(2000)
            .allSatisfy(f -> assertThat(SRC_ROOT_RELATIVE_DIRS.stream()
                .anyMatch(root -> f.startsWith(root + "/")))
                .as("扫描文件必须落在 %s 下（实际：%s）", SRC_ROOT_RELATIVE_DIRS, f)
                .isTrue())
            .allSatisfy(f -> assertThat(f)
                .as("扫描集不得含 .claude/worktrees（范围外扩会被 40 个过期 worktree 副本冲垮）")
                .doesNotContain(".claude/")
                .doesNotContain("worktrees/"));

        assertThat(r.scannedFiles())
            .as("本类刻意不自扫（它必须能谈论『javadoc 链接』这件事本身），"
                + "故它自己不得出现在扫描集合里")
            .doesNotContain(SELF_RELATIVE_PATH);
        assertThat(r.excludedSelfFiles())
            .as("排除集合必须<b>恰好</b>是本类自己那 1 个文件。"
                + "> 1 说明排除逻辑误伤；= 0 说明排除没生效（自扫会污染分子分母配平自检）")
            .isEqualTo(1);
    }

    // ═══════════════════════════════ 门 1 · 已删类本体 ═══════════════════════════════

    /**
     * <b>门 1：已删类的本体文件不得复活。</b>
     *
     * <p>⛔ 已知局限（写进 javadoc，不靠口头约定）：台账驱动 ⇒ 只防「已登记的类复活」，
     * <b>不能</b>发现「新删除未登记」。见 {@link #DELETED_CLASS_LEDGER}。
     *
     * <p>本用例同时是<b>门 1 的反向自检</b>：既断言已删类找不到，也断言「一个<b>活着</b>的类
     * 一定找得到」—— 否则「一个都找不到」与「查找器坏了」不可区分
     * （本仓已判定零鉴别力写法有害）。
     */
    @Test
    @DisplayName("门1：已删类本体不得复活（台账驱动，含反向自检证明查找器可红可绿）")
    void gate1_deletedClassBodies_doNotReappear() {
        Path backend = backendRoot();

        // 反向自检：查找器本身可用 —— 一个确定存在的类必须被找到。
        assertThat(findBodies(backend, "AutoMemPaths"))
            .as("反向自检：AutoMemPaths 本体必须找得到 ⇒ 证明下面的『找不到』不是查找器坏了")
            .isNotEmpty();

        for (Map.Entry<String, String> e : DELETED_CLASS_LEDGER.entrySet()) {
            String name = e.getKey();
            assertThat(findBodies(backend, name))
                .as("""
                    门 1 红：已删类 [%s]（删于 %s）的本体文件在 backend/src 下复活了。
                    ① 若这是<b>有意重新引入</b>：先确认它不是把已收敛的环境态会话槽又搬回来
                       （本仓铁律：会话态一律不得经 ThreadLocal/MDC 读；一 JVM 多会话，
                       子线程读不到，回放也不算合规）。确认后把该条从 DELETED_CLASS_LEDGER
                       删除并说明理由。
                    ② 若这是<b>误建同名类</b>：改名 —— 不要占用死符号的名字，
                       否则死引用判别会串（无法区分『真复活』与『叫同一个名字的新东西』）。""",
                    name, e.getValue())
                .isEmpty();
        }
    }

    // ═══════════════════════════════ 门 2 · 已删方法名提及债务 ═══════════════════════════════

    /**
     * <b>门 2：已删方法名的注释提及不得超过债务台账。</b>
     *
     * <h2>⛔ 这是「防增长门」，不是「清零门」—— 说清楚，否则就是「声称守护 X 实际守不住」</h2>
     * <p>本仓前科：护栏的断言本身对，错的是「它守护了什么」的<b>声明</b>。故此处明确：
     * 本门<b>只</b>保证「<b>新</b>写的死引用会被抓住」。它<b>不</b>保证历史债已清，
     * 也<b>不</b>能判定现时态 / 过去时 —— 后者需要语义理解，不可机械判定。
     * 历史债由 F-26 / F-14 / F-25 按门 3 的红清单 + 人工判断清理；
     * 清完一处应<b>下调</b> {@link #DELETED_METHOD_DEBT_LEDGER} 的对应数字（只许降不许升）。
     *
     * <p>三条词边界排除（否则误伤同名<b>活</b>符号）见 {@link #DEBT_HIT_PATTERNS}。
     */
    @Test
    @DisplayName("门2：已删方法名的『无豁免前缀』提及数不得超过债务台账（防增长，非清零门）")
    void gate2_deletedMethodMentions_doNotExceedDebtLedger() {
        Map<String, Integer> actual = countUnexemptedMentions(backendRoot());

        for (Map.Entry<String, Integer> e : DELETED_METHOD_DEBT_LEDGER.entrySet()) {
            String sym = e.getKey();
            int budget = e.getValue();
            int got = actual.getOrDefault(sym, 0);
            assertThat(got)
                .as("""
                    门 2 红：已删方法名 [%s] 的「无豁免前缀」注释提及数 %d 超过台账上限 %d。
                    两种可能：
                      ① 你<b>新写</b>了一处死引用 ⇒ 改写成过去时/否定式并带豁免前缀
                         （原 / 旧实现 / 已删 / [批 N] ……），或直接删掉；
                      ② 你<b>清理</b>了历史债 ⇒ 把 DELETED_METHOD_DEBT_LEDGER 的 [%s]
                         下调到 %d（本台账只许降不许升）。
                    命中形态（已排除同名活符号）：%s""",
                    sym, got, budget, sym, got, DEBT_HIT_PATTERNS.get(sym).pattern())
                .isLessThanOrEqualTo(budget);
        }
    }

    /**
     * 门 2 的<b>豁免自检</b>：{@link #LIVE_FIELD_EXEMPTION_FILES} 里的豁免必须仍然成立。
     *
     * <p>WHY：豁免是「因为这个同名符号还活着，所以别数它」。若那个活符号<b>被删了</b>，
     * 豁免就变成「静默放过真正的死引用」—— 本仓铁律禁止静默失效。故此处用<b>反射</b>
     * 断言被豁免的字段仍然存在；一旦被删，本用例当场转红，逼人撤掉豁免。
     */
    @Test
    @DisplayName("门2 自检：同名活符号豁免仍成立（被豁免的字段一旦被删，豁免即失效并转红）")
    void gate2_liveFieldExemption_isStillValid() throws IOException {
        for (Map.Entry<String, String> e : LIVE_FIELD_EXEMPTION_FILES.entrySet()) {
            String fieldName = e.getKey();
            String relPath = e.getValue();
            Path file = backendRoot().resolve(relPath);
            assertThat(Files.isRegularFile(file))
                .as("被豁免的文件必须存在（路径漂移会让豁免静默失效）: %s", relPath)
                .isTrue();

            // 从文件里取类型 FQN（package + 文件名），再用反射确认字段仍在。
            String text = Files.readString(file).replace("\r\n", "\n");
            Matcher pkgM = Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE)
                .matcher(text);
            assertThat(pkgM.find()).as("被豁免文件必须声明 package").isTrue();
            String fn = relPath.substring(relPath.lastIndexOf('/') + 1);
            String fqn = pkgM.group(1) + "." + fn.substring(0, fn.length() - ".java".length());

            Class<?> cls = load(fqn);
            assertThat(cls)
                .as("被豁免类型必须可载入（否则豁免失去意义）: %s", fqn)
                .isNotNull();
            assertThat(hasDeclaredField(cls, fieldName))
                .as("门 2 自检红：豁免名单说 [%s] 在本文件里是**活字段**，但 %s 已经没有这个字段了。"
                    + "⇒ 豁免已失效（它会静默放过真正的死引用）。请撤掉 LIVE_FIELD_EXEMPTION_FILES "
                    + "里的这一条。", fieldName, fqn)
                .isTrue();
        }
    }

    private static boolean hasDeclaredField(Class<?> cls, String name) {
        for (Class<?> k = cls; k != null; k = k.getSuperclass()) {
            try {
                for (Field f : k.getDeclaredFields()) {
                    if (f.getName().equals(name)) {
                        return true;
                    }
                }
            } catch (Throwable ignored) {
                return false;
            }
        }
        return false;
    }

    // ═══════════════════════════════ 扫描器 ═══════════════════════════════

    /**
     * 一条 javadoc 链接。{@code receiver} 为空表示「本文件成员」形态（{@code #member}）。
     *
     * <p>{@code params} = 链接里<b>写明的形参表原文</b>（{@code #m()} ⇒ {@code ""}、
     * {@code #m(A, B)} ⇒ {@code "A, B"}）；{@code null} = <b>未写形参表</b>（{@code #m}）
     * ⇒ {@link #arity()} 为 {@code null}，元数不参与判定。
     *
     * <p>元数是「同名重载被删」这一盲区的判据：{@code #m()} 在目标只剩 {@code m(X)} 时必须判死，
     * 而只比方法名的旧实现会把它解析掉（见 {@link #declaredCallable}）。
     * 保留原文（而非只留个数）是为了让红清单能<b>照原样</b>写出悬空链接。
     */
    private record Link(
        String relPath, int line, String raw, String receiver, String member,
        String params, String form) {

        /** 链接写明的形参个数；{@code null} = 未写形参表（元数不参与判定）。 */
        Integer arity() {
            return params == null ? null : countParams(params);
        }

        /** 供红清单 / 失败信息展示的成员写法（{@code m}、{@code m()}、{@code m(A, B)}）。 */
        String memberLabel() {
            return params == null ? member : member + "(" + params + ")";
        }
    }

    /** 一条悬空链接（红清单的一行）。 */
    private record Dangling(String relPath, int line, String raw, String reason) {}

    /** 一次扫描的全部产物。 */
    private record ScanResult(
        List<String> scannedFiles,
        List<Link> links,
        List<Dangling> dangling,
        List<String> unresolvedReceivers,
        Map<String, Integer> formCounts,
        int rawOccurrences,
        int bareTagOccurrences,
        int excludedSelfFiles) {}

    /** 扫描 {@code backend/src/{main,test}/java} 下全部 .java（排除本类自身）。 */
    private static ScanResult scanBackend() {
        Path backend = backendRoot();
        return scan(backend, SRC_ROOT_RELATIVE_DIRS.stream().map(backend::resolve).toList(), true);
    }

    /**
     * 扫描器本体（扫 {@code root} 下 {@code srcRoots} 里的所有 .java）。
     *
     * <p>{@code root} 参数化是为了让反向实验能在 {@code @TempDir} 上跑<b>同一个</b>扫描器
     * —— 门 3 与自检共用同一份代码。
     *
     * <p>{@code enforceRepoChecks}：是否启用「仓内命名空间 / 陈旧字节码」两条纪律。
     * 只对<b>真实 backend 树</b>启用 —— 反向实验扫的是 {@code @TempDir} 合成树，
     * 那时「源码索引」里没有 {@code com.nexusai.common.SessionKeys}，
     * 若照样跑陈旧字节码检查，会把**合法存活**的注入 needle 判红（实测踩过）。
     *
     * <p>每次调用都从真实文件系统重扫（不缓存）：本类只有 8 个用例，全量重扫仍在秒级，
     * 换来的是「断言与磁盘一致」的构造性保证。
     */
    private static ScanResult scan(Path root, List<Path> srcRoots, boolean enforceRepoChecks) {
        // ── 1. 收集源文件（排除本类自身）──
        List<SourceFile> sources = new ArrayList<>();
        int excludedSelf = 0;
        for (Path srcRoot : srcRoots) {
            assertThat(Files.isDirectory(srcRoot))
                .as("扫描根必须存在（fail loud：路径漂移不许静默变成空扫）: %s", srcRoot)
                .isTrue();
            List<Path> files;
            try (Stream<Path> walk = Files.walk(srcRoot)) {
                files = walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
            } catch (IOException e) {
                throw new UncheckedIOException("扫描失败: " + srcRoot, e);
            }
            for (Path p : files) {
                SourceFile sf = SourceFile.read(root, p);
                if (sf.relPath().equals(SELF_RELATIVE_PATH)) {
                    excludedSelf++;
                    continue;
                }
                sources.add(sf);
            }
        }
        assertThat(sources).as("源文件数必须 > 0（空扫 = 假绿）").isNotEmpty();

        TypeIndex index = TypeIndex.build(sources);

        // ── 2. 抽取全部链接 ──
        List<Link> links = new ArrayList<>();
        int rawOccurrences = 0;
        int bareTagOccurrences = 0;
        for (SourceFile sf : sources) {
            rawOccurrences += count(sf.text(), "{@link");
            bareTagOccurrences += count(sf.text(), BARE_LINK_TAG);
            Matcher m = LINK_TAG.matcher(sf.text());
            while (m.find()) {
                Link link = toLink(sf, m.start(), m.group(1));
                if (link != null) {
                    links.add(link);
                }
            }
        }

        // ── 3. 逐条判定 ──
        List<Dangling> dangling = new ArrayList<>();
        List<String> unresolved = new ArrayList<>();
        Map<String, Integer> formCounts = new TreeMap<>();
        for (Link link : links) {
            formCounts.merge(link.form(), 1, Integer::sum);
            String reason = verify(link, index, enforceRepoChecks);
            if (reason == null) {
                continue;
            }
            if (reason.startsWith("UNRESOLVED")) {
                unresolved.add(link.relPath() + ":" + link.line() + "  " + link.raw() + "  <- " + reason);
            } else {
                dangling.add(new Dangling(link.relPath(), link.line(), link.raw(), reason));
            }
        }
        dangling.sort(Comparator.comparing(Dangling::relPath).thenComparingInt(Dangling::line));
        unresolved.sort(Comparator.naturalOrder());

        return new ScanResult(
            sources.stream().map(SourceFile::relPath).toList(),
            links, dangling, unresolved, formCounts,
            rawOccurrences, bareTagOccurrences, excludedSelf);
    }

    /**
     * 把「链接标签后的原始文本」切成 (receiver, member)；切不出目标则返回 {@code null}。
     *
     * <p>切法（覆盖实测的全部形态）：
     * <ol>
     *   <li>丢掉 link <b>label</b>（{@code X 显示文字} 里空白之后的部分）—— 取首个空白前的 token；</li>
     *   <li>在<b>第一个</b> {@code #} 处切 receiver / member；</li>
     *   <li>member 去掉<b>形参表</b>（{@code #m(A, B)} → {@code m}）与链式尾巴
     *       （{@code #m()#n()} → {@code m}；实测 {@code CommandController.java:821} 有此形态）。
     *       ⚠️ 形参表<b>在剥掉之前先数量记进 {@link Link#arity()}</b>（{@code #m()} ⇒ 0、
     *       {@code #m(A, B)} ⇒ 2、未写形参表 ⇒ {@code null}）—— 只有方法名会让
     *       「同名重载被删」判不出来，见 {@link #declaresCallable}。</li>
     * </ol>
     *
     * <p><b>两处刻意规范化（已登记，不是静默跳过）</b>：
     * <ol>
     *   <li><b>剥掉捕获文本的行首 {@code *}</b>。多行形态下捕获到的是
     *       {@code "\n     * BundledSkillEnabledGates#isAutoMemoryEnabled()"}，
     *       {@code strip()} 之后首个 token 是 {@code "*"}（星号不是空白字符）
     *       ⇒ 不剥就整条解析成 receiver = {@code *}。实测 4 处此形态
     *       （{@code ExtractMemoriesController.java:82} 等）。</li>
     *   <li><b>无 {@code #} 的接收者若有尾随形参表，剥掉它</b>。实测
     *       {@code DefaultOAuthHttpClient.java:38} 写的是
     *       {@code {@link McpAuth.MCPRefreshFailed(REQUEST_FAILED)}}（把<b>值</b>当成了形参类型，
     *       合法写法应为 {@code McpAuth#MCPRefreshFailed}）。javadoc 对此会报
     *       「reference not found」（无 {@code #} 时 {@code (...)} 不被当作成员选择器）。
     *       本类的职责是<b>死符号</b>探测，不是 javadoc lint ⇒ 按作者本意解析到嵌套类型
     *       {@code McpAuth.MCPRefreshFailed}（该类型<b>确实存在</b>），
     *       并把这条「形参表写法不合法」作为<b>已知问题另行登记</b>，不混进死符号红清单。</li>
     * </ol>
     */
    private static Link toLink(SourceFile sf, int matchStart, String captured) {
        String body = stripLeadingCommentMarkers(captured);
        if (body.isEmpty()) {
            return null;   // 无目标伪形态（由分子分母配平断言负责发现）
        }
        // 丢 label（`{@link X 显示文字}` ⇒ 取首个空白前的 token）。
        // ⚠️ 但形参表里的空白**不是** label 分隔符：`{@link #list(boolean, String, String)}`
        //    只要在第一个空白处切，就得到 `#list(boolean,` —— 形参表被腰斩，
        //    元数会从 3 被误数成 2（实测：本仓 {@code CommandController.java:224} 等
        //    <b>30+ 处</b>多参链接全部误判）。故只在**括号深度 0** 处切。
        int sp = indexOfTopLevelWhitespace(body);
        if (sp >= 0) {
            body = body.substring(0, sp);
        }
        if (body.isEmpty() || body.startsWith("<") || body.startsWith("http")) {
            return null;
        }
        String receiver = body;
        String member = null;
        String params = null;
        int hash = body.indexOf('#');
        if (hash >= 0) {
            receiver = body.substring(0, hash);
            member = body.substring(hash + 1);
            int paren = member.indexOf('(');
            if (paren >= 0) {
                // ⚠️ 形参表必须在这里取走 —— 下一行 `member = member.substring(0, paren)`
                //    会把括号内容丢掉。旧实现正是在这里丢了元数 ⇒ 对「同名重载被删」结构性失明。
                int close = member.indexOf(')', paren);
                params = member.substring(paren + 1, close < 0 ? member.length() : close);
                member = member.substring(0, paren);
            }
            int hash2 = member.indexOf('#');
            if (hash2 >= 0) {
                member = member.substring(0, hash2);
            }
            member = member.strip();
            if (member.isEmpty()) {
                member = null;
            }
        } else {
            int paren = receiver.indexOf('(');
            if (paren >= 0) {
                receiver = receiver.substring(0, paren);
            }
        }
        receiver = receiver.strip();
        String form;
        if (member != null) {
            form = receiver.isEmpty() ? "MEMBER_OF_CURRENT"
                : (receiver.contains(".") ? "MEMBER_OF_QUALIFIED" : "MEMBER_OF_SIMPLE");
        } else {
            form = receiver.contains(".") ? "TYPE_QUALIFIED" : "TYPE_SIMPLE";
        }
        int line = sf.text().substring(0, matchStart).split("\n", -1).length;
        return new Link(sf.relPath(), line, captured.strip(), receiver, member, params, form);
    }

    /**
     * 形参表文本 → 形参<b>个数</b>（{@code "a, b"} ⇒ 2；{@code ""} ⇒ 0）。
     *
     * <p>只数<b>顶层</b>逗号：泛型里的逗号（{@code Map<String, String>}）与嵌套括号/数组
     * （{@code int[]}）不算分隔符，否则 {@code {@link #m(Map<String, String>)} }（真 1 参）
     * 会被数成 2 参 ⇒ 元数判据反向失效。
     *
     * <p>⛔ 刻意<b>只比个数、不比类型名</b>：类型名要面对泛型擦除、全限定 / 简单名混写、
     * 数组 / 变参后缀、嵌套类名等一堆归一化问题，误红代价远高于收益（本仓「假红陷阱」前科）。
     * ⇒ 「元数对得上但类型名漂移」是本门<b>已知、已登记</b>的漏判边界，见类 javadoc。
     */
    private static int countParams(String paramList) {
        String s = paramList.strip();
        if (s.isEmpty()) {
            return 0;
        }
        int depth = 0;
        int n = 1;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '<' || c == '(' || c == '[') {
                depth++;
            } else if (c == '>' || c == ')' || c == ']') {
                depth--;
            } else if (c == ',' && depth <= 0) {
                n++;
            }
        }
        return n;
    }

    /**
     * 元数对不上时，给红清单补一句<b>可施工</b>的说明（「同名重载被删」与「整个成员不存在」
     * 的修法不同：前者按 F-26 ① 改成现存重载名或 ② 降级为 {@code}，后者只能 ②）。
     * 仅在链接写明了形参表（{@code arity != null}）时才产出。
     */
    private static String arityHint(List<String> candidateFqns, String member, Integer arity) {
        if (arity == null) {
            return "";
        }
        java.util.TreeSet<Integer> existing = new java.util.TreeSet<>();
        int loaded = 0;
        for (String fqn : candidateFqns) {
            Class<?> c = load(fqn);
            if (c == null) {
                continue;
            }
            loaded++;
            collectCallableArities(c, member, existing);
        }
        if (loaded == 0) {
            return "（链接写明了形参个数 " + arity + "）";
        }
        if (existing.isEmpty()) {
            return "（同名可调用成员<b>整个</b>都不存在 —— 不止是元数问题）";
        }
        return "（候选类型上同名方法/构造器（含嵌套）的形参个数 = " + existing
            + "，链接写的是 " + arity + " ⇒ 元数对不上，疑为「同名重载被删」）";
    }

    /** 收集 {@code cls}（含嵌套，不含父类链）里名为 {@code member} 的可调用成员的形参个数。 */
    private static void collectCallableArities(Class<?> cls, String member, Set<Integer> out) {
        if (cls == null || member == null) {
            return;
        }
        try {
            for (Method m : cls.getDeclaredMethods()) {
                if (m.getName().equals(member)) {
                    out.add(m.getParameterCount());
                }
            }
        } catch (Throwable ignored) {
            // 提示性产物，拿不到就不补。
        }
        try {
            for (java.lang.reflect.Constructor<?> ctor : cls.getDeclaredConstructors()) {
                if (cls.getSimpleName().equals(member)) {
                    out.add(ctor.getParameterCount());
                }
            }
        } catch (Throwable ignored) {
            // 同上。
        }
        try {
            for (Class<?> nested : cls.getDeclaredClasses()) {
                collectCallableArities(nested, member, out);
            }
        } catch (Throwable ignored) {
            // 同上。
        }
    }

    /** {@code null} = 目标本体尚存；非 null = 悬空理由，或 {@code UNRESOLVED ...}。 */
    private static String verify(Link link, TypeIndex index, boolean enforceRepoChecks) {
        if (link.receiver().isEmpty()) {
            if (link.member() == null) {
                return null;
            }
            // 「本文件成员」形态：接收者 = 本文件声明的类型（顶层 + 嵌套，取并集）。
            // ⚠️ 并集是刻意「放行」的：多候选只要有一个声明了该成员就算存在。
            //    代价是可能漏判（不同嵌套类的私有成员混淆），收益是<b>不误红</b>
            //    —— 对「红清单要能直接施工」而言，误红比漏判更贵（本仓有「假红陷阱」前科）。
            List<String> declared = index.declaredCandidateFqns(link.relPath());
            for (String fqn : declared) {
                if (anyNestedDeclares(load(fqn), link.member(), link.arity())) {
                    return null;
                }
            }
            return "本文件声明的类型里找不到成员 [#" + link.memberLabel() + "]"
                + arityHint(declared, link.member(), link.arity()) + "（候选：" + declared + "）";
        }

        // 包引用（javadoc 允许 {@link some.pkg}）—— 不是类型，不该按类型判存亡。
        if (index.isKnownPackage(link.receiver())) {
            return null;
        }

        List<String> candidates = index.receiverCandidates(link.receiver(), link.relPath());
        boolean sawReceiver = false;
        boolean memberFound = false;
        for (String candidate : candidates) {
            Class<?> cls = load(candidate);
            if (cls == null) {
                continue;
            }
            if (enforceRepoChecks && index.isRepoNamespace(candidate)
                && !index.isSourceDeclared(candidate)) {
                // 载得到但源码里没有 ⇒ 陈旧字节码（孤儿 .class）或已删本体。
                // ⛔ 此时**不能**当作存在 —— 本仓已因「陈旧索引/陈旧字节码把已删符号报成存在」
                //    吃过一次亏（GitNexus 至今把 CacheSafeParamsHolder 报成存在）。
                return "接收者 [" + link.receiver() + "] 在 classpath 上载得到 ["
                    + candidate + "]，但 backend/src 源码里没有这个类型 ⇒ "
                    + "陈旧字节码（孤儿 .class，需 mvn clean）或本体已删。";
            }
            sawReceiver = true;
            if (link.member() == null || anyNestedDeclares(cls, link.member(), link.arity())) {
                memberFound = true;
                break;
            }
        }
        if (memberFound) {
            return null;
        }
        if (sawReceiver) {
            return "type [" + link.receiver() + "] 存在，但成员 [" + link.memberLabel()
                + "] 找不到（反射不认；该成员疑已删除）"
                + arityHint(candidates, link.member(), link.arity());
        }

        // 无 {@code #} 的**限定成员引用**兜底：javadoc 里 {@code {@link A.B}} 本意是嵌套类型
        // {@code A.B}，但本仓实测有把它当**字段**用的（{@code ConfigStorage.NullMarker}
        // 里 {@code NullMarker} 是 {@code Object} 字段，活在 {@code stored != ConfigStorage.NullMarker}
        // 的生产代码里）⇒ 若按类型解析失败，再按「{@code 前缀#末段}」试一次成员。
        // 这是刻意放行：本类的职责是**死符号**，不是 javadoc lint；宁可漏判也不制造成堆假红
        // （本仓「假红陷阱」前科：误红比漏判更贵）。
        if (link.member() == null) {
            // ① 限定形态 {@code A.B}：按「{@code A#B}」再试一次
            if (link.receiver().contains(".")) {
                String head = link.receiver().substring(0, link.receiver().lastIndexOf('.'));
                String tail = link.receiver().substring(link.receiver().lastIndexOf('.') + 1);
                for (String candidate : index.receiverCandidates(head, link.relPath())) {
                    // 形参表已随 receiver 一起被剥掉（无 `#` 形态，见 toLink 规范化 ②）
                    // ⇒ 元数未知，按 null 走「只比名字」的放行判定。
                    if (anyNestedDeclares(load(candidate), tail, null)) {
                        return null;
                    }
                }
            }
            // ② 简单形态 {@code X}：可能指的是**字段/常量**而非类型。
            //    实测 {@code ConfigStorage} 的 {@code Object NullMarker = new Object() {...}}
            //    —— {@code {@link NullMarker}} 指的是这个字段，而它活在
            //    {@code stored != ConfigStorage.NullMarker} 的生产代码里，绝不是死引用。
            //    在「本文件声明类型 + 同包全部类型 + import 到的类型」里找同名成员。
            for (String fqn : index.visibleTypeFqns(link.relPath())) {
                // 同 ①：形参表已在 toLink 里被剥掉 ⇒ 元数未知，不做元数判定。
                if (anyNestedDeclares(load(fqn), link.receiver(), null)) {
                    return null;
                }
            }
        }

        // 全部候选都没载到。先做**三方包同名探测**（等价于 javadoc 去 classpath 搜同名类型）
        // —— 实测 {@code {@link Component}} / {@code {@link CancellationException}} 这类
        // 无本文件 import 的常用类型必须靠它，否则会误判成悬空。
        String simple = link.receiver().contains(".")
            ? link.receiver().substring(link.receiver().lastIndexOf('.') + 1) : link.receiver();
        Class<?> probed = index.probeExternalClass(simple);
        if (probed != null) {
            sawReceiver = true;
            if (link.member() == null || anyNestedDeclares(probed, link.member(), link.arity())) {
                return null;
            }
        }

        // 还没找到 ⇒ 区分「已删」与「本类解析不到」。
        // 这是本护栏最关键的判定：仓内简单名不存在 + classpath 上也探不到 ⇒ 本体已删。
        boolean repoDeclares = index.repoDeclaresSimpleName(simple);
        if (!repoDeclares && probed == null) {
            return "接收者 [" + link.receiver() + "] 的本体已不存在："
                + "backend/src 全仓无此类型声明，classpath（含仓内出现过的全部三方包）也载不到。";
        }
        return "UNRESOLVED 接收者 [" + link.receiver()
            + "] 解析不到（源码索引命中=" + repoDeclares + "，classpath 同名探测命中=" + (probed != null)
            + "）—— 本类无法判定其存亡，必须显式报告，⛔ 不得静默跳过";
    }

    /**
     * 成员是否声明在该类<b>或其任意深度的嵌套类型</b>里。
     *
     * <p>WHY 必须递归：javadoc 的 {@code {@link #CONST}} 常写在<b>嵌套 record</b> 的字段注释里
     * （实测 {@code MicroCompactResult.CacheEdit.TYPE_DELETE_TOOL_RESULT}）。只查一层嵌套时，
     * 第 2 层及更深的方法/常量会被误判为已删。
     *
     * <p>{@code arity} 语义见 {@link Link#arity()}；{@code null} = 链接未写形参表 ⇒ 不判元数。
     */
    private static boolean anyNestedDeclares(Class<?> cls, String member, Integer arity) {
        if (cls == null || member == null) {
            return false;
        }
        if (reflectHasMember(cls, member, arity)) {
            return true;
        }
        try {
            for (Class<?> nested : cls.getDeclaredClasses()) {
                if (anyNestedDeclares(nested, member, arity)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            // 拿不到嵌套类列表 ≠ 成员不存在。
        }
        return false;
    }

    /**
     * 反射判定成员存亡。<b>必须</b>上溯父类 + 收接口 —— 否则继承来的成员会被误判为已删。
     * 收 {@code getDeclared*}（含 private / package-private / Lombok 生成的 getter /
     * record 访问器 / 常量 / 嵌套类型 / <b>构造器</b>）而非只收 {@code get*}（public）。
     *
     * <p>{@code getDeclaredConstructors()} 不可省：javadoc 的 {@code {@link #Foo(String)}}
     * 指的是<b>构造器</b>，而 {@code getDeclaredMethods()} <b>不含</b>构造器
     * ⇒ 漏了它会把所有构造器引用误判为死引用（实测会多出 49 条假红）。
     *
     * <h2>⭐ 元数判定（{@code arity != null} 时）</h2>
     * <p>链接写明了形参表 ⇒ javadoc 语义上它指向的是<b>形参个数相等</b>的那个重载。
     * 故此处：
     * <ol>
     *   <li>先按「名字 + 元数」在<b>类 → 父类链 → 接口</b>上找；命中即存在；</li>
     *   <li>若某个类型<b>确有同名可调用成员</b>但元数全对不上 ⇒ 判<b>不存在</b>
     *       （这正是旧实现「只比方法名」对<b>同名重载被删</b>结构性失明的那一类：
     *       {@code {@link #agentRegistry()}} 被残留的 1 参重载解析掉 ⇒ 永不判为悬空）；</li>
     *   <li>若该名字<b>根本不是可调用成员</b>（字段 / 嵌套类型 —— 如
     *       {@code {@link #NullMarker()}} 这种写法）⇒ 仍按字段 / 嵌套类型放行
     *       （⛔ 不据此判死，那是 javadoc lint 的活，不是死符号门）。
     * </ol>
     * <p>⚠️ 第 2 条的「判死」只判<b>名字确实是方法/构造器</b>的情形，且必须让父类链先走完 ——
     * 否则「子类声明 {@code m(int)}、父类声明 {@code m(String)}、链接 {@code #m(String)}」
     * 会在子类就被误判死。
     */
    private static boolean reflectHasMember(Class<?> cls, String member, Integer arity) {
        if (cls == null || member == null) {
            return false;
        }
        // ① 类 + 父类链上的 declared 可调用成员（方法 / 构造器），按元数比对。
        boolean callableNamePresent = false;
        for (Class<?> k = cls; k != null; k = k.getSuperclass()) {
            Boolean hit = declaredCallable(k, member, arity);
            if (Boolean.TRUE.equals(hit)) {
                return true;
            }
            if (Boolean.FALSE.equals(hit)) {
                callableNamePresent = true;
            }
        }
        // ② 接口的 default / static 成员不在父类链上，用 getMethods 兜底（同样按元数比对）。
        try {
            for (Method m : cls.getMethods()) {
                if (!m.getName().equals(member)) {
                    continue;
                }
                callableNamePresent = true;
                if (arity == null || m.getParameterCount() == arity) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            // 链接期失败（可选依赖缺失）不影响结论：已在上面的 declared 链里查过。
        }
        // ③ 同名可调用成员存在、链接又写明了形参表，却没有任何重载元数对得上 ⇒ 悬空。
        if (arity != null && callableNamePresent) {
            return false;
        }
        // ④ 元数未写明（或该名字不是可调用成员）⇒ 按字段 / 嵌套类型 / 接口常量放行。
        try {
            for (Class<?> k = cls; k != null; k = k.getSuperclass()) {
                for (Field f : k.getDeclaredFields()) {
                    if (f.getName().equals(member)) {
                        return true;
                    }
                }
                for (Class<?> n : k.getDeclaredClasses()) {
                    if (n.getSimpleName().equals(member)) {
                        return true;
                    }
                }
            }
            for (Field f : cls.getFields()) {
                if (f.getName().equals(member)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            // 同上：拿不到成员列表 ≠ 成员不存在，交给其它候选。
        }
        return false;
    }

    /**
     * 本类（不含父类）是否声明了名为 {@code member} 的<b>可调用成员</b>（方法 / 构造器）。
     *
     * @return {@code TRUE} = 有且元数相符（{@code arity} 为 {@code null} 视为任意元数均相符）；
     *         {@code FALSE} = 有<b>同名</b>可调用成员但元数全不符（仅 {@code arity != null} 时可能）；
     *         {@code null} = 本类无此名字的可调用成员（⇒ 交由父类链 / 接口 / 字段 / 嵌套类型继续判）。
     */
    private static Boolean declaredCallable(Class<?> k, String member, Integer arity) {
        boolean named = false;
        try {
            for (Method m : k.getDeclaredMethods()) {
                if (!m.getName().equals(member)) {
                    continue;
                }
                named = true;
                if (arity == null || m.getParameterCount() == arity) {
                    return Boolean.TRUE;
                }
            }
        } catch (Throwable ignored) {
            // 拿不到方法列表 ≠ 方法不存在，交给其它候选。
        }
        try {
            // 构造器：javadoc 用「类简单名」引用它（{@link #Foo(String)}）。见 reflectHasMember javadoc。
            for (java.lang.reflect.Constructor<?> ctor : k.getDeclaredConstructors()) {
                if (!k.getSimpleName().equals(member)) {
                    continue;
                }
                named = true;
                if (arity == null || ctor.getParameterCount() == arity) {
                    return Boolean.TRUE;
                }
            }
        } catch (Throwable ignored) {
            // 同上。
        }
        if (!named) {
            return null;
        }
        // arity == null 时上面任一命中即已返回 TRUE ⇒ 走到这里必然 arity != null。
        return Boolean.FALSE;
    }

    /** 按 FQN 载入，{@code initialize=false}（⛔ 不触发静态初始化副作用）。 */
    private static Class<?> load(String fqn) {
        if (fqn == null) {
            return null;
        }
        try {
            return Class.forName(fqn, false, DeadSymbolReferenceGuardTest.class.getClassLoader());
        } catch (Throwable e) {
            return null;
        }
    }

    // ═══════════════════════════════ 门 1 辅助 ═══════════════════════════════

    /** {@code backend/src} 下形如 {@code <Name>.java} / {@code <Name>*.java} 的本体文件（相对路径）。 */
    private static List<String> findBodies(Path backend, String simpleName) {
        List<String> hits = new ArrayList<>();
        for (String rel : SRC_ROOT_RELATIVE_DIRS) {
            Path srcRoot = backend.resolve(rel);
            if (!Files.isDirectory(srcRoot)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(srcRoot)) {
                walk.filter(Files::isRegularFile)
                    .filter(p -> {
                        String fn = p.getFileName().toString();
                        return fn.startsWith(simpleName) && fn.endsWith(".java");
                    })
                    .map(p -> rel + "/" + srcRoot.relativize(p).toString().replace('\\', '/'))
                    .sorted()
                    .forEach(hits::add);
            } catch (IOException e) {
                throw new UncheckedIOException("门 1 查找失败: " + srcRoot, e);
            }
        }
        return hits;
    }

    // ═══════════════════════════════ 门 2 辅助 ═══════════════════════════════

    /**
     * 数「无豁免前缀」的提及：命中 {@link #DEBT_HIT_PATTERNS} 的行，取它所属的<b>注释块</b>，
     * 块内若出现 {@link #DEBT_EXEMPTION} 则免债。
     *
     * <p>块级而非行级：审查已证行级会把块标题写着「已删除…旧实现」的
     * {@code MemoryStorage.java:120} 误判（块标题在<b>上一行</b>）。
     */
    private static Map<String, Integer> countUnexemptedMentions(Path backend) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String sym : DEBT_HIT_PATTERNS.keySet()) {
            counts.put(sym, 0);
        }
        List<SourceFile> sources = new ArrayList<>();
        for (String rel : SRC_ROOT_RELATIVE_DIRS) {
            Path srcRoot = backend.resolve(rel);
            if (!Files.isDirectory(srcRoot)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(srcRoot)) {
                walk.filter(p -> p.toString().endsWith(".java"))
                    .sorted()
                    .forEach(p -> sources.add(SourceFile.read(backend, p)));
            } catch (IOException e) {
                throw new UncheckedIOException("门 2 扫描失败: " + srcRoot, e);
            }
        }
        assertThat(sources).as("门 2 源文件数必须 > 0（空扫 = 假绿）").isNotEmpty();

        for (SourceFile sf : sources) {
            String[] lines = sf.text().split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                for (Map.Entry<String, Pattern> e : DEBT_HIT_PATTERNS.entrySet()) {
                    if (!e.getValue().matcher(lines[i]).find()) {
                        continue;
                    }
                    // 同名活符号整文件豁免（见 LIVE_FIELD_EXEMPTION_FILES）
                    if (sf.relPath().equals(LIVE_FIELD_EXEMPTION_FILES.get(e.getKey()))) {
                        continue;
                    }
                    if (DEBT_EXEMPTION.matcher(enclosingCommentBlock(lines, i)).find()) {
                        continue;
                    }
                    counts.merge(e.getKey(), 1, Integer::sum);
                }
            }
        }
        return counts;
    }

    /** 命中行所属的注释块全文（向上找块首，向下补足块尾，并带上命中行本身）。 */
    private static String enclosingCommentBlock(String[] lines, int hitLine) {
        StringBuilder sb = new StringBuilder();
        for (int i = hitLine; i < lines.length; i++) {
            String t = lines[i].stripLeading();
            if (!(t.startsWith("*") || t.startsWith("//") || t.startsWith("/*"))) {
                break;
            }
            sb.append(lines[i]).append('\n');
        }
        String tail = sb.toString();
        sb.setLength(0);
        for (int i = hitLine - 1; i >= 0; i--) {
            String t = lines[i].stripLeading();
            if (!(t.startsWith("*") || t.startsWith("//") || t.startsWith("/*"))) {
                break;
            }
            sb.insert(0, lines[i] + "\n");
        }
        // 命中行本身可能既不是 * 也不是 //（例如 @DisplayName("...") 里的字符串），也带上
        return sb + lines[hitLine] + "\n" + tail;
    }

    // ═══════════════════════════════ 基础工具 ═══════════════════════════════

    /**
     * backend 根：从 <b>code source 位置</b>上溯（不依赖 cwd）。
     *
     * <p>抄的是 CC 的 {@code __dirname} 手法
     * （{@code claude-code-best/src/utils/model/__tests__/providerGates.test.ts:5} 的
     * {@code const RUNNER_ABS = resolve(__dirname, 'providerGates.runner.ts')}）
     * —— 同性质：以代码位置为锚，而不是 {@code Path.of("src/main/...")} 这种依赖 IDE
     * {@code workingDirectory} 的写法（本仓 F-13a finding 已证该写法恒红）。
     */
    private static Path backendRoot() {
        Path anchor = null;
        try {
            anchor = Path.of(DeadSymbolReferenceGuardTest.class
                .getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (URISyntaxException | RuntimeException ignored) {
            // 落到下面的 fail loud 分支
        }
        for (Path p = anchor; p != null; p = p.getParent()) {
            if (Files.isDirectory(p.resolve("src/main/java"))) {
                return p;
            }
        }
        throw new IllegalStateException(
            "无法从 code source [" + anchor + "] 上溯定位 backend 根（须含 src/main/java）。"
                + "⛔ 本护栏刻意不回落 cwd —— 回落会静默扫错目录，属『不许静默失效』禁止项。");
    }

    private static String formatDangling(List<Dangling> dangling) {
        StringBuilder sb = new StringBuilder();
        for (Dangling d : dangling) {
            sb.append("  ").append(d.relPath()).append(':').append(d.line())
                .append("\n      链接目标: ").append(d.raw())
                .append("\n      判定理由: ").append(d.reason()).append('\n');
        }
        return sb.toString();
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
    }

    /**
     * 首个<b>括号深度 0</b> 的空白位置（{@code -1} = 无）。
     *
     * <p>用途：剥 {@code {@link}} 的 label，同时不腰斩形参表 ——
     * {@code {@link #m(A, B)}} 里 {@code "A, B"} 中间的空白在深度 1 上，不算分隔符。
     * ⚠️ 只数圆括号：泛型尖括号内的空白必然也在圆括号内（形参表里不会出现顶层泛型），
     * 多收一对配平符只会在未配平的畸形写法上改变行为，净害无益。
     */
    private static int indexOfTopLevelWhitespace(String s) {
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (depth <= 0 && Character.isWhitespace(c)) {
                return i;
            }
        }
        return -1;
    }

    /** 剥掉多行链接捕获文本里的 javadoc 行首星号与空白（{@code "\n * Foo"} → {@code "Foo"}）。 */
    private static String stripLeadingCommentMarkers(String captured) {
        String s = captured.strip();
        while (!s.isEmpty() && (s.charAt(0) == '*' || s.charAt(0) == '/')) {
            s = s.substring(1).strip();
        }
        return s;
    }

    // ═══════════════════════════════ 源码索引 ═══════════════════════════════

    /** 一个被扫描的源文件。 */
    private record SourceFile(String relPath, String packageName, String text) {
        static SourceFile read(Path root, Path file) {
            try {
                // CRLF → LF：本仓工作区是 CRLF（git autocrlf），行/列切分必须先归一。
                String text = Files.readString(file).replace("\r\n", "\n");
                Matcher m = Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE)
                    .matcher(text);
                String pkg = m.find() ? m.group(1) : "";
                return new SourceFile(
                    root.relativize(file).toString().replace('\\', '/'), pkg, text);
            } catch (IOException e) {
                throw new UncheckedIOException("读源失败: " + file, e);
            }
        }
    }

    /**
     * 源码类型索引：<b>候选 FQN 生成器 + 仓内命名空间裁判</b>。
     *
     * <h2>⛔ 本索引<b>不是</b>存在性判据 —— 存在性一律由 {@code Class.forName} 决定</h2>
     * <p>踩过的坑（实测）：早期版本用「{@code pkg + "." + 简单名}」把<b>嵌套类型</b>
     * 也塞进索引，于是 {@code EffectivePromptOptions#coordinatorModeEnabled()} 这类链接
     * 拿到一个<b>载不到的</b> FQN，被当作「类型存在但成员没了」⇒ <b>197 条假红</b>。
     * 现在：候选 FQN 由本索引给出（含正确的 {@code Outer$Inner} 嵌套形态），
     * 但「到底存不存在」只认 {@code Class.forName}。
     *
     * <p>本索引另有两个不可替代的职责：
     * <ol>
     *   <li><b>陈旧字节码裁判</b>：载得到但源码里没有 ⇒ 孤儿 {@code .class}
     *       （{@code mvn} 不清理已删源文件的产物）⇒ 必须<b>红</b>，不能当存在。</li>
     *   <li><b>三方包探测</b>：见 {@link #probeExternal(String)}。</li>
     * </ol>
     */
    private static final class TypeIndex {

        /**
         * 类型声明正则。<b>必须</b>要求名字后面紧跟 {@code &#123;} / {@code (} /
         * {@code implements} / {@code extends} / {@code permits} —— 否则中文 javadoc 里的
         * 散文会被当成类型声明。实测误命中 2 处：
         * {@code LlmAgentLoop.java:13870}「内部 record toString，丢弃块语义」、
         * {@code :13984}「record vs raw user msg」（{@code toString}/{@code vs} 被当成类型名）。
         */
        private static final Pattern TYPE_DECL = Pattern.compile(
            "\\b(?:class|interface|record|enum|@interface)\\s+([A-Za-z_$][\\w$]*)\\s*"
                + "(?:<[^>{}]{0,300}>)?\\s*"
                + "(?:\\{|\\(|implements\\b|extends\\b|permits\\b)",
            Pattern.MULTILINE);

        private final Map<String, String> packageByFile = new HashMap<>();
        private final Map<String, List<String>> declaredNamesByFile = new HashMap<>();
        private final Map<String, List<String>> importsByFile = new HashMap<>();
        /** 源码声明过的**正确** FQN（顶层 + 嵌套 {@code Outer$Inner}）——陈旧字节码裁判用。 */
        private final Set<String> sourceDeclaredFqns = new LinkedHashSet<>();
        /** 简单名 → 该名字在每个文件里的**正确**候选 FQN（含嵌套形态）。 */
        private final Map<String, List<String>> candidatesBySimple = new HashMap<>();
        /** 仓内命名空间的根包（由源码包名前两段推出，如 {@code com.nexusai}）。 */
        private final Set<String> repoRootPackages = new LinkedHashSet<>();
        /** 全仓出现过的三方包（非通配 import 去掉末段）—— 无 import 的外部类型靠它探。 */
        private final Set<String> projectPackages = new LinkedHashSet<>();
        /** {@link #probeExternalClass(String)} 的记忆表（{@code null} 值表示「探过，没命中」）。 */
        private final Map<String, Class<?>> probeCache = new HashMap<>();

        static TypeIndex build(List<SourceFile> sources) {
            TypeIndex idx = new TypeIndex();
            for (SourceFile sf : sources) {
                idx.packageByFile.put(sf.relPath(), sf.packageName());
                if (!sf.packageName().isEmpty()) {
                    String[] seg = sf.packageName().split("\\.");
                    if (seg.length >= 2) {
                        idx.repoRootPackages.add(seg[0] + "." + seg[1]);
                    } else {
                        idx.repoRootPackages.add(seg[0]);
                    }
                }
                String fileName = sf.relPath().substring(sf.relPath().lastIndexOf('/') + 1);
                String primary = fileName.substring(0, fileName.length() - ".java".length());
                Set<String> names = new LinkedHashSet<>();
                names.add(primary);
                Matcher m = TYPE_DECL.matcher(sf.text());
                while (m.find()) {
                    names.add(m.group(1));
                }
                idx.declaredNamesByFile.put(sf.relPath(), List.copyOf(names));
                for (String candidate : idx.fileCandidateFqns(sf.relPath(), primary, names)) {
                    idx.sourceDeclaredFqns.add(candidate);
                }
                for (String n : names) {
                    idx.candidatesBySimple
                        .computeIfAbsent(n, k -> new ArrayList<>())
                        .addAll(idx.fileCandidateFqns(sf.relPath(), primary, List.of(n)));
                }
                idx.importsByFile.put(sf.relPath(), parseImports(sf.text()));
                for (String imp : idx.importsByFile.get(sf.relPath())) {
                    idx.projectPackages.add(packageOf(imp));
                }
            }
            idx.projectPackages.remove("");
            return idx;
        }

        /**
         * 某文件里「简单名 → 正确候选 FQN」。
         *
         * <p>嵌套类型在磁盘上是<b>扁平文件</b>，classpath 上却是 {@code Outer$Inner}
         * ⇒ 除顶层形态外必须同时给出 {@code pkg.Primary$Nested}。
         * 两个都试、由 {@code Class.forName} 定夺（Java 允许一个文件里有多个顶层类型，
         * 故 {@code pkg.N} 也不能省）。
         */
        private List<String> fileCandidateFqns(String relPath, String primary,
            java.util.Collection<String> names) {
            String pkg = packageByFile.getOrDefault(relPath, "");
            String prefix = pkg.isEmpty() ? "" : pkg + ".";
            Set<String> out = new LinkedHashSet<>();
            out.add(prefix + primary);
            for (String n : names) {
                out.add(prefix + n);
                out.add(prefix + primary + "$" + n);
            }
            return new ArrayList<>(out);
        }

        /** 简单名在某文件里对应的候选 FQN（供嵌套类型解析）。 */
        private List<String> candidatesForSimple(String simple) {
            return candidatesBySimple.getOrDefault(simple, List.of());
        }

        /**
         * import 的字面量；{@code static} import 的属主类型同样收
         * （{@code import static a.b.C.m;} 里的 {@code a.b.C} 能让 {@code {@link m}} 解析到）。
         *
         * <p>⛔ 通配 import 必须收（{@code java.util.*}）。实测早期版本用 {@code ([\\w.$]+)}
         * 收不到 {@code *} —— 连<b>整条 import 都匹配不上</b>（正则要求 {@code *} 后紧跟 {@code ;} 失败），
         * 于是 {@code Map}/{@code LinkedHashMap}/{@code EnumMap}/{@code ConcurrentMap}
         * 等经通配 import 引入的 JDK 类型全部被判「解析不到」。
         */
        private static List<String> parseImports(String text) {
            List<String> out = new ArrayList<>();
            Matcher m = Pattern.compile("^\\s*import\\s+(?:static\\s+)?([\\w.$]+(?:\\.\\*)?)\\s*;",
                Pattern.MULTILINE).matcher(text);
            while (m.find()) {
                String imp = m.group(1);
                out.add(imp);
                int lastDot = imp.lastIndexOf('.');
                if (lastDot > 0 && !imp.endsWith(".*")) {
                    out.add(imp.substring(0, lastDot));
                }
            }
            return out;
        }

        /** import 字面量所属的包：{@code a.b.C} → {@code a.b}；{@code a.b.*} → {@code a.b}。 */
        private static String packageOf(String importLiteral) {
            boolean wildcard = importLiteral.endsWith(".*");
            int cut = wildcard ? importLiteral.length() - 2 : importLiteral.lastIndexOf('.');
            return cut > 0 ? importLiteral.substring(0, cut) : "";
        }

        /** 载得到但源码里没有 ⇒ 陈旧字节码。仅对仓内命名空间生效（三方包不适用）。 */
        boolean isSourceDeclared(String fqn) {
            return sourceDeclaredFqns.contains(fqn);
        }

        /** FQN 是否落在本仓源码的命名空间里（用源码包名推出的根包判断，不硬编码 {@code com.nexusai}）。 */
        boolean isRepoNamespace(String fqn) {
            for (String root : repoRootPackages) {
                if (fqn.startsWith(root + ".")) {
                    return true;
                }
            }
            return false;
        }

        /** backend/src 全仓是否声明过这个简单名（审查点名的「Glob 本体存亡」那一半）。 */
        boolean repoDeclaresSimpleName(String simple) {
            return candidatesBySimple.containsKey(simple);
        }

        /**
         * 该 FQN 是否是 javadoc 合法的<b>包引用</b>（{@code {@link some.pkg}}）。
         *
         * <p>实测 {@code ExtractMemoriesControllerTest.java:65} 写 {@code {@link com.nexusai.application.agent.skill}}
         * —— 那是<b>包</b>，不是类型。按类型判会误红。
         */
        boolean isKnownPackage(String fqn) {
            return packageByFile.containsValue(fqn) || projectPackages.contains(fqn);
        }

        /**
         * 该文件「可见范围内」的全部类型 FQN：本文件声明的 + 同包全部 + import 到的。
         *
         * <p>用途：无 {@code #} 的简单名链接（{@code {@link X}}）若按类型解析不到，
         * 再按「{@code X} 是这个可见范围里某个类型的<b>字段/常量</b>」试一次。
         * 没有这一步，{@code ConfigStorage} 的 {@code NullMarker} 字段会被误判成已删类型
         * （实测 5 处假红，且它在生产代码里活着）。
         */
        List<String> visibleTypeFqns(String relPath) {
            Set<String> out = new LinkedHashSet<>(declaredCandidateFqns(relPath));
            String pkg = packageByFile.getOrDefault(relPath, "");
            for (Map.Entry<String, String> e : packageByFile.entrySet()) {
                if (e.getValue().equals(pkg) && !pkg.isEmpty()) {
                    out.addAll(declaredCandidateFqns(e.getKey()));
                }
            }
            List<String> explicitImports = new ArrayList<>();
            for (String imp : importsByFile.getOrDefault(relPath, List.of())) {
                // 通配 import 无法枚举其下所有类型（探测代价太高）⇒ 跳过，交给 probeExternal。
                if (!imp.endsWith(".*")) {
                    explicitImports.add(imp);
                }
            }
            out.addAll(explicitImports);
            List<String> withNested = new ArrayList<>(out);
            for (String fqn : out) {
                addNestedVariant(withNested, fqn);
            }
            return withNested;
        }

        /**
         * 三方包同名探测：简单名在<b>全仓出现过的任一 import 包</b>下是否存在。
         *
         * <p>WHY 需要它：javadoc 解析无 {@code #} 的限定名时会去 <b>classpath</b> 找同名类型。
         * 实测 {@code LlmAgentLoop.java:451} 写 {@code {@link Component}} 而本文件
         * <b>没有</b> {@code import org.springframework.stereotype.Component}
         * —— 靠「同包 / 本文件 import / java.lang / 仓内同名」四条都探不到它，
         * 但它<b>不是</b>死引用（Spring 的 {@code Component} 一直都在）。
         * 不探就会把它误判成悬空。
         *
         * <p>代价（已知）：若某个<b>真的已删</b>的简单名恰好在某个仓内出现过的三方包下也有同名类，
         * 本类会漏判。实测 46 个待探名字中，只有这一种情况会被放过，且都是 JDK/Spring 的常用名。
         */
        Class<?> probeExternalClass(String simple) {
            if (probeCache.containsKey(simple)) {
                return probeCache.get(simple);
            }
            Class<?> hit = null;
            for (String pkg : projectPackages) {
                hit = load(pkg + "." + simple);
                if (hit != null) {
                    break;
                }
            }
            probeCache.put(simple, hit);
            return hit;
        }

        /** 本文件声明类型（顶层 + 嵌套）的候选 FQN。 */
        List<String> declaredCandidateFqns(String relPath) {
            String primary = relPath.substring(relPath.lastIndexOf('/') + 1,
                relPath.length() - ".java".length());
            return fileCandidateFqns(relPath, primary,
                new LinkedHashSet<>(declaredNamesByFile.getOrDefault(relPath, List.of())));
        }

        /**
         * 接收者的候选 FQN（按可信度排序）。覆盖审查点名的全部落点：
         * 同包 / 本文件 import / 全限定 / {@code java.lang} / 全仓同名兜底（含嵌套 {@code $}）。
         */
        List<String> receiverCandidates(String receiver, String relPath) {
            String pkg = packageByFile.getOrDefault(relPath, "");
            List<String> imports = importsByFile.getOrDefault(relPath, List.of());
            List<String> out = new ArrayList<>();

            if (receiver.contains(".")) {
                out.add(receiver);
                addNestedVariant(out, receiver);
                if (!pkg.isEmpty()) {
                    String inPkg = pkg + "." + receiver;
                    out.add(inPkg);
                    addNestedVariant(out, inPkg);
                }
                String head = receiver.substring(0, receiver.indexOf('.'));
                String tail = receiver.substring(head.length());
                for (String imp : imports) {
                    if (imp.endsWith("." + head)) {
                        String full = imp + tail;
                        out.add(full);
                        addNestedVariant(out, full);
                    }
                }
            } else {
                if (!pkg.isEmpty()) {
                    out.add(pkg + "." + receiver);
                }
                for (String imp : imports) {
                    if (imp.endsWith(".*")) {
                        out.add(imp.substring(0, imp.length() - 1) + receiver);
                    } else if (imp.endsWith("." + receiver)) {
                        out.add(imp);
                    }
                }
                out.add("java.lang." + receiver);
            }

            // 全仓同名兜底（审查点名的第二检索手段「Glob **/Name*.java」的等价物，
            // 且比 Glob 更强：含包名与嵌套类型）
            out.addAll(candidatesForSimple(receiver.contains(".")
                ? receiver.substring(receiver.lastIndexOf('.') + 1) : receiver));

            Set<String> uniq = new LinkedHashSet<>();
            for (String c : out) {
                if (c != null && !c.isBlank()) {
                    uniq.add(c);
                }
            }
            return new ArrayList<>(uniq);
        }

        /**
         * 嵌套类型在 classpath 上是 {@code Outer$Inner}。
         * 逐段把<b>最后一个</b>点换成 {@code $}（{@code a.b.C.D} → {@code a.b.C$D}）；
         * 早期版本把<b>所有</b>点都换掉（{@code a$b$C$D}）⇒ 恒载不到，
         * 实测导致 {@code ConfigStorage.NullMarker} 这类全限定嵌套引用被判「解析不到」。
         */
        private static void addNestedVariant(List<String> out, String fqn) {
            int lastDot = fqn.lastIndexOf('.');
            if (lastDot > 0) {
                out.add(fqn.substring(0, lastDot) + "$" + fqn.substring(lastDot + 1));
            }
        }
    }
}
