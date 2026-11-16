-- ===================================================================
-- V298: settings —— LLM 出站 HTTP 代理（ChatGPT 登录 + 对话专用）
--
-- 背景（2026-11-16）：
--   用户挂本地代理（Clash 等）访问 openai.com / chatgpt.com / anthropic.com 时，
--   此前只能靠环境变量 HTTPS_PROXY / HTTP_PROXY 生效 —— 设置页改不了、也不直观。
--   本轮把代理做成「模型提供商 → 官方卡片」里的可配置项：存 DB，
--   保存后下一次出站请求即生效（不重启，OutboundProxy 每请求实时读本列）。
--
-- 约定：
--   * ⛔ 本列只给 LLM 出站用（OutboundProxy / OpenAI SDK / ChatGPT OAuth token 端点）。
--     WebSearch / WebFetch 不读本列，它们走各自的 settings.proxy，两条通道互不影响。
--   * 列值形如 http://host:port（同样接受 host:port）；NULL / 空串 = 未配置 → 直连
--     （出站时若有环境变量再回落环境变量，见 OutboundProxy.endpoint()）。
--   * settings 是单行宽表（id=1），与其它 settings 列一致，不加 DEFAULT。
-- ===================================================================

ALTER TABLE settings ADD COLUMN http_proxy TEXT;
