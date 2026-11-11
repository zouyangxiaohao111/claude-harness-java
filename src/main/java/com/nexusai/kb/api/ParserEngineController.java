package com.nexusai.kb.api;

import com.nexusai.kb.app.parser.KbParserRegistry;
import com.nexusai.kb.app.parser.ParserEngineInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 解析引擎注册表端点 · 对齐源侧 {@code GET /api/v1/system/parser-engines}。
 *
 * <p><b>为什么需要这个端点</b>：源侧「新建知识库 → 解析引擎」节是<b>后端注册表驱动</b>的 ——
 * 前端拿到引擎清单与各自支持的扩展名，再按扩展名分组成 12 个下拉。前端若硬编码引擎名，
 * 后端一换解析器就会「选了不生效」，故能力声明必须由装配侧出。
 *
 * <p><b>数据来源</b>：{@link KbParserRegistry#describeEngines()}，顺序即 {@code @Order} 升序
 * （与真实路由优先级同一口径）。
 *
 * <p><b>响应形状</b>：沿用本包「下拉元数据」端点的既有形状（{@code /vector-stores/types}、
 * {@code /storage-backends/types}），即 {@code {"success":true,"data":[...]}}；
 * {@code data[]} 元素键名逐字对齐源站大写：{@code Name}/{@code Description}/{@code FileTypes}/
 * {@code Available}/{@code UnavailableReason}。源侧另带的 {@code connected}、
 * {@code docreader_addr}、{@code docreader_transport}、{@code msg} 本仓<b>没有</b>对应设施（无 docreader 服务），
 * 故不输出，⛔ 不编造占位值。
 *
 * <p><b>本仓只有 3 个解析器</b>（PDF / Office / 文本兜底），全部可用；源侧另有
 * {@code simple}/{@code anydoc}/{@code weknoracloud}/{@code mineru}/{@code mineru_cloud}/
 * {@code paddleocr_vl}/{@code paddleocr_vl_cloud}/{@code markitdown}/{@code opendataloader}，
 * 本仓均未实现，故不会出现在响应里。
 */
@RestController
@RequestMapping("/api/v1/system")
public class ParserEngineController {

    private static final Logger log = LoggerFactory.getLogger(ParserEngineController.class);

    @Autowired
    private KbParserRegistry parserRegistry;

    /** 解析引擎清单 · 供前端渲染各文件类型的引擎下拉。 */
    @GetMapping("/parser-engines")
    public Map<String, Object> list() {
        List<ParserEngineInfo> engines = parserRegistry.describeEngines();
        if (log.isDebugEnabled()) {
            log.debug("[解析引擎API] GET /api/v1/system/parser-engines → {} 条 {}", engines.size(),
                engines.stream().map(ParserEngineInfo::name).toList());
        }
        return Map.of("success", true, "data", engines);
    }
}
