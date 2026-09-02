package com.nexusai.application.agent.permission;

import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [IMPL-09 r2 + S10] ClassifierApprovals 生产 feature 门测试 · 对齐 CC
 * {@code utils/classifierApprovals.ts:19-30/32-39/45-59/62-72}.
 *
 * <p><b>WHY (OD-SS-04 + 反思 R1)</b>: 生产调用点传 null feature 门 → 门恒开
 * （EV-SS-006 缺陷）；r1 实现已接入静态 {@code bashClassifierGate}
 * （{@link BashClassifierFeature#wireClassifierApprovalsGate()} @PostConstruct 注入），
 * 但验收 §5-5「feature 关闭 → no-op」无测试承载。本测试断言:
 * <ol>
 *   <li>静态门关闭（gate.test=false）→ set/get/checking/clearChecking 全 no-op
 *       （CC :23-25/:33-35/:63/:69 语义）</li>
 *   <li>静态门未接线（null）→ 门开（生产注入前/测试手动构造场景）</li>
 *   <li>显式传参优先于静态门（Java 以参注入建模 CC 函数内部 feature 调用）</li>
 *   <li>delete/clear/isChecking 不受 feature 门控（CC :76-88 无门）</li>
 *   <li><b>[S10] checking 门 = BASH_CLASSIFIER OR TRANSCRIPT_CLASSIFIER</b>
 *       （CC :63/:69 {@code if (!feature('BASH_CLASSIFIER') && !feature('TRANSCRIPT_CLASSIFIER')) return}
 *       —— 任一 feature 开启即写入）；Yolo/auto-mode 门 = 显式参优先 + 静态
 *       TRANSCRIPT_CLASSIFIER 门回退（S12 确认 AutoModeGate 为 Java 等价门，
 *       PermissionPipeline @PostConstruct 接线）</li>
 * </ol>
 *
 * <p>静态状态: 每用例前后清理（{@link ClassifierApprovals#clearClassifierApprovals()}
 * + 复位静态门为 null）, 防跨用例/跨测试类污染。
 */
@DisplayName("[IMPL-09 r2 + S10] ClassifierApprovals 生产 feature 门（OD-SS-04）")
class ClassifierApprovalsFeatureGateTest {

    private static final Predicate<Void> GATE_ON = v -> true;
    private static final Predicate<Void> GATE_OFF = v -> false;

    @BeforeEach
    @AfterEach
    void resetStaticState() {
        ClassifierApprovals.wireBashClassifierGate(null);
        ClassifierApprovals.wireTranscriptClassifierGate(null);
        ClassifierApprovals.clearClassifierApprovals();
    }

    @Test
    @DisplayName("feature 关闭（双静态门 false）→ setClassifierApproval no-op、get 返回 null、checking 不写入（CC :23-25/:33-35/:62-64）")
    void featureOff_setAndGetAreNoOp() {
        ClassifierApprovals.wireBashClassifierGate(GATE_OFF);
        ClassifierApprovals.wireTranscriptClassifierGate(GATE_OFF);

        ClassifierApprovals.setClassifierApproval("call-1", "Bash(git status:*)", null);
        ClassifierApprovals.setClassifierChecking("call-1", null);

        assertThat(ClassifierApprovals.getClassifierApproval("call-1", null))
            .as("CC classifierApprovals.ts:33-35: feature 关 → get 恒 undefined")
            .isNull();
        assertThat(ClassifierApprovals.isClassifierChecking("call-1"))
            .as("CC :62-64: 双 feature 关 → checking set 不写入")
            .isFalse();
    }

    @Test
    @DisplayName("[S10] checking 门 OR 语义：bash 关 + transcript 开 → checking 写入（CC :63 任一 feature 开启即可）")
    void transcriptOn_bashOff_checkingWrites() {
        ClassifierApprovals.wireBashClassifierGate(GATE_OFF);
        ClassifierApprovals.wireTranscriptClassifierGate(GATE_ON);

        ClassifierApprovals.setClassifierChecking("call-t1", null);

        assertThat(ClassifierApprovals.isClassifierChecking("call-t1"))
            .as("CC :63-64: feature('TRANSCRIPT_CLASSIFIER') 开 → checking 写入")
            .isTrue();
    }

    @Test
    @DisplayName("[S10] checking 门 OR 语义：bash 开 + transcript 关 → checking 写入（CC :63）")
    void bashOn_transcriptOff_checkingWrites() {
        ClassifierApprovals.wireBashClassifierGate(GATE_ON);
        ClassifierApprovals.wireTranscriptClassifierGate(GATE_OFF);

        ClassifierApprovals.setClassifierChecking("call-t2", null);

        assertThat(ClassifierApprovals.isClassifierChecking("call-t2"))
            .as("CC :63-64: feature('BASH_CLASSIFIER') 开 → checking 写入")
            .isTrue();
    }

    @Test
    @DisplayName("feature 关闭时已存在记录也不可读（CC :33-35 读取侧门控）")
    void featureOff_existingRecordUnreadable() {
        ClassifierApprovals.setClassifierApproval("call-2", "Bash(git status:*)", GATE_ON);

        ClassifierApprovals.wireBashClassifierGate(GATE_OFF);

        assertThat(ClassifierApprovals.getClassifierApproval("call-2", null))
            .as("get 侧 feature 门优先于存储内容（CC :33-35）")
            .isNull();
    }

    @Test
    @DisplayName("feature 关闭（双门 false）→ clearClassifierChecking no-op（CC :68-72）")
    void featureOff_clearCheckingNoOp() {
        ClassifierApprovals.setClassifierChecking("call-3", GATE_ON);
        assertThat(ClassifierApprovals.isClassifierChecking("call-3")).isTrue();

        ClassifierApprovals.wireBashClassifierGate(GATE_OFF);
        ClassifierApprovals.wireTranscriptClassifierGate(GATE_OFF);
        ClassifierApprovals.clearClassifierChecking("call-3", null);

        assertThat(ClassifierApprovals.isClassifierChecking("call-3"))
            .as("CC :68-70: 双 feature 关 → clear 不生效（set 侧同门, 写入本就不可能发生）")
            .isTrue();
    }

    @Test
    @DisplayName("静态门未接线（null）→ 门开（生产注入前/测试手动构造场景）")
    void gateNull_isOpen() {
        ClassifierApprovals.setClassifierApproval("call-4", "rule-x", null);
        ClassifierApprovals.setClassifierChecking("call-4", null);

        assertThat(ClassifierApprovals.getClassifierApproval("call-4", null)).isEqualTo("rule-x");
        assertThat(ClassifierApprovals.isClassifierChecking("call-4")).isTrue();
    }

    @Test
    @DisplayName("显式传参优先于静态门（正向: 静态关 + 显式开 → 写入且可读）")
    void explicitParamOverridesStaticGate_forward() {
        ClassifierApprovals.wireBashClassifierGate(GATE_OFF);

        ClassifierApprovals.setClassifierApproval("call-5", "rule-y", GATE_ON);

        assertThat(ClassifierApprovals.getClassifierApproval("call-5", GATE_ON))
            .as("显式 gate=true 优先于静态关")
            .isEqualTo("rule-y");
        assertThat(ClassifierApprovals.getClassifierApproval("call-5", null))
            .as("静态门仍关 → null 参读取 no-op")
            .isNull();
    }

    @Test
    @DisplayName("显式传参优先于静态门（反向: 静态开 + 显式关 → no-op）")
    void explicitParamOverridesStaticGate_reverse() {
        ClassifierApprovals.wireBashClassifierGate(GATE_ON);

        ClassifierApprovals.setClassifierApproval("call-6", "rule-z", GATE_OFF);

        assertThat(ClassifierApprovals.getClassifierApproval("call-6", GATE_ON))
            .as("显式 gate=false → 未写入")
            .isNull();
    }

    @Test
    @DisplayName("delete/clear/isChecking 不受 feature 门控（CC :76-88 无门）")
    void deleteAndClearUngated() {
        ClassifierApprovals.setClassifierApproval("call-7", "rule-w", GATE_ON);
        ClassifierApprovals.setClassifierChecking("call-7", GATE_ON);

        ClassifierApprovals.deleteClassifierApproval("call-7");
        ClassifierApprovals.clearClassifierChecking("call-7", GATE_ON);

        assertThat(ClassifierApprovals.getClassifierApproval("call-7", GATE_ON)).isNull();
        assertThat(ClassifierApprovals.isClassifierChecking("call-7")).isFalse();

        ClassifierApprovals.setClassifierApproval("call-8", "rule-v", GATE_ON);
        ClassifierApprovals.clearClassifierApprovals();
        assertThat(ClassifierApprovals.getClassifierApproval("call-8", GATE_ON)).isNull();
    }

    @Test
    @DisplayName("Yolo/auto-mode 门：显式参优先（CC :45-49/:54-59）")
    void yoloParamGated() {
        ClassifierApprovals.setYoloClassifierApproval("call-y1", "plan approved", GATE_ON);
        assertThat(ClassifierApprovals.getYoloClassifierApproval("call-y1", GATE_ON))
            .as("显式 gate=true → auto-mode 记录可写可读")
            .isEqualTo("plan approved");

        ClassifierApprovals.setYoloClassifierApproval("call-y2", "should not write", GATE_OFF);
        assertThat(ClassifierApprovals.getYoloClassifierApproval("call-y2", GATE_ON))
            .as("显式 gate=false → no-op")
            .isNull();

        ClassifierApprovals.setYoloClassifierApproval("call-y3", "null param = open", null);
        assertThat(ClassifierApprovals.getYoloClassifierApproval("call-y3", null))
            .as("双静态门未接线 → null 参门开（测试/手动构造场景）")
            .isEqualTo("null param = open");
    }

    @Test
    @DisplayName("[S10] Yolo/auto-mode 门：null 参回退静态 TRANSCRIPT_CLASSIFIER 门（CC :45/:54）")
    void yoloStaticTranscriptGate() {
        ClassifierApprovals.wireTranscriptClassifierGate(GATE_OFF);

        ClassifierApprovals.setYoloClassifierApproval("call-y4", "should not write", null);
        assertThat(ClassifierApprovals.getYoloClassifierApproval("call-y4", null))
            .as("静态 transcript 门关 → null 参 no-op（CC :45-49/:54-59）")
            .isNull();

        ClassifierApprovals.wireTranscriptClassifierGate(GATE_ON);
        ClassifierApprovals.setYoloClassifierApproval("call-y5", "static gate on", null);
        assertThat(ClassifierApprovals.getYoloClassifierApproval("call-y5", null))
            .as("静态 transcript 门开 → null 参可写可读")
            .isEqualTo("static gate on");
    }
}
