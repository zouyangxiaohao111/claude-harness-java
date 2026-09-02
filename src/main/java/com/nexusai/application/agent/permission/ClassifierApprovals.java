package com.nexusai.application.agent.permission;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * ClassifierApprovals · 对齐 CC utils/classifierApprovals.ts.
 *
 * <p>L1 语义: classifier auto-approval tracking — bash classifier + auto-mode classifier
 * 各自的 tool use ID 关联 + checking set 通知。
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: 8 method + 2 record (ClassifierApproval) + 2 注入式 featureGate Predicates</li>
 *   <li><b>A2 Golden Trace</b>: setBash→getBash returns matchedRule;setYolo→getYolo returns reason;isChecking tracks IDs</li>
 *   <li><b>A3 内部 mutable</b>: HashMap + HashSet;并发不安全 (CC 同样)</li>
 *   <li><b>A4 边界</b>: feature gate off→no-op;null toolUseID 跳过</li>
 *   <li><b>A5 业务场景</b>: UserToolSuccessMessage 从 approval store 查 matched rule 显示</li>
 * </ul>
 *
 * <p>L3 升级: TS class mutable Map → Java HashMap;
 * TS function feature('BASH_CLASSIFIER') → Java injected Predicate;
 * TS signal subscriber → Java Consumer listener list.
 *
 * <p>[IMPL-09] OD-SS-04: 生产调用点传 null feature 门 → 门恒开（EV-SS-006 缺陷）。
 * 修复: 新增静态 {@link #wireBashClassifierGate}，由 {@link BashClassifierFeature}
 * {@code @PostConstruct} 注入生产 gate（读 {@code nexusai.feature.bash-classifier}），
 * 显式传参优先、null 参回退静态 gate — 与 CC feature('BASH_CLASSIFIER') 关闭时
 * set/get/checking 全 no-op 对齐。同时删除 Java 独有订阅机制
 * （subscribeClassifierChecking/notifySubscribers，DEL-SS-02：CC subscribe 消费者=UI，
 * Java 无 UI 绑定）+ 内嵌 ArrayList 子类（⊕-SS-08）。
 */
public final class ClassifierApprovals {

    public record ClassifierApproval(
        String classifier,  // "bash" | "auto-mode"
        String matchedRule,
        String reason) {}

    private static final Map<String, ClassifierApproval> APPROVALS = new HashMap<>();
    private static final Set<String> CHECKING = new HashSet<>();

    /**
     * 生产 feature gate（BASH_CLASSIFIER）· 对齐 CC feature('BASH_CLASSIFIER')
     * (classifierApprovals.ts:23/33/63)。null = 未接线（测试/手动构造）→ 门开。
     * 由 {@link BashClassifierFeature#wireClassifierApprovalsGate()} 生产注入。
     */
    private static volatile Predicate<Void> bashClassifierGate;

    /**
     * 生产 feature gate（TRANSCRIPT_CLASSIFIER）· 对齐 CC feature('TRANSCRIPT_CLASSIFIER')
     * (classifierApprovals.ts:45/54/63-69)。null = 未接线（测试/手动构造）→ 门开。
     *
     * <p>[S10] 由 {@link PermissionPipeline} 的 @PostConstruct 注入 AutoModeGate::isEnabled
     * （nexusai.auto-mode.enabled —— S12 确认的 feature('TRANSCRIPT_CLASSIFIER') Java 门）。
     */
    private static volatile Predicate<Void> transcriptClassifierGate;

    private ClassifierApprovals() {}

    /**
     * 注入生产 feature gate · 由 {@link BashClassifierFeature} @PostConstruct 调用。
     *
     * @param gate BASH_CLASSIFIER 等价谓词（null = 门开，向后兼容）
     */
    public static void wireBashClassifierGate(Predicate<Void> gate) {
        bashClassifierGate = gate;
    }

    /**
     * 注入生产 feature gate（TRANSCRIPT_CLASSIFIER）· [S10] 由 PermissionPipeline
     * @PostConstruct 调用（AutoModeGate::isEnabled）。
     *
     * @param gate TRANSCRIPT_CLASSIFIER 等价谓词（null = 门开，向后兼容）
     */
    public static void wireTranscriptClassifierGate(Predicate<Void> gate) {
        transcriptClassifierGate = gate;
    }

    /**
     * feature 门判定 · 显式传参优先（测试），null 参回退静态生产 gate。
     *
     * @param param 调用方显式 gate（可为 null）
     * @return true = feature 启用（允许写入/读取）
     */
    private static boolean bashClassifierEnabled(Predicate<Void> param) {
        Predicate<Void> gate = param != null ? param : bashClassifierGate;
        return gate == null || gate.test(null);
    }

    /**
     * TRANSCRIPT_CLASSIFIER 门判定 · 显式传参优先，null 参回退静态生产 gate。
     *
     * <p>[S10] 补 CC feature('TRANSCRIPT_CLASSIFIER') 的 Java 等价门（S12 确认
     * AutoModeGate.isEnabled 为等价载体）；null = 门开（测试/未接线）。
     */
    private static boolean transcriptClassifierEnabled(Predicate<Void> param) {
        Predicate<Void> gate = param != null ? param : transcriptClassifierGate;
        return gate == null || gate.test(null);
    }

    /**
     * checking 指示器门 · 对齐 CC classifierApprovals.ts:63/69
     * {@code if (!feature('BASH_CLASSIFIER') && !feature('TRANSCRIPT_CLASSIFIER')) return}
     * —— 任一 feature 开启即允许写入（no-op 仅当两个 feature 全关）。
     *
     * <p>[S10] 旧实现只判 BASH_CLASSIFIER（"Java 无 TRANSCRIPT_CLASSIFIER 等价 feature"
     * 的历史注释已过时 —— S12 已确认 AutoModeGate 为等价门）。
     *
     * @param param 调用方显式 any-classifier gate（可为 null）
     * @return true = 至少一个 classifier feature 启用
     */
    private static boolean anyClassifierEnabled(Predicate<Void> param) {
        return bashClassifierEnabled(param) || transcriptClassifierEnabled(param);
    }

    /**
     * 登记 bash classifier 放行 · 对齐 CC setClassifierApproval
     * (classifierApprovals.ts:19-30)。
     *
     * <p>[WF2-R2] 死代码登记（保留定义，非删除）：CC 定义并在三处调用本 setter ——
     * useCanUseTool.tsx:141 / interactiveHandler.ts:485 / PermissionContext.ts:199。
     * 三处调用全部门控于 {@code pendingClassifierCheck}（BASH_CLASSIFIER 投机产物），
     * 而 CC stub bashClassifier.ts:24-26 {@code isClassifierPermissionsEnabled()} 恒
     * false → 投机永不触发 → pendingClassifierCheck 恒 undefined → 本 setter 在 stub
     * 构建下无生产调用。按删除铁律（CC 有对应能力 → 完整对齐而非删除），保留定义。
     *
     * <p>TODO(permissions): 待 BASH_CLASSIFIER 投机（非 stub）接线后，在 useCanUseTool
     * 等价路径补 setClassifierApproval 生产调用，届时移除本 TODO 注释。
     */
    public static void setClassifierApproval(
        String toolUseID,
        String matchedRule,
        Predicate<Void> bashClassifierEnabled) {
        if (toolUseID == null) return;
        if (!bashClassifierEnabled(bashClassifierEnabled)) return;
        APPROVALS.put(toolUseID, new ClassifierApproval("bash", matchedRule, null));
    }

    public static String getClassifierApproval(
        String toolUseID,
        Predicate<Void> bashClassifierEnabled) {
        if (toolUseID == null) return null;
        if (!bashClassifierEnabled(bashClassifierEnabled)) return null;
        ClassifierApproval a = APPROVALS.get(toolUseID);
        if (a == null || !"bash".equals(a.classifier())) return null;
        return a.matchedRule();
    }

    public static void setYoloClassifierApproval(
        String toolUseID,
        String reason,
        Predicate<Void> transcriptClassifierEnabled) {
        if (toolUseID == null) return;
        // [S10] 显式传参优先，null 参回退静态 TRANSCRIPT_CLASSIFIER 门（CC :45-49 门控）
        if (!transcriptClassifierEnabled(transcriptClassifierEnabled)) return;
        APPROVALS.put(toolUseID, new ClassifierApproval("auto-mode", null, reason));
    }

    public static String getYoloClassifierApproval(
        String toolUseID,
        Predicate<Void> transcriptClassifierEnabled) {
        if (toolUseID == null) return null;
        // [S10] 显式传参优先，null 参回退静态门（CC :54-59 读取侧门控）
        if (!transcriptClassifierEnabled(transcriptClassifierEnabled)) return null;
        ClassifierApproval a = APPROVALS.get(toolUseID);
        if (a == null || !"auto-mode".equals(a.classifier())) return null;
        return a.reason();
    }

    public static void setClassifierChecking(String toolUseID, Predicate<Void> anyClassifierEnabled) {
        if (toolUseID == null) return;
        // [S10] CC classifierApprovals.ts:62-66 —— 任一 classifier feature 开启即写入
        //   （旧实现只判 BASH_CLASSIFIER；TRANSCRIPT_CLASSIFIER 等价门 S12 已确认）
        if (!anyClassifierEnabled(anyClassifierEnabled)) return;
        CHECKING.add(toolUseID);
    }

    public static void clearClassifierChecking(String toolUseID, Predicate<Void> anyClassifierEnabled) {
        if (toolUseID == null) return;
        // [S10] CC classifierApprovals.ts:68-72 —— 与 set 同门（任一 feature 开启即生效）
        if (!anyClassifierEnabled(anyClassifierEnabled)) return;
        CHECKING.remove(toolUseID);
    }


    public static boolean isClassifierChecking(String toolUseID) {
        return toolUseID != null && CHECKING.contains(toolUseID);
    }

    public static void deleteClassifierApproval(String toolUseID) {
        APPROVALS.remove(toolUseID);
    }

    public static void clearClassifierApprovals() {
        APPROVALS.clear();
        CHECKING.clear();
    }
}
