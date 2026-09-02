package com.nexusai.application.agent.tool.impl.stub;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * OR 复合门控 Condition · 对齐 CC {@code tools.ts:45-48} {@code feature('KAIROS') ||
 * feature('KAIROS_PUSH_NOTIFICATION')}。
 *
 * <p><b>WHY（为何自定义 Condition）</b>: Spring {@code @ConditionalOnProperty} 指定多个 name 时是
 * AND 语义（所有属性都满足才匹配），无法表达 CC PushNotificationTool 的 OR 门控；仓库此前无
 * {@code @ConditionalOnExpression} 先例，且 SpEL 布尔强制转换经 {@code StringToBooleanConverter}
 * 会把 "on"/"yes"/"1" 当 true（DefaultConversionService），破坏与 {@code @ConditionalOnProperty}
 * havingValue="true" 一致的严格匹配（矩阵测试 {@code R32B7a3_StubToolsConditionalMatrixTest}
 * nonStrictTrueDoesNotEnable 锁定该语义）。故用显式字符串比较（不区分大小写 "true"）实现 OR。
 *
 * <p><b>匹配规则</b>（与 @ConditionalOnProperty havingValue="true" matchIfMissing=false 逐属性等价）:
 * {@code nexusai.feature.kairos=true}（任意大小写）或 {@code nexusai.feature.kairos-push-notification=true}
 * → match；双 false / 缺省 / 非 "true" 字面量 → no match。
 *
 * <p>CC feature 原名/行号: {@code KAIROS} / {@code KAIROS_PUSH_NOTIFICATION}
 * （Open-ClaudeCode/src/tools.ts:45-48）。
 */
public class KairosOrPushNotificationEnabledCondition implements Condition {

    private static final Logger log = LoggerFactory.getLogger(KairosOrPushNotificationEnabledCondition.class);

    /** CC feature('KAIROS') · Open-ClaudeCode/src/tools.ts:45-48（同时门控 SendUserFileTool tools.ts:42）. */
    private static final String FEATURE_KAIROS = "nexusai.feature.kairos";
    /** CC feature('KAIROS_PUSH_NOTIFICATION') · Open-ClaudeCode/src/tools.ts:45-48. */
    private static final String FEATURE_KAIROS_PUSH_NOTIFICATION = "nexusai.feature.kairos-push-notification";

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        boolean kairos = matchesTrue(context, FEATURE_KAIROS);
        boolean kairosPush = matchesTrue(context, FEATURE_KAIROS_PUSH_NOTIFICATION);
        boolean result = kairos || kairosPush;
        if (log.isDebugEnabled()) {
            log.debug("[KairosOrPushNotificationEnabledCondition] 门控判定: kairos={}, kairos-push-notification={}, "
                + "OR 结果={}（对齐 CC tools.ts:45-48 feature('KAIROS') || feature('KAIROS_PUSH_NOTIFICATION')）",
                kairos, kairosPush, result);
        }
        return result;
    }

    /** 与 @ConditionalOnProperty havingValue="true" 等价：值不区分大小写匹配 "true"，否则不匹配。 */
    private static boolean matchesTrue(ConditionContext context, String key) {
        String value = context.getEnvironment().getProperty(key);
        return value != null && "true".equalsIgnoreCase(value);
    }
}
