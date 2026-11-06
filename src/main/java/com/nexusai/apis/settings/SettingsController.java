package com.nexusai.apis.settings;

import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.model.settings.dto.ModelTierRole;
import com.nexusai.model.settings.dto.SettingsDto;
import com.nexusai.model.settings.dto.SettingsResponse;
import com.nexusai.domain.settings.SettingsService;
import com.nexusai.infra.exception.NotFoundException;
import com.nexusai.infra.exception.ValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/** Settings REST 端点（singleton） */
@RestController
@RequestMapping("/api/v1/settings")
public class SettingsController {

    private static final Logger log = LoggerFactory.getLogger(SettingsController.class);

    @Autowired private SettingsService settingsService;

    /** 知识库域配置（R0 · V273）：检索驱动/默认存储后端/记忆开关都在这张独立表里。 */
    @Autowired private com.nexusai.kb.config.KbConfigService kbConfigService;

    /** 检索驱动注册表（W1）：驱动清单来自 Spring 容器，⛔ 前端不硬编码。 */
    @Autowired private com.nexusai.kb.app.retrieval.engine.KbRetrievalEngineRegistry engineRegistry;

    /** GET：响应 = {@link SettingsResponse}（内层 SettingsDto 摊平 + appName/configHome 两个只读键）。
     *  ⛔ 这两个键只在这里出站：PUT 的入参/出参仍是 {@link SettingsDto} ⇒ 结构上写不动（见 SettingsResponse javadoc）。 */
    @GetMapping
    public SettingsResponse get() {
        return new SettingsResponse(
            settingsService.get(),
            NexusaiPaths.getAppName(),
            NexusaiPaths.getAppConfigHomeDir());
    }

    @PutMapping
    public SettingsDto update(@RequestBody SettingsDto req) {
        return settingsService.update(req);
    }

    /**
     * GET 知识库模型全局默认。
     *
     * <p><b>为什么独立端点而不塞进 {@code /settings}</b>：{@code SettingsDto} 被 GET/PUT 共用且有
     * 12 处构造点；且「知识库模型」是<b>能力配置</b>，不是用户全局偏好，放独立路径语义更准。
     *
     * <p>⚠️ <b>2026-11-05 起真源是 {@code kb_config}</b>：settings 上的
     * {@code kb_embedding_model_id} / {@code kb_rerank_model_id} 已删除，本端点保留 URL
     * 仅为兼容，内部走 {@link KbConfigService#readModelConfig()}。
     *
     * <p>返回的是<b>原始值</b>（{@code null} = 用户没显式配过），不含「自动选用」的结果 ——
     * 否则用户会以为自己配过，模型被禁用后行为变化却找不到原因。
     */
    @GetMapping("/kb-models")
    public com.nexusai.kb.config.KbConfigService.ModelConfig getKbModels() {
        return kbConfigService.readModelConfig();
    }

    /**
     * PUT 知识库模型全局默认。
     *
     * <p><b>字段语义（与本仓 settings 的 merge 契约一致）</b>：
     * <ul>
     *   <li>字段缺省 / {@code null} ⇒ <b>不改</b>这一项</li>
     *   <li>传空串 ⇒ <b>清空</b>（回到「自动选用同类型第一个启用模型」）</li>
     *   <li>传模型 ID ⇒ 设为该模型</li>
     * </ul>
     *
     * @param req 请求体（两个字段都可选）
     * @return 写入后的配置（原始值）
     */
    @PutMapping("/kb-models")
    public com.nexusai.kb.config.KbConfigService.ModelConfig updateKbModels(@RequestBody KbModelConfigRequest req) {
        String embedding = req == null ? null : req.embeddingModelId();
        String rerank = req == null ? null : req.rerankModelId();
        kbConfigService.updateModelConfig(embedding, rerank);
        return kbConfigService.readModelConfig();
    }

    /**
     * 知识库模型配置请求体。
     *
     * @param embeddingModelId 向量模型 ID（null=不改；""=清空）
     * @param rerankModelId    重排模型 ID（null=不改；""=清空）
     */
    public record KbModelConfigRequest(String embeddingModelId, String rerankModelId) {
    }

