# 01 — `shell.clj` 按 kind 解析并缓存

**What to build:** 让 `harness.infra.shell` 能回答「**某一只**壳在这台机器上是哪个程序、怎么起」，
而不是只有「这台机器的壳是哪个」。今天 `resolution` 是一个 `defonce` 里的一次 `resolve*`，全进程
只有一个答案；`require-shell!` / `require-posix!` / `spawn-argv` / `start` 都读它。这一票之后，
`(resolution :cmd)` 答 cmd 的绝对路径，`(resolution :pwsh)` 在本机没装时答 **nil**，而**不给 kind
时的答案与今天逐字相同**。

**Blocked by:** None — can start immediately

**Status:** ready-for-agent

## 要落地的判断

1. **默认路径一个字不动。** 不给 kind ⇒ 还是 `candidates` 链上第一个找得到的，缓存方式照旧。
   `require-shell!`、`require-posix!`、`spawn-argv`、`start` 的既有调用方**不需要改签名**。
2. **按 kind 也要缓存，包括「问了，本机没有」这个答案。** 今天 `resolved` 用 vector 装住 nil
   就是这个道理（「asked, and there is none」也是缓存下来的答案，不是每次重问）。按 kind 之后
   同样：`{:cmd <path> :pwsh nil ...}`，nil 也要记住。
3. **kind → 候选行是一张表，写在 `candidates` 旁边。** 今天 `candidates` 只有「链的顺序」；按
   kind 定位是另一个问题（`candidates` 里 `:git-bash` 有两行、`:bash` 一行）。一处定义，别让
   第二个人再拼一遍。
4. **`run` / `start` 收一个可选的 `:kind`**，不传 = 默认。这是把选择权交给调用方的接缝，本票只做
   接缝本身，**不碰 `bash` 工具**（那是票 02）。
5. **「本机没有」是答案，不是失败**（`(resolution :pwsh)` ⇒ nil）。指名拒绝的那两句话是**调用方**
   的事（票 02）—— 这一层只答事实。
6. **`reset-resolution!` 要把两类缓存一起清掉**：既有用例用它把假解析恢复成真的，漏一类就是
   一个测试污染下一个。
7. **`how-to-start` 与 `locator` 的 `wsl-launcher?` 拒绝都不动。** 本票不新增任何 kind，也不把
   任何东西加进 `candidates` 链（`cmd` / `pwsh` / `powershell` 今天已经在链里，只是排在后面）。

## 验收

- [ ] 不给 kind 时 `resolution` 与今天**逐字相同**：`harness.infra.shell-test` 既有用例一条不改、
      全绿（尤其 `the-resolution-of-this-machine-really-runs-a-command` 与
      `the-resolution-is-asked-once-per-process`）
- [ ] `(resolution :git-bash)` / `:cmd` / `:pwsh` / `:powershell` 各答出那条绝对路径；本机没有的
      答 nil（不是抛）
- [ ] 按 kind 的答案**也被缓存**：用 `with-redefs` 数 `resolve*` 的调用次数，问三次只解一次
      （形状照 `the-resolution-is-asked-once-per-process`）
- [ ] 「该 kind 本机没有」也被缓存：第二次问不再去问 `resolve*`
- [ ] `reset-resolution!` 之后，默认答案与按 kind 的答案**都**重新去问
- [ ] `require-posix!` 对着非 POSIX 的 kind 仍然按名字拒绝（既有用例不改）
- [ ] `run` / `start` 不传 `:kind` 时，spawn 出来的 argv 与今天逐字相同
      （`the-two-long-lived-shapes-differ-exactly-on-windows` 不改）
