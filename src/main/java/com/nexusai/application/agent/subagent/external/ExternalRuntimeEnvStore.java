package com.nexusai.application.agent.subagent.external;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 外部 CLI 运行时的配置快照 · 供<b>纯静态</b>的执行链读取。
 *
 * <p><b>为什么要有这个静态槽</b>：{@link ExternalAgentRunner} / {@link ExternalRuntimeSpec}
 * 是纯静态类（不注入 Spring），而配置在数据库里。启动期与每次保存后由
 * {@code CliRuntimeConfigService} 把解密后的快照整体替换进来 —— 执行链只做一次
 * map 查表，不碰 DB、不碰加密。
 *
 * <p><b>为什么快照而不是按需查库</b>：一次委派就在子进程启动的关键路径上，读库 + 解密
 * 会把它变成「DB 慢就派不出去」。而且外部运行时配置变更频率极低。
 * 用 volatile 引用整体替换，读写都无锁。
 *
 * <p>⛔ 明文 key 只存在本对象的堆内存里：不写日志、不进 {@code toString}、不回接口。
 */
public final class ExternalRuntimeEnvStore {

    /**
     * 一个 runtime 的全部覆盖值。
     *
     * <p>语义（与设置页一致）：**每项独立**，空 = 该项用 CLI 自己的默认。
     *
     * @param configDir 配置目录（支持 {@code ~} 前缀）；null/空 = 用 CLI 默认
     * @param baseUrl   API base URL；null/空 = 用 CLI 默认
     * @param secrets   环境变量名 → 明文 key（仅内存）。空 = 用 CLI 自己的登录态
     * @param bin       自定义可执行文件绝对路径；null/空 = 走 PATH 探测
     * @param model     选定的模型 id；null/空 = CLI 默认
     * @param reasoning 推理强度 id；null/空 = CLI 默认
     */
    public record Overlay(String configDir, String baseUrl, Map<String, String> secrets,
                          String bin, String model, String reasoning) {

        public Overlay {
            secrets = secrets == null ? Map.of() : Map.copyOf(secrets);
        }

        /** 兼容旧构造：只有三项。 */
        public Overlay(String configDir, String baseUrl, String apiKey) {
            this(configDir, baseUrl,
                    apiKey == null || apiKey.isBlank() ? Map.of() : Map.of("__default__", apiKey),
                    null, null, null);
        }

        /** 全部为空 ⇒ 等价于没配。 */
        public boolean isEmpty() {
            return blank(configDir) && blank(baseUrl) && secrets.isEmpty()
                    && blank(bin) && blank(model) && blank(reasoning);
        }

        /** 除 secrets 外是否有值（用于「已配置」判断；secrets 单独有标志位）。 */
        public boolean hasNonSecret() {
            return !blank(configDir) || !blank(baseUrl) || !blank(bin)
                    || !blank(model) || !blank(reasoning);
        }

        static boolean blank(String s) {
            return s == null || s.isBlank();
        }

        /** ⛔ 不打印 key 值（防日志/异常栈外泄）。 */
        @Override
        public String toString() {
            return "Overlay[configDir=" + configDir + ", baseUrl=" + baseUrl
                    + ", bin=" + bin + ", model=" + model + ", reasoning=" + reasoning
                    + ", secrets=" + secrets.keySet() + "]";
        }
    }

    private static final AtomicReference<Map<String, Overlay>> SNAPSHOT =
            new AtomicReference<>(Map.of());

    private ExternalRuntimeEnvStore() {
    }

    /** 整体替换快照（启动期 / 保存后）。传 null 视为清空。 */
    public static void replace(Map<String, Overlay> byRuntime) {
        SNAPSHOT.set(byRuntime == null ? Map.of() : Map.copyOf(byRuntime));
    }

    public static Overlay get(String runtime) {
        if (runtime == null) return null;
        return SNAPSHOT.get().get(runtime.trim());
    }

    /** 用户配置的自定义可执行路径（未配置 ⇒ null，走 PATH 探测）。 */
    public static String binFor(String runtime) {
        Overlay o = get(runtime);
        return o == null || Overlay.blank(o.bin()) ? null : o.bin().trim();
    }

    /** 用户选定的模型（未配置 ⇒ null）。 */
    public static String modelFor(String runtime) {
        Overlay o = get(runtime);
        return o == null || Overlay.blank(o.model()) ? null : o.model().trim();
    }

    /** 用户选定的推理强度（未配置 ⇒ null）。 */
    public static String reasoningFor(String runtime) {
        Overlay o = get(runtime);
        return o == null || Overlay.blank(o.reasoning()) ? null : o.reasoning().trim();
    }

    /**
     * 生成要注入子进程的环境变量（连接信息部分）。
     *
     * <p>【未配置 ⇒ 空 map】—— 不是「注入空字符串」。空字符串会被很多 CLI 当成
     * 「显式设为空」而覆盖掉它自己的默认配置（例如把 base URL 清掉），与「没配」语义完全不同。
     *
     * <p>【配了 ⇒ 只注入该项】—— 各项独立，互不牵连。
     *
     * <p>【key 的变量名】由预设给出（claude 两个：{@code ANTHROPIC_AUTH_TOKEN} /
     * {@code ANTHROPIC_API_KEY}；codex 两个：{@code CODEX_API_KEY} / {@code OPENAI_API_KEY}）。
     * 用户填的是「哪一个」，就注入哪一个 —— ⛔ 不把同一个 key 塞进所有同名变量，
     * 否则用户无法只设其中一个。
     *
     * @param preset 运行时预设（提供各 CLI 认的环境变量名）
     * @return 环境变量增量；无配置时为空 map（永不为 null）
     */
    public static Map<String, String> envFor(ExternalRuntimeCatalog.RuntimePreset preset) {
        if (preset == null) return Map.of();
        Overlay o = get(preset.runtime());
        if (o == null || o.isEmpty()) return Map.of();

        Map<String, String> env = new LinkedHashMap<>(4);
        if (!Overlay.blank(o.configDir()) && preset.configDirEnv() != null) {
            env.put(preset.configDirEnv(), expandHome(o.configDir().trim()));
        }
        if (!Overlay.blank(o.baseUrl()) && preset.baseUrlEnv() != null) {
            env.put(preset.baseUrlEnv(), o.baseUrl().trim());
        }
        // secrets：只注入「预设认得的变量名」，防止用户误配一个 CLI 不认的名字还以为生效了
        for (Map.Entry<String, String> e : o.secrets().entrySet()) {
            String name = e.getKey();
            String value = e.getValue();
            if (name == null || value == null || value.isBlank()) continue;
            if ("__default__".equals(name)) {
                // 旧构造：塞进该 CLI 的第一个 key 变量
                if (!preset.apiKeyEnvs().isEmpty()) {
                    env.put(preset.apiKeyEnvs().get(0), value.trim());
                }
                continue;
            }
            if (preset.apiKeyEnvs().contains(name)) {
                env.put(name, value.trim());
            }
        }
        return env;
    }

    /** {@code ~/x} → {@code <user.home>/x}；其它形态原样返回。 */
    static String expandHome(String path) {
        if (path == null || path.isEmpty()) return path;
        String home = System.getProperty("user.home", "");
        if (path.equals("~")) return home;
        if (path.startsWith("~/") || path.startsWith("~\\")) {
            // 用平台分隔符拼接：Windows 上 "C:\Users\x" + "/.codex-2" 混用虽能被接受，
            // 但写进 CLI 的 env 后再回显/排错时容易误判成「两个路径」
            return java.nio.file.Path.of(home, path.substring(2)).toString();
        }
        return path;
    }
}
