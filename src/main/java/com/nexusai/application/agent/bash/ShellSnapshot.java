package com.nexusai.application.agent.bash;

import com.nexusai.application.agent.memory.MemoryFileDetection;
import com.nexusai.application.agent.skill.NexusaiPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Shell 环境快照生成 · 对齐 CC {@code createAndSaveSnapshot}（ShellSnapshot.ts:413-582）。
 *
 * <p><b>WHAT</b>：CC 在创建 bash provider 时（bashProvider.ts:63-68）调用
 * {@code createAndSaveSnapshot(shellPath)}：source 用户 {@code .bashrc/.zshrc/.profile}
 * （configFile :181-191）→ 抓函数（bash: {@code declare -F | ... | base64} :217-231 /
 * zsh: {@code typeset +f | ...} :204-215）+ 选项（bash: {@code shopt -p} + {@code set -o}
 * + {@code expand_aliases} :240-247）+ 别名（alias 过滤 winpty :250-260）+ export PATH
 * （:269-340）→ 存 {@code {claude-config-home}/shell-snapshots/snapshot-{type}-{ts}-{id}.sh}
 * （:437-444）；生成 10s 超时（{@code SNAPSHOT_CREATION_TIMEOUT=10000}，:24），失败
 * resolve(undefined)（:519）不阻塞主流程。
 *
 * <p><b>WHY（对齐 CC 行为的意义）</b>：后续每条 bash 命令前
 * {@code source <快照> 2>/dev/null || true}（bashProvider.ts:161-167），使命令获得用户
 * 交互 shell 的函数/别名/选项而<b>不必每条命令都起 login shell（-l）</b>——既快又稳。
 * 快照生成失败时命令回退 {@code -l} login shell（bashProvider.ts:93-103），功能不丢失。
 *
 * <p><b>Java 简化登记</b>：
 * <ul>
 *   <li><b>rg 集成</b>（{@code createRipgrepShellIntegration} ShellSnapshot.ts:65-92 +
 *       :292-315）：依赖 bun 内嵌 ripgrep ARGV0 分发，Java 无对应物，<b>TODO 登记</b>
 *       ——快照不含 rg 别名/函数，命令回退系统 rg；接入时机待 {@code ripgrepCommand()}
 *       等价工具探测接入。</li>
 *   <li><b>find/grep 集成</b>（{@code createFindGrepShellIntegration} :153-179）：ant-native
 *       build（embedded bfs/ugrep）专属，Java 非 ant-native，跳过。</li>
 *   <li><b>清理注册</b>（{@code registerCleanup} :534-545）：CC 在优雅关闭时 unlink 快照；
 *       Java 用 {@code deleteOnExit()} 近似（JVM 正常退出时清理）。</li>
 * </ul>
 *
 * <p><b>CC 真源</b>（grep -n 自验，不信注释）：{@code createAndSaveSnapshot}
 * （Open-ClaudeCode/src/utils/bash/ShellSnapshot.ts:413-582）；{@code getSnapshotScript}
 * （:345-386）；{@code getConfigFile}（:181-191）；{@code getUserSnapshotContent}（:197-263）；
 * {@code getClaudeCodeSnapshotContent}（:269-340）；{@code SNAPSHOT_CREATION_TIMEOUT}（:24）。
 */
public final class ShellSnapshot {

    private static final Logger log = LoggerFactory.getLogger(ShellSnapshot.class);

    /** 快照生成超时 · CC original: {@code SNAPSHOT_CREATION_TIMEOUT = 10000}（ShellSnapshot.ts:24）。 */
    private static final long SNAPSHOT_CREATION_TIMEOUT_MS = 10_000L;

    /** 快照目录名 · CC original: {@code 'shell-snapshots'}（ShellSnapshot.ts:439）。 */
    private static final String SNAPSHOTS_DIR_NAME = "shell-snapshots";

    /** execFile maxBuffer 上限 · CC original: {@code maxBuffer: 1024 * 1024}（ShellSnapshot.ts:469）。 */
    private static final int MAX_BUFFER = 1024 * 1024;

    /** Windows 平台判定 · 等价 CC {@code getPlatform() === 'windows'}（platform.ts:18）。 */
    private static final boolean IS_WINDOWS =
        System.getProperty("os.name", "").toLowerCase().contains("win");

