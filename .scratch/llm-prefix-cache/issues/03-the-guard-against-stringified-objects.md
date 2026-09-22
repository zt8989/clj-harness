# 03 — 守卫：把「描述里出现了某个对象」这件事变成一个会红的用例

**What to build:** 三条廉价的用例，把票 01 那一类 bug 从「要靠人读描述才发现」变成「机器门会红」：
（a）同进程装配两次逐字节相同；（b）任何工具面里**不得出现被字符串化的对象**；
（c）有身份的常量对着**字面量**断言。**外加**一条说明：真正的跨进程相等由票 04 的报告在**真实记录**
上验，不用合成用例。

**Blocked by:** 01、02

**Status:** ready-for-agent

## 要落地的判断

1. **同进程两次相同是必要条件，不是充分条件——这条要写在用例注释里。** 身份哈希在**同一个进程里
   就是同一个**，所以 `(= (specs nil) (specs nil))` 对票 01 那个 bug **永远是绿的**。
   把它留着（它抓的是别的漂移：集合序、时间戳、随机 id），但**别把它当成跨进程的保证**，
   否则下一个读用例的人会以为已经守住了。
2. **真正的守卫是那条哨兵：工具面里不许出现被字符串化的 Clojure 对象。** 机制是确定的：
   Clojure 对象的 `toString` 只有两种形状——`harness.cap.jobs$job_output_default_timeout_ms@4d27716b`
   （munged 类名带 `$`，`@` 后跟十六进制身份哈希）或 `#<...>`。所以：
   - 把 `(specs nil)` 整张表 `json/write-str :escape-unicode false` 成一个串，
   - 断言它**不含** `$`、**不含** `@` 后跟 `[0-9a-f]{6,}`、**不含** `#<`。
   这条哨兵是**通用的**：它不认识 `job_output`，它抓的是「有人把一个对象拼进了描述」这个**形状**，
   所以下一个同类的 bug（一个未调用的函数、一个未 deref 的 delay、一个 var）也逃不掉。
   **要接受它的误报方向**：某个工具描述里真写了 `$`（比如一段 shell 示例）就会红——那时候改成
   断言**具体的** `$` 位置，别把哨兵删掉。
3. **跨进程相等在真实记录上验，不用合成用例。** 不要写一个 spawn 子 JVM 的测试：子进程要么继承
   不到测试运行器的隔离家（`harness.test-runner` 的临时根不是环境变量），于是**有碰真家的风险**——
   这条铁律比这个用例值钱；要么得在一个空进程里 `install!` 出一张表，而那测的是安装而不是描述。
   所以：**票 04 的报告读真实会话记录里的 `model/start` `:tools`，比两个不同进程写下的哈希**——
   那是同一条不变量的**证据**，不是它的替身。用例里放一条注释指向票 04。
4. **有身份的常量，断言要对着字面量。** `test/harness/kernel/tools_test.clj:796` 今天拿
   `(str jobs/job-output-default-timeout-ms "ms")` 去比描述——**同一个坏表达式**，所以带着哈希也
   绿。断言要对着**模型看到的那串字**：`"120000ms"`。这条规则放大一点说：
   **凡是「发出去的字节」的断言，右边都必须是字面量**；右边一旦是「再算一遍」，断言就退化成
   同义反复，而这个特征的整个价值就在那些字节上。
5. **把哨兵放在会被人看到的地方。** 加在 `test/harness/kernel/tools_test.clj`（`specs` 的家里），
   用例名点明它守的是什么，比如 `no-tool-face-carries-a-stringified-clojure-object`。
   别放进一个新文件：读 `specs` 的人本来就会打开那个文件。

## 验收

- [x] 存在一条用例：`(specs nil)` 装配两次逐字节相同，且**注释说明它守不了跨进程**
- [x] 存在一条用例：整张表的 JSON 不含 `$` / `#<` / `@[0-9a-f]{6,}`
- [x] **反着做了一次，它确实会红。** 实测（把常量的取值从 `120000` 改成 `(fn [] 120000)`，别处不动，
      跑 `harness.kernel.tools-test harness.cap.jobs-test` → **exit 1**）：

      FAIL in (the-tool-table-is-a-pure-function-of-the-tools) (tools_test.clj:1002)
        expected: (not (re-find #"harness\.[A-Za-z0-9_.\-]+\$" once))
          actual: (not (not "harness.cap.jobs$"))
      FAIL in (the-tool-table-is-a-pure-function-of-the-tools) (tools_test.clj:1003)
        expected: (not (re-find #"@[0-9a-f]{6,}" once))
          actual: (not (not "@463d48b2"))
      FAIL in (the-three-faces-say-what-they-are-for) (tools_test.clj:802)
        expected: (str/includes? (spec "job_output") "120000ms")
      ERROR in (a-wait-with-no-timeout-uses-the-default-instead-of-throwing)
        java.lang.ClassCastException: class harness.cap.jobs$job_output_default_timeout_ms
        cannot be cast to class java.lang.Number

      顺带两条都不小：**这一轮的哈希是 `@463d48b2`**，与既往前几次观测到的 `@154bc3cf` /
      `@4d27716b` / `@758a3ba0` **都不同**——「它不是契约」因此又被实测钉了一次；那个
      `ClassCastException` 则独立证明 `jobs.clj:845` 的那个 `long` 依赖它是值（不是靠读代码推的）。
      恢复后同一条命令 `0 failures, 0 errors`。
- [x] `job_output` 描述的断言右边是**字面量** `"120000ms"`（上面第三段红的就是它——
      这条断言以前拿同一个坏表达式当右边，所以带着哈希也绿）
- [x] 用例里有一条注释指向票 04，说明跨进程相等在哪验

**两处与票面不同，都是判断：** 用例名不是票面建议的 `no-tool-face-carries-a-stringified-clojure-object`，
而是 `the-tool-table-is-a-pure-function-of-the-tools`——哨兵只是它的一条断言，它真正守的是
「这张表是工具的纯函数」这件事（哨兵、逐字节相同、键序都在这个命题底下）。
另外「反着验一次」没有做成常驻用例：那需要一个**只在测试里**能把常量变回函数的开关，而那个开关本身
就是要提防的东西（它会成为第二份真相）。所以它是一次**写进历史的实验**，不是一条会天天跑的用例。
