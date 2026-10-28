//! 启动错误上报（仅内网包启用）。
//!
//! <p>触发点：LaunchGate 的「本地引擎未能启动」错误卡。用户勾选同意并点击「同意并上报」后，
//! 前端调用 [`report_startup_error`]；**不点不上报**。
//!
//! <p>上报内容（用户裁定 2026-10-28）：`backend.log` + `tauri-launcher.log` + 版本/OS/安装路径/时间，
//! **不含** `info.log` / `error.log`（可能含会话内容）。
//!
//! <p>凭据：只写 MinIO key（仅 `s3:PutObject` 到 `nexusai/error-reports/*`），由编译期环境变量
//! `NEXUSAI_ERROR_REPORT_CONFIG` 注入。GitHub 包不设置该变量 ⇒ [`report_capability`] 返回 false，
//! 前端隐藏上报按钮。⛔ 不得把内网 key 打进 GitHub 包。
//!
//! <p>实现：S3 SigV4 单次 PUT（`ureq` + `hmac` + `sha2`），不依赖 `mc`/外部 SDK。

use chrono::Utc;
use hmac::{Hmac, Mac};
use serde::Deserialize;
use sha2::{Digest, Sha256};
use std::io::{Read, Seek, SeekFrom};
use std::path::Path;
use tauri::AppHandle;

type HmacSha256 = Hmac<Sha256>;

// 编译期内网配置（JSON）：由 scripts/prepare-error-report.mjs 在构建前写入同目录的
// error_report_config.rs（默认 None；内网构建写成 Some(JSON)）。
// ⛔ 不要改回 option_env!/build.rs：Tauri CLI 调 cargo 时环境变量传不进构建，
//    2026-10-28 实测内网包会静默变成「未配置上报」。
include!("error_report_config.rs");

#[derive(Deserialize, Clone)]
#[serde(rename_all = "camelCase")]
struct ErrorReportConfig {
    endpoint: String,
    bucket: String,
    #[serde(default)]
    prefix: String,
    #[serde(default = "default_region")]
    region: String,
    access_key: String,
    secret_key: String,
}

fn default_region() -> String {
    "us-east-1".to_string()
}

impl ErrorReportConfig {
    fn usable(&self) -> bool {
        !self.endpoint.trim().is_empty()
            && !self.bucket.trim().is_empty()
            && !self.access_key.trim().is_empty()
            && !self.secret_key.trim().is_empty()
    }

    fn prefix(&self) -> String {
        let p = self.prefix.trim().trim_matches('/');
        if p.is_empty() {
            "error-reports".to_string()
        } else {
            p.to_string()
        }
    }
}

fn config() -> Option<ErrorReportConfig> {
    CONFIG_JSON
        .and_then(|s| serde_json::from_str::<ErrorReportConfig>(s).ok())
        .filter(ErrorReportConfig::usable)
}

/// 当前构建是否配置了上报（前端据此决定是否显示「同意并上报」）。
#[tauri::command]
pub fn report_capability() -> bool {
    config().is_some()
}

/// 组装报告并上传。只有前端显式调用（用户勾选同意后）才会走到这里。
#[tauri::command]
pub async fn report_startup_error(app: AppHandle, message: String) -> Result<String, String> {
    tauri::async_runtime::spawn_blocking(move || upload_startup_report(&app, &message))
        .await
        .map_err(|e| format!("上报任务失败: {e}"))?
}

fn upload_startup_report(app: &AppHandle, message: &str) -> Result<String, String> {
    let cfg = config().ok_or_else(|| "当前版本未配置错误上报（内网包才启用）".to_string())?;
    let data_dir = crate::backend::data_dir();
    let logs_dir = data_dir.join("logs");
    let backend_log = tail_text(&logs_dir.join("backend.log"), 128 * 1024);
    let launcher_log = tail_text(&logs_dir.join("tauri-launcher.log"), 128 * 1024);

    let report = serde_json::json!({
        "schema": 1,
        "kind": "startup-error",
        "appVersion": app.package_info().version.to_string(),
        "os": std::env::consts::OS,
        "arch": std::env::consts::ARCH,
        "timestamp": Utc::now().to_rfc3339(),
        "installPath": redact_user(std::env::current_exe()
            .ok()
            .map(|p| p.display().to_string())
            .unwrap_or_default()),
        "dataDir": redact_user(data_dir.display().to_string()),
        "message": redact_user(message.to_string()),
        "logs": {
            "backend.log": backend_log,
            "tauri-launcher.log": launcher_log
        }
    });
    let body = serde_json::to_vec_pretty(&report)
        .map_err(|e| format!("序列化错误报告失败: {e}"))?;

    let now = Utc::now();
    let key = format!(
        "{}/startup/{}/report-{}-{}.json",
        cfg.prefix(),
        now.format("%Y%m%d"),
        now.timestamp_millis(),
        std::process::id()
    );
    put_object(&cfg, &key, &body)?;
    Ok(key)
}