    /**
     * GET 检索驱动（W1 · V268）。
     *
     * <p><b>为什么单独一个端点</b>：界面上要显示「有哪些驱动可选」+「哪些本机真能用」，
     * 这两份信息都来自 Spring 容器里的实现集合 —— 只有后端知道。
     * 前端⛔ 不应该硬编码驱动清单（加驱动时会漏改）。
     *
     * @return 当前配置 / 已注册 / 可用 三份清单
     */
    @GetMapping("/retrieval-engines")
    public RetrievalEnginesResponse getRetrievalEngines() {
        return new RetrievalEnginesResponse(
            engineRegistry.resolveEngineType(null),
            engineRegistry.registeredTypes(),
            engineRegistry.availableTypes());
    }

    /**
     * PUT 全局默认检索驱动（W1 · V268）。
     *
     * <p>⚠️ 未注册或本机不可用的驱动在这里就 <b>400</b> —— 不要等到用户去检索时
     * 才在后台抛异常（那时已经上传并索引过文档了，排查成本高得多）。
     *
     * @param req 请求体（{@code engine} 为驱动类型；空串 = 清空回落默认）
     * @return 写入后的三份清单
     */
    @PutMapping("/retrieval-engines")
    public RetrievalEnginesResponse updateRetrievalEngine(@RequestBody RetrievalEngineRequest req) {
        String engine = req == null ? null : req.engine();
        if (engine != null && !engine.isBlank()) {
            // 提前校验：可用性检查放在写入之前。
            // ⚠️ 注册表的 require() 抛 IllegalStateException（那是给「内部调用方」的信号）；
            //    这里是用户输入 ⇒ 必须转成 400。否则用户配错驱动只能看到一句
            //    「Internal Server Error」，不知道该填什么。
            try {
                engineRegistry.require(engine);
            } catch (IllegalStateException e) {
                throw new ValidationException(e.getMessage());
            }
        }
        kbConfigService.updateRetrieverEngine(engine);
        return getRetrievalEngines();
    }

    /**
     * 检索驱动请求体。
     *
     * @param engine 驱动类型（null = 不改；"" = 清空回落 sqlite）
     */
    public record RetrievalEngineRequest(String engine) {
    }

    /**
     * 检索驱动响应。
     *
     * @param current   当前生效的驱动（知识库没覆盖时的全局值）
     * @param registered 本构建已声明的驱动（含未接入的）
     * @param available 本机真正可用的驱动
     */
    public record RetrievalEnginesResponse(
        String current, java.util.List<String> registered, java.util.List<String> available
    ) {
    }

    /**
     * DELETE 模型档位（全部）—— 「一键清除所有已配置的模型」。
     *
     * <p><b>为什么需要独立的 DELETE</b>：{@link #update} 的 merge 契约是「{@code null} = 不覆盖」
     * ⇒ 用 PUT <b>无法</b>表达「把档位清空」（清空要传 {@code null}，而 {@code null} 恰被当成「别动它」）。
     * 清空与覆盖语义相反，故走独立通道。
     *
     * <p>返回清空后的 {@link SettingsDto}（前端经 mergeSettings 合并回 state）。
     */
    @DeleteMapping("/model-tiers")
    public SettingsDto clearAllModelTiers() {
        if (log.isInfoEnabled()) {
            log.info("[SettingsController] 收到请求：清除全部模型档位绑定");
        }
        return settingsService.clearAllModelTiers();
    }

    /**
     * DELETE 模型档位（单个）—— 解除某一个档位与模型的绑定。
     *
     * @param role 档位 wire 名（main / fast / strong / medium / weak / subagent /
     *             fallback / multimodal / tts / asr / classifier）
     * @return 清空后的 {@link SettingsDto}
     * @throws ValidationException 档位名非法（400）
     * @throws NotFoundException   settings 单例行缺失（404）
     */
    @DeleteMapping("/model-tiers/{role}")
    public SettingsDto clearModelTier(@PathVariable String role) {
        ModelTierRole r = ModelTierRole.fromWire(role);
        if (r == null) {
            // fail loud：未知档位名 → 400，不静默忽略（静默会让前端以为清掉了，实际没动）
            throw new ValidationException(
                "档位非法（允许 " + String.join("/", ModelTierRole.wireNames()) + "）：" + role);
        }
        if (log.isInfoEnabled()) {
            log.info("[SettingsController] 收到请求：清除模型档位 {}", r.wire());
        }
        return settingsService.clearModelTier(r);
    }
}
