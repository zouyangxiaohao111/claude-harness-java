package com.nexusai.domain.provider;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [2026-11-23 Octop provider 导入] 品牌归属判据。
 *
 * <p><b>WHY</b>：{@code ProviderService.resolveBrandKey} 遍历 BRANDS 首次命中即返回。
 * 新插品牌/新补 host 一旦与既有 host 重叠，老连接会被算到新卡上（老卡显示未配置）。
 * 本用例把「老 host 仍归老品牌」与「新 host 归预期品牌」同时钉死：
 * <ul>
 *   <li>B2 的 9 个新 host（octop 的国际站/专属域）→ 归各自既有品牌；</li>
 *   <li>3 个新增品牌 → host 唯一命中；</li>
 *   <li>老 host 对照 → 不被新品牌抢走（腾讯混元 / 豆包 / kimi.cn / minimax.cn 等）。</li>
 * </ul>
 * 红前绿后：本文件先于 BRANDS 修改落地 ⇒ B2/新品牌断言必红；改后全绿。
 */
class ProviderBrandAttributionTest {

    private static String resolve(String name, String baseUrl) throws Exception {
        Method m = ProviderService.class.getDeclaredMethod(
            "resolveBrandKey", String.class, String.class, String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, null, name, baseUrl);
    }

    @Test
    @DisplayName("B2 的 9 个新 host 归既有品牌（不新建 Brand）")
    void b2HostsAttributedToExistingBrands() throws Exception {
        assertThat(resolve("Kimi (International)", "https://api.moonshot.ai/v1")).isEqualTo("kimi");
        assertThat(resolve("MiniMax (China)", "https://api.minimaxi.com/v1")).isEqualTo("minimax");
        assertThat(resolve("MiniMax (International)", "https://api.minimax.io/v1")).isEqualTo("minimax");
        assertThat(resolve("Zhipu (Z.AI)", "https://api.z.ai/api/paas/v4")).isEqualTo("zhipu");
        assertThat(resolve("Zhipu Coding Plan (Z.AI)", "https://api.z.ai/api/coding/paas/v4")).isEqualTo("zhipu");
        assertThat(resolve("DashScope (Singapore)", "https://dashscope-intl.aliyuncs.com/compatible-mode/v1")).isEqualTo("qwen");
        assertThat(resolve("DashScope (US)", "https://dashscope-us.aliyuncs.com/compatible-mode/v1")).isEqualTo("qwen");
        assertThat(resolve("Aliyun Coding Plan (International)", "https://coding-intl.dashscope.aliyuncs.com/v1")).isEqualTo("qwen");
        assertThat(resolve("SiliconFlow (International)", "https://api.siliconflow.com/v1")).isEqualTo("siliconflow");
    }

    @Test
    @DisplayName("3 个新增品牌按 host 命中")
    void newBrandsResolve() throws Exception {
        assertThat(resolve("ModelScope", "https://api-inference.modelscope.cn/v1")).isEqualTo("modelscope");
        assertThat(resolve("iFlytek Astron Token Plan", "https://maas-token-api.cn-huabei-1.xf-yun.com/v2")).isEqualTo("iflytek");
        assertThat(resolve("Tencent Cloud Coding Plan", "https://api.lkeap.cloud.tencent.com/coding/v3")).isEqualTo("tencent");
    }

    @Test
    @DisplayName("老连接不串：既有 host 仍归既有品牌（新品牌不抢、新 host 不误伤）")
    void legacyHostsDoNotSteal() throws Exception {
        assertThat(resolve("Kimi", "https://api.moonshot.cn/v1")).isEqualTo("kimi");
        assertThat(resolve("MiniMax", "https://api.minimax.cn/v1")).isEqualTo("minimax");
        assertThat(resolve("智谱 GLM", "https://open.bigmodel.cn/api/paas/v4")).isEqualTo("zhipu");
        assertThat(resolve("通义千问", "https://dashscope.aliyuncs.com/compatible-mode/v1")).isEqualTo("qwen");
        assertThat(resolve("硅基流动", "https://api.siliconflow.cn/v1")).isEqualTo("siliconflow");
        assertThat(resolve("腾讯混元", "https://api.hunyuan.cloud.tencent.com/v1")).isEqualTo("hunyuan");
        assertThat(resolve("豆包", "https://ark.cn-beijing.volces.com/api/v3")).isEqualTo("doubao");
    }

    @Test
    @DisplayName("新品牌带模板模型；同名既有品牌补了缺失模型")
    void brandModelsPresent() {
        assertThat(BuiltinProviderCatalog.byKey("modelscope").models()).isNotEmpty();
        assertThat(BuiltinProviderCatalog.byKey("iflytek").models()).isNotEmpty();
        assertThat(BuiltinProviderCatalog.byKey("tencent").models()).isNotEmpty();
        for (String key : new String[]{"gemini", "groq", "openrouter", "deepseek", "mimo", "ollama"}) {
            assertThat(BuiltinProviderCatalog.byKey(key).models())
                .as("同名品牌 %s 应带模板模型", key).isNotEmpty();
        }
    }
}
