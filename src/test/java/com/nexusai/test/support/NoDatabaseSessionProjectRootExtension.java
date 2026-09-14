package com.nexusai.test.support;

import com.nexusai.common.SessionProjectRoot;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * [fix-junit 2026-09-14] 测试环境的<b>全局默认</b>：「本 JVM 不接 DB 回源，任何未绑定 sessionId 都属『确无会话』」。
 *
 * <p><b>为什么需要它（= 被它治的那个病）</b>：S2（{@code ee6ebfe}）把
 * {@link SessionProjectRoot#lookup(String)} 的「回源解析器<b>未接线</b>」分支从「静默投给
 * {@code unknown()}'」改成第 4 态 {@link SessionProjectRoot.Lookup#resolutionFailure()}（= <b>无法判定</b>），
 * 而 cwd 域（{@code CwdResolution.getCwd}）对该态 <b>fail-loud 抛 {@code IllegalStateException}</b>
 * —— 这在<b>生产</b>完全正确（未接线 = 装配异常），但在<b>测试</b>里「没有 DB」是<b>常态</b>而不是异常：
 * 纯 JUnit 夹具本来就不接 Spring / 不连 DB，其 sessionId 全是合成的。后果是所有「用合成 sessionId 但
 * 从不注册解析器」的夹具都经 {@code ToolUseContext.<init> → CwdResolution.getCwd} 撞上 fail-loud
 * （实测 66 个 {@code LlmAgentLoop*} 类：268 run / 6 failures / 154 errors）。
 *
 * <p><b>S2 的对策与本扩展的关系</b>：S2 用「夹具<b>逐个显式声明</b>」
 * （{@link SessionProjectRootTestSupport#declareNoDatabase()}，已加 53 个类）。方向对（显式优于隐式），
 * 但<b>不可穷举</b> —— 它只覆盖 S2 当时跑到的 127 类定向集，没被跑到的类全红。本扩展把同一语义变成
 * <b>全局默认</b>：零改类、语义明确、可穷举。<b>那 53 个显式声明一律保留</b>（显式优于隐式 + 它们是
 * 「这个类确实需要无 DB」的文档）；在本扩展生效后它们<b>冗余但无害</b>（本扩展先装，类的
 * {@code @BeforeEach} 后装同一个语义的解析器，净效果相同）。
 *
 * <h2>语义（⛔ 本扩展<b>不放宽生产侧的 fail-loud</b>）</h2>
 * <p>本扩展注入的解析器答 {@link SessionProjectRoot.Lookup#sessionlessEnvironment()}（= <b>本环境
 * 确无会话</b>），<b>不是</b> {@link SessionProjectRoot.Lookup#resolutionFailure()}（无法判定）
 * 也<b>不是</b> {@link SessionProjectRoot.Lookup#unknown()}（DB 明确答无此会话）：
 * <table border="1">
 *   <caption>同一状态在两个环境里的正确语义</caption>
 *   <tr><th>环境</th><th>「没有回源解析器」意味着</th><th>正确行为</th></tr>
 *   <tr><td><b>生产</b></td><td>装配异常（本该有却没有）</td>
 *       <td>🔴 fail-loud（{@link SessionProjectRoot.Lookup#resolutionFailure()}）——
 *           ⛔ <b>用户裁定 F-09/F-20 (A) 保持不变，本扩展不触碰</b></td></tr>
 *   <tr><td><b>测试</b></td><td>常态（纯 JUnit 夹具不接 DB）</td>
 *       <td>⚪ 默认答「本环境确无会话」（{@code sessionless()}）⇒ cwd 域走命名无会话出口
 *           （进程 user.dir），行为等于改造前</td></tr>
 * </table>
 * <p>⛔ 三者必须分开表达，否则「装配故障」/「夹具无 DB」/「会话已删」再次混为一谈 —— 而那正是
 * S2 要治的病。
 * <p><b>[cwd3 步骤 2] 为什么从 {@code unknown()} 切到 {@code sessionless()}（⛔ 不是笔误）</b>：
 * 步骤 2 把 cwd 域的 {@code unknown} 分支从「回落进程 user.dir」改成 <b>fail-loud 抛</b>。夹具的
 * 「本 JVM 不连 DB」<b>不是</b>「DB 说没有这一行」—— 后者是数据链路异常，前者是夹具的常态 ⇒ 必须
 * 换到语义正确的那一态，否则每个用合成 sessionId 的纯 JUnit 夹具都会撞 fail-loud（改前实测
 * 66 个 {@code LlmAgentLoop*} 类就是撞在这个错位上）。
 * 本扩展<b>只在测试类路径生效</b>（{@code src/test/java} + {@code src/test/resources}），生产代码
 * 里没有任何引用点。
 *
 * <h2>执行顺序（本设计成立的<b>全部</b>依据）</h2>
 * <p>JUnit 5 的 {@code TestMethodTestDescriptor} 对每个用例按固定顺序调用：
 * <pre>
 *   before():  invokeBeforeEachCallbacks()  →  invokeBeforeEachMethods()
 *   after():   invokeAfterEachMethods()     →  invokeAfterEachCallbacks()
 * </pre>
 * ⇒ 本扩展（{@code BeforeEachCallback}）<b>先于</b>测试类的 {@code @BeforeEach} 执行；其
 * {@code AfterEachCallback} <b>后于</b>测试类的 {@code @AfterEach} 执行。
 * <p>因此：<b>需要「未接线」态的用例可以自建该态</b> —— 在自己的 {@code @BeforeEach} 里
 * {@code SessionProjectRoot.setDbResolver(null)}（本扩展先装、类的 {@code @BeforeEach} 后清，覆盖有效），
 * 或在用例体内先清。两个先例：
 * <ul>
 *   <li>{@code CwdResolutionTest#scenario4_unwiredResolverFailsLoudInsteadOfNonSessionExit}</li>
 *   <li>{@code SessionProjectRootTest#unwiredResolver_reportsResolutionFailureWithWarn}</li>
 * </ul>
 * ⛔ 这两个用例是「守护新语义（未接线必须 fail-loud）」的用例，<b>不得</b>为让测试变绿而放宽它们；
 * 反之，它们的 {@code isDbResolverWired() == false} 前置断言正是本扩展「执行顺序」的<b>实测装置</b>
 * （若 JUnit 反过来先跑类的 {@code @BeforeEach}，它们立刻变红）。
 *
 * <h2>只装不管（不覆盖）· 只清自己装的</h2>
 * <ul>
 *   <li>{@code beforeEach}：若 {@link SessionProjectRoot#isDbResolverWired()} 已为真
 *       （Spring {@code @Bean} 装配 / 该测试类自己装了 {@code @BeforeAll} 装置），<b>不覆盖</b>，
 *       也<b>不在 afterEach 清</b> —— 否则会误清生产装配的解析器（{@code @SpringBootTest} 走
 *       {@code SpringExtension@BeforeAllCallback} 装配上下文）或 {@code @BeforeAll} 装置。</li>
 *   <li>{@code afterEach}：<b>只清本扩展自己装的那一次</b>。{@code dbResolver} 是进程级 static 槽，
 *       装了不清理会跨类污染（{@link SessionProjectRoot#reset()} 刻意<b>不</b>清它，见该方法的 javadoc）。</li>
 * </ul>
 *
 * <h2>注册方式</h2>
 * <p>走 JUnit 5 官方「自动注册扩展」（Automatic Extension Registration），非 {@code pom} 改动：
 * <ol>
 *   <li>{@code src/test/resources/junit-platform.properties} ⇒
 *       {@code junit.jupiter.extensions.autodetection.enabled=true}；</li>
 *   <li>{@code src/test/resources/META-INF/services/org.junit.jupiter.api.extension.Extension}
 *       ⇒ 本类全限定名（{@code ServiceLoader} 须 public 无参构造）。</li>
 * </ol>
 */
public final class NoDatabaseSessionProjectRootExtension implements BeforeEachCallback, AfterEachCallback {

    private static final Logger log = LoggerFactory.getLogger(NoDatabaseSessionProjectRootExtension.class);

    /** 本扩展在 {@link ExtensionContext.Store} 里的命名空间（按用例隔离，避免跨用例串读）。 */
    private static final ExtensionContext.Namespace NAMESPACE =
        ExtensionContext.Namespace.create(NoDatabaseSessionProjectRootExtension.class);

    /** store key：本扩展是否为该用例「装过」解析器（= afterEach 是否有权清）。 */
    private static final String INSTALLED_KEY = "installedNoDatabaseResolver";

    /**
     * 测试环境默认解析器：<b>答「本环境确无会话」</b>
     * （{@code Lookup.sessionlessEnvironment()}；⛔ 既不是 {@code resolutionFailure()}，
     * 也<b>不再是</b> {@code unknown()} —— 见下「[cwd3 步骤 2] 为什么从 unknown 切到 sessionless」）。
     * <p>无状态、幂等 ⇒ 全局复用同一实例（不每用例新建）。
     */
    private static final SessionProjectRoot.DbResolver NO_DATABASE_RESOLVER =
        sessionId -> SessionProjectRoot.Lookup.sessionlessEnvironment();

    /** ServiceLoader 需要（public 无参构造；显式声明以便「为什么是 public」可检索）。 */
    public NoDatabaseSessionProjectRootExtension() {}

    /**
     * 类的 {@code @BeforeEach} <b>之前</b>：若无解析器，装「测试环境无 DB」默认。
     * <p>已有解析器 ⇒ 不覆盖、不记标记（afterEach 因此也不会清它）。
     */
    @Override
    public void beforeEach(ExtensionContext context) {
        if (SessionProjectRoot.isDbResolverWired()) {
            if (log.isDebugEnabled()) {
                log.debug("[fix-junit] 回源解析器已接线（Spring 装配 / 本类显式装置）⇒ 全局默认不介入: "
                    + "测试类={} 用例={}", context.getRequiredTestClass().getSimpleName(),
                    context.getDisplayName());
            }
            return;
        }
        SessionProjectRoot.setDbResolver(NO_DATABASE_RESOLVER);
        context.getStore(NAMESPACE).put(INSTALLED_KEY, Boolean.TRUE);
        if (log.isDebugEnabled()) {
            log.debug("[fix-junit] 已装「测试环境无 DB」默认解析器（答 sessionless，"
                + "⛔ 非 resolutionFailure 也非 unknown）: "
                + "测试类={} 用例={}", context.getRequiredTestClass().getSimpleName(),
                context.getDisplayName());
        }
    }

    /**
     * 类的 {@code @AfterEach} <b>之后</b>：只清本扩展自己装的那一次（static 槽跨类污染）。
     */
    @Override
    public void afterEach(ExtensionContext context) {
        Boolean installed = context.getStore(NAMESPACE).remove(INSTALLED_KEY, Boolean.class);
        if (!Boolean.TRUE.equals(installed)) {
            return;
        }
        SessionProjectRoot.setDbResolver(null);
        if (log.isDebugEnabled()) {
            log.debug("[fix-junit] 已注销「测试环境无 DB」默认解析器（static 槽不得跨类残留）: "
                + "测试类={} 用例={}", context.getRequiredTestClass().getSimpleName(),
                context.getDisplayName());
        }
    }
}
