package com.nexusai.domain.provider;

import java.util.List;
import java.util.Set;

/**
 * 内置品牌目录（常驻左栏的内置卡）。
 *
 * <p>只描述「品牌 → 预置协议 / 地址 / 匹配判据」这几件事，<b>不持有任何凭据</b>
 * （⛔ 任何 API Key / 访问令牌 / 明文密钥都不写在这里）。
 *
 * <p>⚠️ {@code openai} 这一条也留在目录里（前端统一渲染内置卡），但它的创建 / 登录仍走
 * {@link ProviderService#ensureOfficialCodexProvider()}，<b>不</b>走新的「品牌预填」创建路径。
 *
 * <p><b>分组</b>：{@code group} 只用于左栏分节（国际 / 国内 / 本地），不参与任何匹配。
 */
public final class BuiltinProviderCatalog {

    private BuiltinProviderCatalog() {
    }

    /**
     * 一个内置品牌。
     *
     * @param key        稳定键（前端图标 / 排序用）
     * @param group      左栏分组：国际 / 国内 / 本地
     * @param name       展示名
     * @param type       ProviderType 枚举名
     * @param baseUrl    预置地址（新建表单预填；openai 仅展示，实际创建走 ensure）
     * @param subtitle   备注副标题（未配置卡片的说明）
     * @param matchHosts 命中判据①：providers.baseUrl 解析出的 host 等于其中之一（或为其子域）
     * @param matchNames 命中判据②：providers.name trim + 小写后等于其中之一
     */
    public record Brand(String key, String group, String name, String type, String baseUrl, String subtitle,
                        Set<String> matchHosts, Set<String> matchNames) {
    }

    /** 分组常量（前端只做展示分节，不做逻辑判断）。 */
    public static final String GROUP_INTL = "国际";
    public static final String GROUP_CN = "国内";
    public static final String GROUP_LOCAL = "本地";

