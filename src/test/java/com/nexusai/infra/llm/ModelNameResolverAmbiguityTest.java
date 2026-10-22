package com.nexusai.infra.llm;

import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.repository.provider.entity.ModelRecord;
import com.nexusai.repository.provider.mapper.ModelMapper;
import com.nexusai.repository.provider.mapper.ProviderMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * [2026-10-22 根治] 裸名歧义 fail-loud · {@link ModelNameResolver#resolve} 单测。
 *
 * <p>WHY（规则九 · 测试验证意图）：当多个 provider 都提供同名模型（实测
 * {@code deepseek-flash} 同时存在于 deepseek / ds-zcw / ds-zcw-openai）时，旧实现按
 * provider_id 字典序「猜一个」。该顺序与 key 有效性无关 —— 夜间选中了 key 已失效的
 * ds-zcw-openai，主会话被通知唤醒的 32 轮全部 401，而子代理侧一切正常。
 *
 * <p>验收条件：裸名命中多个 enabled 模型 ⇒ **返回 null（拒绝解析）**，绝不静默挑一个；
 * 裸名唯一命中仍兼容；全名精确路径不受影响。
 */
@ExtendWith(MockitoExtension.class)
class ModelNameResolverAmbiguityTest {

    @Mock private ModelMapper modelMapper;
    @Mock private ProviderMapper providerMapper;

    private static ModelRecord model(String id, String name, String providerId) {
        ModelRecord m = new ModelRecord();
        m.setId(id);
        m.setName(name);
        m.setProviderId(providerId);
        return m;
    }

    @Test
    @DisplayName("裸名命中多个 provider 的同名模型 → fail-loud（返回 null，不猜 provider）")
    void ambiguousBareName_isRejected() {
        // providerMapper 为 null → 跳过全名路径，直接走兼容路径
        when(modelMapper.selectListByQuery(any(QueryWrapper.class))).thenReturn(List.of(
            model("m-dszcw-openai", "deepseek-flash", "prov-a583e4c7"),
            model("m-deepseek", "deepseek-flash", "prov-b053b03d"),
            model("m-dszcw", "deepseek-flash", "prov-e90af148")));

        ModelRecord hit = ModelNameResolver.resolve(modelMapper, null, "deepseek-flash");

        assertThat(hit)
            .as("裸名歧义必须拒绝解析：静默选错 provider 会推迟到外部 API 才以 401 暴露")
            .isNull();
    }

    @Test
    @DisplayName("裸名唯一命中 → 保持兼容（历史数据不炸）")
    void uniqueBareName_stillWorks() {
        when(modelMapper.selectListByQuery(any(QueryWrapper.class))).thenReturn(List.of(
            model("m1", "kimi-k3", "prov-de487e66")));

        ModelRecord hit = ModelNameResolver.resolve(modelMapper, null, "kimi-k3");

        assertThat(hit).isNotNull();
        assertThat(hit.getId()).isEqualTo("m1");
    }

    @Test
    @DisplayName("全名路径不受歧义规则影响（精确命中指定 provider）")
    void fullName_bypassesAmbiguityRule() {
        com.nexusai.repository.provider.entity.ProviderRecord p =
            new com.nexusai.repository.provider.entity.ProviderRecord();
        p.setId("prov-b053b03d");
        p.setName("deepseek");
        when(providerMapper.selectOneByQuery(any(QueryWrapper.class))).thenReturn(p);
        when(modelMapper.selectOneByQuery(any(QueryWrapper.class)))
            .thenReturn(model("m-deepseek", "deepseek-flash", "prov-b053b03d"));

        ModelRecord hit = ModelNameResolver.resolve(modelMapper, providerMapper, "deepseek/deepseek-flash");

        assertThat(hit).isNotNull();
        assertThat(hit.getProviderId()).isEqualTo("prov-b053b03d");
    }
}
