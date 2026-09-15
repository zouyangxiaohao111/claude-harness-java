package com.nexusai.application.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.nexusai.application.agent.agent.CwdResolution;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * User Input Dispatcher · 对齐 CC utils/processUserInput/processUserInput.ts (605 行).
 *
 * <p>FIX-USERINPUT-DISP: 简化版 user-input 总路由 (slash command / bash shortcut / file mode / text prompt).
 *
 * <p>L1 行为: 接受 user input, 路由到对应处理器 (slash → CommandRouter; bash → BashShortcut;
 * file mode → FileResolver; default → text prompt).
 */
@Component
public class UserInputDispatcher {

    private static final Logger log = LoggerFactory.getLogger(UserInputDispatcher.class);

    public enum InputKind { SLASH_COMMAND, BASH_SHORTCUT, FILE_MODE, TEXT_PROMPT }

    public record RoutingResult(InputKind kind, String routedTo, String payload) {}

    private final Map<InputKind, Consumer<String>> handlers = new ConcurrentHashMap<>();

    /**
     * 按命令名注册的 slash command handler（CC parseSlashCommand → findCommand 语义）· INV-14。
     *
     * <p><b>[批 3c · 2026-09-13]</b> handler 签名由 {@code Consumer<String>} 改为
     * {@link TriConsumer} = {@code (args, sessionId, inFlightUserMessageId)}。
     * WHY：命令 handler 里原以裸 MDC 会话槽取会话与在途用户消息 id（/files /plan /compact …），
     * 该读点存在第三态（可能读到上一请求残留的、别的会话的 id）且随批 3c 删除 ⇒ 二者必须<b>显式</b>
     * 从分派入口穿进来（对齐 CC {@code command.call(args, context)} 的 context 显式携带语义）。
     */
    private final Map<String, Consumer<SlashCommandContext>> slashCommandHandlers = new ConcurrentHashMap<>();

    /**
     * 按命令名注册的 slash command <b>result</b> handler（Plan-P1 §4.3 · 镜像 CC
     * {@code mod.call(args, context) → LocalCommandResult}）。result handler 可回传执行结果
     * （text/skip），供 SlashCommandInterceptor 组装 {@code <local-command-stdout>} 结果消息。
     *
     * <p><b>[批 3c · 2026-09-13]</b> 签名由 {@code Function<String, LocalCommandResult>} 改为
     * {@link TriFunction} = {@code (args, sessionId, inFlightUserMessageId)}，
     * 同 {@link #slashCommandHandlers} 的 WHY（显式会话标识 + 显式在途消息 id，杜绝 MDC 第三态）。
     */
    private final Map<String, Function<SlashCommandContext, LocalCommandResult>> slashCommandResultHandlers =
        new ConcurrentHashMap<>();

    /** {@code (args, sessionId, inFlightUserMessageId) → void} · JDK 无三参 Consumer，本地声明。
     *
     *  <p><b>[批 r10]</b> 本接口<b>保留</b>为源兼容面：{@link #registerSlashCommand(String, TriConsumer)}
     *  已是转到 {@link #registerSlashCommandCtx} 的**薄包装**，既有 32 个 lambda 调用点**源码零改动**。
     *  新代码（本批迁移的 12 站）用 ctx 形态。 */
    @FunctionalInterface
    public interface TriConsumer {
        void accept(String args, String sessionId, String inFlightUserMessageId);
    }

    /** {@code (args, sessionId, inFlightUserMessageId) → LocalCommandResult} · JDK 无三参 Function，本地声明。
     *
     *  <p><b>[批 r10]</b> 同 {@link TriConsumer}：保留为薄包装层。 */
    @FunctionalInterface
    public interface TriFunction {
        LocalCommandResult apply(String args, String sessionId, String inFlightUserMessageId);
    }

