; NexusAI installer hooks (included by Tauri NSIS template, Unicode build).
;
; 1) Install: write the install dir to the per-user environment so a backend
;    started OUTSIDE the Tauri shell can still resolve <install>/data/workspace.
;    Runtime precedence: the shell injects NEXUSAI_INSTALL_DIR into the backend
;    process env (backend.rs), which wins over this registry value.
; 2) Uninstall: 用户数据默认保留；用户要删时用 **Tauri 自带的**「Delete the
;    application data」复选框（卸载确认页，默认不勾选）。
;
;    ⛔ 我们**不再自己弹 MessageBox**：
;       Tauri v2 的 NSIS 模板已在卸载确认页放了那个复选框
;       （模板函数 un.ConfirmShow 创建、un.ConfirmLeave 把状态写进
;       $DeleteAppDataCheckboxState；默认未勾选）。
;       再加一个自己的弹窗 = 用户面对两个"删不删数据"的询问，
;       而且会破坏 /S（静默卸载）与 /P（被动模式）流程。
;
;    ⛔ 但 Tauri 自己的处理只删 %APPDATA%\com.nexusai.client 与
;       %LOCALAPPDATA%\com.nexusai.client（Tauri 的 app-data 目录），
;       **我们的用户数据不在那里**：
;         - <install>\data\...  工作区 / 知识库原件 / 产物 / 各类缓存
;         - ~\.nexusai\...      **nexusai.db（真正的库）** / agents / plugins / logs
;       ⇒ 复选框勾选时，由本钩子把这两处一并删掉（= 用户说的"全部删除"）。
;
;    ⛔ 未勾选（默认）时**什么都不用做**：Tauri 只会逐条 Delete 它自己安装的
;       文件（data 不在其中），最后的 RMDir "$INSTDIR" 是**非递归**的
;       ⇒ <install>\data 原地留存，不会被删。
;       （曾经担心 Tauri 会 RMDir /r "$INSTDIR"，已核对生成的 installer.nsi：
;        38 处 RMDir 全是 /REBOOTOK 或普通 RMDir，**没有一处递归删 $INSTDIR**。）
;
;    WM_SETTINGCHANGE = 0x001A, HWND_BROADCAST = 0xFFFF.

!include LogicLib.nsh

; 配置自有根（NexusaiPaths.getAppConfigHomeDir() = {user.home}\.{appName}，
; appName 由 spring.application.name 固定为 nexusai）⇒ %USERPROFILE%\.nexusai
; ⛔ 别名化：便于测试时指向临时目录（勿在生产里改）。
!ifndef NEXUSAI_CONFIG_HOME
  !define NEXUSAI_CONFIG_HOME "$PROFILE\.nexusai"
!endif

; 旧版（≤0.1.74）卸载残留：把 data\workspace 搬到同级目录再搬回；
; 若上次卸载被中断 ⇒ 同级目录里会留下它。目标已存在时不搬也不删（不覆盖新数据、不丢旧数据）。
!define NEXUSAI_LEGACY_KEEP_DIR "$INSTDIR\..\NexusAI-workspace-keep"

