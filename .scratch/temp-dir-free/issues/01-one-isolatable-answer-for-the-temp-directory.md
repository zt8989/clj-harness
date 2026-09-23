# 01 — 本机临时目录只有一个可隔离的答案

**What to build:** 「这台机器的临时目录」成为一个与配置根、OS home 同级、可被测试隔离的**单一事实**：
生产里它答 `java.io.tmpdir` 的 canonical 形式、加上 POSIX `/tmp`（canonical 后相同则合并），
测试里 `isolate!` 能把它指到一个本进程 fixture 都不在其下的专属目录。

这一票**不改任何可见行为**——围栏还不消费它，落地即全绿。它是下一票的前置：
没有它，把「整个临时目录」加进围栏自由集会连同套件自己的 fixture 一起放行
（隔离时配置根、OS home、每个项目 fixture 全是 `java.io.tmpdir` 下的兄弟，
`outside-path` 也在里面），届时每条 fence 用例的「界外」都成了界内，strict 也失去意义。

**Blocked by:** None — can start immediately.

**Status:** done

- [ ] 「本机临时目录」只有一个出处，不散落在多处。生产答案 = `java.io.tmpdir` 的 canonical 形式，
      **加上** POSIX `/tmp` 的 canonical 形式；两者 canonical 后相同则合并成一条（Linux 上常见）。
      `/tmp` 不是绝对真实目录的平台（Windows）上它不出现，且不报错。
- [ ] 该事实上挂一个测试可设的 override，规矩与已有的配置根 / OS home override 逐条一致：
      `isolate!` 设它、进程收摊时连同那对一起照顾、用例之间不串。
- [ ] `isolate!` 设定的 override 值指向一个**本进程任何 fixture 都不在其下**的目录：
      `support/temp-dir` 交出的每棵树、以及 `outside-path` 都不在它下面——
      这就是「套件形状不变」的机械判据，写成断言而不是口头约定。
- [ ] 直接断言 override 的用例：设了 ⇒ 本机临时目录答 override 值；
      不设 ⇒ 答 `java.io.tmpdir` 的 canonical 形式与 `/tmp`（去重后逐条相符）。
- [ ] 全量 `clojure -M:test -m harness.test-runner` 全绿，且 `git diff` 可见
      **围栏的自由集一个字没动**——本票只把事实摆好，不放行任何路径。

## 落地（2026-09-23，提交 a3bd32b）

- `harness.infra.env/temp-dirs`：`java.io.tmpdir` + POSIX `/tmp`，`getCanonicalPath` 后 `distinct`
  （macOS 上两条，Linux 上合一，Windows 上没有 `/tmp`；`/tmp` 非绝对真实目录时不出现）。
- `env/*temp-dir-override*`：照 `*root-override*` / `*user-home-override*` 的规矩，替换整张表。
- `harness.test-runner/isolate!` 多造一个兄弟目录 `test-tmp`（`:track? false`）并 `alter-var-root` 设 override，
  `cleanup!` 收它；`test-runner-test` 断言它（连同 `temp-dir` 树与 `outside-path`）不在任何 fixture 之下、也不在
  wipe 的登记表里。
- 判据：全量 1137 tests / 13187 assertions，0 failures 0 errors（基线 1134/13177）。
  `git diff -- src/harness/cap/project.clj` 为空——本票围栏的自由集一个字没动。