    /**
     * slash command 执行上下文 · 对齐 CC {@code command.call(args, context)}
     * （processSlashCommand.tsx:869 / command.ts:62-65：handler 收 context、<b>不收 cwd</b>）的
     * <b>显式携带</b>语义。
     *
     * <p><b>[批 r10] 为什么需要它</b>：CC 命令内直调 ambient {@code getCwd()} 天然正确，前提是
     * 「<b>进程 = 会话</b>」（cwd.ts:19-21 单进程单值）。本仓是 <b>1 JVM : N 会话</b>，
     * {@code CwdResolution.getCwd(String)} 必须按会话键查表 ⇒ 照抄「直调」就等于「每层按 sessionId
     * 反查」（config 包内原有 11 处）。本类把命令执行所需的三个显式标识
     * （args / sessionId / inFlightUserMessageId，同批 3c 已定的「会话态必须显式穿透」铁律）加上
     * <b>两条惰性会话解析槽</b>一次性携带到 handler，使 handler 不再自行反查。
     *
     * <p><b>⭐ 为什么必须惰性（本类第一约束，改动前务必读）</b>：若在构造期 eager 求值，则
     * <b>所有</b>注册命令（36 个）都会在「会话存在但未绑定项目根」时新增 fail-loud 抛出
     * （{@link CwdResolution#getCwd} / {@link CwdResolution#getOriginalCwdLayer} 对该态抛
     * {@code IllegalStateException}），而改造前只有真正需要 cwd 的 9 个命令会抛。
     * 惰性 + memoize ⇒ 只有真正读 {@link #cwd()}/{@link #projectRoot()} 的 handler 才触发解析，
     * <b>fail-loud 面严格不变</b>；同一 handler 内多次读取收敛为 1 次解析。
     *
     * <p><b>⚠️ 测试环境下 fail-loud 分支结构上不可达</b>：全局 JUnit 扩展
     * {@code com.nexusai.test.support.NoDatabaseSessionProjectRootExtension}（经
     * {@code src/test/resources/META-INF/services/org.junit.jupiter.api.extension.Extension}
     * 自动注册）对<b>任意</b> sessionId 都答 {@code Lookup.sessionlessEnvironment()} ⇒ 走
     * 「确无会话」命名出口（进程 user.dir），<b>永不抛</b>。要覆盖 fail-loud <b>必须</b>显式
     * {@code SessionProjectRoot.setDbResolver(...)} 覆盖它（先例：{@code CwdResolutionTest}）。
     * ⛔ 不要因为「把 {@code cwd()} 改成 eager 却没变红」就断言惰性无影响 —— 那只说明装置没接上。
     *
     * <p><b>双槽</b>：4 站要 getCwd 语义、7 站要 getOriginalCwdLayer 语义，两条 L1 不同
     * （{@link CwdResolution} 的 sessionCwd 层 vs originalCwd 重锚层）⇒ 两槽独立缓存，互不串味。
     *
     * <p><b>线程</b>：非线程安全（解析结果非 volatile）。上下文由分派点构造后**同一线程**同步交给
     * handler，不跨线程发布。<b>异常不缓存</b>：解析抛出时标记不置位（下次调用重新解析并重新抛），
     * 保持 fail-loud 可重复暴露。
     */
    public static final class SlashCommandContext {

        private final String args;
        private final String sessionId;
        private final String inFlightUserMessageId;
        private final Function<String, String> cwdResolver;
        private final Function<String, String> projectRootResolver;

        private String cwd;
        private boolean cwdResolved;
        private String projectRoot;
        private boolean projectRootResolved;

        SlashCommandContext(String args, String sessionId, String inFlightUserMessageId,
                            Function<String, String> cwdResolver,
                            Function<String, String> projectRootResolver) {
            this.args = args;
            this.sessionId = sessionId;
            this.inFlightUserMessageId = inFlightUserMessageId;
            this.cwdResolver = cwdResolver;
            this.projectRootResolver = projectRootResolver;
        }

        /** 命令名后的参数文本（已 trim）。 */
        public String args() {
            return args;
        }

        /** 分派入口显式传入的会话 ID（<b>可为 null</b>：非会话驱动的分派）。 */
        public String sessionId() {
            return sessionId;
        }

        /** 本轮「在途用户消息 id」（旧 MDC {@code reqId}；<b>可为 null</b>）。 */
        public String inFlightUserMessageId() {
            return inFlightUserMessageId;
        }

        /**
         * 当前工作目录 · <b>getCwd 语义</b>（受 bash {@code cd} 影响，对齐 CC {@code getCwd()}）。
         * 首次调用解析并 memoize。
         */
        public String cwd() {
            if (!cwdResolved) {
                cwd = nonBlankOrUserDir(cwdResolver.apply(sessionId));
                cwdResolved = true;
            }
            return cwd;
        }