/// 读取文件尾部（最多 `max` 字节），去 NUL，并把当前用户名脱敏成 `<user>`。
fn tail_text(path: &Path, max: usize) -> String {
    let mut f = match std::fs::File::open(path) {
        Ok(f) => f,
        Err(_) => return String::new(),
    };
    let len = f.metadata().map(|m| m.len()).unwrap_or(0);
    let start = len.saturating_sub(max as u64);
    if f.seek(SeekFrom::Start(start)).is_err() {
        return String::new();
    }
    let mut buf = Vec::with_capacity(max.min(len as usize));
    if f.take(max as u64).read_to_end(&mut buf).is_err() {
        return String::new();
    }
    redact_user(String::from_utf8_lossy(&buf).replace('\0', ""))
}

/// 把当前用户名替换成 `<user>`（路径/日志里常见；不影响排障）。
fn redact_user(text: String) -> String {
    if let Ok(user) = std::env::var("USERNAME").or_else(|_| std::env::var("USER")) {
        if !user.is_empty() {
            return text.replace(&user, "<user>");
        }
    }
    text
}

/// S3 SigV4 单次 PUT。
fn put_object(cfg: &ErrorReportConfig, key: &str, body: &[u8]) -> Result<(), String> {
    let endpoint = cfg.endpoint.trim_end_matches('/');
    let url = format!("{}/{}/{}", endpoint, cfg.bucket, key);
    let host = endpoint
        .split("://")
        .nth(1)
        .unwrap_or(endpoint)
        .split('/')
        .next()
        .unwrap_or_default()
        .to_string();
    if host.is_empty() {
        return Err(format!("endpoint 非法: {}", cfg.endpoint));
    }

    let now = Utc::now();
    let amz_date = now.format("%Y%m%dT%H%M%SZ").to_string();
    let date = now.format("%Y%m%d").to_string();
    let payload_hash = hex(&Sha256::digest(body));
    let canonical_uri = format!("/{}/{}", cfg.bucket, key);
    let canonical_headers = format!(
        "host:{host}\nx-amz-content-sha256:{payload_hash}\nx-amz-date:{amz_date}\n"
    );
    let signed_headers = "host;x-amz-content-sha256;x-amz-date";
    let canonical_request = format!(
        "PUT\n{canonical_uri}\n\n{canonical_headers}\n{signed_headers}\n{payload_hash}"
    );
    let scope = format!("{date}/{}/s3/aws4_request", cfg.region);
    let string_to_sign = format!(
        "AWS4-HMAC-SHA256\n{amz_date}\n{scope}\n{}",
        hex(&Sha256::digest(canonical_request.as_bytes()))
    );

    let k_date = hmac_sha256(format!("AWS4{}", cfg.secret_key).as_bytes(), date.as_bytes());
    let k_region = hmac_sha256(&k_date, cfg.region.as_bytes());
    let k_service = hmac_sha256(&k_region, b"s3");
    let k_signing = hmac_sha256(&k_service, b"aws4_request");
    let signature = hex(&hmac_sha256(&k_signing, string_to_sign.as_bytes()));
    let authorization = format!(
        "AWS4-HMAC-SHA256 Credential={}/{scope}, SignedHeaders={signed_headers}, Signature={signature}",
        cfg.access_key
    );

    match ureq::put(&url)
        .set("x-amz-content-sha256", &payload_hash)
        .set("x-amz-date", &amz_date)
        .set("Authorization", &authorization)
        .set("Content-Type", "application/json")
        .send_bytes(body)
    {
        Ok(resp) if (200..300).contains(&resp.status()) => Ok(()),
        Ok(resp) => Err(format!(
            "MinIO 返回 {}: {}",
            resp.status(),
            resp.into_string().unwrap_or_default()
        )),
        Err(ureq::Error::Status(code, resp)) => Err(format!(
            "MinIO 返回 {code}: {}",
            resp.into_string().unwrap_or_default()
        )),
        Err(e) => Err(format!("上传失败: {e}")),
    }
}

fn hmac_sha256(key: &[u8], data: &[u8]) -> Vec<u8> {
    let mut mac = HmacSha256::new_from_slice(key).expect("HMAC 支持任意 key 长度");
    mac.update(data);
    mac.finalize().into_bytes().to_vec()
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    /// 手动联调用：设置 `NEXUSAI_ERROR_REPORT_CONFIG` 后
    /// `cargo test --bin nexusai-client upload_live -- --ignored --nocapture`。
    #[test]
    #[ignore]
    fn upload_live() {
        let cfg = config().expect("需要 NEXUSAI_ERROR_REPORT_CONFIG");
        put_object(&cfg, "error-reports/startup/test/report-probe.json", br#"{"probe":true}"#)
            .expect("上传探测对象失败");
        println!("uploaded error-reports/startup/test/report-probe.json");
    }
}
