# 01 — 工具描述里那个 JVM 身份哈希：把值当值用

**What to build:** `job-output-default-timeout-ms` 从 `defn` 变成**一个值**（与它的兄弟
`answer-budget-bytes` 同形），于是 `job_output` 的工具描述里拼出的是 `120000ms`，不是
`harness.cap.jobs$job_output_default_timeout_ms@4d27716bms`。同一个根修掉 `jobs.clj:845`
传给 `long` 的那个函数对象。顺带改掉那条**把 bug 钉住**的既有断言。

**Blocked by:** 无

**Status:** ready-for-agent

## 要落地的判断

1. **这是根因，不是修饰。** 厂商的前缀缓存按发出去的字节键控，而工具表在消息**之前**：
   `cap/tools.clj:969-970` 那一个身份哈希的字节，作废的是**整段前缀**——96 个工具、system prompt、
   指令文件、技能目录，以及整场对话。真实证据：会话 `63e0e913` 里它是
   `harness.cap.jobs$job_output_default_timeout_ms@4d27716bms`，会话 `e39f4967` 里是
   `…@20eee47cms`——同一个 `@`，不同的进程。
2. **按「它本来是个值」来修，不在拼装处补括号。** `cap/jobs.clj:786-795` 的 docstring 自己写着
   「The tool's description interpolates it, so there is **one number**」——一个数，不是一次调用。
   改成 `(def job-output-default-timeout-ms … 120000)`，与紧邻的
   `answer-budget-bytes`（`cap/jobs.clj:555`）同形；那个兄弟拼出来正是 8000，是对的。
   补括号的写法（`(jobs/job-output-default-timeout-ms)`）也修字节，但把「一个数」留成了
   「一次调用」，下一次谁再插值一次又会漂——形状要指向正确的那件事。
3. **顺手修好的是同一个根，别当成两件事。** `cap/jobs.clj:845`
   `(deref (:ended job) (long (or timeout job-output-default-timeout-ms)) ::timeout)`：
   `wait: true` 且调用没给 `timeout` 时，`long` 拿到的是**函数对象** → ClassCastException，
   本意的 120000 到不了。值化之后这一行是对的。**只修这个变量，不动 `long` 的强制转换本身**：
   `timeout` 从 JSON 参数来，它的类型校验是另一处的事，别在这里顺手加一层。
4. **那条既有断言是错的，它比的不是事实。** `test/harness/kernel/tools_test.clj:796`：
   ```clojure
   (is (str/includes? (spec "job_output") (str jobs/job-output-default-timeout-ms "ms")))
   ```
   它拿**同一个坏表达式**去比描述，所以带着哈希也通过。改成比**字面量** `"120000ms"`——
   断言要对着**模型看到的那串字**，不是对着产生它的表达式。
5. **改完 `def` 之后，引用点只剩三个，都是收益方向的**：`cap/tools.clj:970`（描述）、
   `cap/tools.clj:1021`（`job_output` 参数表里那个 `timeout` 属性的描述——**同一个坏表达式，
   第二处**，第一版票里漏了，实测时才看见）、`cap/jobs.clj:845`（等待）。
   `grep -rn job-output-default-timeout-ms src/ test/ dev/` 现有命中就是这三处加定义加那条断言；
   没有别的调用点会被 `defn`→`def` 打断（没有任何地方**调用**它）。
6. **把「为什么」写在该写的地方。** `cap/tools.clj:957` 的 `job-output-description` 旁边留一句：
   这个描述是**前缀缓存头部**的一部分，插进去的东西必须只依赖**编译期常量**，不许依赖对象身份、
   时间、路径、会话。这一条是给下一个人看的，不是给编译器看的。

## 验收

- [x] `(specs nil)` 里 `job_output` 的 `:description` 含字面量 `120000ms`
      （`tools_test.clj:796` 那条改成字面量的断言，133 tests / 652 assertions 全绿）
- [x] `(specs nil)` 里 `job_output` 的 `:description` **不含** `@` 后跟十六进制的形状，也不含
      `harness.cap.jobs$`（票 03 的哨兵用例；它在修之前会红——`@4d27716bms` 正好匹配
      `@[0-9a-f]{6,}`）
- [x] `job_output {wait: true}` 不带 `timeout` 的用例通过（不再抛 ClassCastException），
      且断言实际等待量小于默认值的一半（没真等两分钟）
- [x] `test/harness/kernel/tools_test.clj:796` 改成比字面量 `"120000ms"`
- [x] 全库 `grep` 这个变量只剩「定义 + 三处引用」（描述 `:970`、参数 `:1021`、等待 `:845`）

## 实测补充（落地时量的，比上面第 1 条那条旧证据硬）

**宏观：那个哈希确实逐进程在变。** `~/.clj-harness/projects/<slug>/*.jsonl` 里 35 个带工具表的
会话，19 个带着这个坑，而它们的哈希是 **10 个不同的值**：

```
3d6e18b4(990次)  758a3ba0(838)  7b787996(790)  4d27716b(184)  20eee47c(132)
3c148f23(50)     6fe8e276(46)   726c1889(40)   40ee8512(30)   154bc3cf(10)
```

**微观：它一字不差地就是那唯一一处不同。** 会话 `7d65cd27` 的记录里前后出现过两个哈希（同一个
文件！），把这两张表逐字段对一遍：都是 41 个工具，**只有 `job_output` 一个工具不同**，差异落在
描述的第 923 字节——`…$job_output_default_timeout_ms@758a3ba0ms` 对 `…@3d6e18b4ms`；`parameters`
里那处跟着一起变，正是 `cap/tools.clj:1021` 那个引用点。另外 40 个工具、所有其它字段，逐字节相同。

**它是不是就是第 1 次调用只命中 3072 token 的原因？量出来对得上。** 活体 body 里 `@154bc3cf`
落在 tools 数组起点之后 **10609 字节**：它之前的每个字节都可能与别的会话对齐，它之后的每个字节
都不可能。3072 token 换回字节大约在 9–12KB 这个量级（这段前缀是 JSON 与英文散文混排），
与「system prompt + 这 10609 字节」相符，且落在厂商 64-token 块的粒度内。

**所以修完的正确预期，以及怎么验**：把那个字节换掉（本票）**并且**把键序与稳定的一半焊死
（票 02）之后，工具表在进程之间就是逐字节相同的——那时**新开一个会话的第 1 次调用**应当能命中
整段稳定的头（约 16k token），而不再是 3072。**要连着开两个会话才看得到**：第一个只是把新字节
写进厂商的缓存，第二个才命中。用票 04 的脚本一条命令比 before/after。

**同一会话内部已经漂过 4 次**——代价不用等到「换个进程」那么抽象：
`02ba8830`（4d27716b→20eee47c）、`4aec5144` 与 `7d65cd27`（758a3ba0→3d6e18b4）、
`d3dfb66c`（旧文本→758a3ba0，看着就是这个坑被引入的那次重启）。

**别把它说成「每次都不一样」**：它是个没被调用的函数对象的身份哈希，而 HotSpot 的默认
`hashCode` 在分配顺序相同的前提下是**可重复**的——所以 35 个会话能塌成少数几张表。它是
**没有契约的稳定**：任何改动启动期分配顺序的东西（多一个工具、MCP 名单变动、走另一条配置分支）
都可能把它挪走，而代码里没有一个字承诺它不动。这比「随机」更值得修：它安静，且在
「恰好一样」的时候会让人以为整件事本来就是稳的。
