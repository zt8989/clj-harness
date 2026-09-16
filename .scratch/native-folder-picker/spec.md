# 原生文件夹选择器（native-folder-picker）

## 现象

在 Windows 上点侧边栏「添加项目」→「选择文件夹」：**点了没反应**。没有弹窗、没有报错、没有提示，
项目列表也不变。macOS 上同一条路径正常。

## 根因

`POST /api/project/pick` 背后的选择器（HTTP edge 里的 `*directory-chooser*` 动态变量）**只有一种
实现**：macOS 的 `osascript choose folder`。Windows 上进程启动即失败，异常被兜成 `nil`，端点答
`{:dir nil}`，而 `{:dir nil}` 在契约里的含义是**用户取消了对话框**。于是「这台机器根本没有
这个命令」和「人点了取消」在服务端是同一个答案，前端按取消处理——什么都不做。

也就是说，**bug 不是"Windows 弹不出窗"，而是"打不开窗被定义成了取消"**。这两件事都要修：
补 Windows 的实现，并且让"打不开"成为它自己的答案。

## 决策

1. **按平台分派选择器**，选择器是一个 seam（生产真实弹窗、测试 bind 成 stub，与
   `*root-override*` 同一手法）。Windows 走**原生 Win32 对话框**：起 PowerShell
   （`-NoProfile -STA`）调 WinForms 的 `FolderBrowserDialog`。选它而不选 JVM 内 Swing 的
   `JFileChooser`，是因为这条边现有的形状就是"起平台原生进程画窗"（macOS 已是如此），
   且原生外观是这条交互的全部价值——人要认出这是自己系统的选目录窗口。
2. **代价明确接受**：外部进程要自己处理编码。字节→字符串一律显式 UTF-8（本仓既有纪律，
   `deps.edn` 头部就写着 JVM 在中文 Windows 上默认 GBK），PowerShell 侧要显式指定输出编码，
   并剥掉 BOM/CRLF。中文目录名、带空格目录名、盘符根目录都要能原样回来。
3. **结果三态，不再二态**：`:picked`（带路径）/ `:cancelled`（人点了取消，**仍然无痕、不报错**）
   / `:unavailable`（没有桌面会话、没有可用机制、进程失败——**答非 2xx + 一句人话 `:error`**）。
   Linux 一并纳入分派：本轮明确答 `unavailable`，不假装支持（后续要支持就加一支实现，形状已经在了）。
4. **兜底是手输绝对路径**，不是猜：选择器不可用时 UI 给出直接填路径的入口，复用既有的
   add-project 端点。pick 端点本身仍然**什么都不绑定**——绑定只有一条路。

## 平台矩阵

| 平台 | 本轮行为 |
| --- | --- |
| macOS | 不变：osascript 原生选目录；取消 = 无痕 |
| Windows | PowerShell + WinForms 原生选目录；失败 = `unavailable` + 人话 |
| Linux / 其他 | `unavailable` + 人话（本轮不实现对话框） |
| 任意平台的无桌面会话 | `unavailable` + 人话 |

## 状态

三票已实现并提交（`01` / `02` / `03` 的验收逐条做过，见下）。

### 已验证到什么程度

- **三支与三态有测试，且测试不弹真窗**：`*directory-chooser*` 之下又开了一层缝
  `*dialog-launcher*`（argv 进、`{:out :exit}` 出，或抛 `IOException`），所以
  「问人」与「起进程」可以分开 stub。Windows 支测到：命令行里带 `-STA` 与 `FolderBrowserDialog`、
  BOM 与尾换行被剥掉而空格保留、空输出 = 取消、exit 2 = 不可用、`powershell.exe` 不在就试 `pwsh.exe`、
  两个都不在 = 不可用。macOS 支测到：路径回传、非零退出 = 取消、**起不来进程 = 不可用**（旧 bug 那条）。
  端点三态：选中 200 `{:dir ..}`、取消 200 `{:dir nil}`、不可用 501 + `:error`，且两种都**不绑定**。
- **PowerShell 那一半在本机上真跑过**（不弹窗的那一段）：程序集能加载、`FolderBrowserDialog` 能构造、
  输出编码改成无 BOM 的 UTF-8 之后，`D:\work 目录\项目` 这样的路径经 stdout 回到 Java 侧
  **逐字节正确**（`b'D:\\work \xe7\x9b\xae\xe5\xbd\x95\\\xe9\xa1\xb9\xe7\x9b\xae'`）。
- **真弹一次窗没有自动验**：`ShowDialog()` 要一个人在窗前按一下，脚本跑起来就是模态等着的，
  自动化到不了那里。这一条留给在机器前的人：起服务 → 点「添加项目」→ 选目录 → 行出现。
- **回归面**：`npm run test`（前端 e2e，自带后端）19 passed。
  `http_test` 改动前后**同名的失败条数一致**（本仓库在这台机器上的固有失败：
  `a-rebind-carries-the-log-because-a-conversation-is-one-file` 五条），
  `the-projects-listing-joins-the-store-with-the-disk` 是 README 已经记着的那条真竞赛；
  `layers_test` 那 85 条与本次无关，改动前后一样。

### 两个在实现里撞上、值得记住的坑

- **`clojure.string` 把字符串 `match` 当文本不当正则**，`(str/replace-first s "^\uFEFF" "")` 找的是
  一个字面上的 `^`，所以 BOM 剥不掉。现在用 BOM 本身做匹配。
- **ns docstring 里写双引号会截断字符串**：给 ns docstring 补一句带 `"..."` 的说明，
  报的是 `Syntax error macroexpanding clojure.core/ns`——错在文档，不在代码。

## 票

- `issues/01-*.md`、`issues/02-*.md`、`issues/03-*.md`；票随完成删除，spec 长期留存。
