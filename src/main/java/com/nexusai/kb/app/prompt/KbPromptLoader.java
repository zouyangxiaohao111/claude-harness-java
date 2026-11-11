package com.nexusai.kb.app.prompt;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 知识库场景提示词加载器 · 从 classpath 读 {@code kb/prompt/**}{@code .md}。
 *
 * <p><b>为什么提示词要外置成文件</b>：
 * <ol>
 *   <li>提示词是<b>规格</b>不是代码逻辑，改一个标点就能改变模型行为，
 *       混在 Java 文本块里既难 diff 也难评审；</li>
 *   <li>与源侧 WeKnora 一致 —— 源侧的提示词本来就在
 *       {@code config/prompt_templates/*.yaml}，不在 Go 代码里硬编码；
 *       外置本身就是行为对齐，不是风格偏好。</li>
 * </ol>
 *
 * <h2>⭐⭐ 找不到 / 读失败 ⇒ 抛异常，绝不返回空串</h2>
 * 提示词缺失的后果是<b>静默缺陷</b>：模型收到空 system prompt，仍然会正常返回一段
 * 看起来通顺、但完全脱离规格的文本，调用链上没有任何报错，直到用户发现答案不对。
 * 把缺文件变成<b>启动即崩</b>（做法 A：常量静态初始化时加载）比让它悄悄跑要好得多。
 * ⇒ 因此本类<b>不做</b>「缺失回落默认值」的兜底。
 *
 * <h2>文件格式约定</h2>
 * <ul>
 *   <li>正文即提示词原文，<b>顶格书写</b>（不带 Java 文本块那层缩进）；</li>
 *   <li>可在<b>文件最前面</b>放一行 {@code <!-- ... -->} 说明来源，
 *       加载时会剥掉 —— 避免来源注释被当成提示词发给模型；</li>
 *   <li>占位符统一 {@code {{NAME}}} 形式，由调用方 {@link String#replace} 替换
 *       （⛔ 不用 {@code String.format}：正文里的 JSON 示例有大量 {@code {}}）。</li>
 * </ul>
 *
 * <p>⚠️ 资源打在 jar 里：改了 {@code .md} 必须重新 {@code mvn package}（或让 IDE 重新
 * 复制 resources），<b>热改磁盘文件不会生效</b>。
 */
public final class KbPromptLoader {

    private static final Logger log = LoggerFactory.getLogger(KbPromptLoader.class);

    /** classpath 下的提示词根目录。 */
    public static final String RESOURCE_BASE = "kb/prompt/";

    /**
     * 内置 Agent 人格提示词的 classpath 根目录 · 本仓扩展（源站无此目录，人格原本在
     * {@code config/prompt_templates/*.yaml}）。
     *
     * <p>与 {@link #RESOURCE_BASE} 分开的理由：人格 md 的生命周期与后处理提示词不同 ——
     * 它是 Agent 定义的一部分（随 {@code AgentDefinition} 一起被读取），
     * 而不是某条流水线阶段用一次。放在子目录也便于 {@code resources} 下按用途分类。
     */
    public static final String AGENT_RESOURCE_BASE = "kb/agent/";

    /** relativePath → 已加载并 trim 过的提示词正文。提示词是只读常量，进程内只需读一次。 */
    private static final Map<String, String> CACHE = new ConcurrentHashMap<>();

    private KbPromptLoader() {
    }

    /**
     * 从 {@code classpath:kb/prompt/{relativePath}} 读取 UTF-8 文本。
     *
     * <p>返回值已剥掉文件头部的 HTML 注释块、并去掉首尾空白。
     *
     * @param relativePath 相对路径，例如 {@code "wiki/summary.system.md"}
     * @return 提示词正文（保证非空白）
     * @throws IllegalStateException 资源不存在、读取失败或内容为空
     */
    public static String load(String relativePath) {
        return loadFrom(RESOURCE_BASE, relativePath);
    }

    /**
     * 从指定 classpath 根目录读取 UTF-8 文本 · 供 {@code kb/agent/} 人格 md 复用同一套
     * 「缺失即抛、⛔ 不回退空串」语义。
     *
     * @param resourceBase  classpath 根目录，必须以 {@code /} 结尾，例如 {@link #AGENT_RESOURCE_BASE}
     * @param relativePath 相对路径，例如 {@code "builtin-assistant.md"}
     * @return 提示词正文（保证非空白）
     * @throws IllegalStateException 资源不存在、读取失败或内容为空
     * @throws IllegalArgumentException resourceBase 为空或未以 / 结尾
     */
    public static String loadFrom(String resourceBase, String relativePath) {
        if (resourceBase == null || resourceBase.isBlank() || !resourceBase.endsWith("/")) {
            throw new IllegalArgumentException(
                "KbPromptLoader.loadFrom 的 resourceBase 必须非空且以 / 结尾，实际=" + resourceBase);
        }
        String rel = normalize(relativePath);
        String classpath = resourceBase + rel;
        ClassLoader cl = KbPromptLoader.class.getClassLoader();
        try (InputStream in = cl.getResourceAsStream(classpath)) {
            if (in == null) {
                // ⛔ 不返回空串：空 system prompt 会让模型自由发挥且不报错。
                throw new IllegalStateException(
                    "KbPromptLoader 找不到提示词资源 classpath:" + classpath
                        + " —— 请确认文件存在于 backend/src/main/resources/" + classpath
                        + "，并且构建时随 resources 打进了 jar"
                        + "（可用 `unzip -l target/nexusai-backend.jar | grep " + resourceBase + "` 自查）");
            }
            String raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            String body = stripHeaderComment(raw).strip();
            if (body.isEmpty()) {
                throw new IllegalStateException(
                    "KbPromptLoader 读到的提示词为空 classpath:" + classpath
                        + " —— 空提示词等于没有规格，请检查文件内容是否被误清空");
            }
            if (log.isDebugEnabled()) {
                log.debug("[KB提示词] 加载成功 classpath:{} 字符数={}", classpath, body.length());
            }
            return body;
        } catch (IOException e) {
            throw new IllegalStateException(
                "KbPromptLoader 读取提示词失败 classpath:" + classpath + " —— " + e.getMessage(), e);
        }
    }

    /**
     * {@link #load} 的缓存版本：同一 relativePath 只真正读一次 classpath。
     *
     * <p>提示词在运行期不会变，因此缓存无失效策略；需要换内容就重新打包/重启。
     *
     * @param relativePath 相对路径，例如 {@code "wiki/summary.system.md"}
     * @return 提示词正文（同一路径始终返回<b>同一个</b> String 实例）
     */
    public static String cached(String relativePath) {
        String rel = normalize(relativePath);
        return CACHE.computeIfAbsent(rel, key -> {
            String body = load(key);
            if (log.isInfoEnabled()) {
                log.info("[KB提示词] 首次加载并缓存 kb/prompt/{} 字符数={}", key, body.length());
            }
            return body;
        });
    }

    /**
     * {@link #loadFrom} 的缓存版本 · 供内置 Agent 人格 md 使用（{@link #AGENT_RESOURCE_BASE}）。
     *
     * <p>缓存键带 dir 前缀，与 {@link #cached(String)} 共用同一张表但互不冲突。
     *
     * @param resourceBase classpath 根目录，必须以 {@code /} 结尾
     * @param relativePath 相对路径，例如 {@code "builtin-assistant.md"}
     * @return 人格正文（同一 (dir, path) 始终返回<b>同一个</b> String 实例）
     */
    public static String cachedFrom(String resourceBase, String relativePath) {
        String rel = normalize(relativePath);
        String key = resourceBase + rel;
        return CACHE.computeIfAbsent(key, k -> {
            String body = loadFrom(resourceBase, rel);
            if (log.isInfoEnabled()) {
                log.info("[KB提示词] 首次加载并缓存 {} 字符数={}", key, body.length());
            }
            return body;
        });
    }

    /**
     * 清空缓存 —— <b>仅供单测使用</b>，生产代码不要调用。
     *
     * <p>缓存的语义是「进程内只读一次」，正常流程不存在需要失效的时机。
     */
    static void clearCacheForTest() {
        CACHE.clear();
    }

    /**
     * 规范化相对路径：去空白、反斜杠转正斜杠、去掉前导 {@code /}。
     *
     * @param relativePath 原始路径
     * @return 规范化后的路径
     * @throws IllegalArgumentException 路径为空
     */
    private static String normalize(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("KbPromptLoader 的 relativePath 不能为空");
        }
        String p = relativePath.strip().replace('\\', '/');
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        if (p.isEmpty()) {
            throw new IllegalArgumentException("KbPromptLoader 的 relativePath 不能为空: " + relativePath);
        }
        if (log.isDebugEnabled() && !p.toLowerCase(Locale.ROOT).endsWith(".md")) {
            log.debug("[KB提示词] 非 .md 后缀，按约定提示词都是 .md：{}", p);
        }
        return p;
    }

    /**
     * 剥掉文件<b>最前面</b>的 HTML 注释块（来源说明），正文内部的注释一概不动。
     *
     * <p>只在 {@code trim 后以 <!-- 开头} 时才剥，且必须能找到配对的 {@code -->}；
     * 找不到就原样返回（说明这不是我们约定的来源注释，交给后续环节报错，
     * 而不是在这里静默吃掉一大段正文）。
     *
     * @param text 原始文件内容
     * @return 剥掉头部注释后的内容
     */
    private static String stripHeaderComment(String text) {
        String s = text;
        // 去掉 BOM（Windows 编辑器另存时有概率带）
        if (!s.isEmpty() && s.charAt(0) == '\uFEFF') {
            s = s.substring(1);
        }
        String lead = s.stripLeading();
        if (!lead.startsWith("<!--")) {
            return s;
        }
        int end = lead.indexOf("-->");
        if (end < 0) {
            if (log.isWarnEnabled()) {
                log.warn("[KB提示词] 文件头部的 HTML 注释没有闭合 -->，按原样处理（不静默吞正文）");
            }
            return s;
        }
        return lead.substring(end + "-->".length());
    }
}
