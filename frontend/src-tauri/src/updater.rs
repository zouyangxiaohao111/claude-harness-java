//! NexusAI 自动更新（自研轻量，非 plugin-updater）。
//!
//! 清单 latest.json（每源根）：{ version, notes, published, platforms: {
//!   windows-x86_64: { url, sha256 },
//!   darwin-aarch64: { url, sha256 }   ← 各平台各一条，客户端按运行平台取
//! } }。
//! ⛔ 平台键由 platform_key() 按运行平台算出；清单里没有本平台 ⇒ 「无可用更新」
//!    （⛔ 不得回退到别的平台 —— 否则 mac 会拿到 Windows 安装包，2026-11-13 实测）。
//! 多源顺序尝试（NEXUSAI_UPDATER_SOURCES / 内置默认 → GitHub release），sha256 校验替代 minisign（免私钥管理）。
//! 命令：update_check( currentVersion, sources? ) / update_download( info ) / update_install( path )
//! 事件：update://found / update://progress / update://installing / update://error

use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::io::Read;
use std::time::Duration;
use tauri::{AppHandle, Emitter, Manager};

/// 平台名（Tauri updater 的 `{target}-{arch}` 风格；macOS 的 target 叫 `darwin`）。
const PLATFORM_OS: &str = if cfg!(target_os = "windows") {
    "windows"
} else if cfg!(target_os = "macos") {
    "darwin"
} else if cfg!(target_os = "linux") {
    "linux"
} else {
    "unknown"
};

/// 架构名。
const PLATFORM_ARCH: &str = if cfg!(target_arch = "x86_64") {
    "x86_64"
} else if cfg!(target_arch = "aarch64") {
    "aarch64"
} else {
    "unknown"
};

/// 纯函数：os+arch → 清单里的平台键。
///
/// <p>抽成纯函数是为了能在**任意宿主**上单测全部组合 —— `cfg!` 决定的
/// `PLATFORM_OS`/`PLATFORM_ARCH` 在本机（Windows）只能覆盖一种组合。
fn platform_key_for(os: &str, arch: &str) -> String {
    format!("{os}-{arch}")
}

/// 本进程的平台键 = 清单 `platforms` 里应查的键。
fn platform_key() -> String {
    platform_key_for(PLATFORM_OS, PLATFORM_ARCH)
}

/// 从清单的 `platforms` 里取**指定平台**的条目。
///
/// <p>返回 `None` = 清单里没有这个平台 ⇒ 调用方判定「本平台无可用更新」。
/// ⛔ **不得**在取不到时回退到别的平台 —— 那正是 2026-11-13「mac 拿到 Windows
/// 安装包」的根因（当时这里是写死的 `windows-x86_64`）。
fn platform_entry<'a>(
    platforms: &'a serde_json::Value,
    key: &str,
) -> Option<&'a serde_json::Value> {
    platforms.get(key)
}

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
    // ⛔ 按**当前运行平台**取条目：清单里没有本平台 ⇒ 判定「本平台无可用更新」。
    //    （原实现硬编码 "windows-x86_64" ⇒ mac 客户端会拿到 Windows 安装包，
    //      2026-11-13 用户实测：mac 提示更新并下到 .exe。）
    let key = platform_key();
    let entry = match platform_entry(&m.platforms, &key) {
        Some(v) => v,
        None => {
            return Ok(UpdateInfo {
                available: false,
                version: m.version,
                notes: String::new(),
                published: None,
                url: String::new(),
                sha256: String::new(),
                source: src.to_string(),
            })
        }
    };
    let url = entry
        .get("url")
        .and_then(|v| v.as_str())
        .ok_or("缺少下载 url".to_string())?
        .to_string();
    let sha256 = entry
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
        // 文件名从 URL 推导（⛔ 不再写死 `_x64-setup.exe` —— mac 包是 .dmg/.app.tar.gz）
        let file_name = sanitized_file_name(&info.url)
            .unwrap_or_else(|| format!("NexusAI_{}_x64-setup.exe", info.version));
        let path = dir.join(file_name);

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

