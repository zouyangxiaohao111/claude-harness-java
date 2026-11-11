package com.nexusai.kb.agent;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 知识库内置 Agent 的启动自检触发器。
 *
 * <h2>为什么单独一个 bean，而不是把 {@code @PostConstruct} 放进 {@link KbBuiltInAgentDefs}</h2>
 * {@code KbBuiltInAgentDefs} 是<b>静态工具类</b>（私有构造、全静态方法），
 * 它不被 Spring 管理 —— 在那上面加 {@code @PostConstruct} 不会被执行，且<b>不报错</b>
 * （这类「注解挂在不被扫描的类上」是最典型的静默失效）。
 *
 * <h2>失败即启动失败</h2>
 * 人格 md 缺失 / 为空 ⇒ {@link KbBuiltInAgentDefs#selfCheckOnStartup()} 抛异常 ⇒
 * Spring 上下文启动失败。这是<b>刻意的</b>：空 system prompt 会让模型自由发挥且零报错，
 * 把问题暴露在启动期远比暴露在用户对话里好。
 *
 * <p>⚠️ 若需临时绕过（如本机只做前端联调），把 {@code nexusai.kb.agent.self-check-on-startup}
 * 设为 {@code false}。
 */
@Component
public class KbBuiltInAgentStartupCheck {

    private static final Logger log = LoggerFactory.getLogger(KbBuiltInAgentStartupCheck.class);

    private final boolean enabled;

    /**
     * @param properties Spring 环境配置
     */
    public KbBuiltInAgentStartupCheck(org.springframework.core.env.Environment properties) {
        this.enabled = properties.getProperty(
            "nexusai.kb.agent.self-check-on-startup", Boolean.class, Boolean.TRUE);
    }

    @PostConstruct
    public void check() {
        if (!enabled) {
            log.warn("[KB内置Agent] 启动自检被配置关闭（nexusai.kb.agent.self-check-on-startup=false）"
                + " —— 人格 md 缺失将不会在启动期暴露");
            return;
        }
        KbBuiltInAgentDefs.selfCheckOnStartup();
    }
}
