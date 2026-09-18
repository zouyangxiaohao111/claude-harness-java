package com.nexusai.application.agent.coordinator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [coordinator 缺件补齐] nexusai.feature.coordinator-mode 配置键存在性 + 默认值 = false ·
 * 对齐 CC coordinator/coordinatorMode.ts:36-41 的 {@code feature('COORDINATOR_MODE')}。
 *
 * <p><b>WHY (意图验证，不是行为验证)</b>：生产构造器
 * {@link CoordinatorMode#CoordinatorMode(org.springframework.core.env.Environment)}
 * 读死键名 {@code "nexusai.feature.coordinator-mode"}（CoordinatorMode.java:85）。
 * 这个读取是 <b>Spring {@code Environment.getProperty} 软读取</b> —— 键不存在时返回 {@code null}，
 * {@code isTruthy(null)=false}，于是 {@link CoordinatorMode#isCoordinatorMode()} 恒早返 false，
 * <b>不抛异常、不打日志、没有任何信号</b>。
 *
 * <p>历史缺陷正是如此：代码读该键，{@code application.yml} 里根本没有这个键（已 grep 全部 yml 确认）
 * ⇒ coordinator 即使 env 设了 {@code CLAUDE_CODE_COORDINATOR_MODE=1} 也永远激活不了
 * （「代码读某键但配置里没有」这一类断头路）。本测试把「键必须在 yml 里、且在正确层级」钉死。
 *
 * <p><b>为何用 SnakeYAML 真解析而不是字符串 contains</b>：{@code coordinator-mode: false} 只要
 * 缩进错一层就会挂到别的父键下（如 {@code nexusai.coordinator-mode}），此时
 * {@code Environment.getProperty("nexusai.feature.coordinator-mode")} 依旧返回 null，而纯字符串
 * 匹配<b>照样通过</b>（假绿）。真解析才能验证 {@code nexusai → feature → coordinator-mode} 这条路径。
 *
 * <p><b>变异点</b>：① 删掉 yml 里该键 → {@link #coordinatorModeKeyExistsAtNexusaiFeaturePath()} 变红；
 * ② 改成 {@code true} → {@link #coordinatorModeDefaultsToFalse()} 变红。
 */
class CoordinatorModeConfigKeyTest {

    /** 生产构造器读死的键名（CoordinatorMode.java:85）；此处刻意重复字面量以充当独立参照。 */
    private static final String KEY_PATH = "nexusai.feature.coordinator-mode";

    /** 从 classpath 读 application.yml（对齐 TranscriptClassifierEnabledDefaultTest 惯例，避免 cwd 差异）。 */
    private static Map<String, Object> loadYaml() {
        try (InputStream in = Thread.currentThread().getContextClassLoader()
            .getResourceAsStream("application.yml")) {
            if (in == null) {
                throw new AssertionError("application.yml not found on classpath");
            }
            Map<String, Object> root = new Yaml().load(in);
            assertThat(root).as("application.yml 顶层必须是 map").isNotNull();
            return root;
        } catch (java.io.IOException e) {
            throw new AssertionError("读取 application.yml 失败", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Object readPath(Map<String, Object> root, String path) {
        Object cur = root;
        for (String seg : path.split("\\.")) {
            if (!(cur instanceof Map)) {
                return null;
            }
            cur = ((Map<String, Object>) cur).get(seg);
        }
        return cur;
    }

    @Test
    @DisplayName("application.yml 存在 nexusai.feature.coordinator-mode（防「代码读某键但配置里没有」重演）")
    void coordinatorModeKeyExistsAtNexusaiFeaturePath() {
        Map<String, Object> root = loadYaml();

        // 逐层确认父键存在，报错时能直接看出是哪一层缺
        assertThat(readPath(root, "nexusai")).as("nexusai 顶层节点").isInstanceOf(Map.class);
        assertThat(readPath(root, "nexusai.feature")).as("nexusai.feature 节点").isInstanceOf(Map.class);

        assertThat(readPath(root, KEY_PATH))
            .as("键 %s 必须存在于 application.yml —— 缺失则 isTruthy(null)=false，coordinator 恒不激活（静默）", KEY_PATH)
            .isNotNull();
    }

    @Test
    @DisplayName("coordinator-mode 默认 false（不设 true，避免所有会话一起变 coordinator）")
    void coordinatorModeDefaultsToFalse() {
        Map<String, Object> root = loadYaml();
        Object value = readPath(root, KEY_PATH);

        assertThat(value)
            .as("默认必须 false：CC 是编译期 feature，Java 用运行时开关更保守，按需激活")
            .isEqualTo(false);
    }

    @Test
    @DisplayName("默认 false 时 isCoordinatorMode() 恒 false（env 即使为真值也短路）")
    void defaultFalseShortCircuitsEvenWithTruthyEnv() {
        // 与 yml 默认值同构的运行时夹具：feature=false + env=true
        CoordinatorMode mode = new CoordinatorMode(
            () -> false,
            () -> "1");

        assertThat(mode.isCoordinatorMode())
            .as("feature 关 → 早返 false，env 通道被短路（对齐 CC coordinatorMode.ts:36-41 feature off 早返）")
            .isFalse();
    }

    @Test
    @DisplayName("回归：feature=true + env 真值时 isCoordinatorMode() = true（证明上一条不是恒假断言）")
    void trueFeatureAndTruthyEnvEnables() {
        CoordinatorMode mode = new CoordinatorMode(
            () -> true,
            () -> "1");

        assertThat(mode.isCoordinatorMode())
            .as("若本断言也 false，说明 isCoordinatorMode 整体失效，上一条的 isFalse 就不是在验证短路语义了")
            .isTrue();
    }
}
