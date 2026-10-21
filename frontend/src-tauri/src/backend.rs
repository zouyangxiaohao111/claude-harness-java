//! 后端进程生命周期管理（T12）。
//!
//! 职责：探活 `http://localhost:3458/actuator/health` → 未就绪则用随安装包分发的裁剪 JRE
//! spawn `java -jar nexusai-backend.jar --spring.profiles.active=prod`（数据落 `%USERPROFILE%/.nexusai`）
//! → 等待健康就绪；窗口关闭 / 应用退出时把 java 进程**整树**回收（防孤儿）。
//!
//! 约束：不引第三方 HTTP / 进程 crate，全部走 std。

use std::io::{Read, Write};
use std::net::{IpAddr, Ipv4Addr, SocketAddr, TcpStream};
use std::path::PathBuf;
use std::process::{Command, Stdio};
use std::time::Duration;

use tauri::Manager;

/// 后端健康检查端口（与 Spring Boot prod 配置对齐）。
const HEALTH_PORT: u16 = 3458;
/// 健康检查 HTTP 路径。
const HEALTH_PATH: &str = "/actuator/health";
/// 单次连接/读取超时。
const PROBE_TIMEOUT: Duration = Duration::from_millis(1500);
/// 等待后端健康就绪的总超时：Spring Boot prod 冷启动（首次 Flyway 全量迁移 / c3p0 /
/// Quartz bean 初始化 / 机器负载）实测可能 >8s，8s 太紧会把「仍在启动」误判为「启动失败」，
/// 导致壳静默闪退。故放宽到 60s。
pub const WAIT_BACKEND_READY_TIMEOUT: Duration = Duration::from_secs(60);

/// 构造一个「不弹控制台黑窗」的 [`Command`]。
///
/// GUI 壳里 spawn 控制台程序（taskkill / powershell / tasklist）默认会新分配一个控制台并
/// 弹出黑色 cmd 窗口——用户关预览时看到的弹窗即 taskkill 所致。Windows 统一设
/// `CREATE_NO_WINDOW` 标志从根上抑制；非 Windows 无此概念，原样返回。
pub fn silent_command(program: &str) -> Command {
    #[allow(unused_mut)]
    let mut cmd = Command::new(program);
    #[cfg(target_os = "windows")]
    {
        use std::os::windows::process::CommandExt;
        /// 不新建控制台窗口（winbase.h `CREATE_NO_WINDOW`）。
        const CREATE_NO_WINDOW: u32 = 0x0800_0000;
        cmd.creation_flags(CREATE_NO_WINDOW);
    }
    cmd
}

/// 探活：GET http://localhost:3458/actuator/health 返回 2xx 即视为后端就绪。
///
/// 用 std::net::TcpStream::connect_timeout 手写一个最简 HTTP/1.1 GET，
/// 读响应前 256 字节，包含 "200" 即 true；连接失败 / 超时 / 非 200 一律 false，不 panic。
pub fn backend_ready() -> bool {
    let addr = SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), HEALTH_PORT);

    // 连接超时（1.5s）：连不上说明端口未监听，直接 not ready
    let mut stream = match TcpStream::connect_timeout(&addr, PROBE_TIMEOUT) {
        Ok(s) => s,
        Err(_) => return false,
    };
    // 读超时兜底：避免连上后服务端迟迟不响应导致阻塞过久
    let _ = stream.set_read_timeout(Some(PROBE_TIMEOUT));

    // 手写 HTTP/1.1 GET（不带 Host 头以外的多余头，Connection: close 让服务端发完即关）
    let request = format!(
        "GET {} HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n",
        HEALTH_PATH
    );
    if stream.write_all(request.as_bytes()).is_err() {
        return false;
    }

    // 只读响应头前 256 字节：状态行 "HTTP/1.1 200 OK" 位于最前，足够判定
    let mut buf = [0u8; 256];
    let n = match stream.read(&mut buf) {
        Ok(n) => n,
        Err(_) => 0,
    };
    let head = String::from_utf8_lossy(&buf[..n]);
    head.contains("200")
}