        /**
         * 原始工作目录 · <b>getOriginalCwdLayer 语义</b>（会话存档锚，对齐 CC {@code getOriginalCwd()}；
         * 不受 bash {@code cd} 影响，随 worktree 重锚）。首次调用解析并 memoize。
         *
         * <p>⛔ <b>与 {@link #cwd()} 不可互相替代</b>：在发生过 bash {@code cd} 的会话里两者必然不同值
         * （cwd 槽被 cd 覆盖、originalCwd 槽不被覆盖）⇒ 用 {@code cwd()} 顶替会使 transcript 锚点漂到
         * cd 后的子目录。
         */
        public String projectRoot() {
            if (!projectRootResolved) {
                projectRoot = nonBlankOrUserDir(projectRootResolver.apply(sessionId));
                projectRootResolved = true;
            }
            return projectRoot;
        }

        /**
         * user.dir 兜底 · <b>单点</b>（原 config 包内 4 份
         * {@code if (x == null || x.isBlank()) x = System.getProperty("user.dir", ".");} 样板收敛于此）。
         *
         * <p>⚠️ 只兜「解析值为 null / 空白」；解析器**抛出**时原样向上抛（fail-loud 不被吞）。
         */
        private static String nonBlankOrUserDir(String v) {
            return v != null && !v.isBlank() ? v : System.getProperty("user.dir", ".");
        }
    }

    public void register(InputKind kind, Consumer<String> handler) {
        handlers.put(kind, handler);
    }

    /**
     * 注册命名 slash command handler · 对齐 CC processUserInput 的
     * {@code parseSlashCommand(input) → findCommand(name) → command.call(args, ...)}。
     *
     * <p>例如 {@code registerSlashCommand("compact", (args, sessionId, msgId) -> ...)} 后，输入
     * {@code /compact x y} 会路由到该 handler，args 为 {@code "x y"}。
     *
     * @param name    slash command 名（不含前导 '/'；如 "compact"）
     * @param handler 参数 handler：{@code (args, sessionId, inFlightUserMessageId)}。
     *                args = 命令名后的参数文本（已 trim）；sessionId = 分派入口显式传入的会话 ID；
     *                inFlightUserMessageId = 本轮的「在途用户消息 id」（旧 MDC {@code reqId}，
     *                即 ChatService {@code set(sessionId, userMessageId)} 的 userMessageId）。
     *                后两者<b>均可为 null</b>（非会话/非用户消息驱动的分派）。
     */
    public void registerSlashCommand(String name, TriConsumer handler) {
        // [批 r10] 薄包装：既有 32 个三参 lambda 调用点源码零改动。
        registerSlashCommandCtx(name, ctx ->
            handler.accept(ctx.args(), ctx.sessionId(), ctx.inFlightUserMessageId()));
    }

    /**
     * 注册命名 slash command handler · <b>ctx 形态</b>（[批 r10]）。
     *
     * <p>与 {@link #registerSlashCommand(String, TriConsumer)} 是同一张表、同一语义，只是把三个散参
     * 换成显式携带的执行上下文（含惰性 {@link SlashCommandContext#cwd()} /
     * {@link SlashCommandContext#projectRoot()} 槽）。新代码与迁移站点用本方法。
     *
     * @param name    slash command 名（不含前导 '/'）
     * @param handler 上下文 handler；<b>不得</b>在体内提前触发解析（惰性约束见
     *                {@link SlashCommandContext} 类注释）
     */
    public void registerSlashCommandCtx(String name, Consumer<SlashCommandContext> handler) {
        boolean overwrite = slashCommandHandlers.containsKey(name);
        slashCommandHandlers.put(name, handler);
        if (log.isDebugEnabled()) {
            log.debug("注册命名 slash command handler: name={} overwrite={}（对齐 CC findCommand 语义）",
                name, overwrite);
        }
    }

