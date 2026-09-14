package com.nexusai.test.support;

import com.nexusai.common.SessionProjectRoot;

/**
 * [S2 · F-09/F-20 2026-09-14] 测试夹具的 <b>「本夹具不接 DB 回源」显式声明</b>。
 *
 * <p><b>为什么需要它</b>：{@link SessionProjectRoot#lookup(String)} 的「回源解析器<b>未接线</b>」
 * 分支（{@code dbResolver == null}）自本批起返回第 4 态
 * {@link SessionProjectRoot.Lookup#resolutionFailure()}（= <b>无法判定</b>），而 cwd 域
 * （{@code CwdResolution.getCwd}）对该态 <b>fail-loud 抛 {@code IllegalStateException}</b>
 * （用户裁定 F-09/F-20 (A)：仍 fail-loud，保留可辨识语义与纠错文案；旧行为是零日志静默投给
 * 「确无会话」并回落进程 {@code user.dir}）。
 *
 * <p>⇒ 凡「不注册回源解析器」的纯 JUnit 夹具，其每个未绑定 sessionId 从「返回 user.dir」
 * 变为「抛异常」。本类提供一个<b>显式、可检索</b>的声明动作，让夹具把「我这里根本没有 DB」
 * 表达出来，而不是让 {@code CwdResolution} 去猜。
 *
 * <h2>语义（⛔ 不是「测试自己重写判据」）</h2>
 * <p>本声明注入的解析器答 {@link SessionProjectRoot.Lookup#sessionlessEnvironment()}
 * （= <b>本环境确无会话</b>），而不是 {@code resolutionFailure()}（无法判定），
 * <b>[cwd3 步骤 2] 也不再是</b> {@code unknown()}（= DB 明确答「无此会话」）：
 * <ul>
 *   <li>未接线 = <b>装配异常</b>（本该有却没有）⇒ 生产必须 fail-loud；
 *       <b>测试夹具没有 DB 则是正常状态</b>（夹具的 sessionId 本就是合成的）。</li>
 *   <li>三者必须分开表达，否则「装配故障」/「夹具无 DB」/「会话已删」就再次混为一谈 ——
 *       而那正是本批要治的病。</li>
 *   <li>⚠️ <b>切换理由（步骤 2 单点）</b>：步骤 2 把 cwd 域的 {@code unknown} 分支从「回落进程
 *       user.dir」改成 <b>fail-loud 抛</b>。夹具「本 JVM 不连 DB」不是「DB 说没有这一行」，
 *       必须落到语义正确的 sessionless 态，否则纯 JUnit 夹具会集体撞 fail-loud。</li>
 * </ul>
 * <p>⚠️ <b>不得</b>在「断言未接线必须 fail-loud」的用例里调用本方法（那会把被测态抹掉）；
 * 该类用例请用 {@link SessionProjectRoot#setDbResolver} 自行装置（先例：{@code CwdResolutionTest}
 * 的 {@code scenario4_...} / {@code SessionProjectRootTest} 的
 * {@code unwiredResolver_reportsResolutionFailureWithWarn}）。
 *
 * <h2>用法</h2>
 * <pre>
 *   &#64;BeforeEach
 *   void declareNoDatabase() { SessionProjectRootTestSupport.declareNoDatabase(); }
 *
 *   &#64;AfterEach
 *   void clearNoDatabase()  { SessionProjectRootTestSupport.clearNoDatabase(); }
 * </pre>
 * <p>必须在 {@code @AfterEach} 注销：{@code dbResolver} 是进程级 static 槽，不清理会跨类污染
 * （{@link SessionProjectRoot#reset()} 刻意<b>不</b>清它）。
 */
public final class SessionProjectRootTestSupport {

    private SessionProjectRootTestSupport() {}

    /**
     * 显式声明「本夹具不接 DB 回源；任何未绑定 sessionId 都属『确无会话』」。
     * <p>效果 = 还原本批之前的 cwd 域行为（合成 id ⇒ 命名无会话出口返回进程 {@code user.dir}）。
     */
    public static void declareNoDatabase() {
        SessionProjectRoot.setDbResolver(sid -> SessionProjectRoot.Lookup.sessionlessEnvironment());
    }

    /** 注销上一行声明（{@code @AfterEach} 必调；static 槽跨类污染）。 */
    public static void clearNoDatabase() {
        SessionProjectRoot.setDbResolver(null);
    }
}