    private ShellSnapshot() {
    }

    /**
     * 生成 shell 快照 · 对齐 CC {@code createAndSaveSnapshot}（ShellSnapshot.ts:413-582）。
     *
     * <p>流程：探测 config file（{@code getConfigFile} :181-191）→ 拼快照脚本
     * （{@code getSnapshotScript} :345-386）→ 创建 {@code shell-snapshots} 目录（:447）→
     * {@code execFile(binShell, ['-c', '-l', script], env, timeout=10s, maxBuffer=1MB)}
     * （:456-471）→ 成功且快照文件存在 → {@code Optional.of(path)}；失败/超时/无文件 →
     * {@code Optional.empty()}（对齐 CC resolve(undefined) :519/:567，不阻塞主流程）。
     *
     * @param shellPath 实际执行 shell（resolveShell() 探测结果，CC {@code binShell}）
     * @return 快照文件路径；生成失败 → {@link Optional#empty()}
     */
    public static Optional<Path> generate(String shellPath) {
        if (shellPath == null || shellPath.isBlank()) {
            if (log.isDebugEnabled()) {
                log.debug("ShellSnapshot.generate: shellPath 为空，直接返回 empty");
            }
            return Optional.empty();
        }
        String shellType = shellPath.contains("zsh") ? "zsh"
            : shellPath.contains("bash") ? "bash" : "sh";
        try {
            String configFile = getConfigFile(shellPath);
            boolean configFileExists = Files.exists(Path.of(configFile));
            // 唯一快照路径 · 对齐 CC timestamp + Math.random().toString(36).substring(2,8)（:437-444）
            String timestamp = String.valueOf(System.currentTimeMillis());
            String randomId = String.format("%06x", ThreadLocalRandom.current().nextInt(0x1000000));
            Path snapshotPath = Paths.get(
                NexusaiPaths.getAppConfigHomeDir(), SNAPSHOTS_DIR_NAME,
                "snapshot-" + shellType + "-" + timestamp + "-" + randomId + ".sh");

            // 确保快照目录存在 · 对齐 CC mkdir(snapshotsDir, {recursive})（:447）
            Files.createDirectories(snapshotPath.getParent());

            String script = getSnapshotScript(shellPath, snapshotPath, configFileExists);
            if (log.isDebugEnabled()) {
                log.debug("ShellSnapshot.generate: 开始生成 snapshot={} shell={} configFile={} exists={} scriptLength={}",
                    snapshotPath, shellPath, configFile, configFileExists, script.length());
            }

            // execFile(binShell, ['-c', '-l', script], env, timeout, maxBuffer) · 对齐 CC :456-471
            ProcessBuilder pb = new ProcessBuilder(shellPath, "-c", "-l", script);
            pb.redirectErrorStream(true);
            pb.environment().put("SHELL", shellPath);
            pb.environment().put("GIT_EDITOR", "true");
            pb.environment().put("CLAUDECODE", "1");
            Process process = pb.start();

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Thread reader = new Thread(() -> {
                try (InputStream in = process.getInputStream()) {
                    byte[] buf = new byte[8192];
                    int n;
                    int total = 0;
                    while ((n = in.read(buf)) != -1) {
                        total += n;
                        if (total <= MAX_BUFFER) {
                            out.write(buf, 0, n);
                        }
                    }
                } catch (Exception e) {
                    if (log.isDebugEnabled()) {
                        log.debug("ShellSnapshot.generate: 读取生成输出失败: {}", e.toString());
                    }
                }
            }, "shell-snapshot-reader");
            reader.setDaemon(true);
            reader.start();

            // 10s 超时 · 对齐 CC SNAPSHOT_CREATION_TIMEOUT（:468），超时 → resolve(undefined)（:519）
            boolean finished = process.waitFor(SNAPSHOT_CREATION_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!finished) {
                if (log.isDebugEnabled()) {
                    log.debug("ShellSnapshot.generate: 生成超时（{}ms），销毁进程 → Optional.empty", SNAPSHOT_CREATION_TIMEOUT_MS);
                }
                process.destroyForcibly();
                reader.interrupt();
                return Optional.empty();
            }
            reader.join(2000);
            int exit = process.exitValue();
            if (exit != 0) {
                // 对齐 CC execFile 回调 error 分支（非 0 退出 → resolve(undefined), :473-519）
                if (log.isDebugEnabled()) {
                    log.debug("ShellSnapshot.generate: 生成退出码非 0={} → Optional.empty", exit);
                }
                return Optional.empty();
            }

            // 快照文件存在校验 · 对齐 CC stat(shellSnapshotPath)（:520-527）+ :549-567
            if (Files.isRegularFile(snapshotPath)) {
                long size = Files.size(snapshotPath);
                // 清理注册 · 对齐 CC registerCleanup 优雅关闭时 unlink（:534-545），Java deleteOnExit 近似
                snapshotPath.toFile().deleteOnExit();
                if (log.isDebugEnabled()) {
                    log.debug("ShellSnapshot.generate: 快照生成成功 size={} bytes", size);
                }
                return Optional.of(snapshotPath);
            }
            if (log.isDebugEnabled()) {
                log.debug("ShellSnapshot.generate: 快照文件不存在（stat 失败）→ Optional.empty");
            }
            return Optional.empty();
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("ShellSnapshot.generate: 快照生成异常 → Optional.empty: {}", e.toString());
            }
            return Optional.empty();
        }
    }

    /**
     * 用户 shell 配置文件路径 · 对齐 CC {@code getConfigFile}（ShellSnapshot.ts:181-191）：
     * zsh → {@code ~/.zshrc}；bash → {@code ~/.bashrc}；其它 → {@code ~/.profile}。
     */
    private static String getConfigFile(String shellPath) {
        String fileName = shellPath.contains("zsh") ? ".zshrc"
            : shellPath.contains("bash") ? ".bashrc" : ".profile";
        return Paths.get(System.getProperty("user.home", ""), fileName).toString();
    }

    /**
     * 用户自定义快照内容（函数 + 选项 + 别名）· 对齐 CC {@code getUserSnapshotContent}
     * （ShellSnapshot.ts:197-263）。
     */
    private static String getUserSnapshotContent(String configFile) {
        boolean isZsh = configFile.endsWith(".zshrc");
        StringBuilder content = new StringBuilder();
        if (isZsh) {
            // 用户函数 · 对齐 CC :203-215（typeset +f 过滤单下划线完成函数）
            content.append("echo \"# Functions\" >> \"$SNAPSHOT_FILE\"\n\n")
                .append("# Force autoload all functions first\n")
                .append("typeset -f > /dev/null 2>&1\n\n")
                .append("# Now get user function names - filter completion functions (single underscore prefix)\n")
                .append("# but keep double-underscore helpers (e.g. __zsh_like_cd from mise, __pyenv_init)\n")
                .append("typeset +f | grep -vE '^_[^_]' | while read func; do\n")
                .append("  typeset -f \"$func\" >> \"$SNAPSHOT_FILE\"\n")
                .append("done\n");
        } else {
            // 用户函数（base64 编码保特殊字符）· 对齐 CC :217-231
            content.append("echo \"# Functions\" >> \"$SNAPSHOT_FILE\"\n\n")
                .append("# Force autoload all functions first\n")
                .append("declare -f > /dev/null 2>&1\n\n")
                .append("# Now get user function names - filter completion functions (single underscore prefix)\n")
                .append("# but keep double-underscore helpers (e.g. __zsh_like_cd from mise, __pyenv_init)\n")
                .append("declare -F | cut -d' ' -f3 | grep -vE '^_[^_]' | while read func; do\n")
                .append("  # Encode the function to base64, preserving all special characters\n")
                .append("  encoded_func=$(declare -f \"$func\" | base64 )\n")
                .append("  # Write the function definition to the snapshot\n")
                .append("  echo \"eval \\`\\$(echo '$encoded_func' | base64 -d)\\` > /dev/null 2>&1\" >> \"$SNAPSHOT_FILE\"\n")
                .append("done\n");
        }
        // Shell 选项 · 对齐 CC :234-247
        if (isZsh) {
            content.append("echo \"# Shell Options\" >> \"$SNAPSHOT_FILE\"\n")
                .append("setopt | sed 's/^/setopt /' | head -n 1000 >> \"$SNAPSHOT_FILE\"\n");
        } else {
            content.append("echo \"# Shell Options\" >> \"$SNAPSHOT_FILE\"\n")
                .append("shopt -p | head -n 1000 >> \"$SNAPSHOT_FILE\"\n")
                .append("set -o | grep \"on\" | awk '{print \"set -o \" $1}' | head -n 1000 >> \"$SNAPSHOT_FILE\"\n")
                .append("echo \"shopt -s expand_aliases\" >> \"$SNAPSHOT_FILE\"\n");
        }
        // 用户别名（过滤 winpty）· 对齐 CC :249-260
        content.append("echo \"# Aliases\" >> \"$SNAPSHOT_FILE\"\n")
            .append("# Filter out winpty aliases on Windows to avoid \"stdin is not a tty\" errors\n")
            .append("# Git Bash automatically creates aliases like \"alias node='winpty node.exe'\" for\n")
            .append("# programs that need Win32 Console in mintty, but winpty fails when there's no TTY\n")
            .append("if [[ \"$OSTYPE\" == \"msys\" ]] || [[ \"$OSTYPE\" == \"cygwin\" ]]; then\n")
            .append("  alias | grep -v \"='winpty \" | sed 's/^alias //g' | sed 's/^/alias -- /' | head -n 1000 >> \"$SNAPSHOT_FILE\"\n")
            .append("else\n")
            .append("  alias | sed 's/^alias //g' | sed 's/^/alias -- /' | head -n 1000 >> \"$SNAPSHOT_FILE\"\n")
            .append("fi\n");
        return content.toString();
    }

    /**
     * Claude Code 专属快照内容（PATH + rg 集成）· 对齐 CC {@code getClaudeCodeSnapshotContent}
     * （ShellSnapshot.ts:269-340）。
     *
     * <p><b>rg 集成先登记 TODO</b>（CC :284-315）：依赖 bun 内嵌 ripgrep ARGV0 分发
     * （{@code createArgv0ShellFunction} :35-59 / {@code createRipgrepShellIntegration} :65-92），
     * Java 无对应物 → 快照不含 rg 别名/函数，命令回退系统 rg。接入时机待
     * {@code ripgrepCommand()} 等价工具探测接入。
     */
    private static String getClaudeCodeSnapshotContent(String shellPath) {
        String pathValue = resolvePathValue(shellPath);
        StringBuilder content = new StringBuilder();
        content.append("# Check for rg availability\n")
            .append("# TODO(G2-1-rg): rg 集成（ShellSnapshot.ts:292-315）未实现——Java 无 bun ARGV0\n")
            .append("#   embedded rg 分发，快照不含 rg 别名/函数，命令回退系统 rg。\n");
        // Add PATH to the file · 对齐 CC :332-337 `echo "export PATH=${quote([pathValue||''])}" >> "$SNAPSHOT_FILE"`
        content.append("# Add PATH to the file\n")
            .append("echo \"export PATH=").append(ShellQuoteParser.quote(List.of(pathValue)))
            .append("\" >> \"$SNAPSHOT_FILE\"\n");
        return content.toString();
    }

    /**
     * 解析 PATH 值 · 对齐 CC {@code getClaudeCodeSnapshotContent} 开头（:271-282）：
     * Windows（git-bash）先试 {@code echo $PATH} 读 Cygwin PATH，失败回落
     * {@code process.env.PATH}；非 Windows 直接用 {@code process.env.PATH}。
     */
    private static String resolvePathValue(String shellPath) {
        if (!IS_WINDOWS) {
            String p = System.getenv("PATH");
            return p != null ? p : "";
        }
        // Windows：读 Cygwin PATH · 对齐 CC execa('echo $PATH', {shell:true})（:274-281）
        try {
            ProcessBuilder pb = new ProcessBuilder(shellPath, "-c", "echo $PATH");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            if (process.waitFor(5, TimeUnit.SECONDS)) {
                String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                if (!out.isEmpty()) {
                    return out;
                }
            }
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("ShellSnapshot.resolvePathValue: 读取 Cygwin PATH 失败，回落 process.env.PATH: {}", e.toString());
            }
        }
        String p = System.getenv("PATH");
        return p != null ? p : "";
    }

    /**
     * 组装快照生成脚本 · 对齐 CC {@code getSnapshotScript}（ShellSnapshot.ts:345-386）：
     * {@code SNAPSHOT_FILE=...} + source config + 清空/创建文件 + unalias + 用户内容 +
     * Claude Code 内容 + 文件存在校验 exit 1。
     *
     * <p><b>包可见（package-private）</b>：供同包单测直调校验 SNAPSHOT_FILE 引号化
     * （ShellSnapshotTest），不暴露为公共 API。
     */
    static String getSnapshotScript(String shellPath, Path snapshotFilePath, boolean configFileExists) {
        String configFile = getConfigFile(shellPath);
        boolean isZsh = configFile.endsWith(".zshrc");
        // 用户内容 · 对齐 CC :354-359（无 config 时 bash 手动强制 expand_aliases）
        String userContent = configFileExists
            ? getUserSnapshotContent(configFile)
            : (!isZsh ? "echo \"shopt -s expand_aliases\" >> \"$SNAPSHOT_FILE\"" : "");
        String claudeCodeContent = getClaudeCodeSnapshotContent(shellPath);
        // 对齐 CC :362-363 `SNAPSHOT_FILE=${quote([snapshotFilePath])}` + source config
        // G2-1 修复：CC quote()（shellQuote.ts:267-304）对含 `\` 的 Windows 路径必加引号（单引号内
        //   反斜杠为字面量）；Java ShellQuoteParser.quote 对"无空白/引号"参数原样返回（不含反斜杠
        //   判定），Windows 路径 `C:\Users\...`（无空白）不被引号化 → bash 解析非引号赋值剥掉全部
        //   反斜杠 → 写到垃圾路径 → Files.isRegularFile 失败 → 快照机制 win32 100% 失效。此处先把
        //   Windows native 路径转 POSIX（/c/...，bashProvider.ts:118-121 同款），再强制单引号化。
        String snapshotPathValue = snapshotFilePath.toString();
        if (IS_WINDOWS) {
            snapshotPathValue = MemoryFileDetection.windowsPathToPosixPath(snapshotPathValue);
        }
        String quotedSnapshot = quoteForSnapshotAssignment(snapshotPathValue);
        String configSource = configFileExists
            ? "source \"" + configFile + "\" < /dev/null"
            : "# No user config file to source";
        return "SNAPSHOT_FILE=" + quotedSnapshot + "\n"
            + configSource + "\n\n"
            + "# First, create/clear the snapshot file\n"
            + "echo \"# Snapshot file\" >| \"$SNAPSHOT_FILE\"\n\n"
            + "# When this file is sourced, we first unalias to avoid conflicts\n"
            + "# This is necessary because aliases get \"frozen\" inside function definitions at definition time,\n"
            + "# which can cause unexpected behavior when functions use commands that conflict with aliases\n"
            + "echo \"# Unset all aliases to avoid conflicts with functions\" >> \"$SNAPSHOT_FILE\"\n"
            + "echo \"unalias -a 2>/dev/null || true\" >> \"$SNAPSHOT_FILE\"\n\n"
            + userContent + "\n\n"
            + claudeCodeContent + "\n\n"
            + "# Exit silently on success, only report errors\n"
            + "if [ ! -f \"$SNAPSHOT_FILE\" ]; then\n"
            + "  echo \"Error: Snapshot file was not created at $SNAPSHOT_FILE\" >&2\n"
            + "  exit 1\n"
            + "fi\n";
    }

    /**
     * SNAPSHOT_FILE 赋值强制单引号化 · 对齐 CC {@code quote([snapshotFilePath])}
     * （shellQuote.ts:267-304）：shell-quote 对含 {@code \} 的 Windows 路径必加引号（单引号内
     * 反斜杠为字面量）。Java {@link ShellQuoteParser#quote} 对"无空白/引号"参数原样返回（不含
     * 反斜杠判定），直接复用会漏引号 → bash 剥反斜杠 → 快照写垃圾路径（G2-1 win32 100% 失效）。
     * 此处无论路径是否含特殊字符都单引号化（与未引号语义等价，但防反斜杠/空格被 bash 剥除）；
     * 内嵌单引号用 {@code '"'"'} escape（与 singleQuoteForEval 同款）。
     *
     * @param value 快照文件路径（Windows 已转 POSIX）
     * @return 单引号包裹的 bash 赋值右值
     */
    private static String quoteForSnapshotAssignment(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }
}
