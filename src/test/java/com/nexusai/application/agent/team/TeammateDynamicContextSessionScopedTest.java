package com.nexusai.application.agent.team;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [S1-T13] {@code Teammate} 必须**零进程级身份状态**：身份一律经显式形参承载。
 *
 * <p><b>WHY（规则九 · 意图）</b>：CC 的模块级 {@code dynamicTeamContext}（teammate.ts:44-51）只由
 * CLI 参数 {@code --agent-id/--agent-name/--team-name} 在 main.tsx:1203 填充，而 CLI 形态的隐含
 * 前提是「<b>一个 teammate 一个进程</b>」（tmux 为每个 teammate 起独立 CLI 进程）⇒ 进程级槽
 * 与 teammate 作用域等价，不会串。本仓是**单 JVM 多会话**的 Web 后端：同一个 static volatile 槽
 * 会被所有会话共享——A 会话的身份被 B 会话读到，是本仓「会话身份跨会话泄漏」的最纯形态
 * （一次 JVM 启动后永不清理、无会话分桶）。
 *
 * <p>因此该槽被整体删除，本测试从两个方向守护：
 * <ol>
 *   <li><b>结构方向（反射）</b>：{@code Teammate} 上不存在任何「非 final 的 static 字段」，
 *       也不存在任何 {@code *DynamicTeamContext} 方法——把这个类的「可变进程级状态」挡在类型层；</li>
 *   <li><b>行为方向（两会话并发）</b>：会话 A 传 identity X、会话 B 传 null，
 *       两线程交错反复取值 ⇒ A 恒得 X 的 agentId、B 恒得 null（**不得**读到 A 的值）。</li>
 * </ol>
 *
 * <p><b>RED teeth</b>：重新加回一个 {@code private static volatile} 字段并在
 * {@code getAgentId} 内写入/回读 ⇒ 方向 1 与方向 2 同时红。
 */
class TeammateDynamicContextSessionScopedTest {

    private static final String AGENT_ID_A = "researcher@team-a";

    private static TeammateIdentity identityA() {
        return new TeammateIdentity(AGENT_ID_A, "researcher", "team-a", "#ff0000", false, "leader-a");
    }

    // ════════════════════════════════════════════════════════════════════════
    // 1. 结构：零可变进程级状态
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("断言1: Teammate 上不存在非 final 的 static 字段（零进程级身份槽）")
    void noMutableStaticStateExists() {
        List<String> mutableStatic = new ArrayList<>();
        for (Field f : Teammate.class.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) && !Modifier.isFinal(f.getModifiers())) {
                mutableStatic.add(f.getName());
            }
        }
        assertThat(mutableStatic)
            .as("Teammate 不得有可变 static 字段——身份槽一旦以进程级形态复活，"
                + "单 JVM 多会话下就会把 A 会话身份带给 B 会话")
            .isEmpty();
    }

    @Test
    @DisplayName("断言1b: dynamicTeamContext 的 set/clear/get 三方法与 record 类型均不存在")
    void dynamicTeamContextApiIsGone() {
        List<String> leftover = new ArrayList<>();
        for (Method m : Teammate.class.getDeclaredMethods()) {
            if (m.getName().toLowerCase().contains("dynamicteamcontext")) {
                leftover.add(m.getName());
            }
        }
        assertThat(leftover).as("dynamicTeamContext 相关方法必须全部删除").isEmpty();

        List<String> nestedTypes = new ArrayList<>();
        for (Class<?> c : Teammate.class.getDeclaredClasses()) {
            if (c.getSimpleName().toLowerCase().contains("dynamicteamcontext")) {
                nestedTypes.add(c.getSimpleName());
            }
        }
        assertThat(nestedTypes).as("DynamicTeamContext record 必须删除（无生产消费者）").isEmpty();
    }

    // ════════════════════════════════════════════════════════════════════════
    // 2. 行为：两会话并发交错，互不串身份
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("断言2: 两会话并发交错 —— 会话A 恒得自己的 agentId、会话B 恒 null（不读到 A 的值）")
    void twoSessionsInterleaved_noCrossTalk() throws Exception {
        int rounds = 200;
        AtomicReference<String> observedA = new AtomicReference<>();
        AtomicReference<String> observedB = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch start = new CountDownLatch(1);

        Thread sessionA = new Thread(() -> {
            try {
                start.await();
                for (int i = 0; i < rounds; i++) {
                    String got = Teammate.getAgentId(identityA());
                    if (!AGENT_ID_A.equals(got)) {
                        observedA.set(got);
                        return;
                    }
                }
                observedA.set(AGENT_ID_A);
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            }
        }, "s1-t13-session-a");

        Thread sessionB = new Thread(() -> {
            try {
                start.await();
                for (int i = 0; i < rounds; i++) {
                    // 会话 B 没有 teammate 身份（主会话/普通会话）⇒ 必须恒 null。
                    // 若实现重新引入任何进程级身份槽并被会话 A 的线程写入，本断言即红。
                    String got = Teammate.getAgentId(null);
                    if (got != null) {
                        observedB.set(got);
                        return;
                    }
                }
                observedB.set(null);
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            }
        }, "s1-t13-session-b");

        sessionA.start();
        sessionB.start();
        start.countDown();
        sessionA.join(TimeUnit.SECONDS.toMillis(10));
        sessionB.join(TimeUnit.SECONDS.toMillis(10));

        assertThat(failure.get()).as("并发夹具内不得抛异常").isNull();
        assertThat(observedA.get()).as("会话 A 恒读到自己的 agentId").isEqualTo(AGENT_ID_A);
        assertThat(observedB.get())
            .as("⭐ 会话 B（identity=null）恒为 null —— 绝不读到 A 的身份（进程级槽已删）")
            .isNull();
        assertThat(Teammate.getAgentId(null)).isNull();
        assertThat(Teammate.isTeammate(null)).isFalse();
    }
}
