package com.nexusai.application.agent.team;

import com.nexusai.application.agent.tasks.TaskSystemConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.web.context.WebApplicationContext;

import jakarta.annotation.PostConstruct;

/**
 * teammate 身份 sysprop <b>启动校验</b>（[S1-T13] 由「启动接线」改为「启动拒绝」） ·
 * 对齐 CC main.tsx CLI 参数注入 dynamicTeamContext。
 *
 * <p>CC 真源（grep 自验，不信注释）：
 * <ul>
 *   <li>CC teammate.ts:44-51 {@code dynamicTeamContext} 由 CLI 参数（{@code --agent-id}/{@code --agent-name}
 *       /{@code --team-name}）在 main.tsx:1203 由 {@code setDynamicTeamContext} 填充；</li>
 *   <li>CC 身份解析（{@code isTeammate} teammate.ts:125-131）只查 in-process（AsyncLocalStorage）
 *       与 dynamicTeamContext，<b>无 env/sysprop 逐次回退</b>——dynamicTeamContext 在启动阶段一次性
 *       填充，运行期身份判断不再重复读环境。</li>
 * </ul>
 *
 * <p><b>WHY 从「接线」改为「拒绝」（[S1-T13]）</b>：CC 的 CLI 参数形态隐含「<b>一个 teammate
 * 一个进程</b>」（tmux 为每个 teammate 起独立 CLI 进程）⇒ 进程级 dynamicTeamContext 与 teammate
 * 作用域等价。本仓是<b>单 JVM 多会话</b>的 Web 后端：同一份进程级槽会被所有会话共享，使
 * A 会话的 teammate 身份被 B 会话读到——这是本仓「会话身份被跨会话读到」的最纯形态。
 * 因此该槽整体删除（见 {@link Teammate} 类头），sysprop 也不再有任何运行期读者。
 *
 * <p>保留下来的唯一职责是<b>把「部署约定」变成「启动期硬保证」</b>：
 * 「Web 部署永不设 {@code nexusai.agent.name}/{@code nexusai.team.name}」原先是口头约定，
 * 现改为启动即校验——一旦出现即 {@link IllegalStateException} <b>fail-fast</b>
 * （否则这两个 sysprop 会被 {@link TaskSystemConfig#getTeamName()} 等读点静默当成
 * 全进程共享身份使用，制造更难查的跨会话串扰）。
 *
 * <p><b>「Web 部署」判据</b>：本仓无名为 {@code web} 的 Spring profile（部署形态仅
 * {@code java -jar nexusai-backend.jar --spring.profiles.active=prod}，见
 * {@code front/src-tauri/src/backend.rs:294}），故判据取
 * ① 「Spring 上下文本体是 {@link WebApplicationContext}」（= 这是 Web 应用）<b>或</b>
 * ② 激活 profile 含 {@code web}（若未来显式声明）。二者皆假（纯 JUnit / 非 web 装配）时不拒绝
 * ——那条路径上这两个 sysprop 是被测对象的输入，不是部署配置。
 *
 * <p><b>注册模式</b>：{@code @Component} + {@code @PostConstruct}（对齐 {@code PluginStartupAssembler}/
 * {@code SessionMemoryService} 先例）。仅依赖静态工具类 {@link TaskSystemConfig}，
 * 无 bean 依赖时序问题，故不需 {@code ApplicationRunner}。
 */
@Component
public class TeammateContextBootstrap {

    private static final Logger log = LoggerFactory.getLogger(TeammateContextBootstrap.class);

    /** Web 部署判据 ② 的 profile 名（本仓当前未声明；保留以覆盖「未来显式声明 web profile」）。 */
    static final String WEB_PROFILE = "web";

    /** Spring 上下文本体（{@code required=false}：plain JUnit 直接 new 时为 null ⇒ 不拒绝）。 */
    @Autowired(required = false)
    private ApplicationContext applicationContext;

    @Autowired(required = false)
    private Environment environment;

    /**
     * Spring 启动阶段校验 sysprop → Web 部署下出现 teammate 身份 sysprop 即拒绝启动。
     *
     * <p>agentName 或 teamName 任一非空即视为「部署侧试图注入进程级 teammate 身份」——
     * 该形态在本仓（单 JVM 多会话）下必然制造跨会话身份串扰，故 fail-fast 而不是静默忽略。
     *
     * @throws IllegalStateException Web 部署下检测到 teammate 身份 sysprop
     */
    @PostConstruct
    public void init() {
        validateIdentitySysprops(isWebDeployment());
    }

    /**
     * 校验主体 · {@code webDeployment} 由调用方显式传入（包级可见<b>测试缝</b>：用例无需构造
     * 真实 Spring Web 上下文即可锁定「Web 部署 ⇒ fail-fast」这一行为）。
     *
     * @param webDeployment 是否 Web 部署（判据见 {@link #isWebDeployment()}）
     * @throws IllegalStateException webDeployment 为真且检测到 teammate 身份 sysprop
     */
    void validateIdentitySysprops(boolean webDeployment) {
        String agentName = TaskSystemConfig.getAgentName();
        String teamName = TaskSystemConfig.getTeamName();
        boolean anyIdentitySysprop = (agentName != null && !agentName.isBlank())
                || (teamName != null && !teamName.isBlank());
        if (!anyIdentitySysprop) {
            if (log.isDebugEnabled()) {
                log.debug("[TeammateContextBootstrap] 未检测到 sysprop nexusai.agent.name/nexusai.team.name，"
                        + "跳过校验（standalone 会话，对齐 CC 无 CLI 参数注入）");
            }
            return;
        }
        if (!webDeployment) {
            // (b) 类：非 web 装配（plain JUnit / 单测）下这两个 sysprop 是被测输入而非部署配置。
            log.warn("[TeammateContextBootstrap] 检测到 teammate 身份 sysprop（agentName={} teamName={}）但"
                    + "当前不是 Web 部署装配 ⇒ 不拒绝启动；注意该身份在本仓**已无任何运行期读者**"
                    + "（进程级 dynamicTeamContext 槽已随 S1-T13 删除），不会影响身份解析",
                agentName, teamName);
            return;
        }
        // Web 部署（单 JVM 多会话）：进程级 teammate 身份槽在该形态下必然是跨会话缺陷。
        throw new IllegalStateException(
            "[S1-T13 fail-fast] Web 部署下检测到进程级 teammate 身份 sysprop（nexusai.agent.name="
                + agentName + "，nexusai.team.name=" + teamName + "）。该形态在本仓（单 JVM 多会话）"
                + "会使 A 会话的 teammate 身份被 B 会话读到；CC 的 CLI 参数形态是「一 teammate 一进程」"
                + "（tmux），本仓无对应形态 ⇒ 这两个 sysprop 已无任何运行期读者（进程级 dynamicTeamContext "
                + "槽已删除），身份一律经 ToolUseContext.teammateIdentity() 显式传参。"
                + "请从启动参数中移除 -Dnexusai.agent.name / -Dnexusai.team.name 后重启。");
    }

    /** 「Web 部署」判据 · 见类头说明（上下文是 WebApplicationContext，或激活 profile 含 web）。 */
    private boolean isWebDeployment() {
        if (applicationContext instanceof WebApplicationContext) {
            return true;
        }
        Environment env = environment;
        if (env != null) {
            for (String p : env.getActiveProfiles()) {
                if (WEB_PROFILE.equalsIgnoreCase(p)) {
                    return true;
                }
            }
        }
        return false;
    }
}
