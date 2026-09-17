//! NexusAI 自动更新（自研轻量，非 plugin-updater）。
//!
//! 清单 latest.json（每源根）：{ version, notes, published, platforms: {
//!   "windows-x86_64": { url, sha256 } } }。
//! 多源顺序尝试（NEXUSAI_UPDATER_SOURCES / 内置默认 → GitHub release），sha256 校验替代 minisign（免私钥管理）。
//! 命令：update_check( currentVersion, sources? ) / update_download( info ) / update_install( path )
//! 事件：update://found / update://progress / update://installing / update://error

use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::io::Read;
use std::time::Duration;
use tauri::{AppHandle, Emitter};

/// 默认源（内网 MinIO 主源 + 公开 GitHub release latest.json 外网兜底）。
///
/// 主源为内网地址（可达时秒回）；部署时仍可用环境变量 `NEXUSAI_UPDATER_SOURCES`
/// （逗号分隔的 latest.json 地址）覆盖，或由前端 `update_check` 命令的 `sources` 参数显式传入。
pub const DEFAULT_SOURCES: [&str; 2] = [
    "http://192.168.20.125:9000/nexusai/updater/latest.json", // 内网主源（秒回）
    "https://github.com/zouyangxiaohao111/claude-harness-java/releases/latest/download/latest.json", // 外网兜底
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

/// 阻塞式 HTTP agent：`timeout` 是**整请求总超时**（含 DNS、连接、读取三段，非每段各计时）。
fn http() -> ureq::Agent {
    ureq::AgentBuilder::new()
        .timeout(Duration::from_secs(3))
        .build()
}

#[tauri::command]
pub async fn update_check(
    current_version: String,
    sources: Option<Vec<String>>,
    app: AppHandle,
) -> Result<UpdateInfo, String> {
    // ureq 是阻塞式 HTTP：必须放到阻塞线程池，否则会占住 Tauri 命令处理线程，
    // 期间所有其他 IPC（如「添加工作区」的文件夹选择框）全部排队。
    let handle = tauri::async_runtime::spawn_blocking(move || -> Result<UpdateInfo, String> {
        let list = resolve_sources(sources);
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
    });
    handle
        .await
        .map_err(|e| format!("更新检查任务失败: {e}"))?
}

/// 更新源优先级：命令显式传入 > 环境变量 `NEXUSAI_UPDATER_SOURCES`（逗号分隔）> 内置默认。
fn resolve_sources(sources: Option<Vec<String>>) -> Vec<String> {
    if let Some(list) = sources.filter(|l| !l.is_empty()) {
        return list;
    }
    if let Ok(raw) = std::env::var("NEXUSAI_UPDATER_SOURCES") {
        let list = parse_sources(&raw);
        if !list.is_empty() {
            return list;
        }
    }
    DEFAULT_SOURCES.iter().map(|s| s.to_string()).collect()
}

/// 逗号分隔 → 去空白/去空项（`"a, b ,,"` → `["a","b"]`）。
fn parse_sources(raw: &str) -> Vec<String> {
    raw.split(',')
        .map(|s| s.trim())
        .filter(|s| !s.is_empty())
        .map(|s| s.to_string())
        .collect()
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
pub async fn update_download(info: UpdateInfo, app: AppHandle) -> Result<String, String> {
    // 安装包体积大（~179MB），下载可达数分钟：同样下放到阻塞线程池，避免占住 Tauri 命令处理线程。
    let handle = tauri::async_runtime::spawn_blocking(move || -> Result<String, String> {
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
    });
    handle
        .await
        .map_err(|e| format!("下载任务失败: {e}"))?
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

    #[test]
    fn parse_sources_splits_and_trims() {
        assert_eq!(parse_sources("a, b ,,c"), vec!["a", "b", "c"]);
        assert!(parse_sources("  ").is_empty());
    }

    /// 主源必须是内网 MinIO（秒回），兜底才是 GitHub。
    /// 防回归：曾经为了「地址不外泄」被改成 `your-update-host.example` 占位符，
    /// 结果每次检查都要等外网超时；用户已裁定写死内网地址，此处钉住顺序与内容。
    #[test]
    fn default_sources_internal_minio_first() {
        assert_eq!(DEFAULT_SOURCES.len(), 2);
        assert!(
            DEFAULT_SOURCES[0].contains("192.168.20.125"),
            "主源必须是内网 MinIO，实得 {}",
            DEFAULT_SOURCES[0]
        );
        assert!(
            DEFAULT_SOURCES[1].contains("github.com"),
            "兜底源必须是 GitHub，实得 {}",
            DEFAULT_SOURCES[1]
        );
    }

    /// `updater.rs` 的**生产段**源码（已切掉 `#[cfg(test)]` 及其后全部内容）。
    ///
    /// ⚠️ `include_str!` 返回的文本**包含本测试模块自身**。若直接对整文件断言，
    /// 下面断言里的字符串字面量会匹配到它们自己 ⇒ 守护被拆掉也照样绿（假绿）。
    /// 必须先切掉测试段，这是本函数存在的唯一理由。
    fn prod_src() -> &'static str {
        include_str!("updater.rs")
            .split("#[cfg(test)]")
            .next()
            .expect("updater.rs 必有生产段")
    }

    /// 截出 `decl`（如 `pub async fn update_check`）的函数体文本（含签名）。
    /// 按 `{}` 配对截取；若签名不是 `decl`（例如被改回同步 `pub fn`）返回 `None`。
    fn fn_body<'a>(src: &'a str, decl: &str) -> Option<&'a str> {
        let start = src.find(decl)?;
        let open = start + src[start..].find('{')?;
        let mut depth = 0usize;
        for (i, c) in src[open..].char_indices() {
            match c {
                '{' => depth += 1,
                '}' => {
                    depth -= 1;
                    if depth == 0 {
                        return Some(&src[start..open + i + 1]);
                    }
                }
                _ => {}
            }
        }
        None
    }

    /// T3 守护（`update_check`）：必须把阻塞的 ureq 调用放进 blocking 线程池。
    ///
    /// 要防住的反例：拆掉 `spawn_blocking` 外壳、直接执行闭包体，
    /// **但保留 `pub async fn` ⇒ 编译照样通过**，而「点添加工作区卡顿」会原样复活。
    /// 【本测试验证意图，非行为】：`#[tauri::command]` 单测里跑不起来（需 App 运行时），
    /// 所以只能做源码级断言 —— 先例见 `main.rs` 的 `close_hook_must_route_through_reclaim_target`。
    #[test]
    fn update_check_must_run_on_blocking_pool() {
        let body = fn_body(prod_src(), "pub async fn update_check").expect(
            "update_check 必须保持 `pub async fn`（改回同步 fn 会占住 Tauri 命令处理线程）",
        );
        // ⚠️ 断言用 `spawn_blocking(`（带左括号）而非 `spawn_blocking`：
        //    后者是纯子串匹配，把调用点改名成 `spawn_blockingX(` 仍会命中 ⇒ 假绿。
        assert!(
            body.contains("spawn_blocking("),
            "update_check 必须经 tauri::async_runtime::spawn_blocking 执行。\
             ureq 是阻塞式 HTTP，同步跑会占住 Tauri 命令处理线程，\
             期间所有其他 IPC 排队（2026-09-17 用户实测「点添加工作区卡顿」）。"
        );
    }

    /// T3 守护（`update_download`）：同型但更严重 —— 179MB 安装包下载可达数分钟。
    #[test]
    fn update_download_must_run_on_blocking_pool() {
        let body = fn_body(prod_src(), "pub async fn update_download").expect(
            "update_download 必须保持 `pub async fn`（改回同步 fn 会占住 Tauri 命令处理线程）",
        );
        assert!(
            body.contains("spawn_blocking("),
            "update_download 必须经 tauri::async_runtime::spawn_blocking 执行。\
             同步跑期间整个 UI 的 Tauri IPC 都会卡（下载耗时数分钟）。"
        );
    }
}