/// 静默安装命令行（Windows NSIS）：延迟 → **杀掉旧 app 与后端** → `/S` 无向导覆盖安装
/// → 安装器退出后启动当前 exe。
///
/// <p>⛔ 调用方必须用 `CommandExt::raw_arg` 原样传这行；不能 `.args(["/C", line])`
/// （Rust 会把内部引号转义成 `\"`，cmd.exe 不认 ⇒「Windows 找不到 '\\' 文件」）。
///
/// <p>⛔ **必须先杀掉运行中的 app**（2026-11-14 用户实测：「静默安装了，但没自动重启，
/// 装完用户没感知」）。根因是主窗口「点 X」= 隐藏到托盘（2026-11-04 裁定）⇒ 前端
/// `getCurrentWindow().close()` **不退出进程** ⇒
/// ① `nexusai-client.exe` 被文件锁 ⇒ NSIS 替换不掉它却仍报「安装完成」⇒ 装完还是旧版；
/// ② 新实例被 `tauri_plugin_single_instance` 顶掉（并把焦点给那个**已隐藏**的旧窗口）
/// ⇒ 用户完全没感知。
///
/// <p>后端 java 进程也要杀：否则 `backend/nexusai-backend.jar` 被锁 ⇒ 覆盖安装失败。
/// ⛔ 按**精确 pid** 杀（`/PID`），不用 `/IM java.exe` —— 那会误杀同机别的 Java 进程。
///
/// <p>开头的延迟只为了让用户看到「正在安装…」而不是被突然杀掉；真正保证 exe 解锁的是
/// 紧随其后的 `taskkill`（它发生在前端关窗之后）。
fn installer_command_line(path: &str, app_exe: &str, backend_pid: Option<u32>) -> String {
    let installer = path.replace('"', "");
    let app = app_exe.replace('"', "");
    // 按映像名杀（/IM）：一次清掉所有旧实例（用户可能同时开了多个）。
    let app_file = app
        .rsplit(['\\', '/'])
        .next()
        .filter(|s| !s.is_empty())
        .unwrap_or("nexusai-client.exe")
        .to_string();
    let kill_backend = backend_pid
        .map(|p| format!("taskkill /PID {p} /T /F & "))
        .unwrap_or_default();
    if app.is_empty() {
        // 取不到当前 exe（极罕见）：至少完成安装，不尝试重启。
        // ⚠️ 此时**不杀 app** —— 杀了就再也起不来。
        format!("ping -n 4 127.0.0.1 >NUL & {kill_backend}start \"\" /wait \"{installer}\" /S")
    } else {
        // ⛔ 不给安装器传 `/R`：它的 RunAsUser 重启分支在部分机器上会报
        //    「Windows 找不到 '\\' 文件」（2026-10-27 用户实测）。
        //    改为 `start /wait` 等安装器退出后，由本命令自己启动当前 exe。
        // ⚠️ taskkill 之后留 2s（ping -n 3）：等文件锁真正释放，否则安装器仍替换不掉 exe。
        format!(
            "ping -n 4 127.0.0.1 >NUL & taskkill /IM {app_file} /F & {kill_backend}\
             ping -n 3 127.0.0.1 >NUL & start \"\" /wait \"{installer}\" /S & start \"\" \"{app}\""
        )
    }
}

#[cfg(target_os = "windows")]
fn spawn_silent_installer(path: &str, backend_pid: Option<u32>) -> Result<(), String> {
    // ⭐ raw_arg：cmd 的命令行必须**原样**传。若用 `.args(["/C", line])`，Rust 的 Windows
    //    参数引用会把内部引号转义成 `\"`，而 cmd.exe 不认 `\"` ⇒ `start` 把反斜杠当程序名
    //    ⇒ 弹「Windows 找不到 '\\' 文件」（2026-10-27 用户实测；即使已经去掉 NSIS `/R`）。
    use std::os::windows::process::CommandExt;
    let app_exe = std::env::current_exe()
        .map(|p| p.to_string_lossy().into_owned())
        .unwrap_or_default();
    crate::backend::silent_command("cmd")
        .arg("/C")
        .raw_arg(installer_command_line(path, &app_exe, backend_pid))
        .spawn()
        .map(|_| ())
        .map_err(|e| format!("启动静默安装器失败: {e}"))
}

