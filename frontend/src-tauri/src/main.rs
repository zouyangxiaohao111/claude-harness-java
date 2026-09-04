// Prevents additional console window on Windows in release, DO NOT REMOVE!!
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use std::path::PathBuf;

use tauri::{Emitter, Manager};

/// 后端进程生命周期管理（T12）：探活 / 启动 / 等待就绪 / 整树回收本地后端 java 进程。
mod backend;

/// 本会话由 Tauri 壳自启的后端 java 进程 pid。
/// None = 启动时 3458 已有外部后端在跑（复用）或尚未自启 —— 此时关窗不回收外部进程。
struct BackendState(std::sync::Mutex<Option<u32>>);

/// 查找本机 Chrome / Chromium 可执行文件。
/// Windows：Program Files / Program Files (x86) / %LOCALAPPDATA%；
/// macOS：/Applications/Google Chrome.app；
/// Linux：PATH 内 google-chrome / chromium 系列（等价 which 语义）。
fn find_chrome() -> Option<PathBuf> {
    let mut candidates: Vec<PathBuf> = Vec::new();

    #[cfg(target_os = "windows")]
    {
        candidates.push(PathBuf::from(r"C:\Program Files\Google\Chrome\Application\chrome.exe"));
        candidates.push(PathBuf::from(r"C:\Program Files (x86)\Google\Chrome\Application\chrome.exe"));
        if let Ok(local) = std::env::var("LOCALAPPDATA") {
            candidates.push(PathBuf::from(format!(r"{}\Google\Chrome\Application\chrome.exe", local)));
        }
    }

    #[cfg(target_os = "macos")]
    {
        candidates.push(PathBuf::from("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"));
    }

    // macOS 与 Linux 都额外扫 PATH（mac 也可能只有 chromimum；Linux 必须经 PATH）
    #[cfg(not(target_os = "windows"))]
    {
        for name in ["google-chrome", "google-chrome-stable", "chromium", "chromium-browser"] {
            if let Some(p) = which_in_path(name) {
                candidates.push(p);
            }
        }
    }

    candidates.into_iter().find(|p| p.is_file())
}

/// 在 PATH 中查找可执行文件（Linux 的 which 语义，不依赖外部 which 命令）。
#[cfg(not(target_os = "windows"))]
fn which_in_path(name: &str) -> Option<PathBuf> {
    let path = std::env::var_os("PATH")?;
    for dir in std::env::split_paths(&path) {
        let p = dir.join(name);
        if p.is_file() {
            return Some(p);
        }
    }
    None
}

/// 返回扩展目录绝对路径（内含 manifest.json）。
/// 1) 优先打包资源目录 resources/extension（安装后随包分发）；
/// 2) 开发模式资源目录无 extension 时，回退项目根 extension/（CARGO_MANIFEST_DIR 上一级）。
fn resolve_chrome_extension_dir(app: &tauri::AppHandle) -> Result<String, String> {
    // 打包后：resource_dir()/extension
    let resource_dir = app.path().resource_dir().map_err(|e| e.to_string())?;
    let bundled = resource_dir.join("extension");
    if bundled.join("manifest.json").is_file() {
        return Ok(bundled.to_string_lossy().to_string());
    }
    // dev 回退：env!("CARGO_MANIFEST_DIR") = 编译期 src-tauri/，其上一级即项目根 extension/
    // （CARGO_MANIFEST_DIR 是编译期常量，运行时用 env! 宏内联；std::env::var 运行时读不到）
    let dev = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../extension");
    if dev.join("manifest.json").is_file() {
        return Ok(dev.to_string_lossy().to_string());
    }
    Err(format!(
        "未找到扩展目录（resources/extension 与项目根 extension/ 均无 manifest.json，当前资源目录：{}）",
        resource_dir.to_string_lossy()
    ))
}

/// 获取 Chrome 扩展目录路径（供前端展示安装位置）。
#[tauri::command]
fn chrome_extension_dir(app: tauri::AppHandle) -> Result<String, String> {
    resolve_chrome_extension_dir(&app)
}

/// 检测本机是否安装 Chrome / Chromium。
#[tauri::command]
fn is_chrome_installed() -> Result<bool, String> {
    Ok(find_chrome().is_some())
}

