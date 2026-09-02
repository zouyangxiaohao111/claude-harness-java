package com.nexusai.application.agent.skill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * bundled /run-skill-generator skill 幽灵空壳登记 · DEC-15 · 对齐 CC bundled/index.ts:73-77 的
 * runSkillGenerator 注册块。
 *
 * <p><b>CC 源引用</b>：CC {@code src/skills/bundled/index.ts:73-77}
 * {@code if (feature('RUN_SKILL_GENERATOR')) { const { ... } = require('./runSkillGenerator.js'); ...() }}。
 * 源文件 {@code src/skills/bundled/runSkillGenerator.js} 本 checkout <b>缺失</b>（bundled 目录仅 16 个
 * .ts，无 runSkillGenerator.ts，探查 §9.3 E1）。
 *
 * <p><b>CC 上游缺陷</b>：CC 用懒 {@code require('./runSkillGenerator.js')}（非 ESM import），flag 开启
 * 时模块文件不存在 → CC 自身抛 {@code MODULE_NOT_FOUND}（生产 bundle flag 编译 false，DCE 掩盖此缺陷）。
 *
 * <p><b>Java 明确不注册（DEC-15）</b>：本类仅为登记该缺陷 + 保留未来移植锚点的空壳，
 * <b>不进入</b> {@link BundledSkillsBootstrapper} 注册列表（注册集保持 14 个不变，对齐 CC 生产
 * bundle 三 flag 编译 false 永不注册）。
 *
 * <p>{@link #SLASH_COMMAND} 证据仅来自生产 bundle 提示文本（源模块缺失），非源码级证据——若主 agent
 * 只认源码级证据，可只断言 {@link #NAME}（模块 basename）不断言斜杠命令。
 *
 * <p>TODO(DEC-15)：CC 源文件 {@code bundled/runSkillGenerator.js} 恢复后，按源内容移植为真实 Registrar
 * 并接入 {@link BundledSkillsBootstrapper}（先由主 agent 确认 feature flag 语义：Java 运行时门控 vs
 * CC 编译期）。
 */
public final class RunSkillGeneratorSkillRegistrar {

    private static final Logger log = LoggerFactory.getLogger(RunSkillGeneratorSkillRegistrar.class);

    /** CC 注册技能名 · 模块 basename（bundled/index.ts:77 runSkillGenerator 注册块），源文件缺失。 */
    public static final String NAME = "runSkillGenerator";

    /**
     * 用户面斜杠命令 · E2 证据 = 生产 bundle 提示文本（源模块缺失，非源模块证据）：
     * {@code package/cli.js:9155/:9234} 均含 '/run-skill-generator'。
     */
    public static final String SLASH_COMMAND = "/run-skill-generator";

    /** CC feature gate · CC original: {@code feature('RUN_SKILL_GENERATOR')} (bundled/index.ts:73)。 */
    public static final List<String> FEATURE_FLAGS = List.of("RUN_SKILL_GENERATOR");

    /** CC 源文件 · 本 checkout 缺失（DCE 剔除），TODO 移植后指向该路径。 */
    public static final String CC_SOURCE_FILE = "bundled/runSkillGenerator.js";

    private RunSkillGeneratorSkillRegistrar() {
        // 空壳不可实例化：防止被误注册（DEC-15 测试反射锁定 final + private 构造器）。
    }

    static {
        // 数据流日志：幽灵 skill 未注册登记（类仅被引用时触发；生产注册链不引用本类 → 实际不注册）。
        log.info("[RunSkillGeneratorSkillRegistrar] 幽灵 skill '{}'（斜杠命令 '{}'）明确不注册（DEC-15）：CC 源文件 {} 本 checkout 缺失（DCE 剔除，feature gate {} 编译 false），flag 开则 CC require 抛 MODULE_NOT_FOUND——登记 CC 上游缺陷",
            NAME, SLASH_COMMAND, CC_SOURCE_FILE, FEATURE_FLAGS);
    }
}
