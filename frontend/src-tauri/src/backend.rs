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
fn utc_timestamp() -> String {
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

    // 4) 数据目录先就绪，日志文件追加打开
    ensure_data_dirs()?;
    let log = log_file();
    // stdout / stderr 各自独立 File handle 打开同一个 log（append+create）：
    // 两个 Stdio 分别接管一个句柄，避免同一句柄被两处持有
    let stdout_file = std::fs::OpenOptions::new().create(true).append(true).open(&log)?;
    let stderr_file = std::fs::OpenOptions::new().create(true).append(true).open(&log)?;

    // 5) spawn：工作目录 = 数据根（应用相对路径产物落 .nexusai），prod profile
    let data_root = data_dir();
    let child = Command::new(&java_path)
        .arg("-jar")
        .arg(&jar_path)
        .arg("--spring.profiles.active=prod")
        .current_dir(&data_root)
        // stdin 置空：防止 console 子进程（即便 javaw 也常继承父句柄）因继承 stdin
        // 而被分配 / 附着控制台；stdout/stderr 仍重定向 backend.log（父传句柄，javaw 下同样可写盘）
        .stdin(Stdio::null())
        .stdout(Stdio::from(stdout_file))
        .stderr(Stdio::from(stderr_file))
        .spawn()?;

    let pid = child.id();
    // 6) 关键：mem::forget 让子进程脱离 Child 句柄托管（父进程退出时不会被 drop 收割/牵连），
    //    之后统一用 kill_process_tree(pid) 显式整树回收
    std::mem::forget(child);
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
        let result = Command::new("taskkill")
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
}
