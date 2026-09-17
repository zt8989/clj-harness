# 02 — `harness.infra.shell` 的解析链与起法

**What to build:** 「这个进程用哪个 shell」从「找 Git Bash，否则 `bash`」变成一条**写明的候选链**，
每一级有名字（`git-bash` / `bash` / `pwsh` / `cmd`），**每种 shell 知道自己该怎么起**。
于是 `<env>` 那一行（03）能说出一句真话：这台机器上，命令真的会被交给谁。

这一票是 03 的前置，也是一处独立的修复：今天 Windows 上没有 Git Bash 时解析结果是字符串 `"bash"`，
而在 Windows 上按 PATH 找到的 `bash` 是 `System32\bash.exe`——**WSL 启动器，另一个文件系统，
从 JVM 里静默失败**（`infra/shell.clj` 的 docstring 写着这件事）。一个报着 `bash` 却什么都跑不动的
答案是这一行块最坏的形态。

**Blocked by:** None — can start immediately

**Status:** ready-for-agent

## 形状

```
resolution = {:command "C:\\Program Files\\Git\\bin\\bash.exe"   ; 真的会被 spawn 的那个
              :kind    :git-bash                                  ; 它是谁
              :posix?  true                                       ; 它认 POSIX 的引号与 -lc 吗
              :argv-prefix ["-lc"]}                               ; 命令怎么交给它
```

- **候选链写在一处**，按顺序取第一个「存在」的：Git Bash（两个已知安装路径）→ PATH 上的 `bash`
  （且**不是**那个 WSL 启动器）→ `pwsh` → `pwsh.exe`/PowerShell → `cmd`。加一级 = 加一行。
- **选择和「存在吗」分开**：链本身是一个纯函数（候选 + 一个存在性判定 → 命中的那一级），
  所以整个判定在 macOS / Linux 上也能测完，不必有一台 Windows。
  「怎么起」也是对 kind 的纯函数（bash 系 `-lc`，pwsh 系 `-NoProfile -Command`，cmd 系 `/c`）。
- `binary` **保持是一个字符串**（`(:command resolution)`）——它是被 spawn 的那个名字，
  现有的 `shell` / `run` 两个调用点因此不必变形；新加的 `kind` / `posix?` / `argv-prefix` 是**新增的读法**。

## 一件不显然的后果：POSIX 引号不是通用的

仓里有两个地方把**用户给的参数拼成 POSIX shell 的引号**，并且明说了它们依赖 `bash -lc`：
`infra/rg.clj` 的 `quoted`（注释里写着「`harness.infra.shell` hands the whole line to `bash -lc`」）
与 `cap/git.clj` 的同类做法。落到 pwsh/cmd 上，那套引号**不再成立**——一条路径里有空格就会悄悄变成两个参数。

所以：**这两类调用方声明自己需要一个 POSIX shell**，而链的答案是 `:posix? false` 时它们**指名失败**
（「这台机器上没有可用的 POSIX shell，`anchor_grep` / `glob` / `git` 因此不可用，装了 Git Bash 就会好」），
绝不静默按另一套引号规则跑。`bash` 工具本身没有这个要求：它跑的是模型自己写的命令，
模型已经被告知那台机器上是哪种 shell。

## 验收

- [ ] 候选链是一个纯函数：给定候选与存在性判定，命中哪一级可断言；macOS 上能测全部四级
  （含「Git Bash 存在」「只有 pwsh」「只有 cmd」「一个都没有」）
- [ ] `argv-prefix` 是按 kind 的纯函数，三种形态各有一条断言；`ProcessBuilder` 收到的 argv 用一条
  真跑的命令验（本机是哪级就验哪级，答案由本机算出来，不写死）
- [ ] `binary` 仍是字符串、仍是「会被 spawn 的那个」，`shell` / `run` 的既有调用点不改形状
- [ ] `:posix? false` 时，`infra/rg` 与 `cap/git` **指名失败**（点名缺的是 POSIX shell、后果是什么、
  怎么装），**不**按非 POSIX 引号跑
- [ ] `:posix? true` 时两条路径行为**逐字节不变**（现有 `rg` / `git` 用例原样通过）
- [ ] 这台机器上没有可用的 shell 时，解析结果如实说「没有」，而不是退回一个跑不动的名字
- [ ] 解析每进程一次（与今天 `binary` 的 `defonce` 同一个位置与理由）；测试要有办法驱动每一级，
  不靠改真实机器的 PATH
- [ ] 离线全量 `harness.test-runner` 全绿

## 不做

- **不改任何工具的定义**、不改 hook 契约、不改批准语义。
- 不把「多 shell 支持」变成一个新抽象层：一个候选链 + 一个 kind → 起法的映射，就是全部。
