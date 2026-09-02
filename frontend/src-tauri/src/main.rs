// Prevents additional console window on Windows in release, DO NOT REMOVE!!
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use std::path::PathBuf;

use tauri::Manager;

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
    tauri::Builder::default()
        .plugin(tauri_plugin_dialog::init())
        .plugin(tauri_plugin_fs::init())
        .invoke_handler(tauri::generate_handler![
            chrome_extension_dir,
            is_chrome_installed,
            install_chrome_extension
        ])
        .setup(|app| {
            // 启动时按显示器尺寸 85% 设置窗口（用户拍板：百分比 85%）
            if let Some(window) = app.get_webview_window("main") {
                if let Some(monitor) = window.current_monitor().ok().flatten() {
                    let s = monitor.size();
                    let w = (s.width as f64 * 0.85) as u32;
                    let h = (s.height as f64 * 0.85) as u32;
                    let _ = window.set_size(tauri::PhysicalSize::new(w, h));
                    let _ = window.center();
                }
            }
            Ok(())
        })
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
