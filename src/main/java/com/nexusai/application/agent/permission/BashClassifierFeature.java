package com.nexusai.application.agent.permission;

import java.util.function.Predicate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * [canUseTool v2] BASH_CLASSIFIER 特性开关 · 对齐 CC {@code feature('BASH_CLASSIFIER')}.
 *
 * <p>CC 的 {@code feature('BASH_CLASSIFIER')} 控制 bash 权限分类器（投机竞速 + async
 * classifier 检查 + PendingClassifierCheck 挂载）。Java 端用 {@code nexusai.feature.bash-classifier}
 * 系统属性/环境变量建模（truthy = 启用，缺省 false，与 SupportedSettingsConfig 的
 * {@code nexusai.feature.*} 约定一致）。
 *
 * @see com.nexusai.application.agent.tool.impl.BashTool
 * @see ToolPermissionGate
 * @since canUseTool v2 修复
 */
@Component
public class BashClassifierFeature {

    private static final String BASH_CLASSIFIER_PROPERTY = "nexusai.feature.bash-classifier";

    private final Predicate<Void> enabled;

    /**
     * [H9 v3 Gap③] Spring 生产构造器 · 标注 {@code @Autowired} 让 Spring 选中本构造器读取
     * {@code nexusai.feature.bash-classifier}。
     *
     * <p><b>WHY (H9 v3 对抗核验缺口③)</b>: 本类此前有 2 个构造器且均未标注 {@code @Autowired}，
     * Spring 多构造器场景默认选 no-arg → {@code env=null} → {@code isEnabled()} 恒 false，
     * 导致 {@code nexusai.feature.bash-classifier=true} 配置在生产永不生效 — BashTool /
     * CoordinatorPermissionHandler / SwarmWorkerPermissionHandler 的 classifier 路径全部
     * 走"未启用"分支。标注 {@code @Autowired} 后 Spring 注入真实 {@link Environment}，
     * 让配置驱动的 BASH_CLASSIFIER 特性生产可达 (对齐 CC feature('BASH_CLASSIFIER'))。
     *
     * @param env Spring Environment (必填; 测试可手动传 mock)
     */
    @Autowired
    public BashClassifierFeature(Environment env) {
        this.enabled = env != null
            ? v -> isTruthy(env.getProperty(BASH_CLASSIFIER_PROPERTY))
            : v -> false;
    }

    /** 缺省构造 · 无 Spring 环境时恒 false（测试 / 手动 new 场景）。 */
    public BashClassifierFeature() {
        this(null);
    }

    /** CC feature('BASH_CLASSIFIER') 等价. */
    public boolean isEnabled() {
        return enabled.test(null);
    }

    /** 以 Predicate 形态暴露（注入式兼容 CoordinatorPermissionHandler 等）。 */
    public Predicate<Void> asPredicate() {
        return v -> enabled.test(null);
    }

    /**
     * [IMPL-09] OD-SS-04: 生产接线 ClassifierApprovals 的 BASH_CLASSIFIER 静态门。
     *
     * <p>WHY: ClassifierApprovals 生产调用点（ToolPermissionGate /
     * WebSocketPermissionPrompter / StreamingToolExecutor）传 null feature 门 → 门恒开
     * （EV-SS-006）。CC feature('BASH_CLASSIFIER') 关闭时 set/get/checking 全 no-op
     * （classifierApprovals.ts:19-30/62-72）。本 @PostConstruct 把真实 feature 谓词
     * 注入静态门，Spring 启动即生效；测试无 Spring 上下文时不接线（门开，向后兼容）。
     */
    @jakarta.annotation.PostConstruct
    void wireClassifierApprovalsGate() {
        ClassifierApprovals.wireBashClassifierGate(asPredicate());
    }

    private static boolean isTruthy(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String lower = value.trim().toLowerCase();
        return "true".equals(lower) || "1".equals(lower)
            || "yes".equals(lower) || "on".equals(lower);
    }
}
