# 03 — 后台作业也记住壳：`bash {run_in_background: true, shell: "cmd"}`

**What to build:** `run_in_background` 的调用也能指名壳，而且作业**真的跑在那只壳里**。今天
`jobs/start!` 只带 `{:command :dir}`，`shell/start {:shape :shell}` 取进程默认壳 —— 所以一条指名
了 cmd 的后台命令，今天只会跑在 Git Bash 里（而前台已经能选，两条路就对不齐了）。

**Blocked by:** 02 — `bash` 收 `shell`：前台调用指名它跑在哪只壳里

**Status:** ready-for-agent

## 要落地的判断

1. **壳是「这一行是谁写的」的属性，不是作业的身份。** 所以答案**仍然是两条**（job id + 记录路径），
   不加第三句「用的是哪只壳」：`job_output` 读记录时不需要知道壳，`job_kill` 也不需要。
   这与 `bash` 的既有答案纪律同一条（后台那半「TWO FACTS AND NOTHING ELSE」）。
2. **记录里也不写壳。** 记录是**命令说出来的话**（`cap.jobs` 的模块 docstring 就是这个立场）；
   谁解释的那行字不进记录。
3. **后台的两条既有规矩与壳无关，一条都不许变**：`timeout` 在后台不适用（作业没有时限），
   `stdin` 在后台按名字拒绝（没有人喂它）。给了 `shell` 也不改变这两句。
4. **拒绝发生在起作业之前。** 指名了本机没有的壳 ⇒ 与前台同一句拒绝，且**没有作业被创建**
   （没有 id、没有记录文件）—— 一个起不来的作业不该先占一个 id。
5. **`jobs` 那条缝只多一个字段。** `shell/start` 已经收 `:shape`，本票让它也收 `:kind`；`start!`
   把它透下去。别在 `jobs` 里再存一份「壳」的状态。
6. **不许把 `:shape` 与 `:kind` 混成一个词。** `:shape` 说的是 program-vs-shell（Windows 上前者
   走 `cmd /c`），`:kind` 说的是哪只壳。后台走的是 `:shape :shell` + 某个 `:kind`。

## 验收

- [ ] `bash {command: "echo %CD% & ping -n 5 127.0.0.1 >nul", run_in_background: true, shell: "cmd"}`
      ⇒ 作业真的跑在 cmd 里（记录里出现 Windows 路径，且没有 bash 的报错）
- [ ] 答案**仍然只有两条**（id + 记录路径），没有第三句说壳
- [ ] 不传 `shell` 的后台调用：行为与答案与今天逐字相同（既有 jobs 用例一条不改）
- [ ] `shell: "nope"` / 本机没有的壳 ⇒ 拒绝句与前台同一句，**且没有作业被创建**
      （注册表里没有新 id、`jobs/` 树下没有新文件）
- [ ] `timeout` 在后台仍然不适用、`stdin` 仍然按名字拒绝：两条既有判据给了 `shell` 之后仍然成立
- [ ] 作业结束的通知、`job_output` 的窗口、`job_kill` 的幂等，在非默认壳下与默认壳下表现一致
