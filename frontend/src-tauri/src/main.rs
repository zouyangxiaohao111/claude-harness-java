// Prevents additional console window on Windows in release, DO NOT REMOVE!!
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use std::path::PathBuf;

use tauri::{Emitter, Manager};

/// 后端进程生命周期管理（T12）：探活 / 启动 / 等待就绪 / 整树回收本地后端 java 进程。
mod backend;
/// 自动更新（多源 latest.json 检查 / 下载 sha256 / NSIS 安装）。
mod updater;

/// 本会话由 Tauri 壳自启的后端 java 进程 pid。
/// None = 启动时 3458 已有外部后端在跑（复用）或尚未自启 —— 此时关窗不回收外部进程。
struct BackendState(std::sync::Mutex<Option<u32>>);

/// 主窗口 label。tauri.conf.json `app.windows` 未写 label，Tauri 首个窗口默认取 "main"。
const MAIN_WINDOW_LABEL: &str = "main";

/// 关窗事件是否应当回收后端？应当则返回要回收的 pid（`None` = 不回收）。
///
/// 【只认主窗口】`Builder::on_window_event` 是**应用级**钩子（对所有窗口都触发），
/// 而独立预览窗口（`standalone-preview`，承载 PDF/Word/HTML/Excel/图片等）关闭**不等于**
/// 应用退出——若一并回收，就会连带杀掉端口 3458 的后端（历史缺陷：用户关预览把后端也关了）。
/// 未知 / 空 label 一律不回收：宁可留孤儿让 [`backend::cleanup_stale_backend_pid`] 下次兜底，
/// 也不误杀正在服务的后端。
///
/// ⛔ **`take()` 必须由本函数执行，调用方不得先取出 pid 再判 label**：非主窗口关闭时
/// 只判定、**不出栈**——否则 pid 被子窗口提前消费，之后真正关主窗口时 state 已是 `None`，
/// 后端回收不掉 → 反而制造孤儿（与防孤儿的初衷相反）。
fn reclaim_target_on_close(
    window_label: &str,
    state: &std::sync::Mutex<Option<u32>>,
) -> Option<u32> {
    if window_label == MAIN_WINDOW_LABEL {
        state.lock().unwrap().take()
    } else {
        None
    }
}

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

/// 返回扩展 ZIP 包绝对路径（内含 manifest.json 等 5 文件，供备份/手动安装/分享）。
/// 1) 优先打包资源目录 resources/extension-pack/nexusai-extension.zip（随安装包分发，安装后与 extension 同级）；
/// 2) 开发模式资源目录无该 zip 时，回退 src-tauri/extension-pack/nexusai-extension.zip（make-extension-zip.mjs 产物）。
fn resolve_chrome_extension_zip_path(app: &tauri::AppHandle) -> Result<String, String> {
    // 打包后：resource_dir()/extension-pack/nexusai-extension.zip
    let resource_dir = app.path().resource_dir().map_err(|e| e.to_string())?;
    let bundled = resource_dir
        .join("extension-pack")
        .join("nexusai-extension.zip");
    if bundled.is_file() {
        return Ok(bundled.to_string_lossy().to_string());
    }
    // dev 回退：env!("CARGO_MANIFEST_DIR") = 编译期 src-tauri/，extension-pack/nexusai-extension.zip 即脚本产物
    let dev = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("extension-pack")
        .join("nexusai-extension.zip");
    if dev.is_file() {
        return Ok(dev.to_string_lossy().to_string());
    }
    Err(format!(
        "未找到扩展 zip 包（resources/extension-pack/nexusai-extension.zip 与 src-tauri/extension-pack/nexusai-extension.zip 均不存在；候选路径：{} / {}）",
        bundled.to_string_lossy(),
        dev.to_string_lossy()
    ))
}

