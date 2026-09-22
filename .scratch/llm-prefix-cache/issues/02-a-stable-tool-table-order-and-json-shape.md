# 02 — 工具表的序与 JSON 形状：稳定的一半在前，键序用 `sorted-map` 焊死

**What to build:** `specs` 不再全局 `sort-by`，改用**一个 `sorted-map-by`**：比较器先比「稳不稳」
（`:source :builtin` 在前），再比名字。同时把我们自己为线上构造的 map 一律构造为 `sorted-map`：
工具信封的两层、内建参数表与它的 `:properties`、以及 MCP 的 `inputSchema`（在过桥那一刻深规范化
一次）。数组一律保序。

**Blocked by:** 01（同一个字节会被两次改；先让描述稳定，再让序稳定）

**Status:** ready-for-agent

## 要落地的判断

1. **序是结构的定义，不是排出来的结果。** 今天 `kernel/tools.clj:411-417` 是
   `(sort-by key (into {} (filter served? (effective-tools thread-id))))`——全局按名字排，于是
   `mcp__*` 落在 `job_output` 与 `read` **之间**：一个外部服务器名单的增删，会把后面那批**内建**
   工具的位置一起挪走。换成 `sorted-map-by`，比较器闭包在那个 `tools` map 上：
   ```clojure
   (let [tools (into {} (filter (fn [[n _]] (served? thread-id n))) (effective-tools thread-id))
         churn? (fn [n] (not= :builtin (:source (get tools n))))
         order  (sorted-map-by (fn [a b]
                                 (let [c (compare (if (churn? a) 1 0) (if (churn? b) 1 0))]
                                   (if (zero? c) (compare a b) c))))]
     (mapv (fn [[n t]] {:type "function" :function (assoc (tool-face thread-id [n t]) :name n)})
           (into order tools)))
   ```
   `into` 到 `sorted-map-by` 上就是「按定义归位」，不需要第二趟 `sort`。
2. **判据是来源标记，不是名字前缀。** `:source :builtin` 由 `cap/tools.clj:54` 的 `register!` 盖，
   `:source :mcp` 由 `cap/mcp.clj:754` 盖——已经有了，别新造一个 `mcp__` 前缀判断：那是第二条
   真相源，改个命名规则就静默失效。
   **`nil` 的情形要写明**：`session-add!`（`kernel/tools.clj:222`）收任意 tool map 且**没有生产
   调用点**（全库 grep 只有定义），所以今天不会有 `:source` 为 nil 的行。归到**善变**那一半
   （排后面）——一个来路不明的工具不该挤进稳定段的字节里。将来真有了调用点，**在门口盖 `:source`**，
   不要在这里猜。
3. **说实话：挪位置救不了对话。** 工具表在**消息之前**（由数据证明：一次 `messages` 全新的调用
   命中了 3072 token，命中只可能来自 system + 工具那一头），所以 MCP 名单一变，它后面的一切
   （包括整场对话）照样冷。这个决策买到的是三样，别多算：稳定的一半成为**一段连续、逐字节不变**
   的字节；**第一处分歧永远落在 MCP 边界**，报告能点名；**名单没变时一切照旧**。
   这三句写进 `specs` 的 docstring。
4. **`sorted-map` 的理由是那个悬崖，不是好看。** 工具表落到线上是 JSON，**键序是字节的一部分**。
   Clojure 的数组 map 到 8 个键为止保插入序，**第 9 个键一进来就整张翻成哈希序**——所有键都可以
   换位，源码上没有任何一行说了这件事。哈希序本身确定（不受进程影响），但它**不再是「键集相同 ⇒
   字节相同」的承诺**：随键集重排、随 Clojure 实现变，而代价是**一次没人察觉的冷前缀**。
   `sorted-map` 让序成为**键的纯函数**：与插入序无关、与键数无关、与哈希实现无关。
   附带的好处是**爆炸半径**：字典序下加一个属性只挪排在它后面的键，哈希序下可能全挪。
   **后者是附带理由，别当成主要理由。**
5. **三处要落，一处不要乱动。**
   - **信封**（`kernel/tools.clj:412-414`）：`{:type "function" :function {…}}` 两层都构造为
     `sorted-map`。注意 `tool-face` 现在返回 2 键 map、`assoc :name` 之后是 3 键——**把
     `tool-face` 的返回值本身也改成 `sorted-map`**，这样它那个 `:describe` 逃逸口
     （`cap/tools.clj:566` 的 read 面、`:604` 的 write 面）返回什么都不会把序带偏。
   - **内建参数表**（`cap/tools.clj:60-64`）：`:parameters` 与 `:properties` 都以 `sorted-map`
     构造。`:required` 保持 `(mapv name required)`——**按声明序**，那是有意义的序，不许排。
   - **MCP 的 `:parameters`**（`cap/mcp.clj:752`）：在**过桥那一刻**做一次**深**规范化——
     递归：map 成 `sorted-map`、vector 成 `mapv`（**保序**）、其余原样。`required`/`enum`/`oneOf`
     的序是内容的一部分，**不许排**。
   - **不要动**：`:required` 的两处构造（内建 `cap/tools.clj:62`、MCP `cap/mcp.clj:753`）。
6. **`cap/mcp.clj:728` 的 docstring 要跟着改。** 它今天写着 `:parameters` 是 `inputSchema`
   「**verbatim**」。深规范化之后，**内容**仍是原话，**键序**是我们规范化过的——把这句话改成
   那个意思，别留一句已经不真的承诺。它的用途（模型看到的与执行缝校验的必须是同一份）不受影响：
   排键序不改 JSON Schema 的语义。
