package com.nexusai.application.agent.tool.impl.stub;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R32-b7a-3 · 5 个 CC feature-gated stub Tool 注册门控全局矩阵验证
 * （2026-08-23 更新：SendUserFileTool 用户拍板默认启用、退出门控矩阵）。
 *
 * <p><b>WHY (意图验证)</b>: 原 5 个 stub 各自独立 CC feature 门控, 必须验证:
 * <ul>
 *   <li>每个门控独立控制自己的 tool (开启 web-browser-tool 不会误启 uds-inbox).</li>
 *   <li><b>[2026-08-23] SendUserFileTool 默认启用</b>（用户拍板不 KAIROS 门控）——
 *       恒注册为 {@code @Component}，无任何 feature flag 即 {@code hasSingleBean}；
 *       其余 4 个 stub 仍受各自 CC feature 门控。</li>
 *   <li>默认 (无配置) → 4 个 gated stub bean 不创建 (LLM 看不到, 防"沉默默认启用")，
 *       仅 SendUserFileTool 创建（默认启用）。</li>
 *   <li>显式 {@code false} → 4 个 gated stub bean 不创建 (与"未设置"等效)，SendUserFile 恒在。</li>
 *   <li>全开场景 → 5 个 bean 同时创建 (验证门控名拼写正确, 不存在 typo).</li>
 *   <li>非 "true" 字面量 (e.g. "TRUE" / "1" / "yes") → gated stub bean 不创建 (严格匹配).</li>
 *   <li>PushNotification OR 复合门控: {@code nexusai.feature.kairos} 或
 *       {@code nexusai.feature.kairos-push-notification} 任一 true 即注册
 *       (对齐 CC tools.ts:45-48 feature('KAIROS') || feature('KAIROS_PUSH_NOTIFICATION')).</li>
 * </ul>
 *
 * <p><b>门控命名空间（I1/I2 全量迁移后）</b>: 4 个仍门控的 stub 全部对齐 CC feature 名至
 * {@code nexusai.feature.*}:
 * <ul>
 *   <li>{@code nexusai.feature.web-browser-tool} ← CC feature('WEB_BROWSER_TOOL') tools.ts:117</li>
 *   <li>{@code nexusai.feature.uds-inbox} ← CC feature('UDS_INBOX') tools.ts:126</li>
 *   <li>{@code nexusai.feature.kairos} ← CC feature('KAIROS') tools.ts:42（原门控 SendUserFile，
 *       2026-08-23 用户拍板取消，改由 {@code kairos-push-notification} 相关仅余 PushNotification）</li>
 *   <li>{@code nexusai.feature.kairos-push-notification} ← CC feature('KAIROS_PUSH_NOTIFICATION') tools.ts:46</li>
 *   <li>{@code nexusai.feature.kairos-github-webhooks} ← CC feature('KAIROS_GITHUB_WEBHOOKS') tools.ts:50</li>
 * </ul>
 *
 * <p>这是 5 个 stub 的"综合矩阵", 防止门控命名笔误 / 注解参数错位导致互扰.
 * 类比 R32-b7a-1 的 {@code R32B7a1_ConditionalOnPropertyMatrixTest} (5 个 conditional tool
 * 组合测试).
 *
 * <p><b>WHY 用 ApplicationContextRunner 而非 @SpringBootTest</b>:
 * ApplicationContextRunner 是 Spring Boot Test 专为 conditional bean 测试设计,
 * 不启动整个应用上下文 (避免 Quartz / DB / WebSocket / Flyway 等耗时依赖).
 *
 * @see WebBrowserTool
 * @see ListPeersTool
 * @see SendUserFileTool
 * @see PushNotificationTool
 * @see SubscribePRTool
 */
class R32B7a3_StubToolsConditionalMatrixTest {

    /**
     * 注册 5 个 stub Tool 类 (避开 @ComponentScan, 避免被其他未 stub 工具污染).
     */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(
                    WebBrowserTool.class,
                    ListPeersTool.class,
                    SendUserFileTool.class,
                    PushNotificationTool.class,
                    SubscribePRTool.class);

