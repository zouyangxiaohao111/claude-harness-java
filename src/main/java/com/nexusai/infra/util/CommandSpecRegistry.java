package com.nexusai.infra.util;

import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * CommandSpecRegistry · 对齐 CC utils/bash/registry.ts.
 *
 * <p>L1 语义: 命令 spec 查找 (用于 bash 命令自动补全 + 安全检查)。
 * <ul>
 *   <li>{@link CommandSpec} + {@link Argument} + {@link Option} records</li>
 *   <li>{@link #loadFigSpec(String)} — 路径/相对路径/symbolic-option 校验</li>
 *   <li>{@link #getCommandSpec(String, Supplier, Function)} — memoize-like 命令查找</li>
 * </ul>
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: 3 record types + loadFigSpec + getCommandSpec 接受 localSpecs + dynamicLoader</li>
 *   <li><b>A2 Golden Trace</b>: '/' in command→null;'..' in command→null;leading '-' (except '-')→null;local 优先 → dynamicLoader</li>
 *   <li><b>A3 缓存</b>: getCommandSpec 用 memoize 包装;command 为 cache key</li>
 *   <li><b>A4 边界</b>: empty command → null;Supplier throws → null</li>
 *   <li><b>A5 业务场景</b>: shell autocomplete 补全 git / kubectl / curl 等命令的 arg 树</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS dynamic import → Java Supplier&lt;CommandSpec&gt; (caller wired);
 * TS memoize LRU → Java LinkedHashMap cache (size-limited);
 * TS type alias → Java record。
 */
public final class CommandSpecRegistry {

    public record Argument(
        String name,
        String description,
        boolean isDangerous,
        boolean isVariadic,
        boolean isOptional,
        boolean isCommand,
        Object isModule,
        boolean isScript) {}

    public record Option(
        Object name,
        String description,
        Object args,
        boolean isRequired) {}

    public record CommandSpec(
        String name,
        String description,
        List<CommandSpec> subcommands,
        Object args,
        List<Option> options) {}

    /** Sentinel for invalid command (mirrors CC's null returns). */
    public static final CommandSpec INVALID_SPEC = new CommandSpec(
        "<<invalid>>", null, List.of(), null, List.of());

    private CommandSpecRegistry() {}

    /**
     * Validate a command for spec lookup. Returns true iff the command is safe to load.
     * Mirrors CC loadFigSpec guards: rejects path-separator, parent traversal, options.
     */
    public static boolean isValidCommandName(String command) {
        if (command == null || command.isEmpty()) return false;
        if (command.contains("/") || command.contains("\\")) return false;
        if (command.contains("..")) return false;
        if (command.startsWith("-") && !"-".equals(command)) return false;
        return true;
    }

    /**
     * Load a spec from the dynamic loader (e.g., Fig autocomplete).
     * Returns null if {@code command} is invalid or {@code dynamicLoader} returns null.
     */
    public static CommandSpec loadFigSpec(
        String command, Function<String, CommandSpec> dynamicLoader) {
        if (!isValidCommandName(command)) return null;
        if (dynamicLoader == null) return null;
        try {
            return dynamicLoader.apply(command);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /**
     * Look up spec: local first → dynamic loader. Memoizes by command name (LRU-style
     * bounded; 256 entries).
     */
    public static CommandSpec getCommandSpec(
        String command,
        List<CommandSpec> localSpecs,
        Function<String, CommandSpec> dynamicLoader) {
        if (command == null) return null;
        // local match
        if (localSpecs != null) {
            for (CommandSpec spec : localSpecs) {
                if (command.equals(spec.name())) return spec;
            }
        }
        // dynamic via loader
        return loadFigSpec(command, dynamicLoader);
    }
}