    /**
     * [P5-②] 命名 slash command handler 是否已注册 · immediate local-jsx 命令 busy 优先判定用。
     *
     * <p>CC handlePromptSubmit.ts:239-252 用 {@code commands.find(cmd => cmd.immediate && ...)} 找到
     * 命令对象后经 {@code command.load() + call} 执行；Java 端"可执行"判据 = 已注册命名 handler
     * （{@link #registerSlashCommand}）。未注册 → 调用方（ChatService）log.warn + 回落原 busy 排队
     * （fail loud 不静默吞）。
     *
     * @param name 命令名（不含前导 '/'）
     * @return true = 已有命名 handler（可 dispatch）
     */
    public boolean hasSlashCommandHandler(String name) {
        // [P5-②] immediate local-jsx busy 优先判定 · 仅 void handler 注册面（result handler 走
        //   dispatchResult 分派，不经本判定）；未注册 → 调用方 log.warn + 回落原路径（fail loud）。
        return name != null && slashCommandHandlers.containsKey(name);
    }

    /**
     * result 承载 · 对齐 CC {@code LocalCommandResult}（processSlashCommand.tsx:670-713，
     * {@code {type: 'skip' | 'compact'} | {type: 'text', value}}）。
     *
     * @param kind  "text" | "skip"（compact 本批次 TODO 未接入）
     * @param value text 结果值（skip → null）
     */
    public record LocalCommandResult(String kind, String value) {

        public static LocalCommandResult text(String value) {
            return new LocalCommandResult("text", value);
        }

        public static LocalCommandResult skip() {
            return new LocalCommandResult("skip", null);
        }
    }

    /**
     * 注册命名 slash command result handler · 镜像 CC {@code mod.call(args, context) →
     * LocalCommandResult}（processSlashCommand.tsx:669）。
     *
     * <p>与 {@link #registerSlashCommand}（void）并存：result handler 优先（可回传结果）；
     * void handler 作向后兼容回落（结果不可得 → skip）。两者独立 map，同名不互相覆盖。
     *
     * @param name    slash command 名（不含前导 '/'）
     * @param handler {@code (args, sessionId, inFlightUserMessageId)} → LocalCommandResult；
     *                后两者由分派入口显式传入（<b>均可为 null</b>，见 {@link #registerSlashCommand}）
     */
    public void registerSlashCommandResult(String name, TriFunction handler) {
        // [批 r10] 薄包装：既有调用点源码零改动（见 registerSlashCommandCtx）。
        registerSlashCommandResultCtx(name, ctx ->
            handler.apply(ctx.args(), ctx.sessionId(), ctx.inFlightUserMessageId()));
    }

    /**
     * 注册命名 slash command <b>result</b> handler · <b>ctx 形态</b>（[批 r10]）。
     *
     * <p>与 {@link #registerSlashCommandResult(String, TriFunction)} 同一张表、同一语义；
     * 惰性约束同 {@link #registerSlashCommandCtx}。
     *
     * @param name    slash command 名（不含前导 '/'）
     * @param handler 上下文 handler → {@link LocalCommandResult}
     */
    public void registerSlashCommandResultCtx(String name,
                                              Function<SlashCommandContext, LocalCommandResult> handler) {
        slashCommandResultHandlers.put(name, handler);
        if (log.isDebugEnabled()) {
            log.debug("注册命名 slash command result handler: name={}（对齐 CC mod.call LocalCommandResult）",
                name);
        }
    }

    /**
     * 构造执行上下文 · 供两个分派点复用。
     *
     * <p><b>本方法不触发任何会话解析</b>（两个 resolver 只作为方法引用传入，惰性约束见
     * {@link SlashCommandContext} 类注释）。
     */
    private static SlashCommandContext newSlashCommandContext(String args, String sessionId,
                                                              String inFlightUserMessageId) {
        return new SlashCommandContext(args, sessionId, inFlightUserMessageId,
            CwdResolution::getCwd, CwdResolution::getOriginalCwdLayer);
    }