    /**
     * 内置品牌（顺序即前端左栏的展示顺序）。
     *
     * <p>⚠️ 本地三个（ollama / vllm / lmstudio）的 {@code matchHosts} 故意留空：
     * 它们都在 {@code 127.0.0.1} 上、只差端口，而 {@link ProviderService#baseUrlHostMatches}
     * 按 host 匹配不看端口 ⇒ 填了会导致三张卡互相误判「已配置」。本地品牌只按名字识别。
     */
    public static final List<Brand> BRANDS = List.of(
        // ---------------- 国际 ----------------
        new Brand("openai", GROUP_INTL, "OpenAI", "openai_response",
            "https://chatgpt.com/backend-api/codex",
            "ChatGPT Plus / Pro 订阅",
            Set.of("chatgpt.com"),
            Set.of("openai", "openai-订阅")),
        new Brand("claude", GROUP_INTL, "Claude", "anthropic",
            "https://api.anthropic.com",
            "Anthropic API Key",
            Set.of("api.anthropic.com"),
            Set.of("claude", "anthropic")),
        new Brand("gemini", GROUP_INTL, "Google Gemini", "openai_compatible",
            "https://generativelanguage.googleapis.com/v1beta/openai",
            "Gemini API Key（OpenAI 兼容）",
            Set.of("generativelanguage.googleapis.com"),
            Set.of("gemini", "google gemini", "谷歌")),
        new Brand("openrouter", GROUP_INTL, "OpenRouter", "openai_compatible",
            "https://openrouter.ai/api/v1",
            "OpenRouter API Key",
            Set.of("openrouter.ai"),
            Set.of("openrouter")),
        new Brand("groq", GROUP_INTL, "Groq", "openai_compatible",
            "https://api.groq.com/openai/v1",
            "Groq API Key",
            Set.of("api.groq.com"),
            Set.of("groq")),
        new Brand("xai", GROUP_INTL, "xAI Grok", "openai_compatible",
            "https://api.x.ai/v1",
            "xAI API Key",
            Set.of("api.x.ai"),
            Set.of("xai", "grok")),
        new Brand("mistral", GROUP_INTL, "Mistral", "openai_compatible",
            "https://api.mistral.ai/v1",
            "Mistral API Key",
            Set.of("api.mistral.ai"),
            Set.of("mistral")),

        // ---------------- 国内 ----------------
        new Brand("deepseek", GROUP_CN, "DeepSeek", "openai_compatible",
            "https://api.deepseek.com/v1",
            "DeepSeek API Key",
            Set.of("api.deepseek.com"),
            Set.of("deepseek")),
        new Brand("qwen", GROUP_CN, "通义千问", "openai_compatible",
            "https://dashscope.aliyuncs.com/compatible-mode/v1",
            "阿里云百炼 API Key",
            // 百炼既有公共 dashscope 域，也有专属实例 *.maas.aliyuncs.com
            Set.of("dashscope.aliyuncs.com", "maas.aliyuncs.com"),
            Set.of("qwen", "通义", "通义千问", "千问", "dashscope", "阿里云百炼")),
        new Brand("kimi", GROUP_CN, "Kimi", "openai_compatible",
            "https://api.moonshot.cn/v1",
            "Moonshot API Key",
            // 编码专用入口是 api.kimi.com（与开放平台 api.moonshot.cn 不是同一个域）
            Set.of("api.moonshot.cn", "api.kimi.com"),
            Set.of("kimi", "moonshot", "月之暗面")),
        new Brand("minimax", GROUP_CN, "MiniMax", "openai_compatible",
            "https://api.minimax.cn/v1",
            "MiniMax API Key",
            Set.of("api.minimax.cn"),
            Set.of("minimax")),
        new Brand("hunyuan", GROUP_CN, "腾讯混元", "openai_compatible",
            "https://api.hunyuan.cloud.tencent.com/v1",
            "腾讯云 API Key",
            Set.of("api.hunyuan.cloud.tencent.com"),
            Set.of("hunyuan", "混元", "腾讯混元")),
        new Brand("zhipu", GROUP_CN, "智谱 GLM", "openai_compatible",
            "https://open.bigmodel.cn/api/paas/v4",
            "智谱 API Key",
            Set.of("open.bigmodel.cn"),
            Set.of("zhipu", "glm", "智谱", "智谱清言")),
        new Brand("doubao", GROUP_CN, "豆包 · 火山方舟", "openai_compatible",
            "https://ark.cn-beijing.volces.com/api/v3",
            "火山方舟 API Key",
            Set.of("ark.cn-beijing.volces.com"),
            Set.of("doubao", "豆包", "火山方舟", "volces")),
        new Brand("wenxin", GROUP_CN, "百度文心", "openai_compatible",
            "https://qianfan.baidubce.com/v2",
            "千帆 API Key",
            Set.of("qianfan.baidubce.com"),
            Set.of("wenxin", "ernie", "文心", "千帆", "baidu")),
        new Brand("mimo", GROUP_CN, "小米 MiMo", "openai_compatible",
            "https://api.xiaomimimo.com/v1",
            "小米 MiMo API Key",
            Set.of("api.xiaomimimo.com"),
            Set.of("mimo", "xiaomi", "小米", "小米mimo")),
        new Brand("stepfun", GROUP_CN, "阶跃星辰", "openai_compatible",
            "https://api.stepfun.com/v1",
            "阶跃星辰 API Key",
            Set.of("api.stepfun.com"),
            Set.of("stepfun", "阶跃", "阶跃星辰")),
        new Brand("siliconflow", GROUP_CN, "硅基流动", "openai_compatible",
            "https://api.siliconflow.cn/v1",
            "SiliconFlow API Key",
            Set.of("api.siliconflow.cn"),
            Set.of("siliconflow", "硅基流动", "硅基")),

        // ---------------- 本地 ----------------
        new Brand("ollama", GROUP_LOCAL, "Ollama", "openai_compatible",
            "http://127.0.0.1:11434/v1",
            "本地 Ollama · 无需 Key",
            Set.of(),
            Set.of("ollama")),
        new Brand("vllm", GROUP_LOCAL, "vLLM", "openai_compatible",
            "http://127.0.0.1:8000/v1",
            "本地 vLLM · 无需 Key",
            Set.of(),
            Set.of("vllm")),
        new Brand("lmstudio", GROUP_LOCAL, "LM Studio", "openai_compatible",
            "http://127.0.0.1:1234/v1",
            "本地 LM Studio · 无需 Key",
            Set.of(),
            Set.of("lmstudio", "lm studio"))
    );

    /** 按键查品牌；未知 / null → null。 */
    public static Brand byKey(String key) {
        if (key == null) return null;
        for (Brand b : BRANDS) {
            if (b.key().equals(key)) return b;
        }
        return null;
    }
}