!macro NSIS_HOOK_POSTINSTALL
  WriteRegExpandStr HKCU "Environment" "NEXUSAI_INSTALL_DIR" "$INSTDIR"
  System::Call 'user32::SendMessageTimeoutW(i 0xFFFF, i 0x001A, i 0, w "Environment", i 0, i 5000, *i .r0)'

  ; ── 随包 Git（MinGit）环境变量 · 三层注入的第二层 ──
  ;
  ; WHY 必须写真实环境变量：后端不一定由 Tauri 拉起（dev / 脚本 / 独立部署），
  ; 这些路径没有 spawn_backend 的进程级注入，Agent 运行时找不到 Git Bash。
  ;
  ; 策略：
  ;   · PATH **末尾追加**（不是前置）⇒ 系统 Git 仍优先命中；
  ;   · CLAUDE_CODE_GIT_BASH_PATH 只在系统没有 Git 时写，避免抢用户已有版本；
  ;   · 写入的段记在 HKCU\Software\NexusAI\BundledGit，供升级/卸载**精确**移除。
  ;
  ; ── [2026-11-23 安装提速] 随包 Git = 单个压缩包，**按需**解压 ──
  ;
  ; WHY：旧版把解压好的 214MB / **4190 个小文件**直接打进安装包 ⇒ 每次装 app
  ; 都要逐个写盘（叠加杀软逐文件扫描）⇒ 用户反馈"安装卡很久"。
  ; 现在安装包内只有一个 portable-git.7z.exe（57MB），先探测系统 Git：
  ;   · 已有 Git for Windows（注册表有 InstallPath）⇒ **完全不解压**（多数机器）
  ;   · 没有 ⇒ 现场解压到 backend\git（一次性约 8–10s，仅这些机器付一次）
  ; 解压/跳过之后，继续走下面的 PATH 注入逻辑（它对 bash.exe 是否存在做判断）。
  IfFileExists "$INSTDIR\backend\git\bin\bash.exe" nexusai_git_probe_done nexusai_git_probe_system

  nexusai_git_probe_system:
    ; 与下方 ③ 同一判据（Git for Windows 安装时写这两个注册表项）
    ReadRegStr $R5 HKLM "SOFTWARE\GitForWindows" "InstallPath"
    StrCmp $R5 "" 0 nexusai_git_probe_done
    ReadRegStr $R5 HKCU "SOFTWARE\GitForWindows" "InstallPath"
    StrCmp $R5 "" 0 nexusai_git_probe_done
    ; 系统无 Git ⇒ 用随包压缩包解压（SFX 静默：-o<目录> -y）
    IfFileExists "$INSTDIR\backend\portable-git.7z.exe" 0 nexusai_git_probe_done
      DetailPrint "本机未检测到 Git —— 正在解压随包 Git（一次性，约 10 秒）..."
      ExecWait '"$INSTDIR\backend\portable-git.7z.exe" -o"$INSTDIR\backend\git" -y'

  nexusai_git_probe_done:
  IfFileExists "$INSTDIR\backend\git\bin\bash.exe" 0 nexusai_git_done

  StrCpy $R0 "$INSTDIR\backend\git\cmd;$INSTDIR\backend\git\bin;$INSTDIR\backend\git\usr\bin"
  ReadRegStr $R1 HKCU "Software\NexusAI\BundledGit" "InjectedSegment"
  ReadRegStr $R2 HKCU "Environment" "PATH"

  ; ① 旧版本注入段（安装路径可能变化）→ 仅在末尾命中时精确移除
  StrCmp $R1 "" nexusai_git_need_append
  StrLen $R3 "$R1"
  StrCpy $R4 "$R2" "" -$R3
  StrCmp $R4 "$R1" nexusai_git_remove_old nexusai_git_need_append
  nexusai_git_remove_old:
    StrCpy $R2 "$R2" -$R3
    ; 去掉截断后残留的末尾分号（否则反复升级会累积空项 ";;"）
    StrCpy $R4 "$R2" 1 -1
    StrCmp $R4 ";" 0 nexusai_git_need_append
      StrCpy $R2 "$R2" -1

  ; ② 新段已在末尾 ⇒ 幂等跳过
  nexusai_git_need_append:
  StrLen $R3 "$R0"
  StrCpy $R4 "$R2" "" -$R3
  StrCmp $R4 "$R0" nexusai_git_path_ready nexusai_git_do_append
  nexusai_git_do_append:
    StrCmp $R2 "" nexusai_git_path_empty nexusai_git_path_nonempty
  nexusai_git_path_empty:
    StrCpy $R2 "$R0"
    Goto nexusai_git_path_ready
  nexusai_git_path_nonempty:
    StrCpy $R2 "$R2;$R0"
  nexusai_git_path_ready:
  WriteRegExpandStr HKCU "Environment" "PATH" "$R2"
  WriteRegStr HKCU "Software\NexusAI\BundledGit" "InjectedSegment" "$R0"

  ; ③ 系统已有 Git（Git for Windows 注册表项）⇒ 不写 bash 路径，保证系统优先。
  ;    便携版/手工解压的 Git 检测不到，会落到随包 Git —— 可用性不受影响，仅版本选择不同。
  ReadRegStr $R5 HKLM "SOFTWARE\GitForWindows" "InstallPath"
  StrCmp $R5 "" 0 nexusai_git_broadcast
  ReadRegStr $R5 HKCU "SOFTWARE\GitForWindows" "InstallPath"
  StrCmp $R5 "" 0 nexusai_git_broadcast
    WriteRegExpandStr HKCU "Environment" "CLAUDE_CODE_GIT_BASH_PATH" "$INSTDIR\backend\git\bin\bash.exe"

  nexusai_git_broadcast:
  System::Call 'user32::SendMessageTimeoutW(i 0xFFFF, i 0x001A, i 0, w "Environment", i 0, i 5000, *i .r0)'
  nexusai_git_done:
!macroend