/// macOS：没有 NSIS 静默安装的概念 —— 打开 `.dmg` 让用户把 `.app` 拖进 Applications。
///
/// <p>⛔ **不做自动替换**（替换运行中的 app 需要停进程 + 提权 + 处理签名/只读卷）——
/// 见计划「明确不做」章节。
#[cfg(target_os = "macos")]
fn spawn_silent_installer(path: &str, _backend_pid: Option<u32>) -> Result<(), String> {
    std::process::Command::new("open")
        .arg(path)
        .spawn()
        .map(|_| ())
        .map_err(|e| format!("打开安装镜像失败: {e}"))
}

/// 其余非 Windows 平台（Linux 等）：维持原行为。
#[cfg(not(any(target_os = "windows", target_os = "macos")))]
fn spawn_silent_installer(path: &str, _backend_pid: Option<u32>) -> Result<(), String> {
    std::process::Command::new(path)
        .arg("-S")
        .spawn()
        .map(|_| ())
        .map_err(|e| format!("启动安装器失败: {e}"))
}

#[tauri::command]
pub fn update_install(path: String, app: AppHandle) -> Result<(), String> {
    let _ = app.emit("update://installing", ());
    // ⛔ 必须把**后端 pid**取出来交给命令行：jar 被 java 进程占着 ⇒ 覆盖安装必然失败。
    //    这里只**读**不 take —— 关窗钩子那边仍要按它回收（两边都杀是幂等的）。
    let backend_pid = {
        let state = app.state::<crate::BackendState>();
        let guard = state.0.lock().unwrap();
        *guard
    };
    // ⭐ 无感升级：静默覆盖安装（/S）；安装器退出后由 cmd 包装器重启当前 exe。
    // 数据目录（~/.nexusai）与安装目录分离，覆盖安装不影响用户数据。
    spawn_silent_installer(&path, backend_pid)
}

fn hex_digest(d: &[u8]) -> String {
    d.iter().map(|b| format!("{b:02x}")).collect()
}

