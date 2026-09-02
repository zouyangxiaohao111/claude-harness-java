package com.nexusai.application.agent.tool.impl;

import com.nexusai.domain.oauth_account.AccountOAuthTokenService;
import com.nexusai.repository.oauth_account.mapper.AccountOAuthTokenMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * R32-b7a-1 · RemoteTriggerTool {@code @ConditionalOnProperty} 守卫验证
 * + 属性重命名迁移测试.
 *
 * <p><b>WHY (意图验证)</b>: RemoteTriggerTool 用于远程 sub-agent 调度 (对齐 CC
 * {@code RemoteTriggerTool.ts} + AGENT_TRIGGERS_REMOTE flag). 测试验证:
 * <ul>
 *   <li>默认 → bean 不创建 (远程触发是高危功能, 必须显式 opt-in)</li>
 *   <li>新属性 {@code nexusai.feature.agent-trigger-remote=true} → bean 创建</li>
 *   <li>旧属性 {@code nexusai.tools.remote-trigger=true} <b>不应再生效</b>
 *       (R32-b7a 重命名后, 旧属性已废弃, 配置必须迁移)</li>
 *   <li>大小写敏感 (与 SleepTool 一致)</li>
 * </ul>
 *
 * <p><b>迁移要求</b>: 旧部署配置 {@code nexusai.tools.remote-trigger=true}
 * 在升级后必须同步迁移为 {@code nexusai.feature.agent-trigger-remote=true}.
 *
 * @see RemoteTriggerTool
 */
class R32B7a1_RemoteTriggerToolConditionalTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(RemoteTriggerTool.class)
            // RemoteTriggerTool 新增 AccountOAuthTokenService 构造依赖（S6 OAuth Bearer 头接线），
            // runner 需补 stub bean，否则缺依赖 bean 创建失败。AccountOAuthTokenService 有
            // @Autowired AccountOAuthTokenMapper 字段注入，须同步补 mapper stub 避免 autowire 失败。
            .withBean(AccountOAuthTokenService.class, () -> mock(AccountOAuthTokenService.class))
            .withBean(AccountOAuthTokenMapper.class, () -> mock(AccountOAuthTokenMapper.class));

    @Test
    @DisplayName("默认无配置 → RemoteTriggerTool bean 不创建")
    void remoteTriggerAbsentByDefault() {
        // WHY: 远程 sub-agent 调度是高危功能 (外部网络调用 + OAuth),
        // 必须显式 opt-in, 默认隐藏避免 LLM 误调
        runner.run(ctx -> {
            assertThat(ctx).doesNotHaveBean(RemoteTriggerTool.class);
        });
    }

    @Test
    @DisplayName("agent-trigger-remote=true → RemoteTriggerTool bean 创建")
    void remoteTriggerPresentWhenEnabled() {
        runner.withPropertyValues("nexusai.feature.agent-trigger-remote=true")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(RemoteTriggerTool.class);
                });
    }

    @Test
    @DisplayName("agent-trigger-remote=false → RemoteTriggerTool bean 不创建")
    void remoteTriggerAbsentWhenExplicitlyDisabled() {
        runner.withPropertyValues("nexusai.feature.agent-trigger-remote=false")
                .run(ctx -> {
                    assertThat(ctx).doesNotHaveBean(RemoteTriggerTool.class);
                });
    }

    @Test
    @DisplayName("旧属性 nexusai.tools.remote-trigger=true 不再生效 (迁移验证)")
    void legacyPropertyDoesNotEnable() {
        // WHY: R32-b7a-1 重命名属性后, 旧配置不应再 enable bean;
        // 这是 fail-loud 迁移: 旧部署必须升级配置, 否则功能不可用 (但不会沉默误启)
        runner.withPropertyValues("nexusai.tools.remote-trigger=true")
                .run(ctx -> {
                    assertThat(ctx)
                            .as("Legacy property nexusai.tools.remote-trigger=true must NOT enable "
                                    + "RemoteTriggerTool (renamed to nexusai.feature.agent-trigger-remote)")
                            .doesNotHaveBean(RemoteTriggerTool.class);
                });
    }

    @Test
    @DisplayName("新 + 旧属性同时设置时, 仅新属性生效 (新属性优先)")
    void newPropertyWinsOverLegacy() {
        // WHY: 当用户正在迁移时, 可能同时设置新旧属性;
        // 期望: 新属性 true → 启用 (不依赖旧属性状态)
        runner.withPropertyValues(
                        "nexusai.feature.agent-trigger-remote=true",
                        "nexusai.tools.remote-trigger=false")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(RemoteTriggerTool.class);
                });
    }

    @Test
    @DisplayName("旧属性 true + 新属性 false → bean 不创建 (新属性决定胜负)")
    void legacyTrueNewFalseStillDisabled() {
        // WHY: 迁移期防御 — 即便旧属性残留 true, 只要新属性 false 就不应启用
        runner.withPropertyValues(
                        "nexusai.feature.agent-trigger-remote=false",
                        "nexusai.tools.remote-trigger=true")
                .run(ctx -> {
                    assertThat(ctx).doesNotHaveBean(RemoteTriggerTool.class);
                });
    }

    @Test
    @DisplayName("agent-trigger-remote 大小写不敏感 (Spring equalsIgnoreCase)")
    void remoteTriggerPropertyCaseInsensitive() {
        // WHY: Spring ConditionalOnProperty 使用 equalsIgnoreCase 比较 havingValue,
        // 与 SleepTool 同模式. 验证 config migration 时不会因大小写导致失败.
        for (String truthy : new String[]{"True", "TRUE", "true"}) {
            runner.withPropertyValues("nexusai.feature.agent-trigger-remote=" + truthy)
                    .run(ctx -> assertThat(ctx)
                            .as("Spring case-insensitive match: remote=" + truthy + " → bean enabled")
                            .hasSingleBean(RemoteTriggerTool.class));
        }
    }

    @Test
    @DisplayName("name() 与 description() 在 bean 创建后可正常访问")
    void remoteTriggerBeanUsable() {
        // WHY: 即使 enabled=true, bean 实例必须可用 (name/description/等基础方法不抛异常)
        runner.withPropertyValues("nexusai.feature.agent-trigger-remote=true")
                .run(ctx -> {
                    RemoteTriggerTool bean = ctx.getBean(RemoteTriggerTool.class);
                    // F2 对齐 CC prompt.ts:1 REMOTE_TRIGGER_TOOL_NAME='RemoteTrigger'
                    assertThat(bean.name()).isEqualTo("RemoteTrigger");
                    assertThat(bean.description())
                            .as("description 应提到属性名, 帮助用户配置")
                            .contains("nexusai.feature.agent-trigger-remote");
                });
    }
}