/// 检测 Chrome / Chromium 是否正在运行。
/// Windows：tasklist 查 chrome.exe；macOS/Linux：pgrep 查进程名。
fn is_chrome_running() -> bool {
    #[cfg(target_os = "windows")]
    {
        std::process::Command::new("tasklist")
            .output()
            .ok()
            .map(|o| String::from_utf8_lossy(&o.stdout).to_lowercase().contains("chrome.exe"))
            .unwrap_or(false)
    }
    #[cfg(target_os = "macos")]
    {
        std::process::Command::new("pgrep")
            .args(["-x", "Google Chrome"])
            .output()
            .map(|o| o.status.success())
            .unwrap_or(false)
    }
    #[cfg(target_os = "linux")]
    {
        std::process::Command::new("pgrep")
            .args(["-x", "google-chrome"])
            .output()
            .map(|o| o.status.success())
            .unwrap_or_else(|_| {
                std::process::Command::new("pgrep")
                    .args(["-x", "chromium"])
                    .output()
                    .map(|o| o.status.success())
                    .unwrap_or(false)
            })
    }
}

/// 一键安装/引导加载扩展。
///
/// <p><b>关键语义（修复 --load-extension 两个坑）</b>：
/// <ol>
///   <li><b>Chrome 已运行 → --load-extension 被静默忽略</b>（spawn 附加到已有单例实例）。
///       此时改引导「chrome://extensions → 开发者模式 → 加载已解压的扩展程序」（选 ext_dir），
///       该加载方式<b>持久</b>（Chrome 重启后扩展仍保留）。</li>
///   <li><b>Chrome 未运行 → 冷启动 --load-extension</b>（本次会话有效；命令行加载在 Chrome
///       重启后消失，文案提示建议持久化安装）。</li>
/// </ol>
#[tauri::command]
fn install_chrome_extension(app: tauri::AppHandle) -> Result<String, String> {
    let ext_dir = resolve_chrome_extension_dir(&app)?;
    let chrome = find_chrome()
        .ok_or_else(|| "未找到 Chrome，请先安装：https://www.google.com/chrome/".to_string())?;

    if is_chrome_running() {
        // Chrome 运行中：--load-extension 无效。打开 chrome://extensions 引导持久化加载。
        std::process::Command::new(&chrome)
            .arg("--new-window")
            .arg("chrome://extensions")
            .spawn()
            .map_err(|e| format!("启动 Chrome 失败：{}", e))?;
        return Ok(format!(
            "检测到 Chrome 正在运行（命令行 --load-extension 会失效）。已打开 chrome://extensions 页面，\n\
             请按以下步骤完成持久化安装（Chrome 重启后仍保留）：\n\
             1. 开启右上角「开发者模式」开关\n\
             2. 点「加载已解压的扩展程序」\n\
             3. 选择扩展目录：{}",
            ext_dir
        ));
    }

    // Chrome 未运行：冷启动 --load-extension（本次会话有效）
    std::process::Command::new(&chrome)
        .arg(format!("--load-extension={}", ext_dir))
        .arg("--new-window")
        .arg("chrome://extensions")
        .spawn()
        .map_err(|e| format!("启动 Chrome 失败：{}", e))?;

    Ok(format!(
        "Chrome 已冷启动并加载扩展（--load-extension={}）。\n\
         注意：命令行加载在 Chrome 重启后失效——建议按「开发者模式 → 加载已解压」做持久化安装（重启后仍保留）。",
        ext_dir
    ))
}