/// 从下载 URL 取文件名：只取 basename，且只保留安全字符（防路径穿越 / 非法字符）。
///
/// <p>mac 包名是 `.dmg` / `.app.tar.gz`，Windows 是 `_x64-setup.exe` ⇒ 不能写死。
///
/// <p>⛔ URL 里出现 `..` 路径段一律拒绝：basename 看着安全（如 `passwd`）也可能是
/// 穿越链的一环，宁可回退到调用方的默认文件名。
fn sanitized_file_name(url: &str) -> Option<String> {
    let base = url.split(['?', '#']).next().unwrap_or(url);
    if base.split('/').any(|seg| seg == "..") {
        return None;
    }
    let name = base.rsplit('/').next()?.trim();
    if name.is_empty() {
        return None;
    }
    let safe: String = name
        .chars()
        .filter(|c| c.is_ascii_alphanumeric() || matches!(c, '.' | '-' | '_'))
        .collect();
    if safe.is_empty() || safe == "." || safe == ".." {
        return None;
    }
    Some(safe)
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
            None,
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

    /// ⛔ 守护（2026-11-14 用户实测：「静默安装了，但没自动重启，装完用户没感知」）：
    /// 安装器要替换 `nexusai-client.exe`，该 exe **必须没被占用**。
    ///
    /// <p>WHY 必须杀 app：主窗口「点 X」= 隐藏到托盘（2026-11-04 裁定）⇒ 前端
    /// `getCurrentWindow().close()` **不退出进程** ⇒ exe 被文件锁 ⇒ NSIS 静默安装
    /// 替换不掉它却仍报「安装完成」⇒ 装完还是旧版；且新实例被单实例插件顶掉
    /// （并把焦点给那个**已隐藏**的旧窗口）⇒ 用户完全没感知。
    #[test]
    fn installer_command_line_must_kill_app_before_installing() {
        let line = installer_command_line(
            r"C:\tmp\NexusAI_0.1.77_x64-setup.exe",
            r"D:\software\NexusAI\nexusai-client.exe",
            None,
        );
        assert!(
            line.contains("taskkill /IM nexusai-client.exe /F"),
            "⛔ 必须先杀掉运行中的 app（taskkill /IM）：exe 被文件锁 ⇒ 覆盖安装替换不掉它。{line}"
        );
        let kill_at = line.find("taskkill /IM").expect("必须含 taskkill /IM");
        let start_at = line.find("start \"\" /wait").expect("必须含 start /wait");
        assert!(
            kill_at < start_at,
            "⛔ taskkill 必须在启动安装器**之前**（先装后杀 ⇒ 锁还在，替换仍然失败）。{line}"
        );
        assert!(
            line.contains("start \"\" \"D:\\software\\NexusAI\\nexusai-client.exe\""),
            "杀完 app、安装完之后仍必须拉起新实例。{line}"
        );
    }

    /// 后端 java 进程树必须被杀：否则 `backend/nexusai-backend.jar` 被锁 ⇒ 覆盖安装失败。
    ///
    /// <p>⛔ 必须按**精确 pid** 杀（`/PID`），不能用 `/IM java.exe` —— 那会误杀
    /// 同机的 IDEA / 其他 Java 进程（2026-11-14 定）。
    #[test]
    fn installer_command_line_must_kill_backend_process_tree_when_pid_known() {
        let with_pid =
            installer_command_line(r"C:\tmp\i.exe", r"D:\s\nexusai-client.exe", Some(4242));
        assert!(
            with_pid.contains("taskkill /PID 4242 /T /F"),
            "⛔ 必须按精确 pid 杀后端进程树（jar 被锁 ⇒ 覆盖安装失败）。{with_pid}"
        );
        let without_pid =
            installer_command_line(r"C:\tmp\i.exe", r"D:\s\nexusai-client.exe", None);
        assert!(
            !without_pid.contains("taskkill /PID"),
            "⛔ 不知道后端 pid 时不得输出空的 taskkill /PID。{without_pid}"
        );
    }

    /// ⛔ 守护：更新前必须把**后端 pid** 取出来交给命令行 ——
    /// 否则 jar 被 java 进程占着 ⇒ NSIS 覆盖安装必然失败。
    #[test]
    fn update_install_must_pass_backend_pid_to_command_line() {
        let body = fn_body(prod_src(), "pub fn update_install")
            .expect("update_install 必须存在（前端经 invoke('update_install') 触发安装）");
        assert!(
            body.contains("BackendState"),
            "⛔ update_install 必须读取后端进程 pid（BackendState）并传给 installer_command_line —— \
             否则 nexusai-backend.jar 被锁 ⇒ 覆盖安装失败。实得：{body}"
        );
    }

    /// 回归守护（2026-10-27 用户实测：「Windows 找不到 '\\' 文件」）：
    /// `.args(["/C", line])` 会让 Rust 把内部引号转义成 `\"`，cmd.exe 不认 ⇒ start 解析错。
    /// 用 `raw_arg` 原样传命令行，cmd 才能正确看到 `start "" /wait "..." /S`。
    #[cfg(target_os = "windows")]
    #[test]
    fn raw_arg_preserves_cmd_quotes() {
        use std::os::windows::process::CommandExt;

        // 只 echo，不启动任何程序：验证 cmd 实际收到的引号形态。
        let line = r#"echo start "" /wait "C:\tmp\NexusAI_x64-setup.exe" /S & echo start "" "D:\software\NexusAI\nexusai-client.exe""#;

        let out = std::process::Command::new("cmd")
            .arg("/C")
            .raw_arg(line)
            .output()
            .expect("cmd raw_arg");
        let stdout = String::from_utf8_lossy(&out.stdout);
        assert!(
            stdout.contains(r#"start "" /wait "C:\tmp\NexusAI_x64-setup.exe" /S"#),
            "raw_arg 必须原样保留安装器路径引号：{stdout}"
        );
        assert!(
            stdout.contains(r#"start "" "D:\software\NexusAI\nexusai-client.exe""#),
            "raw_arg 必须原样保留重启路径引号：{stdout}"
        );

        // 反面对照：走 .args(["/C", line]) 必须复现 `\"`（防止测试假绿）。
        let bad = std::process::Command::new("cmd")
            .args(["/C", line])
            .output()
            .expect("cmd args");
        let bad_stdout = String::from_utf8_lossy(&bad.stdout);
        assert!(
            bad_stdout.contains(r#"\""#),
            "对照组必须复现 Rust 的引号转义（否则本测试没有判别力）：{bad_stdout}"
        );
    }

    /// 平台键映射（意图）：**清单查什么键由运行平台决定**，不能写死某一个平台。
    ///
    /// <p>抽成纯函数是为了能在**任意宿主**上单测全部组合 —— 本机是 Windows，
    /// `cfg!` 里的 mac 组合在 Windows 上永远走不到，测不到就等于没写。
    #[test]
    fn platform_key_for_maps_all_targets() {
        assert_eq!(platform_key_for("windows", "x86_64"), "windows-x86_64");
        assert_eq!(platform_key_for("darwin", "aarch64"), "darwin-aarch64");
        assert_eq!(platform_key_for("darwin", "x86_64"), "darwin-x86_64");
        assert_eq!(platform_key_for("linux", "x86_64"), "linux-x86_64");
    }

    /// 守护：生产**代码**不得出现字面量 `"windows-x86_64"`。
    /// 出现即说明有人把平台键写死了 ⇒ mac 会重新拿到 Windows 安装包（2026-11-13 实测）。
    ///
    /// <p>⚠️ **只检查代码，不检查注释**：注释里保留 `"windows-x86_64"` 字样是**有意的**
    /// （记录根因与实测背景，见文件头与 `fetch_one` 旁的说明）⇒ 连注释一起匹配会误伤
    /// 正确的实现（2026-11-13 实测踩过一次假红：实现已改成 `platform_key()`，
    /// 测试却因注释里的字面量而红）。剔注释的方式是逐行砍掉 `//` 之后的部分 ——
    /// 代码里的字符串 `"windows-x86_64"` 前没有 `//` ⇒ 仍会被抓到，判别力不丢。
    #[test]
    fn production_code_must_not_hardcode_windows_platform_key() {
        let code: String = prod_src()
            .lines()
            .map(|l| match l.find("//") {
                Some(i) => &l[..i],
                None => l,
            })
            .collect::<Vec<_>>()
            .join("\n");
        assert!(
            !code.contains("\"windows-x86_64\""),
            "⛔ updater.rs 生产代码不得硬编码平台键 \"windows-x86_64\" —— \
             平台键必须由 platform_key() 按运行平台算出，否则 mac 会拿到 Windows 安装包。"
        );
    }

    /// 核心行为（意图）：清单里**没有本平台** ⇒ 取不到条目 ⇒ 判定「本平台无可用更新」。
    ///
    /// <p>这是 2026-11-13 那个 bug 的**行为级**钉子：mac 客户端拿到只含
    /// `windows-x86_64` 的清单时，必须**取不到**条目（而不是回退去拿 Windows 的）。
    #[test]
    fn manifest_without_this_platform_yields_no_entry() {
        let manifest: serde_json::Value = serde_json::from_str(
            r#"{"windows-x86_64":{"url":"http://h/NexusAI_0.1.74_x64-setup.exe","sha256":"abc"}}"#,
        )
        .expect("清单 JSON 应可解析");
        assert!(
            platform_entry(&manifest, "darwin-aarch64").is_none(),
            "⛔ 清单里没有 darwin-aarch64 ⇒ 必须取不到条目（否则 mac 会拿到 Windows 安装包）"
        );
        assert!(
            platform_entry(&manifest, "windows-x86_64").is_some(),
            "清单里有 windows-x86_64 ⇒ 必须取得到条目"
        );
    }

    /// 文件名清洗（意图）：下载文件名从 URL 推导（mac 包是 .dmg/.app.tar.gz，
    /// 不能写死 `_x64-setup.exe`），且**不得**允许路径穿越。
    #[test]
    fn sanitized_file_name_takes_basename_and_rejects_traversal() {
        assert_eq!(
            sanitized_file_name("http://h/nexusai/updater/NexusAI_0.1.74_x64-setup.exe").as_deref(),
            Some("NexusAI_0.1.74_x64-setup.exe")
        );
        assert_eq!(
            sanitized_file_name("http://h/nexusai/updater/NexusAI_0.1.74_aarch64.dmg?x=1").as_deref(),
            Some("NexusAI_0.1.74_aarch64.dmg")
        );
        assert_eq!(sanitized_file_name("http://h/../../etc/passwd"), None);
        assert_eq!(sanitized_file_name("http://h/"), None);
        assert_eq!(sanitized_file_name(""), None);
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
