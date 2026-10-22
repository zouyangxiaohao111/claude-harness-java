package com.nexusai.infra.llm;

import com.nexusai.repository.provider.entity.ModelRecord;
import com.nexusai.repository.provider.entity.ProviderRecord;
import com.nexusai.repository.provider.mapper.ModelMapper;
import com.nexusai.repository.provider.mapper.ProviderMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * [2026-10-22 模型绑定漂移修复] {@link ModelNameResolver#toFullName} 单测。
 *
 * <p>WHY（规则九 · 测试验证意图）：settings 存的是全名 {@code deepseek/deepseek-flash}，
 * 若调用方解析出 ModelRecord 后只回传裸名，下游会再解析一次并走「按 name 取第一条」兼容路径，
 * 可能换到**另一个 provider 的同名模型**。实测（2026-10-21 夜间 sess-aa022bdf）：二次解析落到
 * {@code ds-zcw-openai}（key 已失效），主会话被通知唤醒的 32 轮全部 HTTP 401，
 * 子代理完成通知虽已注入却没有任何 assistant 产出。
 *
 * <p>验收条件：已解析的 ModelRecord 必须能还原出与之一致的全名；模型名本身含 '/'（Bedrock ARN /
 * Azure 部署 ID）时不得再拼前缀（否则破坏 G-5 的不透明名透传）；provider 缺失时退回裸名（旧行为）。
 */
@ExtendWith(MockitoExtension.class)
class ModelNameResolverToFullNameTest {

    @Mock private ModelMapper modelMapper;
    @Mock private ProviderMapper providerMapper;

    private static ModelRecord model(String id, String name, String providerId) {
        ModelRecord m = new ModelRecord();
        m.setId(id);
        m.setName(name);
        m.setProviderId(providerId);
        return m;
    }

    private static ProviderRecord provider(String id, String name) {
        ProviderRecord p = new ProviderRecord();
        p.setId(id);
        p.setName(name);
        return p;
    }

    @Test
    @DisplayName("裸名模型 + provider 名 → 拼成 provider/model（绑定不漂移）")
    void bareName_becomesFullName() {
        when(providerMapper.selectOneById("prov-a583e4c7"))
            .thenReturn(provider("prov-a583e4c7", "ds-zcw-openai"));

        String full = ModelNameResolver.toFullName(
            providerMapper, model("m1", "deepseek-flash", "prov-a583e4c7"));

        assertThat(full).isEqualTo("ds-zcw-openai/deepseek-flash");
    }

    @Test
    @DisplayName("模型名本身含 '/'（Bedrock ARN 等不透明名）→ 原样返回，不拼前缀")
    void opaqueName_kept() {
        String arn = "arn:aws:bedrock:us-west-2::foundation-model/anthropic.claude-sonnet-4-20250514";
        lenient().when(providerMapper.selectOneById("p1")).thenReturn(provider("p1", "aws"));

        String full = ModelNameResolver.toFullName(providerMapper, model("m1", arn, "p1"));

        assertThat(full).isEqualTo(arn);
    }

    @Test
    @DisplayName("provider 缺失 / 名为空 / 名含 '/' → 退回裸名（不构造非法全名）")
    void unusableProvider_fallsBackToBareName() {
        when(providerMapper.selectOneById("p1")).thenReturn(null);
        when(providerMapper.selectOneById("p2")).thenReturn(provider("p2", " "));
        when(providerMapper.selectOneById("p3")).thenReturn(provider("p3", "a/b"));

        assertThat(ModelNameResolver.toFullName(providerMapper, model("m1", "deepseek-flash", "p1")))
            .isEqualTo("deepseek-flash");
        assertThat(ModelNameResolver.toFullName(providerMapper, model("m2", "deepseek-flash", "p2")))
            .isEqualTo("deepseek-flash");
        assertThat(ModelNameResolver.toFullName(providerMapper, model("m3", "deepseek-flash", "p3")))
            .isEqualTo("deepseek-flash");
    }

    @Test
    @DisplayName("providerMapper 为 null / model 为 null → 安全返回（裸名 / null）")
    void nullInputs_areSafe() {
        assertThat(ModelNameResolver.toFullName(null, model("m1", "deepseek-flash", "p1")))
            .isEqualTo("deepseek-flash");
        assertThat(ModelNameResolver.toFullName(providerMapper, null)).isNull();
    }
}
