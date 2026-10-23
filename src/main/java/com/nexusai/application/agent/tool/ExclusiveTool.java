package com.nexusai.application.agent.tool;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 专属工具标记 · 本仓扩展（CC 无对应概念）。
 *
 * <p><b>语义</b>：被标注的工具<b>不进入默认池</b>（没有绑定主线程 agent 的会话），
 * 也<b>不进入通配池</b>（{@code AgentDefinition.tools} 缺省或为 {@code ['*']}）。
 * 只有被 {@code AgentDefinition.tools} <b>逐名点名</b>时才可见。
 *
 * <pre>
 * 场景                                  | 可见
 * 没有绑定 main_thread_agent 的会话       | ✗
 * 绑定 agent，但 tools 缺省 / ['*']       | ✗
 * 绑定 agent，tools 显式列出该工具名       | ✓
 * </pre>
 *
 * <p><b>注册不受影响</b>：{@code @Component} 工具仍由 {@link ToolRegistry} 全局注册，
 * 本标记只在每轮装配工具池时参与可见性过滤（见 {@link AgentToolPolicy}）。
 *
 * <p><b>为什么用注解而不是 {@link Tool} 接口方法</b>：{@code Tool} 接口逐条对齐 CC
 * {@code Tool.ts}；CC 没有「专属工具」概念，往接口里加方法会让后续 CC 对齐审计无法区分
 * 「CC 有」与「本仓扩展」。注解是可移除的旁路标记。
 *
 * <p><b>{@code @Inherited}</b>：Spring 若对工具 bean 做 CGLIB 代理，代理类是目标类的子类；
 * 继承标记保证代理类也能被识别。
 */
@Inherited
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ExclusiveTool {
}