/// 用户数据根：Windows 取 `%USERPROFILE%/.nexusai`，其它取 `$HOME/.nexusai`
/// （与 Java `{user.home}/.nexusai` 对齐）。取不到环境变量时兜底相对路径 `./.nexusai`。
pub fn data_dir() -> PathBuf {
    let base = if cfg!(windows) {
        std::env::var_os("USERPROFILE").map(PathBuf::from)
    } else {
        std::env::var_os("HOME").map(PathBuf::from)
    };
    base.unwrap_or_else(|| PathBuf::from(".")).join(".nexusai")
}

/// 确保 `{data_dir}` 与 `{data_dir}/logs` 存在（后端日志落盘与相对数据文件的前提）。
pub fn ensure_data_dirs() -> std::io::Result<()> {
    let root = data_dir();
    std::fs::create_dir_all(&root)?;
    std::fs::create_dir_all(root.join("logs"))?;
    Ok(())
}

/// 后端 java 进程 stdout/stderr 落盘文件：`{data_dir}/logs/backend.log`。
/// 目录由 [`ensure_data_dirs`] 保证存在；仅本模块内使用。
fn log_file() -> PathBuf {
    data_dir().join("logs").join("backend.log")
}

/// 单个活跃日志文件的滚动阈值（64MB）。超过即归档，避免长时间常驻把盘写满。
pub(crate) const LOG_ROLL_MAX_BYTES: u64 = 64 * 1024 * 1024;

/// 归档保留份数（按文件名时间戳保留最新 N 份，其余删除）。
pub(crate) const LOG_ROLL_KEEP: usize = 3;

/// 日志滚动：活跃文件超过 [`LOG_ROLL_MAX_BYTES`] → 改名为 `<stem>-<时间戳>.log`，并清理旧归档。
///
/// <p><b>时机约束</b>：Windows 下被进程持有的文件无法 rename ⇒ `backend.log`（javaw 的
/// stdout/stderr 句柄）**只能在 spawn 之前**轮转；`frontend.log`（每次 append 后即关闭）
/// 可在运行期任意时刻轮转。
///
/// <p>为何需要：这两份日志由壳侧**直接 append**，logback 的 SizeAndTimeBasedRollingPolicy
/// 管不到它们 —— 旧实现下 `backend.log` 单文件可达 GB 级（用户实测 4G）。
pub(crate) fn rotate_log_if_oversized(path: &std::path::Path) {
    rotate_if_oversized_with(path, LOG_ROLL_MAX_BYTES, LOG_ROLL_KEEP);
}

/// [`rotate_log_if_oversized`] 的参数化实现（阈值/保留份数可注入 → 单测用小阈值即可覆盖真实分支）。
pub(crate) fn rotate_if_oversized_with(path: &std::path::Path, max_bytes: u64, keep: usize) {
    let size = match std::fs::metadata(path) {
        Ok(m) => m.len(),
        Err(_) => return, // 不存在 / 不可读 → 无需轮转
    };
    if size <= max_bytes {
        return;
    }
    let parent = path.parent().unwrap_or_else(|| std::path::Path::new("."));
    let stem = path
        .file_stem()
        .and_then(|s| s.to_str())
        .unwrap_or("backend");
    let stamp = utc_timestamp().replace([' ', ':'], "-");
    // 同一秒内滚两次（或系统时钟回拨）会撞名 —— 不处理就会静默覆盖上一份归档（丢日志）
    let mut archived = parent.join(format!("{stem}-{stamp}.log"));
    let mut seq = 2;
    while archived.exists() {
        archived = parent.join(format!("{stem}-{stamp}-{seq}.log"));
        seq += 1;
    }
    match std::fs::rename(path, &archived) {
        Ok(()) => eprintln!(
            "[log-roll] {} 已达 {size} 字节，归档为 {}",
            path.display(),
            archived.display()
        ),
        Err(e) => {
            // 显式失败：轮转失败不等于可以继续无界追加，但也不能因此阻断启动
            eprintln!("[log-roll] 归档 {} 失败：{e}", path.display());
            return;
        }
    }
    prune_old_archives(parent, stem, keep);
}

