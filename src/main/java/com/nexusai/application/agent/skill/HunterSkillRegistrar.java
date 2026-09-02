package com.nexusai.application.agent.skill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * bundled /hunter skill 幽灵空壳登记 · DEC-15 · 对齐 CC bundled/index.ts:41-45 的 hunter 注册块。
 *
 * <p><b>CC 源引用</b>：CC {@code src/skills/bundled/index.ts:41-45}
 * {@code if (feature('REVIEW_ARTIFACT')) { const { ... } = require('./hunter.js'); ...() }}。
 * 源文件 {@code src/skills/bundled/hunter.js} 本 checkout <b>缺失</b>（bundled 目录仅 16 个 .ts，无
 * hunter.ts，探查 §9.3 E1）。
 *
 * <p><b>CC 上游缺陷</b>：CC 用懒 {@code require('./hunter.js')}（非 ESM import），flag 开启时模块文件
 * 不存在 → CC 自身抛 {@code MODULE_NOT_FOUND}（生产 bundle flag 编译 false，DCE 掩盖此缺陷）。
 *
 * <p><b>Java 明确不注册（DEC-15）</b>：本类仅为登记该缺陷 + 保留未来移植锚点的空壳，
 * <b>不进入</b> {@link BundledSkillsBootstrapper} 注册列表（注册集保持 14 个不变，对齐 CC 生产
 * bundle 三 flag 编译 false 永不注册）。
 *
 * <p>TODO(DEC-15)：CC 源文件 {@code bundled/hunter.js} 恢复后，按源内容移植为真实 Registrar 并接入
 * {@link BundledSkillsBootstrapper}（先由主 agent 确认 feature flag 语义：Java 运行时门控 vs CC 编译期）。
 */
public final class HunterSkillRegistrar {

    private static final Logger log = LoggerFactory.getLogger(HunterSkillRegistrar.class);

    /** CC 注册技能名 · CC original: bundled/index.ts:45（hunter 注册块）。 */
    public static final String NAME = "hunter";

    /**
     * 用户面斜杠命令 · E2 证据（源模块缺失，无源码级证据）：{@code src/screens/REPL.tsx:2938}
     * 『Background tmux tasks (e.g. /hunter) run for ...』。
     */
    public static final String SLASH_COMMAND = "/hunter";

    /** CC feature gate · CC original: {@code feature('REVIEW_ARTIFACT')} (bundled/index.ts:41)。 */
    public static final List<String> FEATURE_FLAGS = List.of("REVIEW_ARTIFACT");

    /** CC 源文件 · 本 checkout 缺失（DCE 剔除），TODO 移植后指向该路径。 */
    public static final String CC_SOURCE_FILE = "bundled/hunter.js";

    private HunterSkillRegistrar() {
        // 空壳不可实例化：防止被误注册（DEC-15 测试反射锁定 final + private 构造器）。
    }

    static {
        // 数据流日志：幽灵 skill 未注册登记（类仅被引用时触发；生产注册链不引用本类 → 实际不注册）。
        log.info("[HunterSkillRegistrar] 幽灵 skill '{}'（斜杠命令 '{}'）明确不注册（DEC-15）：CC 源文件 {} 本 checkout 缺失（DCE 剔除，feature gate {} 编译 false），flag 开则 CC require 抛 MODULE_NOT_FOUND——登记 CC 上游缺陷",
            NAME, SLASH_COMMAND, CC_SOURCE_FILE, FEATURE_FLAGS);
    }
}
