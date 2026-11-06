package com.nexusai.application.agent.subagent.external;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 外部 CLI 的**显式**探测：版本号、实时模型列表、连通性测试。
 *
 * <p><b>为什么和 {@link ExternalRuntimeSpec#resolveExecutable} 分开</b>：
 * 路径解析只判存在、**绝不执行**二进制（执行会触发登录提示、写配置目录、甚至下载模型）。
 * 而这三个功能本来就要跑 CLI，所以做成**用户点按钮才触发**的显式动作，
 * ⛔ 不在启动/列表接口里静默执行。
 *
 * <p><b>超时是必须的</b>：{@code opencode models} 实测 >8s（要访问 provider 注册表），
 * {@code pi --list-models} 在 Windows 上冷启动 5s 起。全部设上限并复用本类统一收口。
 */
public final class ExternalRuntimeProbe {

    private static final Logger log = LoggerFactory.getLogger(ExternalRuntimeProbe.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 版本探测超时（pi 在 Windows 冷启动慢，OpenDesign 也给了 15s）。 */
    private static final long VERSION_TIMEOUT_MS = 15_000;
    /** 模型列表超时（opencode 实测 >8s）。 */
    private static final long MODELS_TIMEOUT_MS = 20_000;
    /** 测试连接超时（要真的走一次模型调用）。 */
    private static final long TEST_TIMEOUT_MS = 60_000;

    private ExternalRuntimeProbe() {
    }

    /**
     * 读版本号：{@code <bin> --version}。
     *
     * @return 版本字符串；失败返回 null（不抛 —— 探测失败是常态，不是异常）
     */
    public static String probeVersion(String binPath) {
        if (binPath == null || binPath.isBlank()) return null;
        Exec result = exec(List.of(binPath, "--version"), VERSION_TIMEOUT_MS, null);
        if (!result.ok()) {
            log.debug("[ExternalRuntimeProbe] {} --version 失败: {}", binPath, result.error());
            return null;
        }
        // 各家输出形态：claude "2.1.318 (Claude Code)"；codex "codex-cli 0.162.1"；pi "0.9.1"
        String out = result.stdout().trim();
        if (out.isEmpty()) return null;
        String firstLine = out.split("\\R", 2)[0].trim();
        // 取第一个形如 x.y.z 的片段（前端只显示，不做语义解析）
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(\\d+\\.\\d+(?:\\.\\d+)?(?:[-+][0-9A-Za-z.-]+)?)").matcher(firstLine);
        return m.find() ? m.group(1) : (firstLine.length() > 40 ? firstLine.substring(0, 40) : firstLine);
    }

    /**
     * 一个模型及其**自己支持的**推理档位。
     *
     * <p><b>为什么档位要挂在模型上</b>（2026-11-04 真机实测）：
     * {@code codex debug models} 的每个模型自带 {@code supported_reasoning_levels}，
     * 例如 {@code gpt-6.1-sol} 支持 low/medium/high/xhigh/max/ultra，
     * 而 {@code gpt-6-luna} 只到 max。⛔ 用一个全局档位表会造成「选了 CLI 不认的档位」⇒ 静默无效。
     *
     * @param id               模型 id
     * @param reasoningOptions 该模型支持的推理档位（空 = 该模型/该 CLI 不支持）
     */
    public record ModelInfo(String id, List<String> reasoningOptions) {
        public ModelInfo {
            reasoningOptions = reasoningOptions == null ? List.of() : List.copyOf(reasoningOptions);
        }
    }

    /**
     * 实时拉取模型列表。
     *
     * <p>各家的子命令在 {@link ExternalRuntimeCatalog.RuntimePreset#listModelsArgs()} 里，
     * 且**输出格式各不相同**：
     * <ul>
     *   <li>{@code codex debug models} —— JSON：{@code {"models":[{"slug":..., "supported_reasoning_levels":[...]}]}}</li>
     *   <li>{@code opencode models --verbose} —— 每行 {@code provider/model}（可能带 JSON 元数据）</li>
     *   <li>{@code pi --list-models} —— TSV 表格</li>
     * </ul>
     *
     * @return 模型列表（去重、保序，带各自的推理档位）；不支持或失败返回空列表（调用方回落内置列表）
     */
    public static List<ModelInfo> listModels(ExternalRuntimeCatalog.RuntimePreset preset,
                                             String binPath, Map<String, String> env) {
        if (preset == null || preset.listModelsArgs().isEmpty()) return List.of();
        if (binPath == null || binPath.isBlank()) return List.of();

        List<String> argv = new ArrayList<>();
        argv.add(binPath);
        argv.addAll(preset.listModelsArgs());

        Exec result = exec(argv, MODELS_TIMEOUT_MS, env);
        if (!result.ok()) {
            log.info("[ExternalRuntimeProbe] {} 拉取模型失败（将回落内置列表）: {}",
                    preset.runtime(), result.error());
            return List.of();
        }
        List<ModelInfo> parsed = switch (preset.runtime()) {
            case "codex" -> parseCodexModels(result.stdout());
            case "opencode" -> parseOpenCodeModels(result.stdout());
            case "pi" -> parsePiModels(result.stdout());
            default -> parseModelLines(result.stdout());
        };
        log.info("[ExternalRuntimeProbe] {} 实时模型 {} 个", preset.runtime(), parsed.size());
        return parsed;
    }

    /**
     * 测试连接：发一个极小提示词，看 CLI 是否真的能应答。
     *
     * <p>⚠️ **这会真的消耗一次模型调用**（OpenDesign 的「测试」按钮同样如此）。
     * 提示词固定为 {@code Reply with exactly: OK}，成本可忽略。
     *
     * @return 测试结果（成功/失败 + 人话原因 + 耗时）
     */
    public static TestResult test(ExternalRuntimeCatalog.RuntimePreset preset,
                                  String binPath, Map<String, String> env) {
        return test(preset, binPath, env, null);
    }

    /**
     * 带模型的测试连接。
     *
     * <p>⭐为什么必须传模型：不传时 CLI 用它自己的默认模型 —— codex 的默认是内置目录里的
     * {@code gpt-6.1-sol}。当用户把 provider 换成 DeepSeek 时，那个模型名上游不认
     * （{@code The supported API model names are deepseek-flash, deepseek-v4-pro}），
     * 于是「保存过的配置明明能派发，测试却必然失败」。
     *
     * @param model 用户已保存的模型；null/空 = 不传该参数，CLI 用自身默认
     */
    public static TestResult test(ExternalRuntimeCatalog.RuntimePreset preset,
                                  String binPath, Map<String, String> env, String model) {
        long start = System.currentTimeMillis();
        if (preset == null) {
            return new TestResult(false, "未知的运行时", 0, null);
        }
        if (binPath == null || binPath.isBlank()) {
            return new TestResult(false, preset.displayName() + " 未安装，或不在 PATH 中", 0, null);
        }

        // 复用预设的 argv 模板（含协议参数），把任务换成固定测试句
        List<String> argv = new ArrayList<>();
        argv.add(binPath);
        for (String a : preset.args()) {
            if (a == null) continue;
            argv.add(a.replace(ExternalRuntimeSpec.PROMPT_PLACEHOLDER, "Reply with exactly: OK"));
        }
        // 模型参数：与真实派发用同一套拼装（ExternalRuntimeCatalog.modelArgs）
        List<String> modelPart = ExternalRuntimeCatalog.modelArgs(preset, model);
        if (!modelPart.isEmpty()) {
            // 模型参数要插在 prompt 之前，否则会被 CLI 当成任务文本的一部分
            int promptAt = argv.indexOf("Reply with exactly: OK");
            if (promptAt < 0) promptAt = argv.size();
            argv.addAll(promptAt, modelPart);
            log.info("[ExternalRuntimeProbe] {} 测试带模型 model={} 追加参数={}",
                    preset.runtime(), model, modelPart);
        }

        Exec result = exec(argv, TEST_TIMEOUT_MS, env);
        long ms = System.currentTimeMillis() - start;
        if (!result.ok()) {
            return new TestResult(false, humanizeFailure(preset, result), ms, null);
        }
        ExternalStreamParser.Parsed parsed = ExternalStreamParser.parse(
                preset.streamFormat(), result.stdout());
        String answer = parsed.text() == null ? "" : parsed.text().trim();
        if (answer.isEmpty()) {
            return new TestResult(false,
                    preset.displayName() + " 没有返回任何正文（可能登录态失效或参数不匹配）", ms, null);
        }
        String sample = answer.length() > 80 ? answer.substring(0, 80) + "…" : answer;
        return new TestResult(true, null, ms, sample);
    }

    /** 把底层错误翻译成用户能懂的话（照 OpenDesign 的 settings.test* 口径）。 */
    private static String humanizeFailure(ExternalRuntimeCatalog.RuntimePreset preset, Exec r) {
        String blob = ((r.stderr() == null ? "" : r.stderr()) + " "
                + (r.stdout() == null ? "" : r.stdout())).toLowerCase();
        if (r.timedOut()) {
            return "测试在 " + (TEST_TIMEOUT_MS / 1000) + " 秒后超时";
        }
        if (blob.contains("not logged in") || blob.contains("login") || blob.contains("auth")
                || blob.contains("api key") || blob.contains("unauthorized")
                || blob.contains("401")) {
            return "鉴权失败，请在 " + preset.displayName() + " 里完成登录或填写 API key";
        }
        if (blob.contains("not found") && blob.contains("model")) {
            return "该 CLI 找不到所选的模型";
        }
        if (blob.contains("rate limit") || blob.contains("429")) {
            return "被限流了；配置本身看起来是有效的";
        }
        String tail = r.error();
        if (tail == null || tail.isBlank()) tail = "退出码 " + r.exitCode();
        return "无法启动 " + preset.displayName() + "：" + (tail.length() > 200 ? tail.substring(0, 200) : tail);
    }

    // ── 各家的模型输出解析（格式不同，⛔ 不要写成一个通用正则） ──

    /**
     * codex {@code debug models}：{@code {"models":[{"slug":..., "supported_reasoning_levels":[{effort:...}]}]}}。
     *
     * <p>⚠️ 三个必须处理的细节（2026-11-04 真机实测）：
     * ① 模型字段叫 {@code slug} 不叫 {@code id}；
     * ② {@code visibility:"hide"} 的是内部模型（如 codex-auto-review），不该出现在下拉里；
     * ③ 每个模型自带 {@code supported_reasoning_levels}，档位集合各不相同。
     */
    private static List<ModelInfo> parseCodexModels(String stdout) {
        List<ModelInfo> out = new ArrayList<>();
        try {
            JsonNode root = JSON.readTree(extractJson(stdout));
            JsonNode models = root.get("models");
            if (models == null || !models.isArray()) models = root.isArray() ? root : null;
            if (models == null) return parseModelLines(stdout);

            for (JsonNode m : models) {
                String id = text(m, "slug");
                if (id == null) id = text(m, "id");
                if (id == null || id.isBlank()) continue;
                // 内部模型不暴露给用户
                if ("hide".equalsIgnoreCase(String.valueOf(text(m, "visibility")))) continue;

                List<String> levels = new ArrayList<>();
                JsonNode lv = m.get("supported_reasoning_levels");
                if (lv != null && lv.isArray()) {
                    for (JsonNode l : lv) {
                        String effort = text(l, "effort");
                        if (effort != null && !effort.isBlank()) levels.add(effort.trim());
                    }
                }
                out.add(new ModelInfo(id.trim(), levels));
            }
        } catch (Exception e) {
            log.debug("[ExternalRuntimeProbe] codex 模型 JSON 解析失败，改用逐行: {}", e.toString());
            return parseModelLines(stdout);
        }
        return out;
    }

    /** opencode {@code models --verbose}：每行 {@code provider/model}，可能还有 JSON 行。 */
    private static List<ModelInfo> parseOpenCodeModels(String stdout) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        List<ModelInfo> withVariants = new ArrayList<>();
        for (String line : stdout.split("\\R")) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            if (t.startsWith("{") || t.startsWith("[")) {
                // --verbose 的 JSON 行可能带 variants（= 该模型的推理档位）
                try {
                    JsonNode node = JSON.readTree(t);
                    String id = text(node, "id");
                    if (id != null && !id.isBlank()) {
                        List<String> variants = new ArrayList<>();
                        JsonNode vs = node.get("variants");
                        if (vs != null && vs.isArray()) {
                            for (JsonNode v : vs) {
                                if (v.isTextual()) variants.add(v.asText());
                            }
                        }
                        withVariants.add(new ModelInfo(id.trim(), variants));
                        out.add(id.trim());
                    }
                    continue;
                } catch (Exception ignored) {
                    // 不是 JSON 就按行文本处理
                }
            }
            // 只收形如 provider/model 的（opencode 的模型 id 约定）
            if (t.matches("^[A-Za-z0-9][A-Za-z0-9._-]*/[A-Za-z0-9][A-Za-z0-9._/:@-]*$")) {
                out.add(t);
            }
        }
        // 有 variant 信息的行优先；其余纯文本行补在后面（档位为空）
        Map<String, List<String>> byId = new LinkedHashMap<>();
        for (ModelInfo m : withVariants) byId.put(m.id(), m.reasoningOptions());
        List<ModelInfo> result = new ArrayList<>(out.size());
        for (String id : out) {
            result.add(new ModelInfo(id, byId.getOrDefault(id, List.of())));
        }
        return result;
    }

    /** pi {@code --list-models}：TSV 表格，第一列通常是 provider/model。 */
    private static List<ModelInfo> parsePiModels(String stdout) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String line : stdout.split("\\R")) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            if (t.startsWith("-") || t.toLowerCase().startsWith("model")) continue;
            String first = t.split("\\t", 2)[0].trim();
            if (first.isEmpty()) continue;
            if (first.matches("^[A-Za-z0-9][A-Za-z0-9._-]*(/[A-Za-z0-9][A-Za-z0-9._/:@-]*)?$")) {
                out.add(first);
            }
        }
        // pi 的 thinking 档位与会话级别无关（--thinking 是全局开关），这里留空由预设提供
        List<ModelInfo> result = new ArrayList<>(out.size());
        for (String id : out) result.add(new ModelInfo(id, List.of()));
        return result;
    }

    /** 兜底：逐行取第一个「看起来像模型 id」的 token。 */
    private static List<ModelInfo> parseModelLines(String stdout) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String line : stdout.split("\\R")) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            String first = t.split("[\\s,]+", 2)[0].trim();
            if (first.matches("^[A-Za-z0-9][A-Za-z0-9._-]*(/[A-Za-z0-9][A-Za-z0-9._/:@-]*)?$")
                    && first.length() > 1) {
                out.add(first);
            }
        }
        List<ModelInfo> result = new ArrayList<>(out.size());
        for (String id : out) result.add(new ModelInfo(id, List.of()));
        return result;
    }

    private static String extractJson(String stdout) {
        int arr = stdout.indexOf('[');
        int obj = stdout.indexOf('{');
        int start;
        if (arr < 0) start = obj;
        else if (obj < 0) start = arr;
        else start = Math.min(arr, obj);
        if (start < 0) return stdout;
        char open = stdout.charAt(start);
        char close = open == '[' ? ']' : '}';
        int end = stdout.lastIndexOf(close);
        return end > start ? stdout.substring(start, end + 1) : stdout;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v != null && v.isTextual() ? v.asText() : null;
    }

    // ── 进程执行 ──

    /**
     * 跑一个短命令并收口 stdout/stderr。
     *
     * <p>⛔ 不经 shell（argv 向量）。三处必须做对，否则**整个接口会挂死**（2026-11-04 真机踩到：
     * 测试接口挂 >180s 不返回）：
     * <ol>
     *   <li><b>stdout 与 stderr 都必须由独立线程读</b>。若在主线程同步读 stdout，一旦子进程
     *       不写也不退出，读就一直阻塞，后面那行 {@code waitFor(timeout)} 根本执行不到 ——
     *       超时形同虚设。</li>
     *   <li><b>超时要杀整棵进程树</b>。Windows 上 npm 装的 CLI 是 {@code .cmd} shim，
     *       它再拉起 node/exe 孙进程；只 {@code destroyForcibly} 掉 shim 的话，孙进程仍持有
     *       管道句柄 ⇒ 读线程永远等不到 EOF。（同 {@code ShellExecutor:865} 的 taskkill /T /F）</li>
     *   <li>读线程要设 daemon，异常路径下不能拖住 JVM。</li>
     *   <li><b>必须立刻关闭子进程的 stdin</b>（等价 shell 的 {@code < /dev/null}）。
     *       非 TTY 下很多 CLI 会去读 stdin：父进程既不给数据也不关管道，子进程就永远阻塞。
     *       2026-11-04 实测：{@code opencode run --format json "..."} 在 shell 里 8 秒答完，
     *       经 {@code ProcessBuilder} 不关 stdin 则 70 秒零输出、不退出。</li>
     * </ol>
     */
    private static Exec exec(List<String> argv, long timeoutMs, Map<String, String> extraEnv) {
        ProcessBuilder pb = new ProcessBuilder(argv);
        pb.redirectErrorStream(false);
        if (extraEnv != null && !extraEnv.isEmpty()) {
            pb.environment().putAll(extraEnv);
        }
        Process p = null;
        try {
            p = pb.start();
            // ⛔ 探测/测试的 prompt 全部走 argv，不需要 stdin ⇒ 立刻关闭（见上方 javadoc）
            try { p.getOutputStream().close(); } catch (Exception ignored) { /* 进程可能已退出 */ }
            StringBuilder stdout = new StringBuilder();
            StringBuilder stderr = new StringBuilder();
            Process proc = p;
            Thread outThread = drainThread(proc.getInputStream(), stdout, "ext-probe-out-" + argv.get(0));
            Thread errThread = drainThread(proc.getErrorStream(), stderr, "ext-probe-err-" + argv.get(0));

            boolean finished = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                killTree(p);
                joinQuietly(outThread);
                joinQuietly(errThread);
                return new Exec(null, stdout.toString(), stderr.toString(), -1, true);
            }
            // 进程已退出 ⇒ 管道会 EOF，读线程很快结束；给足余量但不无限等
            joinQuietly(outThread);
            joinQuietly(errThread);
            return new Exec(null, stdout.toString(), stderr.toString(), p.exitValue(), false);
        } catch (Exception e) {
            return new Exec(e.toString(), "", "", -1, false);
        } finally {
            if (p != null && p.isAlive()) {
                killTree(p);
            }
        }
    }

    /** 杀**整棵进程树**（Windows taskkill /T /F；Unix 杀子孙后 destroyForcibly）。 */
    private static void killTree(Process p) {
        try {
            if (ExternalRuntimeSpec.isWindows()) {
                new ProcessBuilder("taskkill", "/T", "/F", "/PID", String.valueOf(p.pid()))
                        .redirectErrorStream(true)
                        .start()
                        .waitFor(5, TimeUnit.SECONDS);
            } else {
                p.descendants().forEach(ProcessHandle::destroyForcibly);
            }
        } catch (Exception ignored) {
            // 进程可能已自行退出；下面的 destroyForcibly 兜底
        }
        p.destroyForcibly();
    }

    private static Thread drainThread(java.io.InputStream in, StringBuilder sink, String name) {
        Thread t = new Thread(() -> readInto(in, sink), name);
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static void joinQuietly(Thread t) {
        try {
            t.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 读一条流直到 EOF（受 1MB 上限保护；进程被杀时流会抛，忽略）。 */
    private static void readInto(java.io.InputStream in, StringBuilder sink) {
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (sink.length() < 1_000_000) {
                    synchronized (sink) {
                        sink.append(line).append('\n');
                    }
                }
            }
        } catch (Exception ignored) {
            // 进程被杀时流会抛，忽略
        }
    }

    /** 一次执行的结果。 */
    private record Exec(String error, String stdout, String stderr, int exitCode, boolean timedOut) {
        boolean ok() {
            return error == null && !timedOut && exitCode == 0;
        }
    }

    /**
     * 连线测试结果。
     *
     * @param ok       是否成功
     * @param error    失败原因（人话）；成功时为 null
     * @param elapsedMs 耗时
     * @param sample   成功时返回的一小段响应样本
     */
    public record TestResult(boolean ok, String error, long elapsedMs, String sample) {
    }
}