/// 清理 `{parent}/{stem}-*.log`：按文件名排序（时间戳可字典序比较），只留最新 `keep` 份。
fn prune_old_archives(parent: &std::path::Path, stem: &str, keep: usize) {
    let prefix = format!("{stem}-");
    let mut archives: Vec<PathBuf> = match std::fs::read_dir(parent) {
        Ok(rd) => rd
            .filter_map(|e| e.ok())
            .map(|e| e.path())
            .filter(|p| {
                p.file_name()
                    .and_then(|n| n.to_str())
                    .map(|n| n.starts_with(&prefix) && n.ends_with(".log"))
                    .unwrap_or(false)
            })
            .collect(),
        Err(_) => return,
    };
    if archives.len() <= keep {
        return;
    }
    archives.sort();
    let remove_count = archives.len() - keep;
    for old in archives.into_iter().take(remove_count) {
        if let Err(e) = std::fs::remove_file(&old) {
            eprintln!("[log-roll] 删除旧归档 {} 失败：{e}", old.display());
        }
    }
}

/// 本会话自启后端 pid 的持久化文件（`{data_dir}/backend.pid`）。
/// 用途：正常关窗/退出时按内存 pid 整树回收；Tauri 被强杀/崩溃丢失该 pid 时，
/// 下次启动经 [`cleanup_stale_backend_pid`] 做“身份校验后”的跨启动兜底清理。
pub const BACKEND_PID_FILE: &str = "backend.pid";

fn backend_pid_path() -> PathBuf {
    data_dir().join(BACKEND_PID_FILE)
}

/// spawn 成功后把 pid 落盘（best-effort；数据目录已在启动时确保存在）。
pub fn write_backend_pid(pid: u32) -> std::io::Result<()> {
    std::fs::write(backend_pid_path(), pid.to_string())
}

/// 删除 pid 记录（幂等：文件不存在则忽略）。
fn clear_backend_pid() {
    let _ = std::fs::remove_file(backend_pid_path());
}

/// 该 pid 是否“本应用自启的后端”（命令行含 `nexusai-backend.jar`）？
/// 只有身份命中才允许跨启动 kill —— 防 pid 复用 / 误杀其它 java（IDE、其它 GUI 应用）。
/// Windows 经 PowerShell 读 CommandLine；查询失败 → false（保守不杀）。
fn is_our_backend_process(pid: u32) -> bool {
    #[cfg(target_os = "windows")]
    {
        let script = format!("(Get-CimInstance Win32_Process -Filter 'ProcessId = {pid}').CommandLine");
        match silent_command("powershell")
            .args(["-NoProfile", "-NonInteractive", "-Command", &script])
            .output()
        {
            Ok(o) => {
                let s = String::from_utf8_lossy(&o.stdout).to_lowercase();
                let ours = s.contains("nexusai-backend.jar");
                if !ours {
                    eprintln!("[backend] pid={} 命令行非本应用后端（或进程已退出）→ 不跨启动清理", pid);
                }
                ours
            }
            Err(e) => {
                eprintln!("[backend] 查询 pid={} 命令行失败（{}）→ 保守不清理", pid, e);
                false
            }
        }
    }
    #[cfg(not(target_os = "windows"))]
    {
        let _ = pid;
        false // 非 Windows 本轮不做跨启动清理
    }
}

/// 启动前兜底清理上次残留：pid 文件记录了上次自启的后端，且进程确为
/// `nexusai-backend.jar` → 整树清理（内部顺带删 pid 文件）；否则只删过时记录、
/// 绝不动进程。文件缺失/内容非法 → no-op 或仅删记录。
pub fn cleanup_stale_backend_pid() {
    let path = backend_pid_path();
    let raw = match std::fs::read_to_string(&path) {
        Ok(s) => s,
        Err(_) => return, // 无记录 → 无事可做
    };
    let pid: u32 = match raw.trim().parse() {
        Ok(p) => p,
        Err(_) => {
            let _ = std::fs::remove_file(&path);
            return;
        }
    };
    if is_our_backend_process(pid) {
        eprintln!("[backend] 检测到上次自启后端残留 pid={}（命令行含 nexusai-backend.jar）→ 整树清理", pid);
        kill_process_tree(pid);
    } else {
        eprintln!("[backend] pid 文件记录={} 非本应用后端或已退出 → 仅删记录，不杀进程", pid);
        clear_backend_pid();
    }
}

