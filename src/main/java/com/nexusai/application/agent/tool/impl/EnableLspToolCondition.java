package com.nexusai.application.agent.tool.impl;

import com.nexusai.application.agent.tasks.TaskSystemConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * ENABLE_LSP_TOOL 注册门控 Condition · 对齐 CC tools.ts:224
 * {@code ...(isEnvTruthy(process.env.ENABLE_LSP_TOOL) ? [LSPTool] : [])}。
 *
 * <p><b>WHY（为何自定义 Condition 而非 @ConditionalOnProperty）</b>: CC 门控用
 * {@code isEnvTruthy}（envUtils.ts:32-37），truthy 集合为 <b>{1, true, yes, on}</b> 四值
 * （trim + lowercase 后精确匹配）；Spring {@code @ConditionalOnProperty(havingValue="true")}
 * 只认字面量 "true"，会漏 1/yes/on，违反规则三「不能简单实现」与「逐字对齐 CC」。
 * 故自定义 {@link Condition} 委托共享 {@link TaskSystemConfig#isEnvTruthy(String)}（规则七
 * 复用更经测试版本，不另起私有拷贝），绝不折中。
 *
 * <p><b>两层门控（与 CC 逐字对齐）</b>:
 * <ul>
 *   <li><b>注册层（本类）</b>: tools.ts:224 ENABLE_LSP_TOOL truthy 才进 getAllBaseTools 数组；
 *       Java 等价 = {@link com.nexusai.application.agent.config.ToolRegistrationConfig#lspTool()}
 *       的 {@code @Bean} 带 {@code @Conditional(EnableLspToolCondition.class)}。ENABLE_LSP_TOOL
 *       未设（默认）→ bean 不创建 → 不进 {@code @Autowired List<Tool>} → 不进 registry/schema。</li>
 *   <li><b>运行时层（LspTool.isEnabled()）</b>: LSPTool.ts:137-139 {@code isEnabled() = isLspConnected()}，
 *       与注册门控独立，两层叠加（对齐 CC tools.ts:175-182 getEnabledTools 二次 filter isEnabled）。</li>
 * </ul>
 *
 * <p><b>读源</b>: {@link ConditionContext#getEnvironment()}{@code .getProperty("ENABLE_LSP_TOOL")}
 * 覆盖 OS 环境变量 / {@code -D} system property / Spring property 三源（CC 只认 {@code process.env}，
 * Java 为三源超集，行为超集差异登记 concerns）。env 名保持 CC 原名 {@code ENABLE_LSP_TOOL}
 * 不映射 {@code nexusai.*}（对齐 verifyPlan/usePowerShellTool 的 raw-env 先例）。
 *
 * <p>CC 原名/行号: {@code ENABLE_LSP_TOOL}（Open-ClaudeCode/src/tools.ts:224）;
 * {@code isEnvTruthy}（Open-ClaudeCode/src/utils/envUtils.ts:32-37）。
 *
 * <p><b>复用来源</b>: {@link TaskSystemConfig#isEnvTruthy(String)}（tasks/TaskSystemConfig.java:400，
 * public static，语义逐字对齐 CC envUtils.ts:32-37 四值 {1,true,yes,on}），本类不保留私有
 * isEnvTruthy 拷贝（消除第 9 份私有实现；规则七复用更经测试版本）。
 */
public class EnableLspToolCondition implements Condition {

    private static final Logger log = LoggerFactory.getLogger(EnableLspToolCondition.class);

    /** CC env 原名: ENABLE_LSP_TOOL（Open-ClaudeCode/src/tools.ts:224），不映射 nexusai.*。 */
    private static final String ENABLE_LSP_TOOL = "ENABLE_LSP_TOOL";

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String value = context.getEnvironment().getProperty(ENABLE_LSP_TOOL);
        boolean result = TaskSystemConfig.isEnvTruthy(value);
        if (log.isDebugEnabled()) {
            log.debug("[EnableLspToolCondition] 门控判定: ENABLE_LSP_TOOL={}, truthy={}（对齐 CC tools.ts:224 "
                + "isEnvTruthy(process.env.ENABLE_LSP_TOOL)）", value, result);
        }
        return result;
    }
}
