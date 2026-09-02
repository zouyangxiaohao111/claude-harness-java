package com.nexusai.application.agent.permission.classifier;

import com.nexusai.infra.util.BashClassifierPermission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * {@link BashCommandClassifier} 缺省实现 · 对齐 CC {@code classifyBashCommand} 外部 stub
 * (utils/permissions/bashClassifier.ts:40-49)。
 *
 * <p>CC 外部构建 classifyBashCommand 恒返回 {@code {matches:false, confidence:'high',
 * reason:'This feature is disabled'}}（classifier permissions 为 ant-only）。本类忠实复刻
 * 该 no-op 语义 —— 缺省/未配置 LLM provider 时恒 matches=false（fail-open，不阻断任何命令，
 * deny/ask 并行分类块据此恒不触发）。
 *
 * <p>Haiku/LLM 真实通道落地属高成本项，实施时单独评估（超 Q-BS-4 范围），届时替换本 stub
 * 为真实 provider 解析（复用 ModelConfigResolver + Haiku 模型）。
 */
@Component
public class BashCommandClassifierImpl implements BashCommandClassifier {

    private static final Logger log = LoggerFactory.getLogger(BashCommandClassifierImpl.class);

    @Override
    public BashClassifierPermission.ClassifierResult classifyBashCommand(
            String command,
            String cwd,
            List<String> descriptions,
            BashClassifierPermission.ClassifierBehavior behavior) {
        if (log.isDebugEnabled()) {
            log.debug("BashCommandClassifier stub：classifyBashCommand 未落地 LLM 通道（对齐 CC 外部 stub matches=false），"
                + "command={} behavior={} descriptions={}", command, behavior, descriptions);
        }
        // 对齐 CC bashClassifier.ts:47-50 外部 stub：恒 matches=false
        return BashClassifierPermission.ClassifierResult.notMatched("This feature is disabled");
    }
}