/// Rust 侧（Tauri 壳）自己的启动失败日志：追加写入 `{data_dir}/logs/tauri-launcher.log`。
///
/// 与后端 java 进程的 backend.log 区分——这里记「壳拉起后端」这一侧的**关键失败**，
/// 供 release（`windows_subsystem = "windows"` 无控制台、eprintln 不可见）静默闪退时排查根因。
/// 目录缺失时 best-effort 再建一次；打开 / 写入失败仅 eprintln 告警，绝不 panic。
pub fn log_launcher(msg: &str) {
    let dir = data_dir().join("logs");
    if let Err(e) = std::fs::create_dir_all(&dir) {
        eprintln!("[backend] 无法创建启动器日志目录 {}：{}", dir.display(), e);
        return;
    }
    let path = dir.join("tauri-launcher.log");
    let line = format!("[{}] {}\n", utc_timestamp(), msg);
    match std::fs::OpenOptions::new().create(true).append(true).open(&path) {
        Ok(mut f) => {
            if let Err(e) = f.write_all(line.as_bytes()) {
                eprintln!("[backend] 写入启动器日志 {} 失败：{}", path.display(), e);
            }
        }
        Err(e) => {
            eprintln!("[backend] 无法打开启动器日志 {} 追加写入：{}", path.display(), e);
        }
    }
    // release 无控制台看不到 eprintln，文件是主要排障通道；dev 下仍保留控制台输出便于直接观察。
    eprintln!("[backend-launcher] {}", msg);
}

/// 无第三方依赖的 UTC 时间戳 `YYYY-MM-DD HH:MM:SS`（避免仅为日志引入 chrono / time）。
/// 采用 Howard Hinnant `civil_from_days`：由 Unix 纪元秒换算公历年月日；
/// 当前时间恒为 1970 年后的正秒，故只实现正数分支。
pub(crate) fn utc_timestamp() -> String {
    let secs = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0);
    let days = secs / 86_400; // 1970-01-01 起的天数
    let rem = secs % 86_400; // 当日秒
    let z = days + 719_468;
    let era = z / 146_097;
    let doe = z - era * 146_097; // [0, 146096]
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365; // [0, 399]
    let y = yoe + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100); // [0, 365]
    let mp = (5 * doy + 2) / 153; // [0, 11]
    let d = doy - (153 * mp + 2) / 5 + 1; // [1, 31]
    let m = if mp < 10 { mp + 3 } else { mp - 9 }; // [1, 12]
    let y = if m <= 2 { y + 1 } else { y }; // 公历年
    format!(
        "{:04}-{:02}-{:02} {:02}:{:02}:{:02}",
        y,
        m,
        d,
        rem / 3600,
        (rem % 3600) / 60,
        rem % 60
    )
}

/// 定位随包分发的 backend 目录（内含 `jre/` 与 `nexusai-backend.jar`）。
///
/// 1) 打包后：`app.path().resource_dir()/backend`（与 tauri.conf.json `bundle.resources` 的
///    `"backend"` 条目对应，资源目录即安装包解压目录）；
/// 2) dev（debug_assertions）回退：`CARGO_MANIFEST_DIR(=front/src-tauri)/backend` ——
///    模仿现有 `resolve_chrome_extension_dir` 对 extension 的 dev 回退写法。
///
/// 返回 `Some(dir)` 仅当该目录下存在 `jre/` 子目录（视为“已由 prepare-backend 铺好”的信号）；
/// 具体 java / jar 文件校验交给 [`spawn_backend`]，以便产出更精确的中文错误。
pub fn resolve_backend_dir(app: &tauri::AppHandle) -> Option<PathBuf> {
    // 打包后：随包资源目录 resource_dir()/backend
    if let Ok(resource_dir) = app.path().resource_dir() {
        let bundled = resource_dir.join("backend");
        if bundled.join("jre").is_dir() {
            return Some(bundled);
        }
    }
    // dev 回退（仅 debug 构建生效；与 resolve_chrome_extension_dir 同款 CARGO_MANIFEST_DIR 思路）
    if cfg!(debug_assertions) {
        let dev = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("backend");
        if dev.join("jre").is_dir() {
            return Some(dev);
        }
    }
    None
}

