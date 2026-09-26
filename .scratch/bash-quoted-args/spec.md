# spec: 命令的字节原样到 shell —— Windows 上那条命令行

## 现象

一条 `bash` 调用里写 `echo ONE "TWO THREE" FOUR`，得到的是**另一条命令**的结果：双引号没了，同一行
后面的命令也一起没了。三张脸是同一个 bug：

- 引号消失：`printf '[%s]' ONE "TWO THREE" FOUR; echo; echo END-MARKER` 只印 `[ONE][TWO]`；
- 管道后面的命令没跑；
- shell 自己报 `unexpected EOF while looking for matching` / `syntax error: unexpected end of file`
  —— 那是一条 caller 没写过的行才有的语法错（走查里 `yes "spill padding…" | head -400` 就这么撞上）。

单引号版一字不差（`echo ONE 'TWO THREE' FOUR` 完好），所以这不是「quote 写法的建议」，是**字节没有
原样到达**：一台机器上写对的命令，跑的是另一条，而模型只看到「结果不对」。

## 根因：两个解析器，一条命令行

POSIX 上 `ProcessBuilder` 拿到的向量就是子进程的 argv，谁也不改它。Windows 上没有 argv：JVM 把向量
拼成**一条命令行**，由子进程那一侧的运行时再读回成词。两侧规则不一样，而它们恰好对 `"` 不一致：

- **JVM 那一侧**（默认 legacy 模式）：`jdk.lang.Process.allowAmbiguousCommands` 默认 `true`，JDK 25
  的 `ProcessImpl` 在**构造函数里** `System.getProperty`（不是静态字段，每次 spawn 现读）。它给
  **含空白的元素**外面包一层 `"`，**里面的引号一个都不转义**。`echo ONE "TWO THREE" FOUR` 于是以
  「同样的字节 + 外面再一层引号」落到命令行上。
- **Git Bash 的 msys2 那一侧**（按 MSVCRT 规则回读）：引号段里，引号只被 `\"` 结束、反斜杠只被
  `\\` 还原；**裸引号结束引号段**，引号段外的空白结束一个词。于是那个元素被读成 `echo ONE TWO`，
  后面的一切（`; echo …`、`| …`）成了 shell 自己的 argv。

反过来验证过：把 JVM 切到 `-Djdk.lang.Process.allowAmbiguousCommands=false`（它就会老老实实转义），
同一条命令一号不差 —— 两侧对「**已经转义过的**命令行」是一致的，分歧只在**会不会转义**。

实测（本机 JDK 25 / Git Bash，2026-09-26）：

| 同一发命令 | 结果 |
| --- | --- |
| `printf '[%s]' ONE "TWO THREE" FOUR; echo; echo TAIL` | `[ONE][TWO]`，TAIL 没了 |
| 换成单引号 | `[ONE][TWO THREE][FOUR]` + TAIL，一条不缺 |
| `echo "A B"; echo C; echo D` | 只印 `A` |
| 同上 + `-Djdk.lang.Process.allowAmbiguousCommands=false` | 完整 |
| `:kind :cmd` 的同一行 | 本来就完好（cmd 不按 MSVCRT 规则读命令行） |
| `:kind :pwsh` 的同一行 | 同样被吃，转义之后完好 |

`harness.infra.rg` / `cap.git` / 钩子那些 caller 用的是**单引号**命令行，所以一直没露；用户/模型手写的
那一条（`bash` 工具、`job`）才是重灾区。

## 修法（branch `bash-quoted-args`）

在「怎么把命令交给 shell」这一处（`harness.infra.shell`）补上 JVM 没做的那一步：`command-word`。

- Windows 上按 MSVCRT 规则自己转义（`\` → `\\`，`"` → `\"`）——就是 JVM 本该写的那条规则；
- **没有空白的命令，前面补一个空白**：JVM 只给含空白的元素加引号，而只有被加引号的元素才按上面那条
  规则回读（不加引号的元素按「反斜杠是字面量」读，我们刚加的反斜杠会留在命令里）。shell 跳过行首
  空白，所以命令本身一个字没变；
- 其余平台**原样**：那里向量就是 argv，多加的转义反而是 shell 要多解一层的字节；
- `:cmd` **原样**：cmd 不按 MSVCRT 规则读命令行，它本来就收到了真实的引号，替它转义等于把反斜杠塞进
  命令里。

前台（`run`）与后台（`spawn-argv` 的 `:shell` 形状，即 `job`）共用这一个函数，所以「哪个 kind」是同一
条路。命令本身一个字都不动：注册表里的 `:command`、通知里的 `<command>`、记录文件，全是 caller 送
的字节（通知要靠它认出是哪个作业）。

**这是对 JVM 行为的一次押注**：转义只在这个模式（包引号、不转义）下成立。押注写在源码那一段
自述里，并由 `a-quoted-word-reaches-every-shell-this-machine-has-whole` 看着 —— JDK 换了默认，
红的是它。

## 测试

- `harness.infra.shell-test/a-windows-command-line-carries-the-word-the-shell-will-read-back`：纯函数，
  两个平台分支与 `:cmd` 那一支都断言（不靠跑在哪台机器上）；
- `…/a-quoted-word-reaches-every-shell-this-machine-has-whole`：真 spawn，**本机解析得到的每一个
  kind 各一条**（本机自己的那条无名路、`:git-bash`、`:pwsh`、`:cmd`……），各用自己语言写探测命令；
  本机没有的 kind 跑不了，但「本机自己的那个 kind」被断言在跑过的那批里；
- `…/the-rest-of-the-line-runs-after-a-quoted-word`：尾标记、管道都还在，stderr 里不再出现
  `unexpected end of file` / `unmatched` 那一族；
- `…/the-shapes-a-command-is-built-out-of-still-mean-what-they-mean`：单引号、重定向、管道、`stdin`
  四样各一条（证明没有连带改坏）；
- `harness.kernel.tools-test/a-double-quoted-word-survives-the-trip-into-the-shell`：`bash` 工具那一层，
  答案里就是完整的四个词；
- `harness.cap.jobs-test/a-commands-own-bytes-are-what-the-registry-and-the-notice-hold`：注册表的
  `:command` 与通知里的 `<command>` 是原文。

## 状态

`clojure -M:test -m harness.test-runner`：**1295 条 / 13848 断言，0 红**（并入 main 之后跑的那一轮）。

那一红曾经是
`harness.edge.pressure-test/the-endpoint-answers-the-pressure-section-and-the-run-leaves-it-on-the-record`
（`pressure_test.clj:344`：49087 < 50000）—— 与本次改动无关，但收尾时顺手查清了：不是估算器的错，是
**量错了面**（端点与自动压缩递进去的是「对话」，锚点那一侧折回来时带系统消息）。修法记在
`.scratch/compaction-shape/spec.md`，commit `c069f8e`。

真浏览器走查：**已做**（`evidence/01-quoted-args.md` + `01-quoted-args-browser.png`）—— `node scripts/dev.mjs
--scripted` 起一发含双引号的 `bash`，工具卡上的参数是 caller 的原文、结果是
`[ONE][TWO THREE][FOUR] END-MARKER`；同一发在记录里也是原文。

## 票

`issues/01-double-quoted-args-arrive-mangled.md` —— 已落地；按 `.scratch` 的约定，完成的票删掉而不是
改状态，记录留在这里与 git 历史里。
