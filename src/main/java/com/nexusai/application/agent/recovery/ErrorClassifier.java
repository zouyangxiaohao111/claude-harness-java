package com.nexusai.application.agent.recovery;

import com.nexusai.application.agent.QuerySource;
import com.nexusai.application.agent.api.ApiErrors;
import com.nexusai.infra.llm.LlmApiException;

import java.io.IOException;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 错误类型分类器 · 对齐 CC withRetry.ts 分类函数全集。
 *
 * <h2>CC 对齐</h2>
 * <ul>
 *   <li>{@code is529Error} — CC withRetry.ts:610-621: APIError 类型闸 + status=529 +
 *       message 含 {@code "type":"overloaded_error"}</li>
 *   <li>{@code shouldRetry529} — CC withRetry.ts:84-89: {@code querySource === undefined ||
 *       FOREGROUND_529_RETRY_SOURCES.has(querySource)}</li>
 *   <li>{@code isTransientCapacityError} — CC withRetry.ts:106-110: 529 或 429（仅两类瞬时容量）</li>
 *   <li>{@code isStaleConnectionError} — CC withRetry.ts:112-118: APIConnectionError 且
 *       root code 为 ECONNRESET / EPIPE（cause 链最多 5 层，对齐 errorUtils.ts extractConnectionErrorDetails）</li>
 *   <li>{@code isPersistentRetryEnabled} — CC withRetry.ts:100-104: env NEXUSAI_UNATTENDED_RETRY
 *       （CC original: CLAUDE_CODE_UNATTENDED_RETRY withRetry.ts:102，Java NEXUSAI_ 前缀改写；
 *       CC 的 feature('UNATTENDED_RETRY') 门 Java 无 statsig 等价，N/A 标注）</li>
 *   <li>{@code shouldRetry} — CC withRetry.ts:696-787 全集决策链</li>
 *   <li>凭证自愈 — CC withRetry.ts:623-694: OAuth 吊销 403 / Bedrock / Google Auth Library /
 *       Vertex / AWS / GCP</li>
 * </ul>
 *
 * <p><b>ER-IMP-04 重建（删除类名/消息启发式）</b>：旧的 429/529 分类方法与临时错误判定
 * 基于异常类名 + 消息子串（CC 无此类名匹配），已删除；
 * 对齐 CC 改类型化状态码判定（{@link LlmApiException#status()}）。
 *
 * <p><b>N/A 标注（用户决策 2026-08-06）</b>：
 * <ul>
 *   <li>subscriber/enterprise 闸（CC withRetry.ts:767/:737）— 本项目自托管 Web 无订阅层
 *       （决策 3）→ {@code isClaudeAISubscriber()/isEnterpriseSubscriber()} 恒 false（视为非订阅）</li>
 *   <li>{@code clearApiKeyHelperCache/clearAwsCredentialsCache/clearGcpCredentialsCache} —
 *       Java 无 API key helper / AWS / GCP 凭证缓存（等价物缺失）→ N/A 空操作</li>
 *   <li>{@code disableKeepAlive} — Java HttpClient 无 per-request keep-alive 禁用（等价物缺失）→ N/A</li>
 *   <li>mock 错误排除（CC:697-700）— Java mock 错误为 {@code RateLimitMocking.MockApiError}
 *       record（非 Throwable），不进入本分类器 → N/A</li>
 * </ul>
 */
public final class ErrorClassifier {

    private static final Logger log = LoggerFactory.getLogger(ErrorClassifier.class);

    /**
     * env 读取器（包内可见，测试可注入）· 默认 {@link System#getenv}。
     *
     * <p>CC withRetry.ts 直接读 {@code process.env}；Java 端 env 门控分支（CLAUDE_CODE_REMOTE /
     * NEXUSAI_UNATTENDED_RETRY / CLAUDE_CODE_USE_BEDROCK / CLAUDE_CODE_USE_VERTEX /
     * USER_TYPE）经本函数读取，测试可替换以覆盖门控分支。
     */
    static volatile java.util.function.Function<String, String> ENV_READER = System::getenv;

    private static String env(String name) {
        return ENV_READER.apply(name);
    }

    private ErrorClassifier() {
        // 工具类不可实例化
    }

    // ════════════════════════════════════════════════════════════════════════
    // FOREGROUND_529_RETRY_SOURCES 前台集合 · CC withRetry.ts:62-82
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 前台 529 重试来源 · 对齐 CC withRetry.ts:62-82 {@code FOREGROUND_529_RETRY_SOURCES}。
     *
     * <p>Java QuerySource 枚举 → CC QuerySource 字符串映射（用户决策 3 拍板）：
     * <table>
     *   <tr><th>本枚举值</th><th>CC 字符串</th><th>前台?</th></tr>
     *   <tr><td>REPL_MAIN_THREAD</td><td>repl_main_thread</td><td>✓</td></tr>
     *   <tr><td>SDK</td><td>sdk</td><td>✓</td></tr>
     *   <tr><td>COMPACT</td><td>compact</td><td>✓</td></tr>
     *   <tr><td>HOOK_AGENT</td><td>hook_agent</td><td>✓</td></tr>
     *   <tr><td>USER</td><td>user（主线程用户输入）</td><td>✓</td></tr>
     *   <tr><td>SUBAGENT</td><td>agent:subagent（Java 聚合占位；CC 生产为动态
     *       agent:builtin:&lt;type&gt;，runAgent.ts:694）</td><td>✗ 后台</td></tr>
     *   <tr><td>FORK</td><td>agent:builtin:fork</td><td>✗ 后台</td></tr>
     *   <tr><td>SESSION_MEMORY</td><td>session_memory</td><td>✗ 后台</td></tr>
     *   <tr><td>EXTRACT_MEMORIES</td><td>extract_memories</td><td>✗ 后台</td></tr>
     *   <tr><td>AUTO_DREAM</td><td>auto_dream</td><td>✗ 后台</td></tr>
     * </table>
     *
     * <p>CC 的 hook_prompt / verification_agent / side_question / auto_mode /
     * bash_classifier 等前台来源 Java 无对应枚举值（N/A）。<b>[收尾 IMP2-05]</b> 早期注释
     * 声称 "Java 生产不产出 agent:custom / agent:default / agent:builtin" 已过时——IMP2-05
     * 运行时接线后，Java 生产端（子代理发射侧，SubagentExecutor.withQuerySourceValue →
     * {@link QuerySource#effectiveValue(QuerySource, String)}）确实产出
     * {@code agent:custom}（自定义/插件子代理，CC promptCategory.ts:26）、{@code agent:default}
     * （builtin agentType 为空，CC promptCategory.ts:24）与 {@code agent:builtin:&lt;type&gt;}
     * （内置子代理，CC promptCategory.ts:23）。但本 529 守卫消费<b>枚举类别</b>
     * {@link QuerySource#SUBAGENT}（canonical {@code agent:subagent}），不达发射侧精确值 →
     * 子代理（无论内置/自定义）均属后台（{@link #FOREGROUND_529_RETRY_SOURCES} 不含 SUBAGENT），
     * 判定行为与 CC 生产路径等价、零变化。
     *
     * <p><b>V-EC-6 USER 映射标注</b>：CC 无 {@code 'user'} querySource
     * （withRetry.ts:62-82 前台集合不含 'user'；CC 的 {@code 'user'} 仅作为
     * {@code message.type} 出现于消息 schema）。本枚举 {@link QuerySource#USER}
     * 为主线程用户输入标记，语义等价 {@code repl_main_thread}（前台），故纳入
     * 前台集合 —— 属 Java 扩展映射，非 CC 直接对应（可接受，JavaDoc 记录）。
     */
    static final Set<QuerySource> FOREGROUND_529_RETRY_SOURCES = Set.of(
        QuerySource.REPL_MAIN_THREAD,
        QuerySource.SDK,
        QuerySource.COMPACT,
        QuerySource.HOOK_AGENT,
        QuerySource.USER
    );

    /**
     * 前台 529 重试判定 · CC withRetry.ts:84-89 {@code shouldRetry529}。
     *
     * <p>{@code querySource === undefined → true}（保守，未打标调用路径重试）；
     * 否则 {@code FOREGROUND_529_RETRY_SOURCES.has(querySource)}。
     *
     * @param querySource 查询来源（null = 未打标，保守重试）
     * @return true=前台来源可重试 529
     */
    public static boolean shouldRetry529(QuerySource querySource) {
        return querySource == null || FOREGROUND_529_RETRY_SOURCES.contains(querySource);
    }

    /**
     * 前台 529 重试判定 · CC withRetry.ts:84-89（String 重载，供 RetryOptions.querySource 用）。
     *
     * <p><b>IMP2-01</b>：传参侧 canonical 化后，本重载同时兼容 {@code name()} 大写枚举名与
     * {@link com.nexusai.application.agent.QuerySource#canonical()} 小写值两种形态
     * （原 {@code valueOf(name())} 在 canonical 输入下抛异常 → 误判后台不重试）。
     *
     * @param querySourceName QuerySource name() 或 canonical 字符串（null = 保守重试）
     * @return true=前台来源可重试 529
     */
    public static boolean shouldRetry529(String querySourceName) {
        if (querySourceName == null) return true;
        QuerySource source = QuerySource.fromString(querySourceName);
        // 未知来源字符串（CC 集合外）→ 后台，不重试 · CC:62-82 "New sources default to no-retry"
        if (source == null) return false;
        return shouldRetry529(source);
    }

    // ════════════════════════════════════════════════════════════════════════
    // is529Error / isTransientCapacityError · CC withRetry.ts:610-621 / 106-110
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 类型化 529 Overloaded 判定 · CC withRetry.ts:610-621 {@code is529Error}。
     *
     * <p>必须为 {@link LlmApiException}（CC APIError 类型闸）；{@code status===529} 或
     * message 含 {@code "type":"overloaded_error"}（SDK 流式下偶发丢失 529 状态码）。
     *
     * @param e 异常
     * @return true=529 overloaded 错误
     */
    public static boolean is529Error(Throwable e) {
        if (!(e instanceof LlmApiException lae)) {
            return false;
        }
        return lae.status() == 529
            || (lae.getMessage() != null
                && lae.getMessage().contains("\"type\":\"overloaded_error\""));
    }

    /**
     * 瞬时容量错误判定 · CC withRetry.ts:106-110 {@code isTransientCapacityError}。
     *
     * <p>仅 529 / 429 两类（持久重试模式 + shouldRetry 首分支使用）。
     *
     * @param e 异常
     * @return true=瞬时容量错误（529 或 429）
     */
    public static boolean isTransientCapacityError(Throwable e) {
        return is529Error(e)
            || (e instanceof LlmApiException lae && lae.status() == 429);
    }

    /**
     * fast mode 未启用错误判定 · CC withRetry.ts:600-608 {@code isFastModeNotEnabledError}。
     *
     * <p>CC 原文注释标注「TODO: 用响应 header 替代（如 x-fast-mode-rejected），字符串匹配脆弱」。
     * 行为：{@code APIError.status === 400 && message 含 'Fast mode is not enabled'}。
     * fast-mode fallback 分支（withRetry.ts:310-314）据此调 {@code handleFastModeRejectedByAPI()}
     * 永久禁用 fast mode 后以标准速度重试。
     *
     * @param e 异常
     * @return true=400 且 message 含 "Fast mode is not enabled"
     */
    public static boolean isFastModeNotEnabledError(Throwable e) {
        if (!(e instanceof LlmApiException lae)) {
            return false;
        }
        return lae.status() == 400
            && lae.getMessage() != null
            && lae.getMessage().contains("Fast mode is not enabled");
    }

    // ════════════════════════════════════════════════════════════════════════
    // isStaleConnectionError / isConnectionError · CC withRetry.ts:112-118
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 陈旧连接判定 · CC withRetry.ts:112-118 {@code isStaleConnectionError}。
     *
     * <p>CC 先 {@code error instanceof APIConnectionError} 类型闸，再走 cause 链
     * （maxDepth=5，errorUtils.ts:62-83 extractConnectionErrorDetails）取 root code
     * {@code ECONNRESET / EPIPE}。Java 无 APIConnectionError 类型（Java 连接错误为
     * IOException/SocketException 或其包装），等价实现为前置 {@link #isConnectionError}
     * 类型闸（收紧后仅 IOException 面进入消息匹配，对齐 CC:113 类型闸语义），再走
     * cause 链（≤5 层）message 命中 ECONNRESET/Connection reset/EPIPE/Broken pipe。
     *
     * <p><b>disableKeepAlive 副作用 N/A</b>（Java HttpClient 无 per-request keep-alive
     * 禁用等价物），分类判定保留。
     *
     * @param e 异常
     * @return true=ECONNRESET/EPIPE 陈旧连接错误
     */
    public static boolean isStaleConnectionError(Throwable e) {
        if (!isConnectionError(e)) {
            return false;
        }
        Throwable cur = e;
        for (int i = 0; cur != null && i < 5; i++) {
            String msg = cur.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase();
                if (lower.contains("econnreset") || lower.contains("connection reset")
                    || lower.contains("epipe") || lower.contains("broken pipe")) {
                    return true;
                }
            }
            cur = cur.getCause();
        }
        return false;
    }

    /**
     * 连接类错误判定 · CC APIConnectionError 等价（withRetry.ts:753-755 shouldRetry 分支；
     * bridgeMain.ts:1590-1601 isConnectionError 为 err.code ∈ {ECONNRESET, ETIMEDOUT,
     * ENETUNREACH, EHOSTUNREACH} 对象闸，无消息子串）。
     *
     * <p>cause 链（最多 5 层）任一为 {@link IOException} 类型闸。Java 连接错误为
     * IOException/SocketException 或其包装；4xx body 含 "connection reset"/"timed out"
     * 文本的非 IOException 异常不判连接错误（与 CC APIConnectionError 类型闸一致：
     * withRetry.ts:753-755 仅 instanceof APIConnectionError → true）。
     *
     * @param e 异常
     * @return true=连接类错误（可重试）
     */
    public static boolean isConnectionError(Throwable e) {
        if (e == null) return false;
        // 仅类型闸：cause 链（最多 5 层）任一为 IOException（对齐 CC:753-755
        // instanceof APIConnectionError；bridgeMain.ts:1590-1601 code 闸等价），
        // 无 message 子串匹配 —— 4xx body 含连接文本的非 IOException 不判连接错误。
        Throwable cur = e;
        for (int i = 0; cur != null && i < 5; i++) {
            if (cur instanceof IOException) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }

    // ════════════════════════════════════════════════════════════════════════
    // isPersistentRetryEnabled · CC withRetry.ts:100-104
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 持久重试启用判定 · CC withRetry.ts:100-104 {@code isPersistentRetryEnabled}。
     *
     * <p>CC 为 {@code feature('UNATTENDED_RETRY') ? isEnvTruthy(CLAUDE_CODE_UNATTENDED_RETRY)
     * : false}；Java 无 statsig feature 等价 → 仅按 env truthy（N/A 标注 feature 门）。
     * Java env 名以 NEXUSAI_ 前缀改写（CC original: CLAUDE_CODE_UNATTENDED_RETRY withRetry.ts:102）。
     * <b>V-PF-4</b>：本方法为持久重试门控<b>唯一来源</b>（QueryConfig.unattendedRetryEnabled 字段已删，
     * CC query/config.ts gates 无等价字段）。
     *
     * @return true=启用 NEXUSAI_UNATTENDED_RETRY
     */
    public static boolean isPersistentRetryEnabled() {
        return isEnvTruthy(env(ApiErrors.ENV_NEXUSAI_UNATTENDED_RETRY));
    }

    // ════════════════════════════════════════════════════════════════════════
    // 凭证自愈分类 · CC withRetry.ts:623-694
    // ════════════════════════════════════════════════════════════════════════

    /**
     * OAuth token 吊销判定 · CC withRetry.ts:623-629 {@code isOAuthTokenRevokedError}。
     *
     * @param e 异常
     * @return true=403 且 message 含 "OAuth token has been revoked"
     */
    public static boolean isOAuthTokenRevokedError(Throwable e) {
        if (!(e instanceof LlmApiException lae) || lae.status() != 403) {
            return false;
        }
        return lae.getMessage() != null
            && lae.getMessage().contains("OAuth token has been revoked");
    }

    /**
     * AWS CredentialsProviderError 判定 · 对齐 CC utils/aws.ts isAwsCredentialsProviderError
     * （duck-typed {@code err.name === 'CredentialsProviderError'}）。
     *
     * <p>Java 无 AWS SDK 异常类型直通，等价为异常类名或 message 含 "CredentialsProviderError"。
     *
     * @param e 异常
     * @return true=CredentialsProviderError
     */
    public static boolean isAwsCredentialsProviderError(Throwable e) {
        if (e == null) return false;
        String name = e.getClass().getSimpleName();
        String msg = e.getMessage();
        if (name.contains("CredentialsProviderError")) return true;
        return msg != null && msg.contains("CredentialsProviderError");
    }

    /**
     * Bedrock 认证错误判定 · CC withRetry.ts:631-644 {@code isBedrockAuthError}。
     *
     * <p>env {@code CLAUDE_CODE_USE_BEDROCK} truthy 时：CredentialsProviderError 或
     * APIError 403。
     *
     * @param e 异常
     * @return true=Bedrock 认证错误
     */
    public static boolean isBedrockAuthError(Throwable e) {
        if (!isEnvTruthy(env("CLAUDE_CODE_USE_BEDROCK"))) {
            return false;
        }
        if (isAwsCredentialsProviderError(e)) {
            return true;
        }
        return e instanceof LlmApiException lae && lae.status() == 403;
    }

    /**
     * AWS 凭证错误处理 · CC withRetry.ts:650-656 {@code handleAwsCredentialError}。
     *
     * <p>{@code clearAwsCredentialsCache}（auth.ts:809）Java 无 AWS 凭证缓存等价 → N/A
     * 空操作（仅日志）。返回 true 表示已处理（凭证错误可重试）。
     *
     * <p><b>V-EC-4 env 未设 N/A 标注</b>：本方法仅在 {@code CLAUDE_CODE_USE_BEDROCK}
     * env truthy 时可达（isBedrockAuthError 门），本项目自托管部署<b>未设该 env</b>且
     * 无 Bedrock 传输通道（仅直连 Anthropic x-api-key HTTP）→ 生产不可达。即使 env 打开，
     * Java 亦无 AWS 凭证缓存可清、无客户端刷新通道；CC 的重试用新失效凭证至耗尽问题在
     * Java 侧因无 Bedrock provider 而不存在（N/A 记录，不伪造实现）。
     *
     * @param e 异常
     * @return true=Bedrock 认证错误已处理
     */
    public static boolean handleAwsCredentialError(Throwable e) {
        if (!isBedrockAuthError(e)) {
            return false;
        }
        log.warn("ErrorClassifier: Bedrock 认证错误，清除 AWS 凭证缓存（clearAwsCredentialsCache · CC auth.ts:809；Java 无等价缓存，N/A）");
        return true;
    }

    /**
     * Google Auth Library 凭证错误判定 · CC withRetry.ts:660-668
     * {@code isGoogleAuthLibraryCredentialError}（google-auth-library 抛 plain Error）。
     *
     * @param e 异常
     * @return true=message 含默认凭证加载/刷新失败或 invalid_grant
     */
    public static boolean isGoogleAuthLibraryCredentialError(Throwable e) {
        if (!(e instanceof Throwable t)) {
            return false;
        }
        String msg = t.getMessage();
        if (msg == null) return false;
        return msg.contains("Could not load the default credentials")
            || msg.contains("Could not refresh access token")
            || msg.contains("invalid_grant");
    }

    /**
     * Vertex 认证错误判定 · CC withRetry.ts:670-682 {@code isVertexAuthError}。
     *
     * <p>env {@code CLAUDE_CODE_USE_VERTEX} truthy 时：Google Auth Library 凭证错误或
     * APIError 401。
     *
     * @param e 异常
     * @return true=Vertex 认证错误
     */
    public static boolean isVertexAuthError(Throwable e) {
        if (!isEnvTruthy(env("CLAUDE_CODE_USE_VERTEX"))) {
            return false;
        }
        if (isGoogleAuthLibraryCredentialError(e)) {
            return true;
        }
        return e instanceof LlmApiException lae && lae.status() == 401;
    }

    /**
     * GCP 凭证错误处理 · CC withRetry.ts:688-694 {@code handleGcpCredentialError}。
     *
     * <p>{@code clearGcpCredentialsCache}（auth.ts:983）Java 无 GCP 凭证缓存等价 → N/A
     * 空操作（仅日志）。返回 true 表示已处理。
     *
     * <p><b>V-EC-4 env 未设 N/A 标注</b>：本方法仅在 {@code CLAUDE_CODE_USE_VERTEX}
     * env truthy 时可达（isVertexAuthError 门），本项目自托管部署<b>未设该 env</b>且
     * 无 Vertex 传输通道（仅直连 Anthropic x-api-key HTTP）→ 生产不可达（N/A 记录，不伪造实现）。
     *
     * @param e 异常
     * @return true=Vertex 认证错误已处理
     */
    public static boolean handleGcpCredentialError(Throwable e) {
        if (!isVertexAuthError(e)) {
            return false;
        }
        log.warn("ErrorClassifier: Vertex 认证错误，清除 GCP 凭证缓存（clearGcpCredentialsCache · CC auth.ts:983；Java 无等价缓存，N/A）");
        return true;
    }

    // ════════════════════════════════════════════════════════════════════════
    // shouldRetry 全集 · CC withRetry.ts:696-787
    // ════════════════════════════════════════════════════════════════════════

    /**
     * shouldRetry 全集判定 · CC withRetry.ts:696-787 {@code shouldRetry}。
     *
     * <p>决策链（按 CC 源码顺序）：
     * <ol>
     *   <li>mock 错误排除（CC:697-700）→ N/A（Java mock 错误非 Throwable）</li>
     *   <li>persistent && transient → true（CC:704-706）</li>
     *   <li>CCR（CLAUDE_CODE_REMOTE）401/403 → true（CC:712-717）</li>
     *   <li>message 含 {@code "type":"overloaded_error"} → true（CC:722-724）</li>
     *   <li>max_tokens 上下文溢出 → true（CC:727-729，X-37；联动 ER-IMP-08）</li>
     *   <li>x-should-retry header：'true' && (!subscriber || enterprise) → true（CC:737-742）；
     *       'false' → 除非 (ant && 5xx) 否则 false（CC:746-751）</li>
     *   <li>连接错误 → true（CC:753-755）</li>
     *   <li>!status → false（CC:757-758）</li>
     *   <li>408 → true（CC:760-761）；409 → true（CC:763-764）</li>
     *   <li>429 → !subscriber || enterprise（CC:767-769；N/A → 非订阅 → true）</li>
     *   <li>401 → clearApiKeyHelperCache + false（BUG1-401：CC:773-776 为 true 清缓存后有限重试，
     *       Java 无 key 缓存/无 OAuth 刷新 → 认证失败直接失败，不按退避重试）</li>
     *   <li>OAuth revoked 403 → true（CC:779-781）</li>
     *   <li>status >= 500 → true（CC:784-785）</li>
     * </ol>
     *
     * @param e 异常
     * @return true=可重试
     */
    public static boolean shouldRetry(Throwable e) {
        if (e == null) return false;
        // CC:704-706 persistent && transient → true
        if (isPersistentRetryEnabled() && isTransientCapacityError(e)) {
            return true;
        }
        // CC:712-717 CCR（CLAUDE_CODE_REMOTE）401/403 → true（JWT 临时抖动）
        if (isEnvTruthy(env("CLAUDE_CODE_REMOTE"))
            && e instanceof LlmApiException laeCcr
            && (laeCcr.status() == 401 || laeCcr.status() == 403)) {
            return true;
        }
        // CC:722-724 overloaded_error message → true
        if (e instanceof LlmApiException laeMsg
            && laeMsg.getMessage() != null
            && laeMsg.getMessage().contains("\"type\":\"overloaded_error\"")) {
            return true;
        }
        // CC:727-729 max_tokens 上下文溢出 → true（X-37）
        if (parseMaxTokensContextOverflowError(e) != null) {
            return true;
        }
        // CC:732-751 x-should-retry header
        String shouldRetryHeader = e instanceof LlmApiException laeHdr
            ? laeHdr.getHeader("x-should-retry") : null;
        if ("true".equals(shouldRetryHeader)
            && (!isClaudeAISubscriber() || isEnterpriseSubscriber())) {
            return true;
        }
        if ("false".equals(shouldRetryHeader)) {
            boolean is5xx = e instanceof LlmApiException lae5 && lae5.status() >= 500;
            // ant 用户 + 5xx 例外可忽略 x-should-retry:false（CC:746-751）
            if (!("ant".equals(env("USER_TYPE")) && is5xx)) {
                return false;
            }
        }
        // CC:753-755 APIConnectionError → true
        if (isConnectionError(e)) {
            return true;
        }
        // CC:757-758 !status → false（非 LlmApiException 且非连接错误）
        if (!(e instanceof LlmApiException apiErr)) {
            return false;
        }
        int status = apiErr.status();
        // CC:760-761 408 → true
        if (status == 408) return true;
        // CC:763-764 409 → true
        if (status == 409) return true;
        // CC:767-769 429 → !isClaudeAISubscriber() || isEnterpriseSubscriber()（N/A → 非订阅 → true）
        if (status == 429) return true;
        // 401 → clearApiKeyHelperCache + false（non-retryable）· BUG1-401
        // CC withRetry.ts:773-776 原为 true（清 key 缓存后以新 key 有限重试）；
        // 用户决策 BUG1-401：Java 无 key helper 缓存（N/A）亦无 OAuth token 刷新通道，
        // 401 属认证失败而非 rate limit，按退避语义重试无意义 → 判 non-retryable 直接失败。
        // 仍保留 clearApiKeyHelperCache()（清缓存供下次新 key，CC 语义保留）。
        if (status == 401) {
            clearApiKeyHelperCache();
            return false;
        }
        // CC:779-781 OAuth revoked 403 → true
        if (isOAuthTokenRevokedError(apiErr)) {
            return true;
        }
        // CC:784-785 status >= 500 → true
        return status >= 500;
    }

    /**
     * 可重试总判定 · CC withRetry.ts:375-382 等价（handleAwsCredentialError/handleGcpCredentialError
     * 或 shouldRetry）。供 LlmAgentLoop Path3 入口门使用。
     *
     * @param e 异常
     * @return true=可重试（凭证自愈或 shouldRetry）
     */
    public static boolean isRetryable(Throwable e) {
        return handleAwsCredentialError(e)
            || handleGcpCredentialError(e)
            || shouldRetry(e);
    }

    // ════════════════════════════════════════════════════════════════════════
    // max_tokens 上下文溢出解析 · CC withRetry.ts:550-595（X-37 联动 ER-IMP-08）
    // ════════════════════════════════════════════════════════════════════════

    /** CC original: parseMaxTokensContextOverflowError regex (withRetry.ts:570-571) */
    private static final Pattern MAX_TOKENS_OVERFLOW_PATTERN =
        Pattern.compile("input length and `max_tokens` exceed context limit: (\\d+) \\+ (\\d+) > (\\d+)");

    /**
     * 解析 max_tokens 上下文溢出错误 · CC withRetry.ts:550-595
     * {@code parseMaxTokensContextOverflowError}。
     *
     * <p>status=400 且 message 含 {@code "input length and `max_tokens` exceed context limit"}，
     * 正则提取 inputTokens/maxTokens/contextLimit，三值全非 NaN 才返回。
     * shouldRetry 分支（CC:727-729）据此返回 true；retryContext.maxTokensOverride 调整
     * 属 ER-IMP-08。
     *
     * @param e 异常
     * @return 溢出数据 record，或 null 表示非溢出错误
     */
    public static MaxTokensOverflowError parseMaxTokensContextOverflowError(Throwable e) {
        if (!(e instanceof LlmApiException lae) || lae.status() != 400 || lae.getMessage() == null) {
            return null;
        }
        if (!lae.getMessage().contains("input length and `max_tokens` exceed context limit")) {
            return null;
        }
        Matcher m = MAX_TOKENS_OVERFLOW_PATTERN.matcher(lae.getMessage());
        if (!m.find() || m.groupCount() != 3) {
            return null;
        }
        Integer inputTokens = tryParseInt(m.group(1));
        Integer maxTokens = tryParseInt(m.group(2));
        Integer contextLimit = tryParseInt(m.group(3));
        if (inputTokens == null || maxTokens == null || contextLimit == null) {
            return null;
        }
        return new MaxTokensOverflowError(inputTokens, maxTokens, contextLimit);
    }

    private static Integer tryParseInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Prompt-too-long 检测 · 对齐 CC services/api/errors.ts:64 isPromptTooLongMessage
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 消息级 prompt-too-long 判定 · CC services/api/errors.ts:64-77 {@code isPromptTooLongMessage}。
     *
     * <p>CC 行为：检查最后一条 assistant message 的 {@code isApiErrorMessage} 标志 +
     * content 是否以 {@code "Prompt is too long"} 开头（CC errors.ts:62 {@code PROMPT_TOO_LONG_ERROR_MESSAGE}）。
     * 该函数在 query.ts:1070-1073 与 {@code isWithheld413} 判定联合使用，
     * 区分流式 withhold 的 PTL 消息（消息级）和异常路径的 PTL（异常级）。
     *
     * @param msg 最后一条 assistant 消息（{@link com.nexusai.model.session.dto.ChatMessageDto}）
     * @return true=消息级 PTL（isApiErrorMessage=true 且 content 以 "Prompt is too long" 开头）
     */
    public static boolean isPromptTooLongMessage(com.nexusai.model.session.dto.ChatMessageDto msg) {
        if (msg == null || !msg.isApiErrorMessage()) {
            return false;
        }
        String content = msg.content();
        return content != null && content.startsWith(com.nexusai.application.agent.api.ApiErrors.PROMPT_TOO_LONG_ERROR_MESSAGE);
    }

    /**
     * 消息级 media-size 判定 · CC services/api/errors.ts:147-153 {@code isMediaSizeErrorMessage}。
     *
     * <p>CC 行为：{@code msg.isApiErrorMessage === true && msg.errorDetails !== undefined &&
     * isMediaSizeError(msg.errorDetails)} —— 检查 {@code errorDetails}（getAssistantMessageFromError
     * 分支填充的原始 API 错误串，errors.ts ~L523/560/573）而非 content 文本（media 错误各变体
     * content 不同，errors.ts:143-145 注释）。
     *
     * <p><b>[P-11 闭环 2026-08-19]</b>：query.ts:1082-1084 {@code isWithheldMedia = mediaRecoveryEnabled &&
     * reactiveCompact?.isWithheldMediaSizeError(lastMessage)} 的谓词镜像，供 LlmAgentLoop 消息级
     * media 恢复门控使用；异常级（LlmApiException Kind.IMAGE）由调用方经
     * {@link ApiErrorMessageFactory#createMediaSizeErrorApiMessage}（生产生产者）转回消息级后
     * 进入本谓词 —— LlmAgentLoop:4802 {@code isMediaError = mediaRecoveryEnabled &&
     * isMediaSizeErrorMessage(lastAssistantMsg)} 可命中 → 走 reactive compact 恢复链。
     * 原「异常级直 surface、本谓词无生产生产者」受控残留（P-11）已解除。
     *
     * @param msg 最后一条 assistant 消息（{@link com.nexusai.model.session.dto.ChatMessageDto}）
     * @return true=消息级 media-size 拒绝（isApiErrorMessage=true 且 errorDetails 命中子串谓词）
     */
    public static boolean isMediaSizeErrorMessage(com.nexusai.model.session.dto.ChatMessageDto msg) {
        if (msg == null || !msg.isApiErrorMessage() || msg.errorDetails() == null) {
            return false;
        }
        return isMediaSizeError(msg.errorDetails());
    }

    /**
     * 原始 API 错误文本 media-size 子串判定 · CC services/api/errors.ts:133-139
     * {@code isMediaSizeError}（image exceeds + maximum / image dimensions exceed + many-image /
     * maximum of N PDF pages 正则）。
     *
     * <p><b>[P-11 生产生产者]</b>：本谓词是消息级 media 恢复链的<b>唯一闭环闸</b>——errorDetails
     * 只在 {@code getAssistantMessageFromError} 媒体分支（errors.ts:577-586/612-639）匹配相同子串后
     * 才被填充，故 {@code isMediaSizeError(errorDetails)} 对该路径恒真（errors.ts:125-131 注释）。
     * {@link #isMediaSizeErrorMessage} 与 {@link ApiErrorMessageFactory#createMediaSizeErrorApiMessage}
     * 共用本谓词，保证产出的 errorDetails 一定使消息级判定命中。
     *
     * @param raw 原始错误文本（errorDetails 载荷）
     * @return true=media-size 拒绝（strip-retry 可修复）
     */
    public static boolean isMediaSizeError(String raw) {
        if (raw == null) {
            return false;
        }
        return (raw.contains("image exceeds") && raw.contains("maximum"))
            || (raw.contains("image dimensions exceed") && raw.contains("many-image"))
            || java.util.regex.Pattern.matches(".*maximum of \\d+ PDF pages.*", raw);
    }

    /**
     * 提取原始 API 错误文本 · CC original: {@code error.message}（getAssistantMessageFromError
     * 各分支填充 {@code errorDetails: error.message}，errors.ts:572/584/621/637）。
     *
     * <p>Java 等价：优先 {@link LlmApiException#body()}（API 响应体原文，translateSdkError 从
     * {@code se.body()} 提取，无 "HTTP xxx: " 前缀），body 空则回落 {@code getMessage()}
     * （"HTTP {status}: " + 截断 body）。保证下游 {@link #isMediaSizeError} 子串命中与
     * {@code errorDetails} 载荷内容一致（同一字符串）。
     *
     * @param error 流错误（provider 已翻译为 LlmApiException 保留 status/body）
     * @return 原始错误文本（errorDetails 载荷），null=无文本
     */
    public static String rawErrorText(Throwable error) {
        if (error == null) {
            return null;
        }
        if (error instanceof LlmApiException lae) {
            if (lae.body() != null && !lae.body().isBlank()) {
                return lae.body();
            }
        }
        return error.getMessage();
    }

    // ════════════════════════════════════════════════════════════════════════
    // Retry-After 提取 · CC withRetry.ts:519-528 getRetryAfter（只读 header）
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 从 {@link LlmApiException} 提取 Retry-After 秒数 · CC withRetry.ts:519-528 getRetryAfter。
     *
     * <p>CC 只读 {@code error.headers?.['retry-after']}，不解析消息文本（DC-04/DC-05：
     * 旧消息正则 fallback 已删）。parseInt 容忍前导数字（"120.5"/"120abc" → 120，
     * 见 {@link RetryDelayCalculator#jsParseInt} 与 withRetry.ts:536），无前导数字 → null
     * （退避回退指数公式）。
     *
     * @param ex LLM API 异常（含 HTTP headers）
     * @return Retry-After 秒数（0 → 0L，下游 calculate 返回 0ms），或 null 表示未指定
     */
    public static Long extractRetryAfterSeconds(LlmApiException ex) {
        if (ex == null) return null;
        String headerVal = ex.getHeader("retry-after");
        if (headerVal == null) {
            return null;
        }
        Long seconds = RetryDelayCalculator.jsParseInt(headerVal);
        if (seconds != null) {
            log.info("ErrorClassifier: 从 Retry-After header 提取到 {}s (parseInt 前导数字容忍)", seconds);
            return seconds;
        }
        if (log.isDebugEnabled()) {
            log.debug("ErrorClassifier: Retry-After header 非数字: {}", headerVal);
        }
        return null;
    }

    // ════════════════════════════════════════════════════════════════════════
    // helpers
    // ════════════════════════════════════════════════════════════════════════

    /**
     * env truthy 判定 · CC utils/envUtils.ts:32 isEnvTruthy。
     *
     * @param envVar 环境变量值（可能为 null）
     * @return true=值规范化后 ∈ {1,true,yes,on}
     */
    public static boolean isEnvTruthy(String envVar) {
        if (envVar == null) return false;
        String normalized = envVar.trim().toLowerCase();
        return normalized.equals("1") || normalized.equals("true")
            || normalized.equals("yes") || normalized.equals("on");
    }

    /**
     * Claude AI 订阅判定 · CC auth.ts:1564 isClaudeAISubscriber。
     *
     * <p><b>N/A（用户决策 3）</b>：本项目自托管 Web 无订阅层，恒 false（视为非订阅）。
     * shouldRetry 的 429 闸 / x-should-retry 'true' 闸因此均按非订阅处理。
     *
     * @return false（N/A）
     */
    public static boolean isClaudeAISubscriber() {
        return false;
    }

    /**
     * 企业订阅判定 · CC auth.ts:1694 isEnterpriseSubscriber。
     *
     * <p><b>N/A（用户决策 3）</b>：本项目无企业订阅层，恒 false。
     *
     * @return false（N/A）
     */
    public static boolean isEnterpriseSubscriber() {
        return false;
    }

    /**
     * API key helper cache 清除 · CC auth.ts:585 clearApiKeyHelperCache。
     *
     * <p><b>N/A</b>：Java 无 API key helper 缓存（等价物缺失），空操作。CC 在 shouldRetry
     * 401 分支调用后允许重试（withRetry.ts:773-776）；BUG1-401 用户决策改为仍调用
     * （保留清缓存供下次新 key 语义）但返回 false 直接失败。
     */
    private static void clearApiKeyHelperCache() {
        // CC auth.ts:585 clearApiKeyHelperCache — Java 无 API key helper 缓存（N/A 空操作）
    }
}