!macro NSIS_HOOK_PREUNINSTALL
  ; --- 环境变量（仅当仍指向本次安装） ---
  ReadRegStr $0 HKCU "Environment" "NEXUSAI_INSTALL_DIR"
  ${If} $0 == "$INSTDIR"
    DeleteRegValue HKCU "Environment" "NEXUSAI_INSTALL_DIR"
    System::Call 'user32::SendMessageTimeoutW(i 0xFFFF, i 0x001A, i 0, w "Environment", i 0, i 5000, *i .r0)'
  ${EndIf}

  ; --- 随包 Git 环境变量：只移除本产品写入的部分 ---
  ; ⛔ 绝不整条覆盖 PATH（会删掉用户其它路径）。判据 = 记录的段仍位于**末尾**，
  ;    命中才截掉；用户手动调整过顺序的边界情况保持原样（宁可不删，不可误删）。
  ReadRegStr $R1 HKCU "Software\NexusAI\BundledGit" "InjectedSegment"
  StrCmp $R1 "" nexusai_git_env_check_done
    ReadRegStr $R2 HKCU "Environment" "PATH"
    StrLen $R3 "$R1"
    StrCpy $R4 "$R2" "" -$R3
    StrCmp $R4 "$R1" 0 nexusai_git_env_check_done
      StrCpy $R2 "$R2" -$R3
      ; 去掉截断后可能残留的末尾分号
      StrCpy $R4 "$R2" 1 -1
      StrCmp $R4 ";" 0 nexusai_git_env_write
        StrCpy $R2 "$R2" -1
      nexusai_git_env_write:
      WriteRegExpandStr HKCU "Environment" "PATH" "$R2"
  nexusai_git_env_check_done:
  DeleteRegKey HKCU "Software\NexusAI\BundledGit"

  ; CLAUDE_CODE_GIT_BASH_PATH 仅在仍指向本次安装时删除
  ReadRegStr $R2 HKCU "Environment" "CLAUDE_CODE_GIT_BASH_PATH"
  StrCmp $R2 "$INSTDIR\backend\git\bin\bash.exe" 0 nexusai_git_bash_keep
    DeleteRegValue HKCU "Environment" "CLAUDE_CODE_GIT_BASH_PATH"
    System::Call 'user32::SendMessageTimeoutW(i 0xFFFF, i 0x001A, i 0, w "Environment", i 0, i 5000, *i .r0)'
  nexusai_git_bash_keep:

  ; --- 这里刻意不提示、不删除任何数据 ---
  ; 删除与否由用户在**卸载确认页的复选框**决定，
  ; 该状态在卸载段执行时已由模板写入 $DeleteAppDataCheckboxState
  ; ⇒ 实际删除放在 POSTUNINSTALL（此时应用已被 Tauri 关掉、文件不再被占用）。
!macroend

!macro NSIS_HOOK_POSTUNINSTALL
  ; 勾选了「Delete the application data」⇒ 把**我们的**数据也删掉
  ; （Tauri 只会删它自己的 %APPDATA%/%LOCALAPPDATA%\com.nexusai.client）。
  ; $UpdateMode <> 1：与 Tauri 自身逻辑一致 —— 更新模式下不删应用数据。
  ${If} $DeleteAppDataCheckboxState = 1
  ${AndIf} $UpdateMode <> 1
    ; ① <install>\data：工作区 / 知识库原件 / 产物 / 附件缓存
    RMDir /r "$INSTDIR\data"
    ; ② 配置自有根：nexusai.db（真正的库）/ agents / plugins / logs / settings
    ;    ⛔ 这里**不能**用 NSIS 的 RMDir /r：配置根里有指向安装目录的 **junction**
    ;       （image-cache / kb-storage / pdf-cache → <install>\data\workspace\default\*）。
    ;       实测（makensis + 真实 junction 夹具）：
    ;         - NSIS `RMDir /r`   → 不删穿目标（安全），但**删不掉 junction 本身**
    ;                              ⇒ 残留悬空链接
    ;         - cmd `rmdir /s /q` → 同样不删穿目标，且**能删掉 junction 本身** ⇒ 用它
    ;    用 cmd 时把路径加引号（路径含空格/中文时不加会解析错）。
    ;    ⛔ 用 NSIS **内置** ExecWait 而非 nsExec 插件：真实 installer.nsi 里
    ;       从未使用过 nsExec ⇒ 打包时未必携带该插件，用它会报"找不到插件"。
    ;       实测内置 ExecWait 同样能删掉 junction 本身且不删穿目标。
    ExecWait 'cmd /c rmdir /s /q "${NEXUSAI_CONFIG_HOME}"'
    ; ③ 上面把 data 删掉后 $INSTDIR 才可能为空 ⇒ 顺手清掉（否则留个空壳目录）
    RMDir "$INSTDIR"
    Goto nexusai_postuninstall_done
  ${EndIf}

  ; --- 未勾选（默认）：保留数据 ---
  ; <install>\data 原地留存（Tauri 不删它）。
  ; 兼容旧版残留：0.1.73/0.1.74 的卸载器会把 data\workspace 搬到同级目录再搬回，
  ; 上次卸载被中断就会留下 NexusAI-workspace-keep ⇒ 仅在目标不存在时搬回。
  IfFileExists "${NEXUSAI_LEGACY_KEEP_DIR}\*" 0 nexusai_postuninstall_done
    IfFileExists "$INSTDIR\data\workspace\*" 0 nexusai_legacy_move
      ; 目标已有 workspace（新版留下的）⇒ 旧残留保持原样，不动
      Goto nexusai_postuninstall_done
    nexusai_legacy_move:
    CreateDirectory "$INSTDIR\data"
    Rename "${NEXUSAI_LEGACY_KEEP_DIR}" "$INSTDIR\data\workspace"
  nexusai_postuninstall_done:
!macroend
