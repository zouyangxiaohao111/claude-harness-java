package com.nexusai.application.agent.tool.config;

import com.nexusai.application.agent.tool.cron.CronJitter.CronJitterConfig;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RES-A6 · {@link CronJitterProperties} 边界校验（对齐 CC cronJitterConfig.ts Zod schema）。
 *
 * <p><b>WHY (意图验证)</b>: CC 对 GrowthBook push 的 {@code tengu_kairos_cron_config} 用
 * Zod schema 逐字段约束 + refine 交叉校验（cronJitterConfig.ts:40-52），任一越界/refine 失败
 * 整对象回退 DEFAULT_CRON_JITTER_CONFIG（:74）。Java 侧分两半对齐：
 * <ul>
 *   <li>逐字段边界 → {@code @Validated + @Min/@Max}（Spring 绑定拦截，启动即 abort，
 *       决策 A6 拍板取拦截语义，非 CC 软回退——越界部署显式失败而非静默吃默认）；</li>
 *   <li>跨字段 {@code floor ≤ max}（CC :52 refine，@Min/@Max 无法表达）→
 *       {@link CronJitterProperties#toConfig()} log.error + 回退 {@link CronJitterConfig#DEFAULT}。</li>
 * </ul>
 * 本测试用 Validator API（非 Spring 上下文）直接断言注解存在且行为正确——jakarta.validation
 * 校验仅 Spring 绑定路径生效，直接 new 不经校验，必须断言注解本身。
 */
class CronJitterPropertiesValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private void assertPropertyViolated(CronJitterProperties props, String property) {
        Set<ConstraintViolation<CronJitterProperties>> violations = validator.validate(props);
        assertThat(violations.stream().map(v -> v.getPropertyPath().toString()))
            .as("属性 " + property + " 越界应被 @Min/@Max 拦截（对齐 CC cronJitterConfig.ts:40-50）")
            .contains(property);
    }

    @Test
    @DisplayName("逐字段越界被 @Min/@Max 拦截: recurringFrac/oneShotMinuteMod/recurringMaxAgeMs/cap")
    void outOfBoundsValuesProduceConstraintViolations() {
        // recurringFrac 边界 [0,1]（cronJitterConfig.ts:40 z.number().min(0).max(1)）
        assertPropertyViolated(new CronJitterProperties(1.5, 900000, 90000, 0, 30, 604800000), "recurringFrac");
        assertPropertyViolated(new CronJitterProperties(-0.1, 900000, 90000, 0, 30, 604800000), "recurringFrac");
        // oneShotMinuteMod 边界 [1,60]（cronJitterConfig.ts:44）
        assertPropertyViolated(new CronJitterProperties(0.1, 900000, 90000, 0, 0, 604800000), "oneShotMinuteMod");
        assertPropertyViolated(new CronJitterProperties(0.1, 900000, 90000, 0, 61, 604800000), "oneShotMinuteMod");
        // recurringMaxAgeMs 边界 [0,2592000000]（cronJitterConfig.ts:45-50，上限超 int 范围）
        assertPropertyViolated(new CronJitterProperties(0.1, 900000, 90000, 0, 30, 2_592_000_001L), "recurringMaxAgeMs");
        // recurringCapMs 边界 [0,1800000]（cronJitterConfig.ts:41）
        assertPropertyViolated(new CronJitterProperties(0.1, -1, 90000, 0, 30, 604800000), "recurringCapMs");
    }

    @Test
    @DisplayName("合法边界值不产生违规（边界包含端点，对齐 Zod min/max 闭区间）")
    void boundaryValuesAreValid() {
        // 全字段压边界端点：recurringFrac=1 / cap=1800000 / max=1800000 / floor=1800000 /
        // minuteMod=60 / maxAge=2592000000 —— Zod 闭区间 min/max 均接受。
        CronJitterProperties boundary = new CronJitterProperties(
            1.0, 1_800_000L, 1_800_000L, 1_800_000L, 60, 2_592_000_000L);
        assertThat(validator.validate(boundary)).as("端点值合法（Zod min/max 闭区间）").isEmpty();
    }

    @Test
    @DisplayName("oneShotFloorMs > oneShotMaxMs → toConfig() 回退 DEFAULT（对齐 CC refine :52→:74）")
    void floorGreaterThanMaxFallsBackToDefault() {
        // floor=1800000 > max=90000：jitter 区间反置（cronTasks.ts:440-441），
        // CC refine 失败整对象回退 DEFAULT_CRON_JITTER_CONFIG（cronJitterConfig.ts:52/74）。
        CronJitterProperties bad = new CronJitterProperties(0.1, 900_000L, 90_000L, 1_800_000L, 30, 604_800_000L);
        assertThat(bad.toConfig())
            .as("floor>max 时消费方必须拿到合法默认而非反置区间")
            .isEqualTo(CronJitterConfig.DEFAULT);
    }

    @Test
    @DisplayName("合法值 toConfig() 透传（默认值 == DEFAULT；非默认值原样映射不回退）")
    void validValuesPassthrough() {
        // 默认值（@DefaultValue 无参构造）→ 恰为 DEFAULT，toConfig 不触发回退分支。
        assertThat(new CronJitterProperties().toConfig()).isEqualTo(CronJitterConfig.DEFAULT);
        // 非默认合法值 → 原样映射，绝不因误判回退 DEFAULT。
        CronJitterConfig custom = new CronJitterProperties(0.5, 100_000L, 50_000L, 10_000L, 15, 60_000L).toConfig();
        assertThat(custom).isNotEqualTo(CronJitterConfig.DEFAULT);
        assertThat(custom.recurringFrac()).isEqualTo(0.5);
        assertThat(custom.recurringCapMs()).isEqualTo(100_000L);
        assertThat(custom.oneShotMaxMs()).isEqualTo(50_000L);
        assertThat(custom.oneShotFloorMs()).isEqualTo(10_000L);
        assertThat(custom.oneShotMinuteMod()).isEqualTo(15);
        assertThat(custom.recurringMaxAgeMs()).isEqualTo(60_000L);
    }
}