    /**
     * 分派 slash 输入并回传结果（镜像 CC {@code mod.call(args, context)}）· Plan-P1 §4.3。
     *
     * <p>入参为完整 slash 输入（{@code /name args}），内部解析 name + args。分派优先级：
     * ① result handler（{@link #registerSlashCommandResult}）→ 回传 LocalCommandResult；
     * ② void handler（{@link #registerSlashCommand}）→ 执行（向后兼容，结果不可得）→ {@code skip}；
     * ③ 两者均未注册 → {@code null}（调用方 fail loud）。
     *
     * @param input                  完整 slash 输入（须以 '/' 开头）
     * @param sessionId              显式会话 ID（命令 handler 的会话标识来源；非会话来源可传 null）
     * @param inFlightUserMessageId  本轮在途用户消息 id（旧 MDC {@code reqId}）；非用户消息驱动的
     *                               分派（如 REST 直调）可传 null
     * @return LocalCommandResult（text/skip）；无 handler → null
     */
    public LocalCommandResult dispatchResult(String input, String sessionId, String inFlightUserMessageId) {
        if (input == null || !input.startsWith("/")) {
            return null;
        }
        String rest = input.substring(1);
        int space = rest.indexOf(' ');
        String name = space == -1 ? rest : rest.substring(0, space);
        String args = space == -1 ? "" : rest.substring(space + 1).trim();
        Function<SlashCommandContext, LocalCommandResult> rh = slashCommandResultHandlers.get(name);
        if (rh != null) {
            LocalCommandResult result = rh.apply(newSlashCommandContext(args, sessionId, inFlightUserMessageId));
            if (log.isDebugEnabled()) {
                log.debug("分发命名 slash command result: session={} inFlightUserMessage={} name={} args={} kind={}",
                    sessionId, inFlightUserMessageId, name, args, result == null ? "null" : result.kind());
            }
            return result;
        }
        Consumer<SlashCommandContext> cmd = slashCommandHandlers.get(name);
        if (cmd != null) {
            if (log.isDebugEnabled()) {
                log.debug("分发命名 slash command（void 回落，结果不可得 → skip）: session={} inFlightUserMessage={} name={} args={}",
                    sessionId, inFlightUserMessageId, name, args);
            }
            cmd.accept(newSlashCommandContext(args, sessionId, inFlightUserMessageId));
            return LocalCommandResult.skip();
        }
        return null;
    }

    /**
     * 路由 + 分发 user input.
     *
     * @param input                  用户输入
     * @param sessionId              显式会话 ID（slash 命令 handler 的会话标识来源；非会话来源可传 null）
     * @param inFlightUserMessageId  本轮在途用户消息 id（旧 MDC {@code reqId}）；可为 null
     */
    public RoutingResult dispatch(String input, String sessionId, String inFlightUserMessageId) {
        if (input == null || input.isBlank()) {
            return new RoutingResult(InputKind.TEXT_PROMPT, "default", "");
        }
        if (input.startsWith("/")) {
            // CC parseSlashCommand：解析命令名 + 参数（首个空白分隔）
            String rest = input.substring(1);
            int space = rest.indexOf(' ');
            String name = space == -1 ? rest : rest.substring(0, space);
            String args = space == -1 ? "" : rest.substring(space + 1).trim();
            Consumer<SlashCommandContext> cmd = slashCommandHandlers.get(name);
            if (cmd != null) {
                if (log.isDebugEnabled()) {
                    log.debug("分发命名 slash command: session={} inFlightUserMessage={} name={} args={}",
                        sessionId, inFlightUserMessageId, name, args);
                }
                cmd.accept(newSlashCommandContext(args, sessionId, inFlightUserMessageId));
                return new RoutingResult(InputKind.SLASH_COMMAND, name, args);
            }
            // 未注册命名 handler → 回落通用 SLASH_COMMAND handler（向后兼容）
            Consumer<String> h = handlers.get(InputKind.SLASH_COMMAND);
            if (h != null) h.accept(input);
            return new RoutingResult(InputKind.SLASH_COMMAND, "command-router", input);
        }
        if (input.startsWith("!")) {
            Consumer<String> h = handlers.get(InputKind.BASH_SHORTCUT);
            if (h != null) h.accept(input.substring(1));
            return new RoutingResult(InputKind.BASH_SHORTCUT, "bash", input.substring(1));
        }
        if (input.startsWith("@")) {
            Consumer<String> h = handlers.get(InputKind.FILE_MODE);
            if (h != null) h.accept(input.substring(1));
            return new RoutingResult(InputKind.FILE_MODE, "file-resolver", input.substring(1));
        }
        Consumer<String> h = handlers.get(InputKind.TEXT_PROMPT);
        if (h != null) h.accept(input);
        return new RoutingResult(InputKind.TEXT_PROMPT, "default", input);
    }
}