/// spawn 后端：以 `jre/bin/javaw(.exe) -jar backend/nexusai-backend.jar --spring.profiles.active=prod`
/// 启动，stdout/stderr 均追加写入 `{data_dir}/logs/backend.log`，工作目录切到数据根。
/// Windows 用 javaw.exe（GUI 版）替代 java.exe —— 不申请控制台，从根上消除 cmd 黑窗。
/// 成功返回子进程 pid（内部 `mem::forget` 托管，避免 `Child` drop 语义干扰）。
pub fn spawn_backend(app: &tauri::AppHandle) -> std::io::Result<u32> {
    // 1) 定位 backend 目录（随包资源或 dev 回退）
    let backend_dir = resolve_backend_dir(app).ok_or_else(|| {
        std::io::Error::new(
            std::io::ErrorKind::NotFound,
            "未找到随包分发的 backend 目录（应含 backend/jre 与 backend/nexusai-backend.jar）",
        )
    })?;

    // 2) 校验裁剪 JRE 的 java 可执行文件。
    //    Windows 用 javaw.exe（GUI 版不申请/不附着控制台 → 消除 cmd 黑窗）；非 Windows 无 javaw 概念，仍用 java
    let java_exe = if cfg!(windows) { "javaw.exe" } else { "java" };
    let java_path = backend_dir.join("jre").join("bin").join(java_exe);
    if !java_path.is_file() {
        return Err(std::io::Error::new(
            std::io::ErrorKind::NotFound,
            format!("未找到后端 JRE：{}", java_path.display()),
        ));
    }

    // 3) 校验后端 jar
    let jar_path = backend_dir.join("nexusai-backend.jar");
    if !jar_path.is_file() {
        return Err(std::io::Error::new(
            std::io::ErrorKind::NotFound,
            format!("未找到后端 jar：{}", jar_path.display()),
        ));
    }

    // 4) 数据目录先就绪。
    //
    // 日志口径（用户裁定 2026-10-21）：业务日志由 logback 写 info/warn/error.log（自带滚动），
    // prod profile 下不再挂 STDOUT ⇒ java 的 stdout 只剩 Spring banner，没有保留价值 → 直接丢弃。
    // stderr 仍落 backend.log：它承载 JVM/原生层的致命信息（启动早期失败、OutOfMemory 摘要等），
    // 这些刻意不经 logback，丢了就只能看到「应用没起来」而查不到原因；量极小，且带滚动兜底。
    ensure_data_dirs()?;
    let log = log_file();
    // 必须在打开句柄**之前**轮转：javaw 一旦持有 stderr 句柄，Windows 下就无法 rename
    rotate_log_if_oversized(&log);
    let stderr_file = std::fs::OpenOptions::new().create(true).append(true).open(&log)?;

    // 5) spawn：工作目录 = 数据根（应用相对路径产物落 .nexusai），prod profile
    let data_root = data_dir();
    let child = Command::new(&java_path)
        .arg("-jar")
        .arg(&jar_path)
        .arg("--spring.profiles.active=prod")
        .current_dir(&data_root)
        // stdin 置空：防止 console 子进程（即便 javaw 也常继承父句柄）因继承 stdin
        // 而被分配 / 附着控制台。
        // stdout 丢弃（prod 已无 console 日志，只剩 Spring banner，无保留价值）；
        // stderr 仍落 backend.log（JVM/原生层致命信息不经 logback，丢了就查不到启动失败原因）。
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::from(stderr_file))
        .spawn()?;

    let pid = child.id();
    // 6) 关键：mem::forget 让子进程脱离 Child 句柄托管（父进程退出时不会被 drop 收割/牵连），
    //    之后统一用 kill_process_tree(pid) 显式整树回收
    std::mem::forget(child);
    // 7) pid 落盘：Tauri 被强杀/崩溃时仍留下记录，下次启动可身份校验后兜底清理（防孤儿）
    if let Err(e) = write_backend_pid(pid) {
        eprintln!("[backend] 写入 pid 文件失败（不影响本次运行）：{}", e);
    }
    Ok(pid)
}

/// 轮询 [`backend_ready`]，每 500ms 一次，最多 `timeout` 时长；就绪返回 true，超时 false。
/// 调用方通常传 [`WAIT_BACKEND_READY_TIMEOUT`]。
pub fn wait_backend_ready(timeout: Duration) -> bool {
    let deadline = std::time::Instant::now() + timeout;
    loop {
        if backend_ready() {
            return true;
        }
        if std::time::Instant::now() >= deadline {
            return false;
        }
        std::thread::sleep(Duration::from_millis(500));
    }
}