7. **付一次，故意的。** 键序从插入序翻成字典序，**发出去的字节变了一次**，于是**故意冷一次前缀**。
   这是本特征里唯一一次主动的冷启动：写在提交信息里，也写进 `spec.md`，免得下次有人当成回归去查。
8. **集合不许喂进 JSON。** 任何 set 喂进 `:required` 或 `:properties` 都是 bug——集合序连
   「同一进程里两次相同」都不保证。落这一条时顺手 grep 一遍 `#{"` 与 `(vec (set`。

## 验收

- [x] `(specs nil)` 里**所有内建工具的名字**都排在**所有 `:source :mcp` 工具**之前（用例
      `a-sessions-own-roster-sorts-after-the-built-ins`：注册两个 `:source :mcp` 的工具，
      断言内建那一段的字节不变、名单在其后且按名字序）
- [x] 造两个只有 MCP 名单不同的会话 → **内建那一段的字节完全相同**（逐字节比：拿
      `json/write-str` 比 `subvec`，不是比名字集合）
- [x] `(specs nil)` 装配两次 → **逐字节**相同（用例 `the-tool-table-is-a-pure-function-of-the-tools`）
- [x] 工具信封的键序是 `[:function :type]` / `[:description :name :parameters]`，且有用例钉住
- [x] 内建工具的 `:properties` 键按字典序（用例 `every-object-the-table-sends-has-its-keys-in-sorted-order`
      递归遍历**每一层**；失败时报出路径而不是只说「没排好」）；`todo_write` 那条的
      `:items.:required` 顺序 = 声明顺序 `["content" "status"]`，`:enum` 保持
      `["pending" "in_progress" "completed"]`
- [x] MCP 过桥后的 `:parameters`：见下面「与票面不一致的两处」——测在门口，不在过桥处
- [x] `cap/mcp.clj:728` 的 docstring **保住了**它的原话，见下面
- [x] `specs` 的 docstring 里有决策 3 那三句（挪位置救不了对话 / 分歧落在边界 / 名单没变照旧）
- [x] `grep -n '#{' src/harness` 里没有一处喂进 `:required` / `:properties` / `:parameters`
      （命中的都是工具名集合、provider 模态集合、配置键集合；`shell-names` 是 `(mapv name …)`
      出来的**向量**）——而且这条不再靠 grep 守：门口**按名字拒绝**集合

## 与票面不一致的两处（落地时的判断，比票面好，理由在此）

**一、深规范化落在 wire 门口，不落在过桥处。** 决策 5 第三条与决策 6 说要改
`cap/mcp.clj:752` 并弱化 `:728` 的 docstring。改成：`kernel/tools.clj` 新增私有
`wire-json`，`tool-face` 的返回值整个过一遍它（map → `sorted-map` 递归、sequential → `mapv`、
其余原样）。这样子更好，而且**不是**折中：

- **一处生效，两条来源同样受保护。** 内建参数表是嵌套 schema（`todo_write` 的 `:items`），
  过桥处那一次规范化**够不着它**——按票面写，内建那半还得在 `cap/tools.clj:60-64` 再规范一次，
  于是「同一个承诺有两个实现」，将来谁加第三种工具来源就漏一个。
- **能力层继续写可读的字面 map。** 声明式的 schema 是给人读的；让人把每层都手写成
  `sorted-map` 既难看又多一个会忘的地方。
- **`cap/mcp.clj` 一个字没动，那句「verbatim」也就不用弱化了。** 票面担心的是「docstring 说了
  一句已经不真的话」；规范化挪到门口之后，过桥处的承诺（`:parameters` 是 `inputSchema` 本身、
  `:required` 从 schema 里推出来）**仍然是全部真的**——键序是门口的事，不是过桥的事。少改一句
  已经写对的 docstring，比多改一句好。

**二、集合不是「grep 一遍」，是门口按名字拒绝。** `wire-json` 遇到 set 直接抛
`ExceptionInfo("a set may not reach the wire: …")`。理由：集合序连「同进程两次相同」都不保证，
而它的症状正是这个门存在的理由（一次没人解释得了的冷前缀）；悄悄给它排个序会让这个谎看起来
被修好了。用例 `a-set-reaching-a-schema-is-refused-by-name`。

## 在野证据：这张票修的不是假想问题

修完 `toolFingerprint`（见票 04 的落地记录）后，报告脚本在 35 个带工具表的真实会话里读出
**11 张不同的表、8 个中途漂移的会话**。其中 **`d62a2cd9` 的漂移就是本票要修的那件事**：

```
field: order -- read is #87 in the first table and #88 in the second
```

那个会话里多了一个 `mcp__zvec_grep__zvec_grep_search`，于是按名字全局排的 `sort-by` 把
**内建**的 `read` 从第 87 位挤到第 88 位。这正是决策 1 说的「外部服务器名单的增删会挪走内建工具
的位置」，在一份真实记录里被逮到——`mcp__*` 落在 `job_output` 与 `read` 之间，不是推测。

另外 7 个漂移会话分成两类，**都不该算在这张票头上**，报告现在能把它们分开点名：
3 个（`02ba8830`/`4aec5144`/`7d65cd27`）差在 `job_output.description`，是票 01 的身份哈希；
4 个（`b553ed1d`/`d230946c`/`f7085705`/`d3dfb66c`）差在 `bash.description` 的**文本**，是我们
自己改过那个描述——那是「改一次描述付一次冷前缀」的必然代价，不是 bug。