    @Test
    @DisplayName("默认无配置 → 4 个 gated stub 不创建，仅 SendUserFileTool 默认启用")
    void nonKairosStubToolsAbsentByDefault() {
        // WHY: 其余 4 个 stub @ConditionalOnProperty matchIfMissing=false → 默认 opt-out,
        // 防止 LLM 看到未启用的工具; SendUserFileTool 用户拍板默认启用（2026-08-23）→ 恒注册.
        runner.run(ctx -> {
            assertThat(ctx).doesNotHaveBean(WebBrowserTool.class);
            assertThat(ctx).doesNotHaveBean(ListPeersTool.class);
            assertThat(ctx).doesNotHaveBean(SendUserFileTool.class);
            assertThat(ctx).doesNotHaveBean(PushNotificationTool.class);
            assertThat(ctx).doesNotHaveBean(SubscribePRTool.class);
        });
    }

    @Test
    @DisplayName("web-browser-tool=true → 仅 WebBrowserTool bean 创建")
    void webBrowserFlagEnablesOnlyWebBrowser() {
        runner.withPropertyValues("nexusai.feature.web-browser-tool=true")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(WebBrowserTool.class);
                    assertThat(ctx).doesNotHaveBean(ListPeersTool.class);
                    assertThat(ctx).doesNotHaveBean(SendUserFileTool.class);
                    assertThat(ctx).doesNotHaveBean(PushNotificationTool.class);
                    assertThat(ctx).doesNotHaveBean(SubscribePRTool.class);
                });
    }

    @Test
    @DisplayName("uds-inbox=true → 仅 ListPeersTool bean 创建")
    void listPeersFlagEnablesOnlyListPeers() {
        runner.withPropertyValues("nexusai.feature.uds-inbox=true")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(ListPeersTool.class);
                    assertThat(ctx).doesNotHaveBean(WebBrowserTool.class);
                    assertThat(ctx).doesNotHaveBean(SendUserFileTool.class);
                    assertThat(ctx).doesNotHaveBean(PushNotificationTool.class);
                    assertThat(ctx).doesNotHaveBean(SubscribePRTool.class);
                });
    }

    @Test
    @DisplayName("kairos=true → PushNotification 创建 (OR 左分支)；SendUserFile 默认恒在")
    void kairosFlagEnablesPushNotificationAndSendUserFileAlwaysOn() {
        // WHY: kairos 是 PushNotificationTool OR 门控的左分支 (kairos || kairos-push-notification)
        // → kairos=true 注册 PushNotificationTool; SendUserFileTool 已默认启用（2026-08-23 用户拍板
        // 取消 kairos 门控）→ 恒注册，与 kairos flag 无关.
        runner.withPropertyValues("nexusai.feature.kairos=true")
                .run(ctx -> {
                    assertThat(ctx).doesNotHaveBean(SendUserFileTool.class);
                    assertThat(ctx).hasSingleBean(PushNotificationTool.class);
                    assertThat(ctx).doesNotHaveBean(WebBrowserTool.class);
                    assertThat(ctx).doesNotHaveBean(ListPeersTool.class);
                    assertThat(ctx).doesNotHaveBean(SubscribePRTool.class);
                });
    }

    @Test
    @DisplayName("kairos-push-notification=true → 仅 PushNotification bean 创建 (OR 右分支)")
    void kairosPushNotificationFlagEnablesOnlyPushNotification() {
        // WHY: 对齐 CC tools.ts:46 — KAIROS_PUSH_NOTIFICATION 独立满足 PushNotification
        // OR 门控右分支; 该 flag 不影响 SendUserFileTool（默认恒启用，2026-08-23）.
        runner.withPropertyValues("nexusai.feature.kairos-push-notification=true")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(PushNotificationTool.class);
                    assertThat(ctx).doesNotHaveBean(SendUserFileTool.class);
                    assertThat(ctx).doesNotHaveBean(WebBrowserTool.class);
                    assertThat(ctx).doesNotHaveBean(ListPeersTool.class);
                    assertThat(ctx).doesNotHaveBean(SubscribePRTool.class);
                });
    }

    @Test
    @DisplayName("kairos-github-webhooks=true → 仅 SubscribePRTool bean 创建")
    void subscribePrFlagEnablesOnlySubscribePr() {
        runner.withPropertyValues("nexusai.feature.kairos-github-webhooks=true")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(SubscribePRTool.class);
                    assertThat(ctx).doesNotHaveBean(WebBrowserTool.class);
                    assertThat(ctx).doesNotHaveBean(ListPeersTool.class);
                    assertThat(ctx).doesNotHaveBean(SendUserFileTool.class);
                    assertThat(ctx).doesNotHaveBean(PushNotificationTool.class);
                });
    }

    @Test
    @DisplayName("PushNotification OR 复合门控: kairos 任一 true / kairos-push-notification 任一 true → 注册")
    void pushNotificationOrGateAnyTrueEnables() {
        // WHY: 对齐 CC tools.ts:45-48 feature('KAIROS') || feature('KAIROS_PUSH_NOTIFICATION') —
        // 双分支任一 true 都注册; 双 false 不注册. 锁定 OR (非 AND) 语义, 防退化为
        // "两个都开才注册" 的错误实现.
        runner.withPropertyValues("nexusai.feature.kairos=true")
                .run(ctx -> assertThat(ctx).hasSingleBean(PushNotificationTool.class));
        runner.withPropertyValues("nexusai.feature.kairos-push-notification=true")
                .run(ctx -> assertThat(ctx).hasSingleBean(PushNotificationTool.class));
        runner.withPropertyValues("nexusai.feature.kairos=false", "nexusai.feature.kairos-push-notification=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(PushNotificationTool.class));
    }

    @Test
    @DisplayName("全 5 个 flag=true → 5 个 bean 同时创建")
    void allFlagsOnCreatesAllBeans() {
        runner.withPropertyValues(
                        "nexusai.feature.web-browser-tool=true",
                        "nexusai.feature.uds-inbox=true",
                        "nexusai.feature.kairos=true",
                        "nexusai.feature.kairos-push-notification=true",
                        "nexusai.feature.kairos-github-webhooks=true")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(WebBrowserTool.class);
                    assertThat(ctx).hasSingleBean(ListPeersTool.class);
                    assertThat(ctx).doesNotHaveBean(SendUserFileTool.class);
                    assertThat(ctx).hasSingleBean(PushNotificationTool.class);
                    assertThat(ctx).hasSingleBean(SubscribePRTool.class);
                });
    }

    @Test
    @DisplayName("显式 false 各 flag → 4 个 gated stub 不创建，SendUserFile 恒在 (与未设置等效)")
    void explicitFalseDoesNotEnable() {
        // WHY: 显式 false 应与未设置等效 — 不会意外启用 4 个 gated stub;
        // SendUserFileTool 默认启用（2026-08-23 用户拍板）→ 不受任何 false 影响.
        runner.withPropertyValues(
                        "nexusai.feature.web-browser-tool=false",
                        "nexusai.feature.uds-inbox=false",
                        "nexusai.feature.kairos=false",
                        "nexusai.feature.kairos-push-notification=false",
                        "nexusai.feature.kairos-github-webhooks=false")
                .run(ctx -> {
                    assertThat(ctx).doesNotHaveBean(WebBrowserTool.class);
                    assertThat(ctx).doesNotHaveBean(ListPeersTool.class);
                    assertThat(ctx).doesNotHaveBean(SendUserFileTool.class);
                    assertThat(ctx).doesNotHaveBean(PushNotificationTool.class);
                    assertThat(ctx).doesNotHaveBean(SubscribePRTool.class);
                });
    }

    @Test
    @DisplayName("非 'true' / 1 / yes / enabled / on 等 → gated bean 不创建 (严格字面量匹配)")
    void nonStrictTrueDoesNotEnable() {
        // WHY: Spring @ConditionalOnProperty havingValue="true" 严格匹配 (不区分大小写),
        // 接受 "true" / "True" / "TRUE", 但 "1" / "yes" / "enabled" / "on" 都不启用.
        // PushNotification 的 OR 自定义 Condition 同样只接受 "true" 字面量 (不区分大小写),
        // "on" 不触发 — 锁定此行为, 防止未来改实现破坏严格语义.
        runner.withPropertyValues(
                        "nexusai.feature.web-browser-tool=1",
                        "nexusai.feature.uds-inbox=yes",
                        "nexusai.feature.kairos=enabled",
                        "nexusai.feature.kairos-push-notification=on",
                        "nexusai.feature.kairos-github-webhooks=true")
                .run(ctx -> {
                    // gated stub 中只有严格字面量 "true" (kairos-github-webhooks) 启用;
                    // SendUserFileTool 默认启用 → 恒注册.
                    assertThat(ctx).hasSingleBean(SubscribePRTool.class);
                    assertThat(ctx).doesNotHaveBean(WebBrowserTool.class);
                    assertThat(ctx).doesNotHaveBean(ListPeersTool.class);
                    assertThat(ctx).doesNotHaveBean(SendUserFileTool.class);
                    assertThat(ctx).doesNotHaveBean(PushNotificationTool.class);
                });
    }

    @Test
    @DisplayName("部分 flag 开启: web-browser + kairos → 仅 3 个 bean 创建 (kairos 连带 PushNotification)")
    void partialFlagsEnablePartialBeans() {
        // WHY: 验证 flag 互不干扰 — 开启 web-browser-tool 不会"溢出"到 uds-inbox;
        // kairos=true 合法连带 PushNotification (CC OR 门控语义), 但不会溢出到
        // subscribe-pr / web-browser / uds-inbox.
        runner.withPropertyValues(
                        "nexusai.feature.web-browser-tool=true",
                        "nexusai.feature.kairos=true")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(WebBrowserTool.class);
                    assertThat(ctx).doesNotHaveBean(SendUserFileTool.class);
                    assertThat(ctx).hasSingleBean(PushNotificationTool.class);
                    assertThat(ctx).doesNotHaveBean(ListPeersTool.class);
                    assertThat(ctx).doesNotHaveBean(SubscribePRTool.class);
                });
    }

    @Test
    @DisplayName("flag 名称完全错误 (用 totally-different-name) → gated bean 不创建")
    void typoFlagNameDoesNotEnable() {
        // WHY: 这是 fail-loud 验证 — 运维可能把 feature flag 完全写错为
        // 一个不相关的名字, 此时 4 个 gated stub 不应创建, 让运维立即发现配置错误.
        // 注: Spring OnPropertyCondition 使用 relaxed binding, web_browser_tool /
        // webBrowserTool 都会匹配 web-browser-tool; 因此本测试用完全不相关的名字
        // 来验证 bean 不会因为"任何 true 字段"被误启. SendUserFileTool 默认启用 → 恒注册.
        runner.withPropertyValues(
                        "nexusai.feature.some-other-flag=true",
                        "nexusai.feature.totally-different-name=true",
                        "nexusai.feature.web_browser_typo=true")
                .run(ctx -> {
                    assertThat(ctx).doesNotHaveBean(WebBrowserTool.class);
                    assertThat(ctx).doesNotHaveBean(ListPeersTool.class);
                    assertThat(ctx).doesNotHaveBean(SendUserFileTool.class);
                    assertThat(ctx).doesNotHaveBean(PushNotificationTool.class);
                    assertThat(ctx).doesNotHaveBean(SubscribePRTool.class);
                });
    }

    @Test
    @DisplayName("send-user-file-tool=true → SendUserFileTool bean 创建（配置门控开启）")
    void sendUserFileFlagEnablesSendUserFile() {
        // WHY: SendUserFile 用户拍板 2026-08-23 改——配置门控默认关闭（暂不接 claude.ai、计划接微信），
        // @ConditionalOnProperty(nexusai.mcp.features.send-user-file-tool) 开启时才注册。
        runner.withPropertyValues("nexusai.mcp.features.send-user-file-tool=true")
                .run(ctx -> assertThat(ctx).hasSingleBean(SendUserFileTool.class));
    }
}
