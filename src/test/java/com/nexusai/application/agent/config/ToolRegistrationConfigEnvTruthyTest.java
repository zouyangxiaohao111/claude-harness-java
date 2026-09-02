package com.nexusai.application.agent.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ToolRegistrationConfig.isEnvTruthy 四值语义补全测试。
 *
 * <p>WHY（意图验证 · CLAUDE.md 规则九）：CC envUtils.ts:32-37 isEnvTruthy 真源 truthy 四值为
 * {@code ['1','true','yes','on']}（含 on）；旧 Java 私有拷贝仅三值缺 on，导致
 * {@code NEXUSAI_SESSION_MEMORY=on} / {@code DISABLE_COMPACT=on} 等环境变量无法被识别为真
 * （与 CC 行为漂移）。本测试锁定「on 须 truthy」这一修复意图——若业务逻辑把 on 当假，
 * 本测试必红。
 */
class ToolRegistrationConfigEnvTruthyTest {

    @Test
    @DisplayName("isEnvTruthy 四值 {1,true,yes,on} → true · off/0/空/null → false")
    void isEnvTruthyFourValues() {
        assertTrue(ToolRegistrationConfig.isEnvTruthy("on"), "on 须 truthy（对齐 CC ['1','true','yes','on']）");
        assertTrue(ToolRegistrationConfig.isEnvTruthy("ON"), "ON 大小写不敏感须 truthy");
        assertTrue(ToolRegistrationConfig.isEnvTruthy("1"), "1 须 truthy");
        assertTrue(ToolRegistrationConfig.isEnvTruthy("true"), "true 须 truthy");
        assertTrue(ToolRegistrationConfig.isEnvTruthy("yes"), "yes 须 truthy");
        assertFalse(ToolRegistrationConfig.isEnvTruthy("off"), "off 须 falsy");
        assertFalse(ToolRegistrationConfig.isEnvTruthy("0"), "0 须 falsy");
        assertFalse(ToolRegistrationConfig.isEnvTruthy(""), "空串须 falsy（isBlank）");
        assertFalse(ToolRegistrationConfig.isEnvTruthy(null), "null 须 falsy");
    }
}
