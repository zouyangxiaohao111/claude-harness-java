//! NexusAI 自动更新（自研轻量，非 plugin-updater）。
//!
//! 清单 latest.json（每源根）：{ version, notes, published, platforms: {
//!   "windows-x86_64": { url, sha256 } } }。
//! 多源顺序尝试（默认内网 MinIO → GitHub release），sha256 校验替代 minisign（免私钥管理）。
//! 命令：update_check( currentVersion, sources? ) / update_download( info ) / update_install( path )
//! 事件：update://found / update://progress / update://installing / update://error

use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::io::Read;
use std::time::Duration;
use tauri::{AppHandle, Emitter};

/// 默认源（内网 MinIO 桶 nexusai/updater · GitHub release latest.json）
pub const DEFAULT_SOURCES: [&str; 2] = [
    "http://192.168.20.125:9000/nexusai/updater/latest.json",
    "https://github.com/zouyangxiaohao111/claude-harness-java/releases/latest/download/latest.json",
];

#[derive(Serialize, Deserialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct UpdateInfo {
    pub available: bool,
    pub version: String,
    pub notes: String,
    pub published: Option<String>,
    pub url: String,
    pub sha256: String,
    pub source: String,
}

#[derive(Deserialize)]
struct Manifest {
    version: String,
    #[serde(default)] notes: String,
    #[serde(default)] published: Option<String>,
    platforms: serde_json::Value,
}

/// 版本语义比较：0.1.5 > 0.1.4 → 返回是否有可用更新（严格大于）。
fn newer(remote: &str, current: &str) -> bool {
    let a = parse(remote);
    let b = parse(current);
    for i in 0..a.len().max(b.len()) {
        let x = a.get(i).copied().unwrap_or(0);
        let y = b.get(i).copied().unwrap_or(0);
        if x != y {
            return x > y;
        }
    }
    false
}
fn parse(v: &str) -> Vec<u32> {
    v.split(|c: char| !c.is_ascii_digit())
        .filter(|s| !s.is_empty())
        .filter_map(|s| s.parse().ok())
        .collect()
}

fn http() -> ureq::Agent {
    ureq::AgentBuilder::new()
        .timeout(Duration::from_secs(15))
        .build()
}

#[tauri::command]
pub fn update_check(
    current_version: String,
    sources: Option<Vec<String>>,
    app: AppHandle,
) -> Result<UpdateInfo, String> {
    let list = sources.unwrap_or_else(|| DEFAULT_SOURCES.iter().map(|s| s.to_string()).collect());
    let mut last_err: Option<String> = None;
    for src in list {
        let info = fetch_one(&src, &current_version);
        match info {
            Ok(mut found) => {
                found.source = src.clone();
                let _ = app.emit("update://found", &found);
                return Ok(found);
            }
            Err(e) => {
                last_err = Some(format!("{src} → {e}"));
            }
        }
    }
    Err(last_err.unwrap_or_else(|| "无更新源".into()))
}

fn fetch_one(src: &str, current: &str) -> Result<UpdateInfo, String> {
    let resp = http()
        .get(src)
        .call()
        .map_err(|e| e.to_string())?;
    let text = resp
        .into_string()
        .map_err(|e| format!("读取清单失败: {e}"))?;
    let m: Manifest = serde_json::from_str(&text).map_err(|e| format!("清单解析失败: {e}"))?;
    if !newer(&m.version, current) {
        return Ok(UpdateInfo {
            available: false,
            version: m.version,
            notes: String::new(),
            published: None,
            url: String::new(),
            sha256: String::new(),
            source: src.to_string(),
        });
    }
    let win = m
        .platforms
        .get("windows-x86_64")
        .ok_or("清单缺少 windows-x86_64".to_string())?;
    let url = win
        .get("url")
        .and_then(|v| v.as_str())
        .ok_or("缺少下载 url".to_string())?
        .to_string();
    let sha256 = win
        .get("sha256")
        .and_then(|v| v.as_str())
        .unwrap_or("")
        .to_string();
    Ok(UpdateInfo {
        available: true,
        version: m.version,
        notes: m.notes,
        published: m.published,
        url,
        sha256,
        source: src.to_string(),
    })
}

#[tauri::command]
pub fn update_download(info: UpdateInfo, app: AppHandle) -> Result<String, String> {
    let dir = std::env::temp_dir().join("nexusai-update");
    std::fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
    let path = dir.join(format!("NexusAI_{}_x64-setup.exe", info.version));
    let resp = http()
        .get(&info.url)
        .call()
        .map_err(|e| format!("下载失败: {e}"))?;
    let total = resp
        .header("Content-Length")
        .and_then(|v| v.parse::<u64>().ok());
    let mut src = resp.into_reader();
    let mut out = std::io::BufWriter::new(
        std::fs::File::create(&path).map_err(|e| e.to_string())?,
    );
    let mut hasher = Sha256::new();
    let mut buf = [0u8; 64 * 1024];
    let mut done: u64 = 0;
    loop {
        let n = src.read(&mut buf).map_err(|e| e.to_string())?;
        if n == 0 {
            break;
        }
        hasher.update(&buf[..n]);
        std::io::Write::write_all(&mut out, &buf[..n]).map_err(|e| e.to_string())?;
        done += n as u64;
        let _ = app.emit("update://progress", serde_json::json!({ "done": done, "total": total }));
    }
    std::io::Write::flush(&mut out).map_err(|e| e.to_string())?;
    let hex = hex_digest(&hasher.finalize());
    if !info.sha256.is_empty() && !hex.eq_ignore_ascii_case(&info.sha256) {
        let _ = std::fs::remove_file(&path);
        return Err(format!("sha256 校验失败（期望 {} 实得 {}）", info.sha256, hex));
    }
    let _ = app.emit("update://ready", serde_json::json!({ "path": path.to_string_lossy() }));
    Ok(path.to_string_lossy().into_owned())
}

#[tauri::command]
pub fn app_version() -> String {
    env!("CARGO_PKG_VERSION").to_string()
}

#[tauri::command]
pub fn update_install(path: String, app: AppHandle) -> Result<(), String> {
    let _ = app.emit("update://installing", ());
    // 运行安装包（暂用带向导模式便于可见/调试；定稿可切 /S 静默覆盖，保留数据目录）。
    // spawn 后由前端短暂延迟关窗退出，安装器接管（UAC 或向导会接管前台）。
    std::process::Command::new(&path)
        .spawn()
        .map_err(|e| format!("启动安装器失败: {e}"))?;
    Ok(())
}

fn hex_digest(d: &[u8]) -> String {
    d.iter().map(|b| format!("{b:02x}")).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn newer_semver() {
        assert!(newer("0.1.5", "0.1.4"));
        assert!(newer("0.1.10", "0.1.9"));
        assert!(newer("0.2.0", "0.1.99"));
        assert!(!newer("0.1.4", "0.1.4"));
        assert!(!newer("0.1.4", "0.1.5"));
        assert!(!newer("0.1.4-rc", "0.1.4")); // 预发布前缀不被解析为更大
    }

    #[test]
    fn parse_digits() {
        assert_eq!(parse("0.1.5"), vec![0, 1, 5]);
        assert_eq!(parse("v1.2.3-beta.2"), vec![1, 2, 3, 2]);
    }
}
