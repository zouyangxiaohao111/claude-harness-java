-- [V300] 存量 provider 回填品牌归属：能对上内置品牌的一律归入，对不上才留在「自定义」。
-- 幂等：只处理 brand_key IS NULL 的行；重复执行无副作用。
-- ⚠️ 顺序有意义：本地三个品牌端口不同，必须先按端口精确匹配，再跑通用 host。

-- ---------------- 国际 ----------------
UPDATE providers SET brand_key = 'openai'     WHERE brand_key IS NULL AND base_url LIKE '%chatgpt.com%';
UPDATE providers SET brand_key = 'claude'     WHERE brand_key IS NULL AND base_url LIKE '%api.anthropic.com%';
UPDATE providers SET brand_key = 'gemini'     WHERE brand_key IS NULL AND base_url LIKE '%generativelanguage.googleapis.com%';
UPDATE providers SET brand_key = 'openrouter' WHERE brand_key IS NULL AND base_url LIKE '%openrouter.ai%';
UPDATE providers SET brand_key = 'groq'       WHERE brand_key IS NULL AND base_url LIKE '%api.groq.com%';
UPDATE providers SET brand_key = 'xai'        WHERE brand_key IS NULL AND base_url LIKE '%api.x.ai%';
UPDATE providers SET brand_key = 'mistral'    WHERE brand_key IS NULL AND base_url LIKE '%api.mistral.ai%';
UPDATE providers SET brand_key = 'opencode'   WHERE brand_key IS NULL AND base_url LIKE '%opencode.ai%';

-- ---------------- 国内 ----------------
UPDATE providers SET brand_key = 'deepseek'    WHERE brand_key IS NULL AND base_url LIKE '%api.deepseek.com%';
UPDATE providers SET brand_key = 'qwen'        WHERE brand_key IS NULL AND base_url LIKE '%dashscope.aliyuncs.com%';
UPDATE providers SET brand_key = 'qwen'        WHERE brand_key IS NULL AND base_url LIKE '%maas.aliyuncs.com%';
UPDATE providers SET brand_key = 'kimi'        WHERE brand_key IS NULL AND base_url LIKE '%api.moonshot.cn%';
UPDATE providers SET brand_key = 'kimi'        WHERE brand_key IS NULL AND base_url LIKE '%api.kimi.com%';
UPDATE providers SET brand_key = 'minimax'     WHERE brand_key IS NULL AND base_url LIKE '%api.minimax.cn%';
UPDATE providers SET brand_key = 'hunyuan'     WHERE brand_key IS NULL AND base_url LIKE '%api.hunyuan.cloud.tencent.com%';
UPDATE providers SET brand_key = 'zhipu'       WHERE brand_key IS NULL AND base_url LIKE '%open.bigmodel.cn%';
UPDATE providers SET brand_key = 'doubao'      WHERE brand_key IS NULL AND base_url LIKE '%ark.cn-beijing.volces.com%';
UPDATE providers SET brand_key = 'wenxin'      WHERE brand_key IS NULL AND base_url LIKE '%qianfan.baidubce.com%';
UPDATE providers SET brand_key = 'mimo'        WHERE brand_key IS NULL AND base_url LIKE '%api.xiaomimimo.com%';
UPDATE providers SET brand_key = 'stepfun'     WHERE brand_key IS NULL AND base_url LIKE '%api.stepfun.com%';
UPDATE providers SET brand_key = 'siliconflow' WHERE brand_key IS NULL AND base_url LIKE '%api.siliconflow.cn%';

-- ---------------- 本地（只认回环地址 + 默认端口；内网其它机器不算「本地品牌」） ----------------
UPDATE providers SET brand_key = 'ollama'   WHERE brand_key IS NULL AND (base_url LIKE '%127.0.0.1:11434%' OR base_url LIKE '%localhost:11434%');
UPDATE providers SET brand_key = 'vllm'     WHERE brand_key IS NULL AND (base_url LIKE '%127.0.0.1:8000%' OR base_url LIKE '%localhost:8000%');
UPDATE providers SET brand_key = 'lmstudio' WHERE brand_key IS NULL AND (base_url LIKE '%127.0.0.1:1234%' OR base_url LIKE '%localhost:1234%');

