package com.nexusai.model.provider.dto;

/** Model 协议类型（对齐前端 ModelType · 含多模态/视频/TTS） */
public enum ModelType {
    chat,
    text,
    vision,
    multimodal,
    image,
    image_generation,
    embedding,
    audio,
    rerank,
    moderation
}
