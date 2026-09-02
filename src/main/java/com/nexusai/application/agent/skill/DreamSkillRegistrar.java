package com.nexusai.application.agent.skill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * bundled /dream skill 幽灵空壳登记 · DEC-15 · 对齐 CC bundled/index.ts:35-40 的 dream 注册块。
 *
 * <p><b>CC 源引用</b>：CC {@code src/skills/bundled/index.ts:35-40}
 * {@code if (feature('KAIROS') || feature('KAIROS_DREAM')) { const { ... } = require('./dream.js'); ...() }}。
 * 源文件 {@code src/skills/bundled/dream.js} 本 checkout <b>缺失</b>（bundled 目录仅 16 个 .ts，无
 * dream.ts，探查 §9.3 E1）；{@code services/autoDream/consolidationPrompt.ts:1-2} 注释
 * 『Extracted from dream.ts so auto-dream ships independently of KAIROS feature flags (dream.ts is
 * behind a feature()-gated require)』佐证 dream.ts 内容已被 DCE 抽离至 auto-dream 服务。
 *
 * <p><b>CC 上游缺陷</b>：CC 用懒 {@code require('./dream.js')}（非 ESM import），flag 开启时模块文件
 * 不存在 → CC 自身抛 {@code MODULE_NOT_FOUND}（生产 bundle 三 flag 编译 false，DCE 掩盖此缺陷）。
 *
 * <p><b>Java 明确不注册（DEC-15）</b>：本类仅为登记该缺陷 + 保留未来移植锚点的空壳，
 * <b>不进入</b> {@link BundledSkillsBootstrapper} 注册列表（注册集保持 14 个不变，对齐 CC 生产
 * bundle 三 flag 编译 false 永不注册）。
 *
 * <p><b>与 auto-dream 系统区别</b>：本类名与 memory 包 {@code AutoDreamConsolidator}
 * （对齐 CC services/autoDream 记忆巩固系统）同词根但<b>互不相关</b>——本类是 bundled /dream
 * skill 占位，非 auto-dream 系统，DEC-15 不动后者。
 *
 * <p>TODO(DEC-15)：CC 源文件 {@code bundled/dream.js} 恢复后，按源内容移植为真实 Registrar 并接入
 * {@link BundledSkillsBootstrapper}（先由主 agent 确认 feature flag 语义：Java 运行时门控 vs CC 编译期）。
 */
public final class DreamSkillRegistrar {

    private static final Logger log = LoggerFactory.getLogger(DreamSkillRegistrar.class);

    /** CC 注册技能名 · CC original: bundled/index.ts:39（dream 注册块）。 */
    public static final String NAME = "dream";

    /**
     * 用户面斜杠命令 · E2 证据（源模块缺失，无源码级证据）：{@code src/components/memory/
     * MemoryFileSelector.tsx:344} 『/dream to run』+ {@code src/memdir/paths.ts:243}
     * nightly /dream skill。
     */
    public static final String SLASH_COMMAND = "/dream";

    /** CC feature gate · CC original: {@code feature('KAIROS') || feature('KAIROS_DREAM')}
     * (bundled/index.ts:35)。 */
    public static final List<String> FEATURE_FLAGS = List.of("KAIROS", "KAIROS_DREAM");

    /** CC 源文件 · 本 checkout 缺失（DCE 剔除），TODO 移植后指向该路径。 */
    public static final String CC_SOURCE_FILE = "bundled/dream.js";

    private DreamSkillRegistrar() {
        // 空壳不可实例化：防止被误注册（DEC-15 测试反射锁定 final + private 构造器）。
    }

    static {
        // 数据流日志：幽灵 skill 未注册登记（类仅被引用时触发；生产注册链不引用本类 → 实际不注册）。
        log.info("[DreamSkillRegistrar] 幽灵 skill '{}'（斜杠命令 '{}'）明确不注册（DEC-15）：CC 源文件 {} 本 checkout 缺失（DCE 剔除，feature gate {} 编译 false），flag 开则 CC require 抛 MODULE_NOT_FOUND——登记 CC 上游缺陷",
            NAME, SLASH_COMMAND, CC_SOURCE_FILE, FEATURE_FLAGS);
    }
}
