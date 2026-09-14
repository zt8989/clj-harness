# 02 — 四组用例跟着搬到 TypeScript，CLJS 工具链整体离场

**What to build:** 验收侧那六份 CLJS 搬成 TypeScript 加 vitest，然后 shadow-cljs 从仓库里彻底消失。
六份里四份是用例（frames / client / turn / approval），另外两份是支撑：一个起脚本后端、跑一轮、收事件的
`e2e` 辅助命名空间，和一个让 vitest 能驱动 cljs.test 的桥。桥随语言一起拆——它存在的唯一理由就是让
两个语言对接，没有 CLJS 就没有它。

搬测试不是为了形式统一，而是因为**这套用例从 03 起是唯一的回归网**。它们驱动的是真 `@ag-ui/client`，
不经过任何 UI 库，所以换库期间它们必须活着；而它们必须换成好读好改的语言，因为 03 之后每一次界面改动
都要靠它们证明协议那一层没被碰坏——用 CLJS 写的东西没人愿意在那时候改。

**Blocked by:** 01

**Status:** done

- [x] 四组用例（frames / client / turn / approval）搬成 TS，用例数与断言逐条对位，不增不减、不改判据
      —— **11 用例 / 48 断言**，与搬迁前逐条相同（见落地说明 2）
- [x] `e2e` 辅助命名空间（起脚本后端、驱动一轮、收事件）在 TS 里重建，四组共用同一份，不许各写一份
- [x] `test/support/harness.js` 那套「端口由 OS 分配 + 假 provider + 不要 api-key」仍然生效，
      `npm test` 一条命令跑完，不再需要预编译步骤（`test:build` 退场）
- [x] shadow-cljs 整条链子拆掉：`shadow-cljs.edn`、`vite-plugin-cljs.js`（01 已删）、`cljs-test/` 桥、
      `test/support/build.js`、`test/support/java.js`、`test/cljs.test.js`，以及依赖里的 `shadow-cljs`
- [x] `ui/` 下按源码搜不到 `shadow-cljs`、`cljs`、`helix`；顺带把 `.gitignore` 里三条死条目也删了
      （`ui/cljs-out/`、`ui/cljs-test/`、`ui/.shadow-cljs/`——它们已不可能再匹配到任何东西）
- [x] `npm test` 全绿：11 用例，审批那条链路仍端到端通过
- [x] `npm run build` 全绿，且构建链上不再有 Java：`java.js` 删除，`harness.ts` 也不再设 `JAVA_HOME`
- [x] 落地说明记下「零用例」这个失败模式是怎么防的（见落地说明 3，并附一次实测）

## 落地说明

### 1. 文件地图

CLJS 那边的 `harness.ui.*` 命名空间层级在 TS 里没有对应物（01 已把 `src/` 平铺），所以测试也平铺。
`test/harness/ui/` 整个消失：

| 旧 | 新 | 职责 |
|---|---|---|
| `test/harness/ui/e2e.cljs` | `test/e2e.ts` | 共享管道 + 一个用例/一组用例的形状 |
| `test/harness/ui/{frames,client,turn,approval}_test.cljs` | `test/suites/{frames,client,turn,approval}.ts` | 四组用例 |
| `test/cljs.test.js` | `test/ui.test.ts` | 唯一的驱动：起harness、注册四组、挡零用例 |
| `test/harness/ui/test_runner.{clj,cljs}` | （删） | 桥随语言一起拆 |
| `test/support/{build,java}.js` | （删） | 只为 CLJS 构建存在 |
| `test/support/harness.js` | `test/support/harness.ts` | 起/停脚本后端（行为不变） |

四组用例不是四个 `*.test.ts`，而是被唯一驱动 `import` 进来的普通模块。两个理由：**一个 harness**（vitest
给每个测试文件各自的模块图，四个文件就是四个 JVM 后端）与**一份注册清单**（少了哪个套件一目了然，与旧
`test_runner.cljs` 里的 `suites` 向量同形）。`vitest.config.ts` 的 `include` 钉在 `test/**/*.test.ts`，
所以 `test/suites/*.ts` 永远不会被当成独立测试文件。

### 2. 用例与断言逐条对位

11 用例、48 断言，四组各自的名字、顺序、判据一字未改：

- frames 5（断言 15）、client 2（10）、turn 2（7）、approval 2（16）。
- 48 条断言逐条变成 `expect`，判据原样（`empty?` → `toEqual([])`；`not-any?` → `expect(...some(...)).toBe(false)`；
  `some?` → `!== undefined`；`pr-str` 的失败信息 → `JSON.stringify(types(bad))`，仍是「报出坏的帧类型、不只报计数」）。
  审批那条链路仍是 14 条断言，与搬迁前一致。
- `deftest` 之间的「步骤」变成了 `async/await` 的线性体：`cljs.test` 的 `async!`（那套自己写的 step 链）
  在 TS 里没有存在理由，`await` 就是它想表达的东西。断言顺序与原来逐步一致。

### 3. 「零用例」这个失败模式，现在怎么防

旧桥专门挡过一次：`deftest` 被编译器丢掉会静默变成 0 个用例，跑出一片绿。那道挡板其实有**三**种形态，
旧桥只挡了其中两种，第三种它管不到。新套件三种都在，且都在**任何用例开跑之前**或**该用例结束的那一刻**生效：

1. **某一组一个用例都没贡献** —— 逐组检查 `cases.length === 0`，收集期 `throw`；
2. **某组被从 `SUITES` 清单里丢掉** —— 断言四组贡献的用例总数等于钉死的 `EXPECTED_CASES = 11`，收集期 `throw`；
3. **某个用例跑了但一条断言都没有** —— 每个用例体后跟一句 `expect.hasAssertions()`（vitest 自带），
   零断言即失败。这正是旧桥 `if (r.pass === 0) throw` 那一条——它判的是「空」而不是「绿」——
   换成 vitest 后由 `hasAssertions` 接手，粒度从「用例组」收到了「用例」。

