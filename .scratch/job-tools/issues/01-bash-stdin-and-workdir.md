# 01 — `bash` 的进路：`stdin` 与 `workdir`

**What to build:** 一次前台 `bash` 调用能带上**它要喂进去的那段文本**（`stdin`）与**它在哪个目录里跑**
（`workdir`）。今天这两条路只能靠命令串自己绕：喂文本要 `echo … | …`（引号、换行、大小上限全是模型
自己处理），换目录要 `cd … && …`（而 `job` 那条路的 cwd 已经与本会话的项目绑定同一处解析，两个工具
因此对不齐）。这一票之后，`bash {command: "sort", stdin: "b\na\n"}` 答 `a` / `b`，
`bash {command: "pwd", workdir: "src"}` 答那个解析后的绝对路径 —— 而底下的执行器**已经准备好了**
（`harness.infra.shell/run` 收 `:stdin` 与 `:dir`），`bash` 只是没接出来。

**Blocked by:** None — can start immediately

**Status:** ready-for-agent

## 要落地的判断

1. **`stdin` 写进去随即关掉**，与 `run` 今天的行为一致：一个读 stdin 的命令看到的是 **EOF**，
   不是「等一个不会打字的父进程」。
2. **`workdir` 按本会话的项目绑定解析**（相对路径就是相对那个目录），与 `read` / `grep` / `glob`
   同一条路，一处解析。`bash` 本来就没有围栏（任意代码执行），`workdir` 不新增逃逸面。
3. **不是目录就指名拒绝**：说出它是文件、还是不在（一个字都不许猜）。默认（不给 `workdir` 时）
   与今天逐字相同：本会话的项目绑定，没有绑定时是进程的工作目录。
4. **描述里说清单位和默认值**，与 `timeout` 同一处插值（`bash-default-timeout-ms` 的先例：
   写第二个字面量就是留一次「改了默认值、描述没跟上」）。
5. **不新增答案里的表头行**：答案仍然是命令打出来的东西 +（到点时）既有的那一条。一条命令跑在哪儿，
   它自己 `pwd` 说得比我们准。

## 验收

- [ ] `bash {command: "sort", stdin: "b\na\n"}` → `a` / `b`（顺序证明真的进了 stdin）
- [ ] `bash {command: "cat", stdin: ""}` 与 `bash {command: "true"}` 都在时限内返回
      （没有「等着 stdin 的父进程」这种挂）
- [ ] `bash {command: "pwd", workdir: "src"}` → 本会话项目目录之下的那个绝对路径；
      `workdir` 指向一个文件、或指向不存在的路径 → **两条指名拒绝**，各说清它是什么
- [ ] 不给 `workdir` / 不给 `stdin` 时，行为与答案**今天逐字相同**（既有用例一条不改）
- [ ] `timeout` 的语义一个字没动（毫秒、默认 120000、不封顶、到点连子孙一起收）
- [ ] 描述里两条参数的默认值是从同一个 `def` 插值出来的
- [ ] `node scripts/test.mjs --backend` 全绿（失败用例名与基线一致）
