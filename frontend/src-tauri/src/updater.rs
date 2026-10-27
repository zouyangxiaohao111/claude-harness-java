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

/// 下载专用 agent：⛔ 不能用 `http()` 的 3s **整请求总超时** —— 179MB 安装包必然超时。
/// 连接 15s、单次 read 120s（连接停滞才中断）；总时长不设上限。
fn http_download() -> ureq::Agent {
    ureq::AgentBuilder::new()
        .timeout_connect(Duration::from_secs(15))
        .timeout_read(Duration::from_secs(120))
        .build()
}

/// 解析 `Content-Range: bytes 100-199/2000` 的总长度；通配 `*` / 非法 → None。
fn content_range_total(value: &str) -> Option<u64> {
    value.rsplit('/').next()?.trim().parse::<u64>().ok()
}

/// 对最终落盘文件做 SHA-256（续传后必须覆盖完整文件，不能只哈希本次新增字节）。
fn sha256_file(path: &std::path::Path) -> Result<String, String> {
    let mut file = std::fs::File::open(path).map_err(|e| e.to_string())?;
    let mut hasher = Sha256::new();
    let mut buf = [0u8; 64 * 1024];
    loop {
        let n = file.read(&mut buf).map_err(|e| e.to_string())?;
        if n == 0 {
            break;
        }
        hasher.update(&buf[..n]);
    }
    Ok(hex_digest(&hasher.finalize()))
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

        // 断点续传：上次失败留下的完整/半截文件直接 Range 续传；服务端忽略 Range 则从头覆盖。
        let mut existing = std::fs::metadata(&path).map(|m| m.len()).unwrap_or(0);
        let mut retried = false;
        let (resp, append, offset) = loop {
            let req = if existing > 0 {
                http_download()
                    .get(&info.url)
                    .set("Range", &format!("bytes={existing}-"))
            } else {
                http_download().get(&info.url)
            };
            match req.call() {
                Ok(r) => {
                    let append = existing > 0 && r.status() == 206;
                    let offset = if append { existing } else { 0 };
                    break (r, append, offset);
                }
                Err(ureq::Error::Status(416, _)) if existing > 0 && !retried => {
                    // 残片已到末尾（或资源已换）：清残片后从 0 重下一次
                    let _ = std::fs::remove_file(&path);
                    existing = 0;
                    retried = true;
                }
                Err(e) => return Err(format!("下载失败: {e}")),
            }
        };

        let total = resp
            .header("Content-Range")
            .and_then(content_range_total)
            .or_else(|| {
                resp.header("Content-Length")
                    .and_then(|v| v.parse::<u64>().ok())
                    .map(|len| len + offset)
            });
        let mut src = resp.into_reader();
        let mut out = std::io::BufWriter::new(if append {
            std::fs::OpenOptions::new()
                .append(true)
                .open(&path)
                .map_err(|e| e.to_string())?
        } else {
            std::fs::File::create(&path).map_err(|e| e.to_string())?
        });
        let mut buf = [0u8; 64 * 1024];
        let mut done: u64 = offset;
        loop {
            let n = src.read(&mut buf).map_err(|e| e.to_string())?;
            if n == 0 {
                break;
            }
            std::io::Write::write_all(&mut out, &buf[..n]).map_err(|e| e.to_string())?;
            done += n as u64;
            let _ = app.emit("update://progress", serde_json::json!({ "done": done, "total": total }));
        }
        std::io::Write::flush(&mut out).map_err(|e| e.to_string())?;

        // ⛔ 必须哈希**最终文件**：续传时只哈希本次新增字节会漏掉前半段。
        let hex = sha256_file(&path)?;
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

/// 当前应用版本（更新检查的「当前版本」基准）。
///
/// <p>⛔ <b>必须读 `package_info().version`（= `tauri.conf.json` 的 `version`），不得读 `env!("CARGO_PKG_VERSION")`</b>。
///
/// <p><b>WHY（2026-09-17 用户实测）</b>：本函数原先读 `CARGO_PKG_VERSION`，即 `Cargo.toml` 的 `version`。
/// 而**安装包版本由 `tauri.conf.json` 的 `version` 决定**（tauri-codegen `context.rs:273-278`：
/// 有 `config.version` 就用它，没有才回退 `CARGO_PKG_VERSION`）。本仓 `Cargo.toml` 长期停在 `0.1.6`、
/// `tauri.conf.json` 一路走到 `0.1.10` ⇒ 应用**自报 `0.1.6`**、远端 `latest.json` 是 `0.1.10`
/// ⇒ `newer("0.1.10", "0.1.6") == true` ⇒ **无论装到哪个版本，更新提示永远不消失**。
/// （这也是用户早前「装了新版还提示更新」的真正根因 —— 当时误归因为 CDN 缓存。）
///
/// <p>改读 `package_info()` 后，自报版本与安装包**永远同源**，`Cargo.toml` 再漂移也不会影响比版本。
/// 另见 `cargo_toml_version_matches_tauri_conf` 测试：它钉住「两个版本文件必须一致」，防再次漂移。
#[tauri::command]
pub fn app_version(app: tauri::AppHandle) -> String {
    app.package_info().version.to_string()
}

/// 静默安装命令行（Windows NSIS）：延迟约 2.5s → `/S` 无向导覆盖安装 → `/R` 装完自动重启。
///
/// <p>延迟的目的：让前端 `getCurrentWindow().close()` 先走 `main.rs` 的正常关窗路径
/// （`CloseRequested` → `kill_process_tree(backend_pid)` 回收 Java 后端）。否则 NSIS 静默模式
/// 会直接 kill 主进程，后端仍占用 `backend/nexusai-backend.jar` ⇒ 覆盖安装失败。
fn installer_command_line(path: &str, app_exe: &str) -> String {
    let installer = path.replace('"', "");
    let app = app_exe.replace('"', "");
    if app.is_empty() {
        // 取不到当前 exe（极罕见）：至少完成安装，不尝试重启
        format!("ping -n 4 127.0.0.1 >NUL & start \"\" /wait \"{installer}\" /S")
    } else {
        // ⛔ 不给安装器传 `/R`：它的 RunAsUser 重启分支在部分机器上会报
        //    「Windows 找不到 '\\' 文件」（2026-10-27 用户实测）。
        //    改为 `start /wait` 等安装器退出后，由本命令自己启动当前 exe。
        format!(
            "ping -n 4 127.0.0.1 >NUL & start \"\" /wait \"{installer}\" /S & start \"\" \"{app}\""
        )
    }
}

#[cfg(target_os = "windows")]
fn spawn_silent_installer(path: &str) -> Result<(), String> {
    let app_exe = std::env::current_exe()
        .map(|p| p.to_string_lossy().into_owned())
        .unwrap_or_default();
    crate::backend::silent_command("cmd")
        .args(["/C", &installer_command_line(path, &app_exe)])
        .spawn()
        .map(|_| ())
        .map_err(|e| format!("启动静默安装器失败: {e}"))
}

#[cfg(not(target_os = "windows"))]
fn spawn_silent_installer(path: &str) -> Result<(), String> {
    std::process::Command::new(path)
        .arg("-S")
        .spawn()
        .map(|_| ())
        .map_err(|e| format!("启动安装器失败: {e}"))
}

#[tauri::command]
pub fn update_install(path: String, app: AppHandle) -> Result<(), String> {
    let _ = app.emit("update://installing", ());
    // ⭐ 无感升级：静默覆盖安装（/S）；安装器退出后由 cmd 包装器重启当前 exe。
    // 数据目录（~/.nexusai）与安装目录分离，覆盖安装不影响用户数据。
    spawn_silent_installer(&path)
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

    /// ⭐ 守护：`app_version` 必须读**安装包版本**（`package_info().version`），
    /// ⛔ 不得读 `env!("CARGO_PKG_VERSION")`（= `Cargo.toml` 的 version）。
    ///
    /// <p><b>WHY（2026-09-17 用户实测）</b>：安装包版本由 `tauri.conf.json` 决定
    /// （tauri-codegen `context.rs:273-278`：有 `config.version` 就用它，没有才回退 `CARGO_PKG_VERSION`）；
    /// 而 `Cargo.toml` 曾长期停在 `0.1.6`、`tauri.conf.json` 一路走到 `0.1.10`
    /// ⇒ 应用自报 `0.1.6`、远端 `0.1.10` ⇒ **更新提示永远不消失**（用户连着两个版本都遇到）。
    /// 这条守护让「有人把它改回去」立刻变红。
    #[test]
    fn app_version_must_use_package_info_not_cargo_pkg_version() {
        let body = fn_body(prod_src(), "pub fn app_version")
            .expect("app_version 必须存在（前端经 invoke('app_version') 取「当前版本」）");
        assert!(
            body.contains("package_info()"),
            "app_version 必须读安装包版本 `app.package_info().version`（= tauri.conf.json 的 version），\
             实得函数体：{body}"
        );
        assert!(
            !body.contains("CARGO_PKG_VERSION"),
            "⛔ app_version 不得读 CARGO_PKG_VERSION（= Cargo.toml 的 version）—— \
             那与安装包版本是两个来源，一旦漂移会导致更新提示永远不消失（2026-09-17 实测）。"
        );
    }

    /// ⭐⭐ 守护：**两个版本文件必须一致** —— `Cargo.toml` 的 `version` == `tauri.conf.json` 的 `version`。
    ///
    /// <p><b>WHY</b>：本仓有**两个**版本真相（crate 版本 vs 打包版本），且历史上它们长期不一致
    /// （`Cargo.toml` 停在 `0.1.6` 而 `tauri.conf.json` 到 `0.1.10`）。发版时只改三处
    /// （`tauri.conf.json` / `package.json` / `pom.xml`）而忘了 `Cargo.toml` 是**极易发生**的疏漏。
    /// 这条守护把「发版时忘改」变成**编译后立刻红**，而不是等用户装了包发现还在提示更新。
    /// ⚠️ 读的是两个文件的**源文本**（`include_str!`），不是运行时值 —— 所以覆盖「发版流程」而非「运行行为」。
    #[test]
    fn cargo_toml_version_matches_tauri_conf() {
        let cargo = include_str!("../Cargo.toml");
        let conf = include_str!("../tauri.conf.json");

        /// 从一行里取出值：先去行尾逗号（JSON 有、TOML 无），再去首尾引号。
        /// ⚠️ 顺序不能反 —— 先 `trim_matches('"')` 的话，`"0.1.11",` 会剩下一个尾部引号。
        fn pick(src: &str, prefix: &str) -> String {
            src.lines()
                .map(str::trim)
                .find_map(|l| l.strip_prefix(prefix))
                .map(|v| v.trim().trim_end_matches(',').trim().trim_matches('"').to_string())
                .unwrap_or_default()
        }

        let cargo_v = pick(cargo, "version =");
        let conf_v = pick(conf, "\"version\":"); // tauri.conf.json 是 JSON：`"version": "x.y.z",`

        assert!(
            !cargo_v.is_empty() && !conf_v.is_empty(),
            "未能从两个文件中解析出版本号：Cargo.toml={cargo_v:?} tauri.conf.json={conf_v:?}"
        );
        assert_eq!(
            cargo_v, conf_v,
            "⛔ Cargo.toml 与 tauri.conf.json 的 version 必须一致（发版时**两个都要改**）。\
             不一致会导致：Cargo.lock/crate 版本、与安装包版本、与 app_version 三者语义分叉。"
        );
    }

    #[test]
    fn content_range_total_parses_standard_form() {
        assert_eq!(content_range_total("bytes 100-199/2000"), Some(2000));
        assert_eq!(content_range_total("bytes 0-0/1"), Some(1));
        assert_eq!(content_range_total("bytes 0-199/*"), None);
        assert_eq!(content_range_total("garbage"), None);
    }

    #[test]
    fn installer_command_line_is_silent_and_relaunches() {
        let line = installer_command_line(
            r"C:\tmp\NexusAI_0.1.60_x64-setup.exe",
            r"D:\software\NexusAI\nexusai-client.exe",
        );
        assert!(line.contains("ping -n 4"), "必须先延迟，让关窗路径回收后端：{line}");
        assert!(line.contains("/S"), "必须静默安装（不弹 NSIS 向导）：{line}");
        assert!(line.contains("/wait"), "必须等安装器退出后再重启：{line}");
        assert!(
            !line.contains("/R"),
            "⛔ 不传 NSIS /R（其 RunAsUser 重启分支会报找不到 '\\\\' 文件）：{line}"
        );
        assert!(
            line.contains(r#""C:\tmp\NexusAI_0.1.60_x64-setup.exe""#),
            "安装包路径必须带引号（允许空格）：{line}"
        );
        assert!(
            line.contains(r#""D:\software\NexusAI\nexusai-client.exe""#),
            "安装完必须自行重启当前 exe：{line}"
        );
    }

    #[test]
    fn sha256_file_hashes_whole_file() {
        let path = std::env::temp_dir().join(format!(
            "nexusai-updater-sha-{}-{}.tmp",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .map(|d| d.as_nanos())
                .unwrap_or(0)
        ));
        std::fs::write(&path, b"abc").expect("write temp");
        assert_eq!(
            sha256_file(&path).expect("hash"),
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        );
        let _ = std::fs::remove_file(&path);
    }
}