-- ---------------- 名字兜底（地址被改过 / 代理地址） ----------------
UPDATE providers SET brand_key = 'deepseek' WHERE brand_key IS NULL AND LOWER(TRIM(name)) IN ('deepseek');
UPDATE providers SET brand_key = 'claude'   WHERE brand_key IS NULL AND LOWER(TRIM(name)) IN ('claude', 'anthropic');
UPDATE providers SET brand_key = 'kimi'     WHERE brand_key IS NULL AND LOWER(TRIM(name)) IN ('kimi', 'moonshot');
UPDATE providers SET brand_key = 'qwen'     WHERE brand_key IS NULL AND LOWER(TRIM(name)) IN ('qwen', '通义', '通义千问', '千问', 'dashscope');
UPDATE providers SET brand_key = 'doubao'   WHERE brand_key IS NULL AND LOWER(TRIM(name)) IN ('doubao', '豆包', '火山方舟');
UPDATE providers SET brand_key = 'ollama'   WHERE brand_key IS NULL AND LOWER(TRIM(name)) IN ('ollama');
UPDATE providers SET brand_key = 'opencode' WHERE brand_key IS NULL AND LOWER(TRIM(name)) IN ('opencode', 'oc', 'oc2', 'oc_new', 'oc-new');
UPDATE providers SET brand_key = 'minimax'  WHERE brand_key IS NULL AND LOWER(TRIM(name)) IN ('minimax');
UPDATE providers SET brand_key = 'openai'   WHERE brand_key IS NULL AND LOWER(TRIM(name)) IN ('openai', 'chatgpt', 'openai-订阅');
UPDATE providers SET brand_key = 'gemini'   WHERE brand_key IS NULL AND LOWER(TRIM(name)) IN ('gemini', 'google gemini');
UPDATE providers SET brand_key = 'openrouter' WHERE brand_key IS NULL AND LOWER(TRIM(name)) IN ('openrouter');
UPDATE providers SET brand_key = 'groq'     WHERE brand_key IS NULL AND LOWER(TRIM(name)) IN ('groq');
UPDATE providers SET brand_key = 'xai'      WHERE brand_key IS NULL AND LOWER(TRIM(name)) IN ('xai', 'grok');
UPDATE providers SET brand_key = 'mistral'  WHERE brand_key IS NULL AND LOWER(TRIM(name)) IN ('mistral');
UPDATE providers SET brand_key = 'hunyuan'  WHERE brand_key IS NULL AND LOWER(TRIM(name)) IN ('hunyuan', '混元', '腾讯混元');
UPDATE providers SET brand_key = 'zhipu'    WHERE brand_key IS NULL AND LOWER(TRIM(name)) IN ('zhipu', 'glm', '智谱', '智谱glm');
UPDATE providers SET brand_key = 'wenxin'   WHERE brand_key IS NULL AND LOWER(TRIM(name)) IN ('wenxin', 'ernie', '文心', '百度文心');
UPDATE providers SET brand_key = 'mimo'     WHERE brand_key IS NULL AND LOWER(TRIM(name)) IN ('mimo', '小米', 'xiaomi');
UPDATE providers SET brand_key = 'stepfun'  WHERE brand_key IS NULL AND LOWER(TRIM(name)) IN ('stepfun', '阶跃', '阶跃星辰');
UPDATE providers SET brand_key = 'siliconflow' WHERE brand_key IS NULL AND LOWER(TRIM(name)) IN ('siliconflow', '硅基流动');
UPDATE providers SET brand_key = 'vllm'     WHERE brand_key IS NULL AND LOWER(TRIM(name)) IN ('vllm');
UPDATE providers SET brand_key = 'lmstudio' WHERE brand_key IS NULL AND LOWER(TRIM(name)) IN ('lmstudio', 'lm studio');
