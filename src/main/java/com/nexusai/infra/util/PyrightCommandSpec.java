package com.nexusai.infra.util;

import java.util.List;
import java.util.Map;

/**
 * PyrightCommandSpec · 对齐 CC utils/bash/specs/pyright.ts.
 *
 * <p>L1 语义: pyright 命令的 BashSpec 描述 — Python type checker 命令行参数定义。
 * 用于 bash autocomplete + 安全检查。
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: 静态 record (CommandSpec) + get() 静态方法返 spec</li>
 *   <li><b>A2 Golden Trace</b>: name='pyright';含 --help/--version/--watch 等 options;args.name='files'</li>
 *   <li><b>A3 不可变</b>: 静态 final spec;static initializer only</li>
 *   <li><b>A4 边界</b>: 不变 null</li>
 *   <li><b>A5 业务场景</b>: shell autocomplete pyright 提示 --watch / --project / --pythonversion</li>
 * </ul>
 *
 * <p>L3 升级: TS satisfies CommandSpec 断言 → Java record + 静态 init;
 * TS 对象字面量 → Java nested records.
 */
public final class PyrightCommandSpec {

    public record Arg(String name, String description, Boolean isOptional, Boolean isVariadic) {}
    public record Option(String name, String description, Arg args, Boolean isRequired) {}
    public record CommandSpec(
        String name,
        String description,
        List<CommandSpec> subcommands,
        List<Option> options,
        Arg args) {

        public static CommandSpec of(String name, String description, List<Option> options, Arg args) {
            return new CommandSpec(name, description, List.of(), options, args);
        }
    }

    public record CommandSpecBuilder() {} // placeholder

    public record _Placeholder() {
        // Just to ensure no syntax errors
    }

    private static final Arg FILES_ARG = new Arg("files",
        "Specify files or directories to analyze (overrides config file)",
        true, true);

    private static final List<Option> OPTIONS = List.of(
        opt("--help", "-h", "Show help message", null, null, null),
        opt("--version", null, "Print pyright version and exit", null, null, null),
        opt("--watch", "-w", "Continue to run and watch for changes", null, null, null),
        opt("--project", "-p", "Use the configuration file at this location",
            new Arg("FILE OR DIRECTORY", null, null, null), null, null),
        opt("-", null, "Read file or directory list from stdin", null, null, null),
        opt("--createstub", null, "Create type stub file(s) for import",
            new Arg("IMPORT", null, null, null), null, null),
        opt("--typeshedpath", "-t", "Use typeshed type stubs at this location",
            new Arg("DIRECTORY", null, null, null), null, null),
        opt("--verifytypes", null, "Verify completeness of types in py.typed package",
            new Arg("IMPORT", null, null, null), null, null),
        opt("--ignoreexternal", null, "Ignore external imports for --verifytypes", null, null, null),
        opt("--pythonpath", null, "Path to the Python interpreter",
            new Arg("FILE", null, null, null), null, null),
        opt("--pythonplatform", null, "Analyze for platform",
            new Arg("PLATFORM", null, null, null), null, null),
        opt("--pythonversion", null, "Analyze for Python version",
            new Arg("VERSION", null, null, null), null, null),
        opt("--venvpath", "-v", "Directory that contains virtual environments",
            new Arg("DIRECTORY", null, null, null), null, null),
        opt("--outputjson", null, "Output results in JSON format", null, null, null),
        opt("--verbose", null, "Emit verbose diagnostics", null, null, null),
        opt("--stats", null, "Print detailed performance stats", null, null, null),
        opt("--dependencies", null, "Emit import dependency information", null, null, null),
        opt("--level", null, "Minimum diagnostic level",
            new Arg("LEVEL", null, null, null), null, null),
        opt("--skipunannotated", null, "Skip type analysis of unannotated functions", null, null, null),
        opt("--warnings", null, "Use exit code of 1 if warnings are reported", null, null, null),
        opt("--threads", null, "Use up to N threads to parallelize type checking",
            new Arg("N", "Number of threads", true, null), null, null)
    );

    private static final CommandSpec SPEC = CommandSpec.of(
        "pyright", "Type checker for Python", OPTIONS, FILES_ARG);

    private PyrightCommandSpec() {}

    public static CommandSpec get() {
        return SPEC;
    }

    /** Helper for constructing options with optional alias and args. */
    private static Option opt(String name, String altName, String desc, Arg args, Boolean isRequired, Object unused) {
        return new Option(name, desc, args, isRequired);
    }
}
