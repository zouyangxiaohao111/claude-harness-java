package com.nexusai.application.agent.computeruse;

import com.nexusai.application.agent.tool.impl.PdfSupport;
import com.nexusai.infra.llm.ModelNameResolver;
import com.nexusai.repository.provider.entity.ModelRecord;
import com.nexusai.repository.provider.entity.ProviderRecord;
import com.nexusai.repository.provider.mapper.ModelMapper;
import com.nexusai.repository.provider.mapper.ProviderMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

/**
 * computer-use <b>视觉能力闸</b> · 本仓<b>特有增强</b>（CCB 无此闸 —— 见文末「与 CCB 的差异」）。
 *
 * <h2>WHY 需要闸（背景）</h2>
 * <p>Phase 1b 之前，CU 截图以 {@code role=user} + {@code image_url} 送达
 * （{@code OpenAiSdkProvider.toSdkImageCarrierMessage}）⇒ <b>不支持视觉的模型端点
 * （如 {@code deepseek-chat}）会返回 400</b>（改前是静默丢图）。
 * <p>⇒ CU 工具执行前必须判断<b>当前模型是否支持视觉</b>；不支持 ⇒ <b>fail loud</b>，
 * 返回模型可见的明确错误，<b>不发图</b>。
 *
 * <h2>⛔ 为什么不能直接用 {@code vision_analyze} 兜底</h2>
 * <p>本仓的 {@code vision_analyze}（{@code ToolNameConstants.java:199}）走<b>独立视觉模型并返回纯文本</b>
 * ⇒ 主模型拿不到图像像素 ⇒ 它自己<b>无法给出像素坐标</b>。
 * <br>⇒ 但这<b>不等于</b>「computer-use 在无视觉主模型下不可行」：
 * <b>[Phase 1e] 已补一条 grounding 降级路径</b> ——
 * {@link ComputerUseGroundingService} 用<b>专用的 grounding prompt</b>（要求返回结构化
 * JSON 坐标）驱动同一个视觉模型，产出<b>归一化（百分比）坐标</b>回给主模型
 * ⇒ 主模型拿到的是「二手定位的百分比坐标」，而不是像素。
 * <br>CCB 仍无此设计（CCB 的 computer-use 只跑在能看图的 Claude 主模型上）⇒ 属
 * <b>本仓增强</b>，非 CCB 行为。
 *
 * <h2>判定链（实例级 · 带 modelName）</h2>
 * <p>本接口的实例实现 {@link DbBacked} <b>持有 mapper 引用</b>（实例级），
 * 判定时<b>必带 modelName</b>（当前 turn 的有效模型名，来源
 * {@code ToolUseContext.effectiveModelName()}）。判定顺序：
 * <ol>
 *   <li><b>modelName 空白</b> ⇒ {@link Verdict#undetermined}（判不出，不阻断）。</li>
 *   <li><b>DB {@code models.type} ∈ {vision, multimodal}</b> ⇒ <b>supported</b>
 *       （{@link Verdict#SOURCE_DB_TYPE}）—— 与本仓既有
 *       {@code ModelCapabilityResolver.supportsImage} 的「正向命中」同一条判据。</li>
 *   <li><b>provider 家族 = anthropic</b> ⇒ <b>supported</b>
 *       （{@link Verdict#SOURCE_PROVIDER_FAMILY}）—— Claude 模型族原生支持图片输入；
 *       唯一例外 {@code claude-3-haiku} 走本仓<b>既有</b>名字契约
 *       {@link PdfSupport#isPDFSupported(String)}（CC {@code pdfUtils.ts:59-61}
 *       「唯一不支持图片/文档块的 Haiku 3」）⇒ {@link Verdict#SOURCE_NAME_CONTRACT} unsupported。</li>
 *   <li><b>DB {@code models.type} 非空且非多模态</b> ⇒ <b>unsupported</b>
 *       （{@link Verdict#SOURCE_DB_TYPE}）—— 显式文本模型 / 非对话模型（embedding / rerank / audio …）。</li>
 *   <li>其余（模型未登记 / type 空白 / DB 异常）⇒ {@link Verdict#undetermined}（<b>不阻断</b>）。</li>
 * </ol>
 *
 * <h2>⚠️ 与 {@code ModelCapabilityResolver.supportsImage} 的<b>刻意分歧</b>（规则七 · 显式暴露冲突）</h2>
 * <p>{@code supportsImage} 是<b>保守 false</b>（未登记 / type 空白 ⇒ false）——
 * 本仓其它路径（用户附件图 / PDF 页图）用它决定「要不要把图直接送给模型」，
 * 判 false 时走「登记图片 + 让模型调 {@code vision_analyze}」的降级。
 * <p><b>本闸不能沿用那个方向</b>：CU 场景里「把图藏起来」正是本批要修的缺陷本身
 * （模型看不到画面 ⇒ 点击坐标无从谈起）。故本闸：
 * <ul>
 *   <li><b>Claude 家族（anthropic provider）</b> ⇒ 支持（{@code supportsImage} 会因
 *       {@code models.type=chat} 判 false ⇒ <b>本闸比它更准</b>）；</li>
 *   <li><b>未登记模型</b> ⇒ 判不出 ⇒ <b>放行</b>（{@code supportsImage} 判 false ⇒
 *       <b>本闸比它更宽</b>，不会把图重新藏起来）。</li>
 *   <li><b>显式文本模型（deepseek-chat 等）</b> ⇒ 不支持（与 {@code supportsImage} 同向）。</li>
 * </ul>
 * ⛔ 本闸<b>不</b>调用 {@code ModelCapabilityResolver.supportsImage}（不作为闸门）——
 * 只复用其<b>正向判据</b>（{@code models.type ∈ {vision, multimodal}}）与本仓既有的
 * {@link PdfSupport#isPDFSupported(String)} 名字契约。
 *
 * <h2>与 CCB 的差异（如实标注）</h2>
 * <p>CCB 2.8.4 <b>没有</b>任何视觉能力闸：它的 computer-use MCP 假定主模型恒为
 * Claude（能看图）。本仓主模型可被换成 deepseek / minimax 等文本端点 ⇒
 * <b>本闸是本仓必需的护栏</b>，非 CCB 行为。
 *
 * @see ComputerUseScreenshotTool 视觉闸消费点
 * @see ComputerUseLeftClickTool 视觉闸消费点
 */