/// 整树杀掉后端进程：Windows 执行 `taskkill /PID {pid} /T /F`。
///
/// 忽略退出码——进程已退时 taskkill 报错属幂等预期；仅当命令本身无法启动（Err）时打中文 warn，
/// 绝不 panic。非 Windows：本轮不要求，留中文 TODO。
pub fn kill_process_tree(pid: u32) {
    #[cfg(target_os = "windows")]
    {
        let pid_str = pid.to_string();
        // taskkill /PID <pid> /T /F：/T 连子进程树一起杀，/F 强制
        let result = silent_command("taskkill")
            .args(["/PID", pid_str.as_str(), "/T", "/F"])
            .status();
        if let Err(e) = result {
            eprintln!("[backend] 执行 taskkill 整树杀进程失败（pid={}）：{}", pid, e);
        }
    }

    #[cfg(not(target_os = "windows"))]
    {
        // TODO：非 Windows 平台整树杀后端（进程组 / pgrep 树）本轮未实现，退出时可能残留 java 进程
        let _ = pid;
        eprintln!("[backend] 非 Windows 平台整树杀后端进程未实现（pid={}），请手动终止 java 进程。", pid);
    }
    // 本次 kill 的目标即本会话自启后端（pid 文件只写过自启 pid）→ 顺带清 pid 记录
    clear_backend_pid();
}

#[cfg(test)]
mod log_roll_tests {
    use super::*;

    /// 唯一临时目录（std 无 tempfile 依赖 —— 本仓 Cargo 只允许既有依赖）。
    fn temp_dir(tag: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!(
            "nexusai-logroll-{tag}-{}-{}",
            std::process::id(),
            utc_timestamp().replace([' ', ':'], "-")
        ));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).expect("建临时目录");
        dir
    }

    /// 超过阈值 → 活跃文件被归档（内容不丢），且活跃路径不再存在（下次 append 从空开始）。
    /// WHY：这份日志由壳侧直接 append，logback 的滚动策略管不到 —— 没有本函数就会无界增长。
    #[test]
    fn oversized_log_is_archived_and_trimmed_to_keep() {
        let dir = temp_dir("roll");
        let active = dir.join("backend.log");
        std::fs::write(&active, vec![b'x'; 200]).unwrap();

        // 预置 2 份旧归档 → 连同本次新归档共 3 份，keep=3 时全部保留
        std::fs::write(dir.join("backend-2026-01-01-00-00-00.log"), b"old1").unwrap();
        std::fs::write(dir.join("backend-2026-01-02-00-00-00.log"), b"old2").unwrap();

        rotate_if_oversized_with(&active, 100, 3);

        assert!(!active.exists(), "归档后活跃文件必须不存在（否则会继续在同一文件上叠加）");
        let archives: Vec<String> = std::fs::read_dir(&dir)
            .unwrap()
            .filter_map(|e| e.ok())
            .map(|e| e.file_name().to_string_lossy().into_owned())
            .filter(|n| n.starts_with("backend-") && n.ends_with(".log"))
            .collect();
        assert_eq!(archives.len(), 3, "两次旧归档 + 本次新归档 = 3，实得：{archives:?}");

        // 再滚一次（4 份）→ keep=3 必须删掉最旧的一份
        std::fs::write(&active, vec![b'y'; 200]).unwrap();
        rotate_if_oversized_with(&active, 100, 3);
        let after: Vec<String> = std::fs::read_dir(&dir)
            .unwrap()
            .filter_map(|e| e.ok())
            .map(|e| e.file_name().to_string_lossy().into_owned())
            .filter(|n| n.starts_with("backend-") && n.ends_with(".log"))
            .collect();
        assert_eq!(after.len(), 3, "保留份数必须封顶，实得：{after:?}");
        assert!(
            after.iter().all(|n| n != "backend-2026-01-01-00-00-00.log"),
            "最旧归档必须被删除，实得：{after:?}"
        );

        let _ = std::fs::remove_dir_all(&dir);
    }

    /// 未超阈值 / 文件不存在 → 完全不动（不能在正常启动时把活跃日志改名）。
    #[test]
    fn small_or_missing_log_is_untouched() {
        let dir = temp_dir("noop");
        let active = dir.join("backend.log");
        std::fs::write(&active, b"small").unwrap();

        rotate_if_oversized_with(&active, 1024, 3);
        assert!(active.exists(), "未超阈值不得归档");

        let missing = dir.join("missing.log");
        rotate_if_oversized_with(&missing, 1, 3);
        assert!(!missing.exists(), "不存在的文件不得被创建");

        let _ = std::fs::remove_dir_all(&dir);
    }
}
