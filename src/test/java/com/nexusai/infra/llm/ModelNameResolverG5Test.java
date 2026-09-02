package com.nexusai.infra.llm;

import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.repository.provider.entity.ModelRecord;
import com.nexusai.repository.provider.entity.ProviderRecord;
import com.nexusai.repository.provider.mapper.ModelMapper;
import com.nexusai.repository.provider.mapper.ProviderMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [G-5] parseFullName '/' 误拆修复 · ModelNameResolver.resolve 拆分门控单测。
 *
 * <p>WHY（规则九 · 测试验证意图）：CC model.ts:445-506 模型名不透明——含 '/' 的自定义名
 * （Bedrock ARN / Azure 部署 ID）原样透传，绝不按第一个 '/' 拆 provider。旧 Java 实现无条件
 * 取第一个 '/' 拆，导致 {@code models.name} 含 '/' 被误拆：首段非 provider 时直接 fail-loud
 * 返回 null，模型名本身反而无法按整体查询。
 *
 * <p>验收条件（G-5）：<b>仅当首段命中 providers 表精确前缀时才拆</b>；首段未命中 →
 * 整体 name 透传走历史兼容路径（按整体名查询），不误拆、不误拒。真全名命中 provider 但该
 * 提供商下无 enabled 模型时仍 fail-loud 返回 null（G-2 回归保护，绝不回退重绑）。
 */
@ExtendWith(MockitoExtension.class)
class ModelNameResolverG5Test {

    @Mock private ModelMapper modelMapper;
    @Mock private ProviderMapper providerMapper;

    /** 兼容路径返回的模型（全名查询命中目标）。 */
    private static ModelRecord compatModel() {
        ModelRecord m = new ModelRecord();
        m.setId("compat");
        m.setProviderId("p-custom");
        m.setName("arn:aws:bedrock:us-west-2::foundation-model/anthropic.claude-sonnet-4-20250514");
        m.setEnabled(true);
        m.setMaxTokens(123456);
        return m;
    }

    /** 联合查路径返回的模型（仅当 provider 前缀命中时被消费）。 */
    private static ModelRecord jointModel() {
        ModelRecord m = new ModelRecord();
        m.setId("joint");
        m.setProviderId("p1");
        m.setName("claude-sonnet-4");
        m.setEnabled(true);
        m.setMaxTokens(200000);
        return m;
    }

    @Test
    @DisplayName("G-5: 含 '/' 不透明名（Bedrock ARN），首段非 provider 前缀 → 整体透传，按整体名兼容查询命中")
    void opaqueSlashName_noProviderPrefix_passesThroughWholeName() {
        String arn = "arn:aws:bedrock:us-west-2::foundation-model/anthropic.claude-sonnet-4-20250514";
        // 首段不是任何 provider 名 → provider 查询返回 null
        when(providerMapper.selectOneByQuery(any())).thenReturn(null);
        // 联合查 stub（provider==null 时绝不触发——若误触发会误返 jointModel；lenient 因该路径刻意不触发）
        lenient().when(modelMapper.selectOneByQuery(any())).thenReturn(jointModel());
        // 兼容路径（整体名查询）命中
        when(modelMapper.selectListByQuery(any())).thenReturn(List.of(compatModel()));

        ModelRecord result = ModelNameResolver.resolve(modelMapper, providerMapper, arn);

        assertThat(result)
            .as("首段非 provider 前缀 → 整体 name 透传走兼容路径命中，绝不误拆后 fail-loud null")
            .isNotNull();
        assertThat(result.getId()).isEqualTo("compat");

        // 铁证：兼容查询按【整体名】查询（toSQL 内联值），而非拆出的后缀
        ArgumentCaptor<QueryWrapper> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(modelMapper).selectListByQuery(captor.capture());
        String sql = captor.getValue().toSQL();
        assertThat(sql)
            .as("整体透传：兼容查询 name 条件必须是完整 ARN（CC model.ts:445-506 模型名不透明）")
            .contains("name = '" + arn + "'");
        assertThat(sql)
            .as("不得把 '/' 当拆分隔符——name 条件不能只查拆出的后缀")
            .doesNotContain("name = 'anthropic.claude-sonnet-4-20250514'");
    }

    @Test
    @DisplayName("G-5: 真全名（首段命中 provider 前缀）→ 仍走联合查，不落入兼容路径")
    void realFullName_providerPrefixHit_usesJointQuery() {
        when(providerMapper.selectOneByQuery(any())).thenAnswer(inv -> {
            ProviderRecord p = new ProviderRecord();
            p.setId("p1");
            p.setName("anthropic");
            return p;
        });
        when(modelMapper.selectOneByQuery(any())).thenReturn(jointModel());

        ModelRecord result = ModelNameResolver.resolve(modelMapper, providerMapper, "anthropic/claude-sonnet-4");

        assertThat(result).isNotNull();
        assertThat(result.getId())
            .as("provider 前缀命中 → 联合查命中该 provider 下模型")
            .isEqualTo("joint");
        verify(modelMapper, never()).selectListByQuery(any());
    }

    @Test
    @DisplayName("G-2 回归保护: 真全名命中 provider 但该提供商下无 enabled 模型 → fail-loud null，绝不回退兼容路径重绑")
    void realFullName_providerHitButNoModel_failLoudNull() {
        when(providerMapper.selectOneByQuery(any())).thenAnswer(inv -> {
            ProviderRecord p = new ProviderRecord();
            p.setId("p1");
            p.setName("anthropic");
            return p;
        });
        // 该 provider 下无 enabled 模型
        when(modelMapper.selectOneByQuery(any())).thenReturn(null);
        // 兼容路径有同名模型——G-2 要求【绝不回退】，故不能命中（lenient：该 stub 刻意不被消费）
        lenient().when(modelMapper.selectListByQuery(any())).thenReturn(List.of(compatModel()));

        ModelRecord result = ModelNameResolver.resolve(modelMapper, providerMapper, "anthropic/claude-sonnet-4");

        assertThat(result)
            .as("provider 前缀命中但模型缺失 → fail-loud null，不静默重绑其他 provider 同名模型（G-2）")
            .isNull();
        verify(modelMapper, never()).selectListByQuery(any());
    }

    @Test
    @DisplayName("G-5: resolveMaxTokens 继承透传——含 '/' 不透明名整体解析出 max_tokens")
    void resolveMaxTokens_opaqueName_wholeNameTokens() {
        String arn = "arn:aws:bedrock:us-west-2::foundation-model/anthropic.claude-sonnet-4-20250514";
        when(providerMapper.selectOneByQuery(any())).thenReturn(null);
        // 联合查 stub 刻意不触发（整体透传走兼容路径），lenient 防止 STRICT_STUBS 误报
        lenient().when(modelMapper.selectOneByQuery(any())).thenReturn(jointModel());
        when(modelMapper.selectListByQuery(any())).thenReturn(List.of(compatModel()));

        Integer maxTokens = ModelNameResolver.resolveMaxTokens(modelMapper, providerMapper, arn);

        assertThat(maxTokens)
            .as("含 '/' 不透明名整体透传后 max_tokens 应取兼容命中模型的 123456")
            .isEqualTo(123456);
    }
}
