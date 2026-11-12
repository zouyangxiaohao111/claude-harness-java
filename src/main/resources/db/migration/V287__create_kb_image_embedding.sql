-- ===================================================================
-- V287: kb_image_embedding —— 图片向量通道（caption / OCR 文本向量）
--
-- 背景：本仓没有多模态 embedding 模型。图片理解（KbImageEnrichService）把
--       caption / ocr_text 写进 kb_chunk.image_info 后，这些文字只能靠
--       「拼进索引文本」被文本向量捎带命中，没有独立的图片向量通道。
--
-- 本表存的是**图片相关文本**的向量，不是伪造的图片语义向量：
--   * source = 'caption_text'  → 该图 caption 的文本向量；
--   * source = 'ocr_text'      → 该图 OCR 文本的向量；
--   * source = 'multimodal'    → 预留（真·多模态模型接入后使用，当前不写）。
-- 配置 nexusai.knowledge.image-vector.model 为空时，服务明确报「未配置」，
-- ⛔ 不写任何假向量（宁可查不到，也不返回假数据）。
--
-- 唯一键 = (knowledge_base_id, image_key, source, model_id)：
--   · 换模型必须重算（不同模型的向量空间不可比）；
--   · 同一张图同一来源只留最新一行，重跑索引按 content_hash 判是否跳过。
--
-- vector 口径与 kb_embedding 一致：float32 小端序 BLOB（见 KbVectorCodec）。
-- ===================================================================

CREATE TABLE kb_image_embedding (
    id                TEXT PRIMARY KEY,

    knowledge_base_id TEXT NOT NULL,
    knowledge_id      TEXT NOT NULL,
    chunk_id          TEXT,

    -- 图片资源键：与该图所在 image_info 节点的键一致
    -- （url → original_url → "<chunk_id>#<index>"，与 kb_chunk_image 投影同口径）
    image_key         TEXT NOT NULL,

    -- 'caption_text' | 'ocr_text' | 'multimodal'
    source            TEXT NOT NULL,

    model_id          TEXT NOT NULL,
    dim               INTEGER NOT NULL,

    -- float32 小端序字节序列（长度 = dim * 4）
    vector            BLOB NOT NULL,

    -- 参与编码的文本 SHA-256，用于跳过重复编码
    content_hash      TEXT NOT NULL,

    created_at        TEXT NOT NULL
);

CREATE UNIQUE INDEX ux_kb_image_embedding_key
    ON kb_image_embedding (knowledge_base_id, image_key, source, model_id);

CREATE INDEX ix_kb_image_embedding_kb
    ON kb_image_embedding (knowledge_base_id);
