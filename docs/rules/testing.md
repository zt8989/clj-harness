# 测试细则

铁律和「怎么跑」在 `AGENTS.md`；这里放细则。脚本各自在守什么、漏掉会怎样，写在
`scripts/test.mjs`、`scripts/dev.mjs` 的头注释里，这里不复述。

## 铁律的完整版：`~/.clj-harness` 只读

跑用例、起 dev、走查，家一律自己造（`--scripted` 的那对临时家、`with-temp-env`），不指着真应用连、
不拿真家目录起第二个 harness。2026-09-18 的一次走查里两个进程抢同一个 `harness.db`：应用侧拿到
`SQLITE_BUSY`，迁移把库判成「受损」并隔离重建，**开发者自己那份 18M 的库当场被清空**
（`harness.db.emptied-by-quarantine-*` 与 `.corrupt-*` 就是那次留下的）。库只有一把锁：读没事，
写就是把正在跑的应用一起带走。

### 判据为什么是两半（2026-09-20）

**「真家未动」这句话有两个读者，而只有一个能归到测试头上：**

- **这个进程开过真家的库没有**——`harness.infra.db/store-paths-opened` 记着本进程解析并打开过的
  每一个库路径，`isolate!` 之后清空一次。开过就是 `ISOLATION FAILURE`，**按名字**报出那个路径；
  连"只读没动字节"也算失败：读到别人的行/配置/锚点，这次运行就已经不干净了。
- **那个文件动没动**——`[bytes mtime]` 前后对一次。**动了而这个进程没开过它 ⇒ 只报一行
  `ISOLATION NOTE`，不算失败**：那说明写它的是另一个进程，而最平常的另一个进程就是**开发者自己那个
  活着的 harness 会话**（锚点、待办、会话行都在那个库里；实测一次带锚点的 `read` 就能把 mtime 推走，
  而一次什么都不碰的全量跑前后逐字不变）。

**为什么必须分开**：2026-09-20 之前判据只有后半截（文件有没有动），于是「我在改文件、同时在跑全量」
那种日子里，**绿的跑会被报成红的**，而报告里那句话（"some code path resolved the store against the
developer's real home"）指向一个根本不存在的代码路径。两半一分开，"是谁写的"就有一个能站得住的名字。
两个分支各有用例：`test/harness/test_runner_test.clj`（纯输入，不碰任何库）。

## 界面走查：机器门替代不了的那一格

2026-09-18 那次 i18n 合并，859 + 36 全绿、`tsc` 与打包都过，而侧栏每一行的标题都是空的。渲染那
一格现在有套件守了（`ui/test/suites/sidebar.tsx`，把一行渲染成字符串再读它说什么），但**渲染看不到
布局**——类名、截断、间距、有没有行盒，都只有一个真浏览器说得清。所以动过 `ui/src/` 的改动，
合之前跑一次 `node scripts/dev.mjs --scripted` 走查。

## 看 run 的记录要趁它开着

一轮 run 的记录是**边跑边写**的，而 `--scripted` 的那对临时家**退出即删**：要看
`projects/<workspace>/<thread>.jsonl` 就在它开着的时候看，路径它报在启动横幅里。

## 写新用例时要自己守的（脚本管「怎么跑」，这几条它管不到）

- 要 home / 项目目录 / 配置目录的用例**自己造**：`harness.test-support/with-temp-env` 给它一对临时
  root + OS home（跑完连目录一起删掉），项目目录用 `temp-dir`；临时 root 里它会种一份最小
  `config.edn`，否则 run 会被「没有 `:default` provider」拒掉。**不要往 `isolate!` 那对里写**——
  它是整个 JVM 共用的，留下的文件会变成下一条用例的输入（凭空多出的 `<instructions>` / `<skills>` 块）。
- **临时目录一律 `temp-dir`，不要自己拼 `<tmpdir>/<名字>`**：它是 `Files/createTempDirectory`，名字由
  OS 取（同一个 label 两次是两个目录），目录交回来是**空的**，所以「先 delete 再 mkdirs」那两行要删掉。
  拼出来的名字上一个 run 用过、并排的另一个进程也在用——`java.io.tmpdir` 里那些前任留下的树就是这么来的。
  它返回**路径字符串**：`file-seq`，以及形参带 `^java.io.File` 提示的私有 helper，都要自己包一层
  `io/file`（不包报的是 `String cannot be cast to java.io.File`，且指不到真正那一行）。
- 起服务用现成的 wrapper（`with-server` / `with-declaring-server` / `with-resolved-config`），
  一律 `{:port 0}`；改那两个 override 用 `alter-var-root` 不要 `binding`——服务在别的线程上跑。
- 自己拉 JVM 的用例（fork 子进程）要把**两个家**都指过去：`CLJ_HARNESS_HOME` 给 root、
  `-Duser.home` 给 OS home；**答案不要从子进程的 stdout 读**（JDK 的原生访问告警混在里面），
  让它写进文件再读。
