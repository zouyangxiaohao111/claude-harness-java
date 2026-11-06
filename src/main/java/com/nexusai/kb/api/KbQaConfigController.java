package com.nexusai.kb.api;

import com.nexusai.infra.exception.ValidationException;
import com.nexusai.kb.config.KbConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 问答链参数端点（本仓自有，源侧 178 无对应条目 ⇒ <b>不计入端点账</b>）。
 *
 * <p><b>⚠️ 为什么现在才补</b>：{@link KbConfigService#readQaParams()} /
 * {@link KbConfigService#updateQaParams} 从批次 W5 就齐全（含三态更新、默认值单点），
 * 但<b>全仓没有调用 {@code updateQaParams} 的地方</b> —— 19 个参数（含
 * {@code data_analysis_enabled}、{@code web_fetch_enabled}、{@code context_template}）
 * 因此只能靠手改库。这与缺陷 27/31 同属「服务层写完但没接线」。
 *
 * <p><b>路径选择</b>：{@code /api/v1/kb/config/qa}。理由：
 * <ul>
 *   <li>{@code /api/v1/settings/kb-models} 已由 {@code SettingsController} 占用，
 *       那是「模型」维度，本端点是「问答行为」维度，不该混在一个载荷里。</li>
 *   <li>{@code kb_config} 是知识域全局单行 ⇒ 路径带 {@code kb} 前缀可避免将来
 *       与其它子系统的 config 撞车。</li>
 * </ul>
 *
 * <p><b>⚠️ 与 {@code sessions.kb_tools_enabled} 的区别</b>：本端点的
 * {@code kbToolsEnabled} 是<b>全局默认</b>（{@code kb_config} 表），
 * 会话级开关在 {@code sessions} 表（V278），由会话 PATCH 端点管理。
 * 主对话真正消费的是<b>会话级</b>那一列；这里的全局值当前只作为配置快照展示与
 * 新建会话的取值来源。
 */
@RestController
@RequestMapping("/api/v1/kb/config")
public class KbQaConfigController {

    private static final Logger log = LoggerFactory.getLogger(KbQaConfigController.class);

    private final KbConfigService configService;

    public KbQaConfigController(KbConfigService configService) {
        this.configService = configService;
    }

    /**
     * 读问答链参数（已解析快照：未配置项已回落默认值）。
     *
     * @return 19 个参数（字段名与原列表一致，驼峰）
     */
    @GetMapping("/qa")
    public KbConfigService.QaParams getQaParams() {
        return configService.readQaParams();
    }

    /**
     * 更新问答链参数：每个字段 {@code null} = 不改（三态）。
     *
     * <p>⚠️ 服务层刻意不校验范围（见 {@code updateQaParams} javadoc），
     * 范围校验在本方法内完成 —— 让非法值在入口被拒，而不是存进库后
     * 在检索阶段表现为「一条都搜不到」。
     *
     * @param req 载荷（可只传部分字段）
     * @return 更新后的已解析快照（便于调用方立即看到生效值）
     */
    @PutMapping("/qa")
    public KbConfigService.QaParams updateQaParams(@RequestBody KbConfigService.QaParamsUpdate req) {
        if (req == null) {
            throw new ValidationException("请求体不能为空");
        }
        validate(req);
        configService.updateQaParams(req);
        KbConfigService.QaParams after = configService.readQaParams();
        log.info("[kb_config] 问答参数已更新: topK={} rerankTopK={} maxRounds={} "
                        + "webFetch={} dataAnalysis={} kbTools={}",
                after.embeddingTopK(), after.rerankTopK(), after.maxRounds(),
                after.webFetchEnabled(), after.dataAnalysisEnabled(), after.kbToolsEnabled());
        return after;
    }

    /**
     * 范围校验（只校验非 null 字段）。
     *
     * <p>阈值类必须落在 {@code [0,1]}：越界不会报错，只会让过滤恒真/恒假 ——
     * 这正是缺陷 27 那类「静默无效」的形态。
     */
    private static void validate(KbConfigService.QaParamsUpdate r) {
        if (r.embeddingTopK() != null && (r.embeddingTopK() < 1 || r.embeddingTopK() > 500)) {
            throw new ValidationException("embeddingTopK 必须在 1~500 之间");
        }
        if (r.rerankTopK() != null && (r.rerankTopK() < 1 || r.rerankTopK() > 500)) {
            throw new ValidationException("rerankTopK 必须在 1~500 之间");
        }
        if (r.contextMaxChunks() != null
                && (r.contextMaxChunks() < 1 || r.contextMaxChunks() > 100)) {
            throw new ValidationException("contextMaxChunks 必须在 1~100 之间");
        }
        if (r.maxRounds() != null && (r.maxRounds() < 0 || r.maxRounds() > 20)) {
            throw new ValidationException("maxRounds 必须在 0~20 之间");
        }
        if (r.webSearchMaxResults() != null
                && (r.webSearchMaxResults() < 0 || r.webSearchMaxResults() > 50)) {
            throw new ValidationException("webSearchMaxResults 必须在 0~50 之间");
        }
        if (r.webFetchTopN() != null && (r.webFetchTopN() < 0 || r.webFetchTopN() > 20)) {
            throw new ValidationException("webFetchTopN 必须在 0~20 之间");
        }
        requireUnit(r.vectorThreshold(), "vectorThreshold");
        requireUnit(r.keywordThreshold(), "keywordThreshold");
        requireUnit(r.rerankThreshold(), "rerankThreshold");
        requireUnit(r.faqScoreBoost(), "faqScoreBoost");
        requireUnit(r.faqDirectAnswerThreshold(), "faqDirectAnswerThreshold");
    }

    private static void requireUnit(Double v, String name) {
        if (v != null && (v < 0 || v > 1)) {
            throw new ValidationException(name + " 必须在 0~1 之间");
        }
    }
}
