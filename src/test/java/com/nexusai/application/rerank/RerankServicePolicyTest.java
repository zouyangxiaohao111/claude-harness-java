package com.nexusai.application.rerank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexusai.application.provider.ModelEndpointResolver;
import com.nexusai.infra.exception.ValidationException;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.infra.rerank.RerankClient;
import com.nexusai.infra.rerank.RerankCommand;
import com.nexusai.infra.rerank.RerankResult;
import com.nexusai.repository.provider.entity.ModelRecord;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * RerankService 策略纯函数测试（规则九：测意图）。
 *
 * <p><b>WHY（意图）</b>：重排与向量共用 provider 配置，最容易出错的是「类型门」与「topN
 * 越界」——把 chat 模型当 rerank 调、或 topN 大于候选数时行为不确定。测试钉死：
 * 只有 {@code models.type=rerank} 能进重排链路；topN ≤0 明确 400，超过候选数收敛。
 */
@DisplayName("RerankService: 类型门 / 输入校验 / topN 归一")
class RerankServicePolicyTest {

    private static ModelRecord model(String type) {
        ModelRecord m = new ModelRecord();
        m.setName("qwen3-rerank");
        m.setType(type);
        return m;
    }

    @Test
    @DisplayName("类型门：只有 rerank 类型能进重排；embedding/chat/null 一律明确拒绝")
    void typeGate() {
        ModelEndpointResolver.assertType(model("rerank"), "rerank");

        assertThatThrownBy(() -> ModelEndpointResolver.assertType(model("embedding"), "rerank"))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("不能用于重排序");
        assertThatThrownBy(() -> ModelEndpointResolver.assertType(model("chat"), "rerank"))
            .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> ModelEndpointResolver.assertType(model(null), "rerank"))
            .isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("输入校验：query 空 / documents 空 / 空串 / 超条数 / 超长 都被拒")
    void validate() {
        assertThatThrownBy(() -> RerankService.validate(null, List.of("a")))
            .isInstanceOf(ValidationException.class).hasMessageContaining("query");
        assertThatThrownBy(() -> RerankService.validate("q", List.of()))
            .isInstanceOf(ValidationException.class).hasMessageContaining("documents");
        assertThatThrownBy(() -> RerankService.validate("q", List.of("ok", "  ")))
            .isInstanceOf(ValidationException.class).hasMessageContaining("documents[1]");
        assertThatThrownBy(() -> RerankService.validate("q", List.of("x".repeat(32_769))))
            .isInstanceOf(ValidationException.class).hasMessageContaining("超过");

        List<String> tooMany = new ArrayList<>();
        for (int i = 0; i < RerankService.MAX_DOCUMENTS + 1; i++) {
            tooMany.add("d" + i);
        }
        assertThatThrownBy(() -> RerankService.validate("q", tooMany))
            .isInstanceOf(ValidationException.class).hasMessageContaining("最多");
    }

    @Test
    @DisplayName("topN：null 原样；≤0 拒绝；超过候选数收敛到候选数")
    void normalizeTopN() {
        assertThat(RerankService.normalizeTopN(null, 5)).isNull();
        assertThat(RerankService.normalizeTopN(3, 5)).isEqualTo(3);
        assertThat(RerankService.normalizeTopN(9, 5)).isEqualTo(5);
        assertThatThrownBy(() -> RerankService.normalizeTopN(0, 5))
            .isInstanceOf(ValidationException.class).hasMessageContaining("topN");
        assertThatThrownBy(() -> RerankService.normalizeTopN(-1, 5))
            .isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("指令优先级：请求显式值 > 模型默认；空白 → null（不发送）")
    void resolveInstruct() {
        assertThat(RerankService.resolveInstruct("req", "model")).isEqualTo("req");
        assertThat(RerankService.resolveInstruct("  ", "model")).isEqualTo("model");
        assertThat(RerankService.resolveInstruct(null, null)).isNull();
        assertThat(RerankService.resolveInstruct("", "  ")).isNull();
    }

    @Test
    @DisplayName("客户端路由：恰好 1 个命中才返回；0 个/多个都 fail loud（⛔ 不猜）")
    void selectClient() {
        ProviderConfig config = new ProviderConfig("http://127.0.0.1", "k");
        RerankClient a = fake("a", true);
        RerankClient b = fake("b", false);

        assertThat(RerankService.selectClient(List.of(a, b), config, "p1")).isSameAs(a);

        assertThatThrownBy(() -> RerankService.selectClient(List.of(), config, "p1"))
            .isInstanceOf(ValidationException.class).hasMessageContaining("没有可用");
        assertThatThrownBy(() -> RerankService.selectClient(List.of(b), config, "p1"))
            .isInstanceOf(ValidationException.class).hasMessageContaining("provider=p1");
        assertThatThrownBy(() -> RerankService.selectClient(List.of(a, fake("c", true)), config, "p1"))
            .isInstanceOf(ValidationException.class).hasMessageContaining("多个");
    }

    private static RerankClient fake(String protocol, boolean supports) {
        return new RerankClient() {
            @Override
            public String protocol() {
                return protocol;
            }

            @Override
            public boolean supports(ProviderConfig config) {
                return supports;
            }

            @Override
            public RerankResult rerank(ProviderConfig config, RerankCommand command) {
                return new RerankResult(List.of(), command.model(), 0);
            }
        };
    }
}
