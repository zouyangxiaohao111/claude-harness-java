package com.nexusai.infra.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * [G4] strict 结构化输出模型层门控 · 对齐 CC api.ts:185-192 的 4 条件门控的"模型层"。
 *
 * <p>CC 4 条件（Open-ClaudeCode/src/utils/api.ts:185-192）：
 * {@code strictToolsEnabled && tool.strict === true && options.model &&
 * modelSupportsStructuredOutputs(options.model)}。Java 端分两层：
 * <ul>
 *   <li><b>意图层</b>（{@code ToolRegistry.toOpenAiToolsArray}）：flag && tool.strict() → 向
 *       工具 JSON 写 {@code fn.strict=true}（模型无关）。</li>
 *   <li><b>模型层</b>（本类 + provider 侧）：model != null + provider first-party + 白名单，
 *       决定 JSON 里的 strict 是否放行到 SDK 请求。门控失败静默降级不传（防 Bedrock/Vertex 400）。</li>
 * </ul>
 *
 * <p>CC original: {@code strictToolsEnabled}（api.ts:154-155，growthbook flag
 * {@code tengu_tool_pear}，默认 false）；{@code modelSupportsStructuredOutputs}
 * （betas.ts:142-157）；{@code isFirstPartyAnthropicBaseUrl}（providers.ts:25-37）。
 */
public final class StructuredOutputsSupport {

    private static final Logger log = LoggerFactory.getLogger(StructuredOutputsSupport.class);

    /** CC original: {@code tengu_tool_pear} growthbook flag（Open-ClaudeCode/src/utils/api.ts:154-155，默认 false）。 */
    public static final String TENGU_TOOL_PEAR_PROPERTY = "nexusai.feature.tengu-tool-pear";

    /** CC original: modelSupportsStructuredOutputs 白名单（Open-ClaudeCode/src/utils/betas.ts:151-157）·
     *  canonical 名含 claude-sonnet-4-6 / claude-sonnet-4-5 / claude-opus-4-1 / claude-opus-4-5 /
     *  claude-opus-4-6 / claude-haiku-4-5。 */
    private static final List<String> ANTHROPIC_STRUCTURED_OUTPUT_MARKERS = List.of(
        "claude-sonnet-4-6", "claude-sonnet-4-5",
        "claude-opus-4-1", "claude-opus-4-5", "claude-opus-4-6",
        "claude-haiku-4-5");

    /** OpenAI 白名单 · Java 多 provider 扩展 ⊕（CC 无参考）· OpenAI strict function calling 支持模型族 ·
     *  canonical 名以这些前缀开头（gpt-4o-* / gpt-4.1-* / o1-* / o3-* / gpt-4.5-* / gpt-5-*）· 可能需调。 */
    private static final List<String> OPENAI_STRUCTURED_OUTPUT_PREFIXES = List.of(
        "gpt-4o", "gpt-4.1", "o1", "o3", "gpt-4.5", "gpt-5");

    private StructuredOutputsSupport() {
    }

    /** CC original: {@code strictToolsEnabled}（api.ts:154-155）· 系统属性承载（默认 false，GB 未接入）。 */
    public static boolean tenguToolPearEnabled() {
        return Boolean.parseBoolean(System.getProperty(TENGU_TOOL_PEAR_PROPERTY, "false"));
    }

    /** CC original: {@code isFirstPartyAnthropicBaseUrl()}（Open-ClaudeCode/src/utils/model/providers.ts:25-37）·
     *  baseUrl 空/默认 api.anthropic.com → first-party；自定义 URL（LiteLLM 代理 / Bedrock / Vertex）→ false，
     *  必须不传 strict（防 400）。 */
    // [B-2 登记 · IMP-MV2-40] △-A10b：Java contains 子串判定 vs CC 精确 host 白名单（providers.ts:
    //   25-40，含 USER_TYPE==='ant' 的 api-staging 例外）—— 子串匹配放行 proxy.api.anthropic.com 等
    //   CC 拒绝形态，且无 staging 放行。共享 infra 实现偏差：默认配置（无 ANTHROPIC_BASE_URL）两端
    //   一致不触发；影响面超出本模块（E 域 structured outputs 亦消费）→ 共享 infra 统一登记，不修。
    public static boolean isFirstPartyAnthropicBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return true;
        }
        return baseUrl.contains("api.anthropic.com");
    }

    /** CC original: modelSupportsStructuredOutputs（betas.ts:142-157）· Anthropic 白名单（canonical contains）。
     *  <p>注：CC 会先把 Bedrock ARN 等 resolve 回 1P canonical（model.ts:279-283），Java 直接对
     *  modelName contains —— 白名单本身挡不住 Bedrock，须配合 {@link #isFirstPartyAnthropicBaseUrl}。 */
    public static boolean modelSupportsStructuredOutputs(String model) {
        if (model == null) {
            return false;
        }
        String canonical = model.toLowerCase();
        for (String marker : ANTHROPIC_STRUCTURED_OUTPUT_MARKERS) {
            if (canonical.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    /** Java 多 provider 扩展 ⊕（CC 无参考）：OpenAI 白名单（canonical startsWith 前缀匹配）。 */
    public static boolean modelSupportsStructuredOutputsOpenAi(String model) {
        if (model == null) {
            return false;
        }
        String canonical = model.toLowerCase();
        for (String prefix : OPENAI_STRUCTURED_OUTPUT_PREFIXES) {
            if (canonical.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** 4 条件门控的模型层 · Anthropic：flag && model != null && firstParty && 白名单。
     *  <p>{@code tool.strict} 已由意图层（ToolRegistry）写入 JSON，此处只判模型层（防 Bedrock/Vertex 400）。
     *  @param firstParty {@link #isFirstPartyAnthropicBaseUrl} 判定结果（baseUrl 在 provider 层作用域）。 */
    public static boolean shouldTransmitStrictAnthropic(String model, boolean firstParty) {
        boolean flag = tenguToolPearEnabled();
        boolean modelOk = model != null && modelSupportsStructuredOutputs(model);
        boolean ok = flag && firstParty && modelOk;
        if (log.isDebugEnabled()) {
            log.debug("StructuredOutputsSupport strict 门控判定(Anthropic): flag={} firstParty={} 模型白名单={} model={} → {}",
                flag, firstParty, modelOk, model, ok ? "透传 strict" : "静默降级不传");
        }
        return ok;
    }

    /** 4 条件门控的模型层 · OpenAI：flag && model != null && 白名单（Java 多 provider 扩展 ⊕，无 firstParty 判定）。 */
    public static boolean shouldTransmitStrictOpenAi(String model) {
        boolean flag = tenguToolPearEnabled();
        boolean modelOk = model != null && modelSupportsStructuredOutputsOpenAi(model);
        boolean ok = flag && modelOk;
        if (log.isDebugEnabled()) {
            log.debug("StructuredOutputsSupport strict 门控判定(OpenAI): flag={} 模型白名单={} model={} → {}",
                flag, modelOk, model, ok ? "透传 strict" : "静默降级不传");
        }
        return ok;
    }
}