public interface ComputerUseVisionCapability {

    /**
     * 判定当前模型是否支持视觉（图片）输入。
     *
     * @param modelName    当前 turn 的有效模型名（{@code ctx.effectiveModelName()}；可空/空白 ⇒ 判不出）
     * @param providerType 当前 turn 的 provider 类型（{@code ctx.effectiveProviderType()}；
     *                     可空 ⇒ 由实例实现回查 DB 的 provider.type）
     * @return 判定结果（永不为 null）
     */
    Verdict resolve(String modelName, String providerType);

    /**
     * 判定结果（三态）。
     *
     * @param supported  是否支持视觉输入
     * @param determinate 是否<b>已确定</b>（false = 判不出 ⇒ 消费方<b>放行</b>，不阻断）
     * @param source     判定来源（{@link #SOURCE_DB_TYPE} 等）
     * @param modelName  判定所用模型名（回显用）
     * @param detail     判定细节（回显用）
     */
    record Verdict(boolean supported, boolean determinate, String source, String modelName, String detail) {

        /** 判定来源：DB {@code models.type} 实值。 */
        public static final String SOURCE_DB_TYPE = "db-type";
        /** 判定来源：provider 家族（anthropic ⇒ Claude 家族原生支持图片）。 */
        public static final String SOURCE_PROVIDER_FAMILY = "provider-family";
        /** 判定来源：本仓既有名字契约（{@code claude-3-haiku}）。 */
        public static final String SOURCE_NAME_CONTRACT = "name-contract";
        /** 判定来源：判不出（模型未登记 / type 空白 / DB 异常）。 */
        public static final String SOURCE_UNDETERMINED = "undetermined";

        /** @param source 判定来源 @param modelName 模型名 @return supported 判定 */
        public static Verdict supported(String source, String modelName) {
            return new Verdict(true, true, source, modelName, "模型支持图片输入");
        }

        /**
         * @param source    判定来源
         * @param modelName 模型名
         * @param detail    细节（回显用）
         * @return unsupported 判定（已确定不支持 ⇒ 消费方 fail loud）
         */
        public static Verdict unsupported(String source, String modelName, String detail) {
            return new Verdict(false, true, source, modelName, detail);
        }

        /**
         * @param source    判定来源
         * @param modelName 模型名
         * @param detail    细节（回显用）
         * @return 判不出（消费方<b>放行</b>，不阻断 ⇒ 不把图重新藏起来）
         */
        public static Verdict undetermined(String source, String modelName, String detail) {
            return new Verdict(false, false, source, modelName, detail);
        }

