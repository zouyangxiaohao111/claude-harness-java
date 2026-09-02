package com.nexusai.application.agent.tool.impl;

import com.nexusai.application.agent.tasks.TaskSystemConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * PowerShellTool 注册期门控 Condition · 对齐 CC tools.ts:150-156 {@code getPowerShellTool()}
 * 注册期条件 {@code isPowerShellToolEnabled()}（shellToolUtils.ts:17-22）。
 *
 * <p><b>WHY（为何自定义 Condition 而非 @ConditionalOnProperty）</b>: CC 门控是
 * {@code platform==='windows' ? (USER_TYPE==='ant' ? !isEnvDefinedFalsy(env) :
 * isEnvTruthy(env)) : false} 的<b>三因子</b>复合（平台 + USER_TYPE + CLAUDE_CODE_USE_POWERSHELL_TOOL），
 * 单一 {@code @ConditionalOnProperty(name="nexusai.feature.use-powershell", matchIfMissing=false)}
 * 只能表达「该属性显式为真才注册」，无法表达平台因子与 ant 默认开/外部默认关的三元语义
 * （外部默认关 = 缺省不注册，正好是 matchIfMissing=false；但 ant 默认开需真实现，且非 Windows
 * 恒 false 需平台因子）。故自定义 {@link Condition} 委托共享 {@link TaskSystemConfig#isEnvTruthy}
 * / {@link TaskSystemConfig#isEnvDefinedFalsy}（规则七复用更经测试版本，EnableLspToolCondition
 * 同款先例），绝不折中。</p>
 *
 * <p><b>CC 真源</b>（shellToolUtils.ts:17-22，grep 自验，不信注释）：
 * <pre>
 * export function isPowerShellToolEnabled(): boolean {
 *   if (getPlatform() !== 'windows') return false
 *   return process.env.USER_TYPE === 'ant'
 *     ? !isEnvDefinedFalsy(process.env.CLAUDE_CODE_USE_POWERSHELL_TOOL)
 *     : isEnvTruthy(process.env.CLAUDE_CODE_USE_POWERSHELL_TOOL)
 * }
 * </pre>
 * 对应 CC tools.ts:150-156：
 * <pre>
 * const getPowerShellTool = () => {
 *   if (!isPowerShellToolEnabled()) return null
 *   return (require('./tools/PowerShellTool/PowerShellTool.js')).PowerShellTool
 * }
 * </pre>
 * 及 tools.ts:242 {@code ...(getPowerShellTool() ? [getPowerShellTool()] : [])} —— 未启用时
 * <b>不进 getAllBaseTools</b>（注册层门控），与 {@link PowerShellTool#isEnabled()} 运行时门控
 * 独立两层（同 EnableLspToolCondition/LspTool 两层门控模式）。</p>
 *
 * <p><b>Java 介质映射</b>：platform 因子 = {@code os.name} 含 "win"（等价 CC getPlatform()==='windows'）；
 * USER_TYPE 与 CLAUDE_CODE_USE_POWERSHELL_TOOL 读 {@link ConditionContext#getEnvironment()}
 * （Spring 环境覆盖 OS 环境变量 / -D system property / Spring property 三源，CC 只认 process.env，
 * Java 为三源超集，行为超集差异登记 concerns）。两 env 名保持 CC 原名，不映射 {@code nexusai.*}
 * （对齐 EnableLspToolCondition ENABLE_LSP_TOOL 先例）。</p>
 *
 * <p><b>与 {@link PowerShellTool#isEnabled()} 的关系</b>：isEnabled() 合成
 * {@code isWindows() && featureFlags.usePowerShellTool()}（平台 × env/USER_TYPE 三元）——
 * 本 Condition 是同一逻辑的<b>注册层</b>表达。注册层过 → bean 存在 → 运行时 isEnabled()
 * 同源（均为 true）；注册层不过 → bean 不存在（非 Windows 或外部未 opt-in）。两因子同源，
 * 无分裂。</p>
 *
 * <p>CC 原名/行号: {@code isPowerShellToolEnabled}（Open-ClaudeCode/src/utils/shell/shellToolUtils.ts:17-22）;
 * {@code getPowerShellTool}（Open-ClaudeCode/src/tools.ts:150-156）。
 *
 * @see PowerShellTool
 */
public class PowerShellToolRegistrationCondition implements Condition {

    private static final Logger log = LoggerFactory.getLogger(PowerShellToolRegistrationCondition.class);

    /** CC 原名: USER_TYPE（shellToolUtils.ts:19）。 */
    private static final String USER_TYPE = "USER_TYPE";
    /** CC 原名: CLAUDE_CODE_USE_POWERSHELL_TOOL（shellToolUtils.ts:20-21）。 */
    private static final String USE_POWERSHELL_TOOL = "CLAUDE_CODE_USE_POWERSHELL_TOOL";

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        // 平台因子 · CC shellToolUtils.ts:18 getPlatform() === 'windows'
        String osName = context.getEnvironment().getProperty("os.name", "");
        if (!osName.toLowerCase().contains("win")) {
            if (log.isDebugEnabled()) {
                log.debug("[PowerShellToolRegistrationCondition] 非 Windows 平台 os.name={} → 不注册（对齐 CC shellToolUtils.ts:18）",
                    osName);
            }
            return false;
        }
        // USER_TYPE + env 三元 · CC shellToolUtils.ts:19-21
        String userType = context.getEnvironment().getProperty(USER_TYPE, "");
        String env = context.getEnvironment().getProperty(USE_POWERSHELL_TOOL);
        boolean enabled = "ant".equals(userType)
            ? !TaskSystemConfig.isEnvDefinedFalsy(env)
            : TaskSystemConfig.isEnvTruthy(env);
        if (log.isDebugEnabled()) {
            log.debug("[PowerShellToolRegistrationCondition] 门控判定: os.name={} USER_TYPE={} CLAUDE_CODE_USE_POWERSHELL_TOOL={} → 注册={}"
                + "（对齐 CC isPowerShellToolEnabled shellToolUtils.ts:17-22）",
                osName, userType, env, enabled);
        }
        return enabled;
    }
}
