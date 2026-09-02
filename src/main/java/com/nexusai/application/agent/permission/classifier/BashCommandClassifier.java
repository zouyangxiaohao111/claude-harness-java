package com.nexusai.application.agent.permission.classifier;

import com.nexusai.infra.util.BashClassifierPermission;

import java.util.List;

/**
 * Bash 命令 Haiku/LLM 分类通道 · 对齐 CC {@code classifyBashCommand}
 * (utils/permissions/bashClassifier.ts:40-49)。
 *
 * <p>CC 签名：{@code classifyBashCommand(command, cwd, descriptions, behavior, signal,
 * isNonInteractiveSession)}。Java 版省略 {@code signal}/{@code isNonInteractiveSession}
 * 两参（缺省 stub 不做异步 LLM 调用，无 abort/非交互语义可消费）；LLM 通道落地时补回。
 *
 * <p>缺省实现 {@link BashCommandClassifierImpl} 返回 {@code matches=false}（对齐 CC 外部
 * stub 的 no-op 语义），{@code BashTool.checkPermissions} 的 deny/ask 并行分类块据此恒不触发。
 *
 * <p><b>不得与 {@code YoloClassifier}（{@code classifyYoloAction} Sonnet 2-stage allow 分类）
 * 混为一谈</b>：CC 中 classifyBashCommand（Haiku deny/ask/allow prompt-rule 分类）与
 * classifyYoloAction 是两条独立通道。
 */
public interface BashCommandClassifier {

    /**
     * 分类 bash 命令 · 对齐 CC {@code classifyBashCommand}。
     *
     * @param command      命令字符串（CC input.command）
     * @param cwd          工作目录（CC getCwd()）
     * @param descriptions 描述数组（CC denyDescriptions / askDescriptions）
     * @param behavior     分类行为（deny/ask/allow）
     * @return 分类结果（缺省 stub：matches=false）
     */
    BashClassifierPermission.ClassifierResult classifyBashCommand(
            String command,
            String cwd,
            List<String> descriptions,
            BashClassifierPermission.ClassifierBehavior behavior);
}