        /**
         * <b>已确定不支持</b>时的模型可见错误文案。
         *
         * <p>⚠️ 本仓其它工具的失败文案多为英文（CC 对齐面），本条按<b>用户裁定的大白话中文</b>
         * 给出（用户在本批指令里给的原话方向：「当前模型不支持图片输入，无法执行屏幕操作」）。
         *
         * @return 错误文案；未确定不支持时返回 null（调用方不应调用）
         */
        public String rejectionMessage() {
            if (supported || !determinate) {
                return null;
            }
            String model = (modelName == null || modelName.isBlank()) ? "当前模型" : modelName;
            return "当前模型（" + model + "）不支持图片输入，无法执行屏幕操作。"
                    + "computer-use 需要模型能直接看到屏幕截图才能给出点击坐标。"
                    + "请切换到支持视觉输入的模型（如 Claude / GPT-4o 等多模态模型），"
                    + "或在模型设置里把该模型标记为「多模态 / 视觉」后重试。";
        }
    }

    /**
     * <b>视觉闸消费点</b>（CU 工具执行前的统一入口）· 返回<b>拒绝文案</b>或 {@code null}（放行）。
     *
     * <p>判定链（与 {@link Verdict} 的三态一一对应）：
     * <ol>
     *   <li>能力未接线（{@code capability == null}）或无工具上下文（{@code ctx == null}）⇒ <b>放行</b>
     *       —— PoC / 单测路径无模型上下文可判（生产恒有 ctx，见
     *       {@code StreamingToolExecutor} 的 execute(call, ctx, onProgress) 派发链）。</li>
     *   <li>判定为支持 ⇒ 放行（正常送图）。</li>
     *   <li><b>已确定不支持</b> ⇒ 返回拒绝文案（消费方据此 fail loud，<b>不发图</b>）。</li>
     *   <li><b>判不出</b> ⇒ <b>放行</b>（⛔ 不把图重新藏起来 —— 那正是本批要修的缺陷）+ WARN 留痕。</li>
     * </ol>
     *
     * @param capability 视觉能力判定实例（可 null ⇒ 未接线 ⇒ 放行）
     * @param ctx        工具上下文（可 null ⇒ 无模型上下文 ⇒ 放行）
     * @return 拒绝文案（null = 放行）
     */
    static String rejectionIfUnsupported(ComputerUseVisionCapability capability,
                                         com.nexusai.application.agent.tool.ToolUseContext ctx) {
        Verdict verdict = verdictOf(capability, ctx);
        if (verdict == null) {
            return null;
        }
        if (verdict.supported()) {
            return null;
        }
        if (!verdict.determinate()) {
            if (LOG.isWarnEnabled()) {
                LOG.warn("[computer-use] 视觉闸判不出（放行 · 不阻断）: model={} source={} detail={}",
                        verdict.modelName(), verdict.source(), verdict.detail());
            }
            return null;
        }
        if (LOG.isWarnEnabled()) {
            LOG.warn("[computer-use] 视觉闸拦截（fail loud · 不发图）: model={} source={} detail={}",
                    verdict.modelName(), verdict.source(), verdict.detail());
        }
        return verdict.rejectionMessage();
    }

    /**
     * 取视觉能力判定结果（三态）· <b>[Phase 1e] grounding 降级路径的接线点</b>。
     *
     * <p><b>WHY 需要单独一个方法</b>：{@link #rejectionIfUnsupported} 只返回「拒绝文案」，
     * 无法区分「确定不支持」与「判不出」（两者都返回 null）⇒ 降级路径需要知道
     * 「<b>确定</b>不支持」才能决定走 grounding（判不出 ⇒ 保持现状：放行）。
     *
     * @param capability 视觉能力判定实例（可 null ⇒ 未接线 ⇒ null）
     * @param ctx        工具上下文（可 null ⇒ 无模型上下文 ⇒ null）
     * @return 判定结果；无法判定时返回 null（能力未接线 / 无上下文 / 实现返回 null）
     */
    static Verdict verdictOf(ComputerUseVisionCapability capability,
                             com.nexusai.application.agent.tool.ToolUseContext ctx) {
        if (capability == null || ctx == null) {
            return null;
        }
        return capability.resolve(ctx.effectiveModelName(), ctx.effectiveProviderType());
    }

    /** 闸的日志器（接口字段隐式 public static final）。 */
    Logger LOG = LoggerFactory.getLogger(ComputerUseVisionCapability.class);

    /**
     * DB 支撑的实现（<b>实例级</b>：持有 mapper 引用）。
     *
     * <p>静态工厂 {@link ComputerUseVisionCapability#db(ModelMapper, ProviderMapper)} 是唯一
     * 生产入口；单测可自造实现或直接构造本类。
     */
    final class DbBacked implements ComputerUseVisionCapability {

        private static final Logger log = LoggerFactory.getLogger(DbBacked.class);

        private final ModelMapper modelMapper;
        private final ProviderMapper providerMapper;