/// 获取 Chrome 扩展 ZIP 包路径（供前端展示 zip 备份/手动安装位置）。
#[tauri::command]
fn chrome_extension_zip_path(app: tauri::AppHandle) -> Result<String, String> {
    resolve_chrome_extension_zip_path(&app)
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
        backend::silent_command("tasklist")
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
            if let Some(window) = app.get_webview_window(MAIN_WINDOW_LABEL) {
                let _ = window.set_focus();
            }
        }))
        .manage(BackendState(std::sync::Mutex::new(None)))
        .plugin(tauri_plugin_dialog::init())
        .plugin(tauri_plugin_fs::init())
        .invoke_handler(tauri::generate_handler![
            chrome_extension_dir,
            chrome_extension_zip_path,
            is_chrome_installed,
            install_chrome_extension,
            updater::app_version,
            updater::update_check,
            updater::update_download,
            updater::update_install
        ])
        .setup(|app| {
            // ===== 启动期小窗（承载 loader）+ 就绪放大交给前端 =====
            // 背景：后端冷启动实测可能 >8s（首次 Flyway 全量迁移 / c3p0 / Quartz bean 初始化）。
            // 若窗口初始就是 85% 大窗，首帧将长时间空白/白屏。故启动期用 460x400 小窗承载 loader，
            // 前端收到 backend-ready 事件后自驱放大到显示器 85%（放大权在前端，L2 前端实现）。
            // 注：460x400 小于 tauri.conf.json 的 minWidth/minHeight(960x600)，先临时下调最小尺寸，
            //     否则 set_size 会被系统 clamp 回 960x600，小窗不生效；前端放大后如需恢复
            //     960x600 下限，由前端 setMinSize 再设回（后端就绪前保持小窗由壳保证）。
            if let Some(window) = app.get_webview_window(MAIN_WINDOW_LABEL) {
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

            // [T12-pid] 仅 release：启动前清理上次自启残留（身份校验 —— 只杀命令行含
            //   nexusai-backend.jar 的后端，pid 复用/其它 java 一律不碰）；dev 由外部/IDE 后端
            //   自行管理，不做跨进程清理。
            if !cfg!(debug_assertions) {
                backend::cleanup_stale_backend_pid();
            }

            // 2) 3458 无后端才自启；已有（外部进程 / 上一会话残留）则直接复用，不重复拉起
            if !backend::backend_ready() {
                match backend::spawn_backend(&handle) {
                    Ok(pid) => {
                        // 记录 pid 到 state（关窗/退出整树回收）；pid 落盘已在 spawn_backend 内完成
                        *app.state::<BackendState>().0.lock().unwrap() = Some(pid);
                        eprintln!("[backend] 已启动本地后端，pid={}，后台等待健康就绪…", pid);

                        // 3) 关键：setup 不阻塞主线程 —— 起后台线程轮询 /actuator/health 最多 60s，
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
                    }
                    Err(e) => {
                        if cfg!(debug_assertions) {
                            // dev 模式（tauri dev）：不一定随包分发 backend —— 不因缺少捆绑后端而 panic，
                            // 跳过自启、交给前端探活（外部后端通常由 IDE 单独启动 3458）。日志中文便于排障。
                            let msg = format!(
                                "dev 模式未找到随包后端，跳过自启（请用 IDE 启动后端 3458）。原因：{}",
                                e
                            );
                            backend::log_launcher(&msg);
                            eprintln!("[backend-launcher] {}", msg);
                        } else {
                            // release：捆绑后端缺失属部署错误 → 显式失败（fail loud）
                            let msg = format!(
                                "本地后端启动失败：{}（请查看 {}/logs/backend.log）",
                                e,
                                backend::data_dir().display()
                            );
                            backend::log_launcher(&msg);
                            return Err(msg.into());
                        }
                    }
                }
            } else {
                eprintln!("[backend] 检测到 3458 已有后端在运行，跳过自启");
                // dev 等外部后端已就绪：立即通知前端。
                // 注：此刻 webview 可能尚未挂载监听，事件可能 miss —— 前端启动时应自带一次探活兜底
                let _ = handle.emit("backend-ready", ());
            }

            Ok(())
        })
        .on_window_event(|window, event| {
            // 用户关窗：整树杀掉本会话自启的后端 java（防孤儿）。本钩子对所有窗口触发，
            // 是否该回收（及出栈）一律交给 reclaim_target_on_close 判定。
            if let tauri::WindowEvent::CloseRequested { .. } = event {
                let state = window.app_handle().state::<BackendState>();
                if let Some(p) = reclaim_target_on_close(window.label(), &state.0) {
                    // 走 log_launcher：release 无控制台，eprintln 会丢，文件才是唯一排障通道
                    backend::log_launcher(&format!("主窗口关闭，回收后端进程树 pid={}", p));
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

#[cfg(test)]
mod tests {
    use super::*;

    fn state_of(pid: Option<u32>) -> std::sync::Mutex<Option<u32>> {
        std::sync::Mutex::new(pid)
    }

    /// 主窗口关闭 → 必须回收（防孤儿的原始意图，不许丢），且出栈后 state 置 None
    /// （保证 `RunEvent::ExitRequested` 兜底分支幂等，不会重复 kill）。
    #[test]
    fn main_window_close_reclaims_and_clears_state() {
        let state = state_of(Some(1234));
        assert_eq!(reclaim_target_on_close("main", &state), Some(1234));
        assert_eq!(*state.lock().unwrap(), None);
        // 无 pid（启动时复用外部后端）⇒ 无事发生
        assert_eq!(reclaim_target_on_close("main", &state_of(None)), None);
    }

    /// 独立预览子窗口（PDF/Word/HTML/Excel/图片）关闭 → **绝不**回收后端。
    /// 这是本次缺陷的不变量：关预览 ≠ 应用退出，不该连带杀掉端口 3458 的后端。
    #[test]
    fn preview_child_window_close_does_not_reclaim_backend() {
        assert_eq!(
            reclaim_target_on_close("standalone-preview", &state_of(Some(1234))),
            None
        );
    }

    /// ⛔ 子窗口关闭**不得消费掉 pid**：否则之后关主窗口时 state 已空 → 后端回收不掉，
    /// 「防孤儿」反而变成「制造孤儿」。这条守的是 `take()` 的**时机**，不只是返回值。
    #[test]
    fn child_window_close_must_not_consume_pid() {
        let state = state_of(Some(1234));
        assert_eq!(reclaim_target_on_close("standalone-preview", &state), None);
        assert_eq!(*state.lock().unwrap(), Some(1234)); // pid 仍在
        // 之后关主窗口仍能正常回收
        assert_eq!(reclaim_target_on_close("main", &state), Some(1234));
    }

    /// 空 / 未知 label → 不回收（保守：宁可留孤儿给下次启动兜底，也不误杀在服务的后端）。
    #[test]
    fn unknown_or_empty_label_does_not_reclaim_backend() {
        assert_eq!(reclaim_target_on_close("", &state_of(Some(1234))), None);
        assert_eq!(
            reclaim_target_on_close("some-other-window", &state_of(Some(1234))),
            None
        );
        assert_eq!(
            reclaim_target_on_close("Main", &state_of(Some(1234))),
            None // label 大小写敏感，不做模糊匹配
        );
    }

    /// ⭐ 接线层守护（源码级）：把「该不该杀、杀哪个」抽成纯函数后，上面几条只守住了
    /// **函数本身**，守不住「钩子是否真的把 `window.label()` 喂进来了」——而 Tauri 的
    /// `on_window_event` 钩子需要 App 运行时，**无法在单测里执行**，故行为级测试永远抓不到
    /// 「钩子绕过判定函数、恢复原始 bug」这类变异（实测：钩子改 `if true` 时上列测试全绿）。
    /// 本测试补上这一层。
    ///
    /// ⚠️ **局限（如实登记，非完备守护）**：这是字符串级守护，只拦「调用被整行删除 / 改名」，
    /// 拦不住「改了传参」或等价改写（如 `if window.label() == "main"`）。它是对
    /// 「钩子不可单测」这一平台约束的妥协。
    #[test]
    fn close_hook_must_route_through_reclaim_target() {
        let src = include_str!("main.rs");
        // 只查 `#[cfg(test)]` 之前的生产代码段：否则本测试自己的字符串字面量会自匹配 → 假绿
        let prod = src.split("#[cfg(test)]").next().unwrap_or("");
        assert!(
            prod.contains("reclaim_target_on_close(window.label()"),
            "关窗钩子必须经由 reclaim_target_on_close(window.label(), …) 判定；\
             绕过它直接 kill 会复活「关独立预览窗连带杀掉后端」缺陷"
        );
    }
}