fn main() {
    let app = tauri::Builder::default()
        // 单实例：第二实例启动即退出并把已运行实例的主窗口置前聚焦，
        // 防止两个壳实例各自拉起后端、争抢 3458 端口（窗口 label 默认 "main"）
        .plugin(tauri_plugin_single_instance::init(|app, _args, _cwd| {
            if let Some(window) = app.get_webview_window("main") {
                let _ = window.set_focus();
            }
        }))
        .manage(BackendState(std::sync::Mutex::new(None)))
        .plugin(tauri_plugin_dialog::init())
        .plugin(tauri_plugin_fs::init())
        .invoke_handler(tauri::generate_handler![
            chrome_extension_dir,
            is_chrome_installed,
            install_chrome_extension
        ])
        .setup(|app| {
            // ===== 启动期小窗（承载 loader）+ 就绪放大交给前端 =====
            // 背景：后端冷启动实测可能 >8s（首次 Flyway 全量迁移 / c3p0 / Quartz bean 初始化）。
            // 若窗口初始就是 85% 大窗，首帧将长时间空白/白屏。故启动期用 460x400 小窗承载 loader，
            // 前端收到 backend-ready 事件后自驱放大到显示器 85%（放大权在前端，L2 前端实现）。
            // 注：460x400 小于 tauri.conf.json 的 minWidth/minHeight(960x600)，先临时下调最小尺寸，
            //     否则 set_size 会被系统 clamp 回 960x600，小窗不生效；前端放大后如需恢复
            //     960x600 下限，由前端 setMinSize 再设回（后端就绪前保持小窗由壳保证）。
            if let Some(window) = app.get_webview_window("main") {
                let _ = window.set_min_size(Some(tauri::PhysicalSize::new(460, 400)));
                let _ = window.set_size(tauri::PhysicalSize::new(460, 400));
                let _ = window.center();
            }

            // ===== 后端进程生命周期（T12）=====
            // 1) 数据目录 .nexusai 与 .nexusai/logs 就绪（后端日志落盘前提）。
            //    失败先把关键信息写启动器日志 tauri-launcher.log（release 无控制台时唯一排障通道），再返回 Err
            if let Err(e) = backend::ensure_data_dirs() {
                let msg = format!("初始化本地数据目录失败：{}（请检查用户主目录权限）", e);
                backend::log_launcher(&msg);
                return Err(msg.into());
            }

            // 供后台线程 emit 事件；clone 出独立 AppHandle，避免线程内借用 app
            let handle = app.handle().clone();

            // 2) 3458 无后端才自启；已有（外部进程 / 上一会话残留）则直接复用，不重复拉起
            if !backend::backend_ready() {
                // 3) spawn 随包裁剪 JRE：javaw -jar backend/nexusai-backend.jar --spring.profiles.active=prod
                let pid = backend::spawn_backend(&handle).map_err(|e| {
                    let msg = format!(
                        "本地后端启动失败：{}（请查看 {}/logs/backend.log）",
                        e,
                        backend::data_dir().display()
                    );
                    backend::log_launcher(&msg);
                    msg
                })?;
                // 记录 pid 到 state，供关窗 / 退出时整树回收
                *app.state::<BackendState>().0.lock().unwrap() = Some(pid);
                eprintln!("[backend] 已启动本地后端，pid={}，后台等待健康就绪…", pid);

                // 4) 关键：setup 不阻塞主线程 —— 起后台线程轮询 /actuator/health 最多 60s，
                //    窗口首帧立即渲染 loader（Spring Boot prod 冷启动实测 >8s：首次 Flyway 全量迁移 /
                //    c3p0 / Quartz bean 初始化）。就绪 / 超时都经事件通知前端（事件名定死，L2 前端 listen）：
                //    - backend-ready：后端已就绪，前端据此放大窗口到 85%
                //    - backend-error：启动超时，前端据此提示；超时先 kill_process_tree(pid) 防孤儿
                std::thread::spawn(move || {
                    if backend::wait_backend_ready(backend::WAIT_BACKEND_READY_TIMEOUT) {
                        eprintln!("[backend] 本地后端已就绪：http://localhost:3458/actuator/health");
                        let _ = handle.emit("backend-ready", ());
                    } else {
                        backend::kill_process_tree(pid);
                        let msg = format!(
                            "本地后端启动超时（{} 秒内未就绪，已回收进程树 pid={}），请查看 {}/logs/backend.log（中文）。",
                            backend::WAIT_BACKEND_READY_TIMEOUT.as_secs(),
                            pid,
                            backend::data_dir().display()
                        );
                        backend::log_launcher(&msg);
                        let _ = handle.emit("backend-error", msg);
                    }
                });
            } else {
                eprintln!("[backend] 检测到 3458 已有后端在运行，跳过自启");
                // dev 等外部后端已就绪：立即通知前端。
                // 注：此刻 webview 可能尚未挂载监听，事件可能 miss —— 前端启动时应自带一次探活兜底
                let _ = handle.emit("backend-ready", ());
            }

            Ok(())
        })
        .on_window_event(|window, event| {
            // 用户关窗：整树杀掉本会话自启的后端 java（防孤儿）；take() 后 state 置 None
            if let tauri::WindowEvent::CloseRequested { .. } = event {
                let pid = window
                    .app_handle()
                    .state::<BackendState>()
                    .0
                    .lock()
                    .unwrap()
                    .take();
                if let Some(p) = pid {
                    eprintln!("[backend] 窗口关闭，回收后端进程树 pid={}", p);
                    backend::kill_process_tree(p);
                }
            }
        })
        .build(tauri::generate_context!())
        .expect("error while building tauri application");

    app.run(|app_handle, event| {
        // 兜底：非“关窗”路径的退出（RunEvent::ExitRequested）再回收一次；
        // take() 幂等——若已在关窗时 kill 过则此处为 None，直接跳过
        if let tauri::RunEvent::ExitRequested { .. } = event {
            let pid = app_handle
                .state::<BackendState>()
                .0
                .lock()
                .unwrap()
                .take();
            if let Some(p) = pid {
                eprintln!("[backend] 应用退出，回收后端进程树 pid={}", p);
                backend::kill_process_tree(p);
            }
        }
    });
}
