# 02 — `bash` 收 `shell`：前台调用指名它跑在哪只壳里

**What to build:** `bash` 多一个可选的 `shell` 参数，取值 `git-bash` / `cmd` / `pwsh` / `powershell`。
不传 = 今天的行为（逐字不变）。传了而**本机没有那只壳** ⇒ 按名字拒绝，并列出这台机器有什么；
传了一个**不认识的词** ⇒ 另一句拒绝。`bash {command: "dir %TEMP%", shell: "cmd"}` 要真的跑在 cmd
里，`bash {command: "Get-ChildItem $env:TEMP", shell: "pwsh"}` 要真的跑在 pwsh 里。

**Blocked by:** 01 — `shell.clj` 按 kind 解析并缓存

**Status:** ready-for-agent

## 要落地的判断

1. **不传 `shell` 时，任何行为都不许变。** 包括：解析出来的未必是 Git Bash（一台没有 Git Bash 的
   Windows 机器上链会落到 pwsh 或 cmd）—— 那是**机器**的事，不是参数的事，默认值不许因此改口径。
2. **两条拒绝是两句话，不是一句。** 不认识的词（`shell: "bash5"`）与本机没有的壳（`shell: "pwsh"`
   但没装）对模型意味着完全不同的下一步：前者改拼写，后者换壳或先装。形状照 `workdir` 那两条
   （`{:argument :shell :reason ...}`），后者要把**本机有的**逐个列出来。
3. **描述里必须说清 `command` 是「写给那只壳的一行」。** 换 `shell` 换的是**解释这行字的壳**，
   不是「同一行字换个人执行」：cmd 要 `&`、`%VAR%`、`dir`；pwsh/powershell 要 `;`、`$env:VAR`。
   描述里每只壳给一个最小例子 —— 这一条不写清，模型会写出在 cmd 里是 `;`、在 pwsh 里是 `&&` 的东西。
4. **答案的形状一个字不改。** 仍然是被杀前的输出 +（到点时）那一条；仍然按流封顶、仍然给记录文件；
   仍然不新增表头行说明「用的是哪只壳」—— 是模型自己指的名，它知道。
5. **不加动词。** 工具表仍是 17 个（`editing_mode_tools_test` 与 `CONTEXT.md` 的闭清单是证据）；
   这一票加的是**参数**。
6. **`timeout`、答案封顶、`stdin`、`workdir` 对每只壳语义一致。** 已实测四只壳都保住「到点回来 +
   保住被杀之前的输出」（见 spec 的验收主线表），且都没有 `-c` 那个陷阱的 5s 拖尾签名。
7. **不许顺手动 Git Bash 的 `-lc`。** 那个 flag 是超时杀树的承重结构（6652097）：跳过 profile 会让
   超时被杀的树收不回来、输出丢掉。本票只加「哪只壳」，一只壳的 flag 都不碰。

## 验收

- [ ] 不传 `shell`：既有 `bash` 用例**一条不改**、全绿
- [ ] `shell: "git-bash"` 与不传，答案逐字相同
- [ ] `shell: "cmd"`：一条只有 cmd 认得的写法生效（`echo %CD%` 出 Windows 路径，且**不是** bash 的报错）
- [ ] `shell: "pwsh"` 与 `shell: "powershell"`：`$env:TEMP` 展开成真路径（bash 做不到）
- [ ] `shell: "nope"` ⇒ 拒绝句说的是「不认识这个取值」，且列出合法取值
- [ ] 本机没有的壳 ⇒ 拒绝句说的是「这台机器上没有它」，且**列出本机有的**
- [ ] 每只壳各一条「到点回来 + 保住被杀之前的输出」的用例（四只壳，八条断言）
- [ ] 工具表仍是 17 个；`bash` 的必需参数仍然只有 `command`（不传 `shell` 不报缺参）
