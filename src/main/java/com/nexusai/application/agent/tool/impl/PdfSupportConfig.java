package com.nexusai.application.agent.tool.impl;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * [pdf-vision-align] PDF 提取阈值配置载体 · yml {@code nexusai.pdf.*} → {@link PdfSupport#setThresholds}。
 *
 * <p><b>WHY</b>：{@link PdfSupport} 是静态工具类（无 Spring bean），但 PDF 分大小框架阈值需经
 * yml 可配置（对齐 CC constants/apiLimits.ts:66/72 的 3MB/100MB 默认值）。本类作为可注入配置载体，
 * 启动期 @Value 读取 + {@code @PostConstruct} 写入 {@link PdfSupport} 静态 volatile 字段
 * （写后运行期只读，无并发竞争）。PdfSupport 消费者统一经
 * {@link PdfSupport#getExtractSizeThreshold()} / {@link PdfSupport#getMaxExtractSize()} 读取生效值。
 *
 * <p><b>默认值</b>：extract-size-threshold 3145728 = 3MB（CC apiLimits.ts:66 PDF_EXTRACT_SIZE_THRESHOLD）；
 * max-extract-size 104857600 = 100MB（CC apiLimits.ts:72 PDF_MAX_EXTRACT_SIZE）。
 */
@Component
public final class PdfSupportConfig {

    private static final Logger log = LoggerFactory.getLogger(PdfSupportConfig.class);

    /** 提取阈值（PDF ≤该值走 document block，>该值≤max 走逐页 image block / 文本模型页图注册）· 默认 3MB 对齐 CC apiLimits.ts:66。 */
    @Value("${nexusai.pdf.extract-size-threshold:3145728}")
    private long extractSizeThreshold;

    /** 最大提取尺寸（PDF 提取路径最大文件尺寸，超过报 too_large）· 默认 100MB 对齐 CC apiLimits.ts:72。 */
    @Value("${nexusai.pdf.max-extract-size:104857600}")
    private long maxExtractSize;

    @PostConstruct
    void init() {
        PdfSupport.setThresholds(extractSizeThreshold, maxExtractSize);
        if (log.isInfoEnabled()) {
            log.info("PdfSupport 阈值配置生效: extract={}B maxExtract={}B", extractSizeThreshold, maxExtractSize);
        }
    }
}