        /**
         * @param modelMapper    模型 mapper（null ⇒ 只能走 provider 家族 / 判不出）
         * @param providerMapper 提供商 mapper（null ⇒ 只能走 TUC 传入的 providerType）
         */
        public DbBacked(ModelMapper modelMapper, ProviderMapper providerMapper) {
            this.modelMapper = modelMapper;
            this.providerMapper = providerMapper;
        }

        @Override
        public Verdict resolve(String modelName, String providerType) {
            if (modelName == null || modelName.isBlank()) {
                return Verdict.undetermined(Verdict.SOURCE_UNDETERMINED, modelName,
                        "拿不到当前模型名（ctx.effectiveModelName 为空）");
            }
            String model = modelName.trim();

            ModelRecord record = lookup(model);
            String type = normalizeType(record);

            // ② DB type 正向命中多模态
            if ("multimodal".equals(type) || "vision".equals(type)) {
                return Verdict.supported(Verdict.SOURCE_DB_TYPE, model);
            }

            // ③ provider 家族 = anthropic ⇒ Claude 家族原生支持图片（DB type 未标多模态不影响）
            String family = resolveProviderFamily(model, record, providerType);
            if ("anthropic".equals(family)) {
                // 例外：本仓既有名字契约（CC pdfUtils.ts:59-61）—— 唯一不支持图片/文档块的 Haiku 3
                if (!PdfSupport.isPDFSupported(model)) {
                    return Verdict.unsupported(Verdict.SOURCE_NAME_CONTRACT, model,
                            "claude-3-haiku 不支持图片输入（CC isPDFSupported 名字契约）");
                }
                return Verdict.supported(Verdict.SOURCE_PROVIDER_FAMILY, model);
            }

            // ④ DB type 明确不是多模态 ⇒ 不支持（显式文本模型 / 非对话模型）
            if (type != null) {
                return Verdict.unsupported(Verdict.SOURCE_DB_TYPE, model,
                        "模型类型=" + type + "（非 multimodal / vision）");
            }

            // ⑤ 判不出 ⇒ 放行（⛔ 不把图重新藏起来）
            return Verdict.undetermined(Verdict.SOURCE_UNDETERMINED, model,
                    record == null ? "模型未登记（查不到 models 行）" : "模型 type 为空");
        }

        /** DB 查模型行（宽松解析：未命中 / 异常 ⇒ null，不打 ERROR）。 */
        private ModelRecord lookup(String modelName) {
            if (modelMapper == null) {
                return null;
            }
            try {
                return ModelNameResolver.resolveQuiet(modelMapper, providerMapper, modelName);
            } catch (Exception e) {
                if (log.isWarnEnabled()) {
                    log.warn("[computer-use] 视觉闸: 模型解析异常（判不出 ⇒ 放行）model={} err={}",
                            modelName, e.toString());
                }
                return null;
            }
        }

        /** 归一后的 {@code models.type}（小写；null / 空白 ⇒ null）。 */
        private static String normalizeType(ModelRecord record) {
            if (record == null) {
                return null;
            }
            String raw = record.getType();
            if (raw == null || raw.isBlank()) {
                return null;
            }
            return raw.trim().toLowerCase(Locale.ROOT);
        }

        /**
         * provider 家族解析：<b>TUC 传入的 providerType 优先</b>（每轮盖章，零 DB 开销），
         * 否则回查 DB（模型已登记时按 {@code models.provider_id} → {@code providers.type}）。
         */
        private String resolveProviderFamily(String modelName, ModelRecord record, String providerType) {
            if (providerType != null && !providerType.isBlank()) {
                return providerType.trim().toLowerCase(Locale.ROOT);
            }
            if (record == null || providerMapper == null) {
                return null;
            }
            try {
                ProviderRecord provider = providerMapper.selectOneById(record.getProviderId());
                if (provider == null || provider.getType() == null || provider.getType().isBlank()) {
                    return null;
                }
                return provider.getType().trim().toLowerCase(Locale.ROOT);
            } catch (Exception e) {
                if (log.isDebugEnabled()) {
                    log.debug("[computer-use] 视觉闸: provider 类型回查失败 model={} err={}", modelName, e.toString());
                }
                return null;
            }
        }
    }

    /**
     * DB 支撑实现的静态工厂（生产入口）。
     *
     * @param modelMapper    模型 mapper
     * @param providerMapper 提供商 mapper
     * @return 视觉能力判定实例
     */
    static ComputerUseVisionCapability db(ModelMapper modelMapper, ProviderMapper providerMapper) {
        return new DbBacked(modelMapper, providerMapper);
    }
}
