// 由 scripts/prepare-error-report.mjs 在构建前生成/覆盖。
// 默认 None（GitHub 包）；内网构建会写成 Some(只写 key 的 JSON)。
// ⚠️ 内网构建会临时修改本文件；打包后必须恢复为 None（release-publish 会检查 app exe）。
pub const CONFIG_JSON: Option<&str> = None;
