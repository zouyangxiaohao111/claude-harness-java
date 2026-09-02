package com.nexusai.application.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

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

    /** 按命令名注册的 slash command handler（CC parseSlashCommand → findCommand 语义）· INV-14。 */
    private final Map<String, Consumer<String>> slashCommandHandlers = new ConcurrentHashMap<>();

    /**
     * 按命令名注册的 slash command <b>result</b> handler（Plan-P1 §4.3 · 镜像 CC
     * {@code mod.call(args, context) → LocalCommandResult}）。result handler 可回传执行结果
     * （text/skip），供 SlashCommandInterceptor 组装 {@code <local-command-stdout>} 结果消息。
     */
    private final Map<String, Function<String, LocalCommandResult>> slashCommandResultHandlers =
        new ConcurrentHashMap<>();

    public void register(InputKind kind, Consumer<String> handler) {
        handlers.put(kind, handler);
    }

    /**
     * 注册命名 slash command handler · 对齐 CC processUserInput 的
     * {@code parseSlashCommand(input) → findCommand(name) → command.call(args, ...)}。
     *
     * <p>例如 {@code registerSlashCommand("compact", args -> ...)} 后，输入 {@code /compact x y}
     * 会路由到该 handler，args 为 {@code "x y"}。
     *
     * @param name    slash command 名（不含前导 '/'；如 "compact"）
     * @param handler 参数 handler（收到命令名后的参数文本，已 trim）
     */
    public void registerSlashCommand(String name, Consumer<String> handler) {
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
     * @param handler args → LocalCommandResult
     */
    public void registerSlashCommandResult(String name, Function<String, LocalCommandResult> handler) {
        slashCommandResultHandlers.put(name, handler);
        if (log.isDebugEnabled()) {
            log.debug("注册命名 slash command result handler: name={}（对齐 CC mod.call LocalCommandResult）",
                name);
        }
    }

    /**
     * 分派 slash 输入并回传结果（镜像 CC {@code mod.call(args, context)}）· Plan-P1 §4.3。
     *
     * <p>入参为完整 slash 输入（{@code /name args}），内部解析 name + args。分派优先级：
     * ① result handler（{@link #registerSlashCommandResult}）→ 回传 LocalCommandResult；
     * ② void handler（{@link #registerSlashCommand}）→ 执行（向后兼容，结果不可得）→ {@code skip}；
     * ③ 两者均未注册 → {@code null}（调用方 fail loud）。
     *
     * @param input 完整 slash 输入（须以 '/' 开头）
     * @return LocalCommandResult（text/skip）；无 handler → null
     */
    public LocalCommandResult dispatchResult(String input) {
        if (input == null || !input.startsWith("/")) {
            return null;
        }
        String rest = input.substring(1);
        int space = rest.indexOf(' ');
        String name = space == -1 ? rest : rest.substring(0, space);
        String args = space == -1 ? "" : rest.substring(space + 1).trim();
        Function<String, LocalCommandResult> rh = slashCommandResultHandlers.get(name);
        if (rh != null) {
            LocalCommandResult result = rh.apply(args);
            if (log.isDebugEnabled()) {
                log.debug("分发命名 slash command result: name={} args={} kind={}",
                    name, args, result == null ? "null" : result.kind());
            }
            return result;
        }
        Consumer<String> cmd = slashCommandHandlers.get(name);
        if (cmd != null) {
            if (log.isDebugEnabled()) {
                log.debug("分发命名 slash command（void 回落，结果不可得 → skip）: name={} args={}", name, args);
            }
            cmd.accept(args);
            return LocalCommandResult.skip();
        }
        return null;
    }

    /** 路由 + 分发 user input. */
    public RoutingResult dispatch(String input) {
        if (input == null || input.isBlank()) {
            return new RoutingResult(InputKind.TEXT_PROMPT, "default", "");
        }
        if (input.startsWith("/")) {
            // CC parseSlashCommand：解析命令名 + 参数（首个空白分隔）
            String rest = input.substring(1);
            int space = rest.indexOf(' ');
            String name = space == -1 ? rest : rest.substring(0, space);
            String args = space == -1 ? "" : rest.substring(space + 1).trim();
            Consumer<String> cmd = slashCommandHandlers.get(name);
            if (cmd != null) {
                if (log.isDebugEnabled()) {
                    log.debug("分发命名 slash command: name={} args={}", name, args);
                }
                cmd.accept(args);
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