第 3 条是这一票的 `/code-review` 抓出来的：第一版只写了前两条，spec 轴指出「0 断言」那道挡板被漏掉了。

**实测两条都真会喊**：

- 第 2 条：把 `EXPECTED_CASES` 临时改成 12，`vitest run` 立即在收集期失败，输出是
  `Tests no tests` + `the suites contributed 11 cases, expected 12`，没有一条用例被报成通过。改回 11 即绿。
- 第 3 条：把一个用例的断言临时抽掉（留个 `void frames` 占位），那一条报
  `expected any number of assertion, but got none`、1 failed | 10 passed；改回即绿。

（vitest 自己也会把「文件里没收集到测试」当错误而非通过，但显式那道留在代码里，免得换个 runner 就没了。）

### 4. 构建链上不再有 Java

- `npm test` = `vitest run`，**`test:build` 与预编译步骤一起退场**（`package.json` 里已无此脚本）。
- `test/support/java.js` 删除（它是「找 JDK 21」的唯一去处）；`test/support/harness.ts` 不再设 `JAVA_HOME`，
  子进程直接继承环境。本机 `java` 是 21，但**这里不再挑版本**：后端本来就跑在 17 上，旧代码给后端强塞
  JDK 21 只是为了迁就 shadow-cljs。
- `npm run build` = `tsc --noEmit && vite build`，全绿；`tsc` 现在也覆盖 `test/`（`include` 加了 `test`
  与 `vitest.config.ts`），所以测试的类型错误会让构建失败，而不是等到跑测试才发现。
- 新增 `@types/node`（`test/` 用到的 `node:*` 模块需要它）；`tsconfig.types` 相应加了 `"node"`。
- 依赖里的 `shadow-cljs` 删除，`node_modules` 里一并清掉。

### 5. 两处诚实的偏差（都不改判据，各记一条）

1. **thread id 现在真的写在 agent 上。** 旧代码把 `:threadId tid` 传给 `runAgent`，但
   `AbstractAgent.prepareRunAgentInput` 只读 `this.threadId`——那个参数**从来没被读过**，每个用例
   实际跑的都是构造函数给 agent 自动铸的 id。这次移植把它写在 agent 上（构造参数），于是
   `threadId("frames")` 这些名字第一次真的成了日志文件名。断言一条没动：它们判的是消息与事件，
   与 id 无关。这不是「顺手修 bug」，是 TS 的类型面把这件事摆到了台面上——`RunAgentParameters` 根本没有
   `threadId` 这个字段，想传也传不进去。
2. **`harness.js` 跟着语言走成 `harness.ts`。** 票面只说它「仍然生效」。若留 `.js`，要么开 `allowJs`
   （削掉这批测试的类型门），要么把它排除在 `include` 之外（那它就成了仓库里唯一不设防的源码）。
   改成 `.ts` 后它和其他测试一样受 `strict` 约束，行为一字未改。

### 6. 顺带（一处，可回退）

`.gitignore` 里删了 `ui/cljs-out/`、`ui/cljs-test/`、`ui/.shadow-cljs/` 三条。票面说「gitignore 里的说法
不算」，所以这本可不动；删的理由是它们已不可能再匹配到任何产物，留着只会让「CLJS 还在这个仓库里」这句话
有个落点。`ui/dist/` 保留（还在用）。另删掉了磁盘上残留的 `ui/.shadow-cljs/` 构建缓存目录。

### 7. 这一票没有改什么

内核 `src/harness/*.clj`、AG-UI 帧、CORS 放行名单、5173 契约、`src/` 下四份 TSX——一行未动。
浏览器仍直连 8080。**README 的前端章节刻意留着**（它还写着 shadow-cljs 与 Java 21）：那是 08 的
「文档收口」，这一票不动它，免得两票改同一段。

### 8. 验收记录

- `npm test`：**11 passed (11)**，单文件 1 passed，约 6–10s（含 JVM 启动）。
- `npm run build`：`tsc --noEmit` 0 error + Vite 打包成功（`tsc` 已覆盖 `test/`）。
- `ui/` 源码搜 `shadow-cljs` / `cljs` / `helix` / `javaHome21` / `test:build`：0 命中。
- 收集期挡板实测一次（见第 3 条）。
- 注：本机沙箱的 `PATH` 不含 `/opt/homebrew/bin`，所以这里跑 `npm test` 要显式带上它（`clojure` 在那儿）。
  这是环境的 PATH，不是代码问题；牛总的常规终端本就有它。

### 9. `/code-review` 抓到的三处，已修

两条轴各跑一遍，Standards 无硬违规（三条 smell 都判为「原件本来就这样，属超范围」），Spec 抓出三处真问题，
都出在移植的手上，全部当场修掉：

1. **判据被悄悄加强**：`reasoning-frames-are-shape-legal` 里 `some?`（非 nil）被写成了
   `typeof === "string"`。已还原成 `!== undefined`。
2. **失败信息被丢**：`every-frame-passes-the-ag-ui-schema` 原本会把坏的帧类型 `pr-str` 出来，
   第一版只剩一句固定消息。已把 `JSON.stringify(types(bad))` 放回消息里。
3. **「0 断言也是绿」那道挡板漏了**：见第 3 条，已用 `expect.hasAssertions()` 补上。

三处都改了代码而不是改说法——票面「判据一字未改」这句，是靠这一轮才对得上的。
