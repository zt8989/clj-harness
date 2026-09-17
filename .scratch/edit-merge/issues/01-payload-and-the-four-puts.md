# 01 — 载荷与 `PUT` 四形：锚点模式的编辑只剩一个名字

**What to build:** 锚点模式下的**改写**只剩 `edit` 一个名字。它的参数是一个必填的 `input`（字符串），
载荷是一段「段 + 动作」的补丁语言；本票只实现**一个段**与**四种 `PUT`**：

```text
[src/harness/cap/tools.clj]
PUT a3f9..b1c2:
+(defn t-bash [])
PUT <k7m2:
+;; 一行注释
PUT >q4p8:
+(defn t-grep [])
PUT >$:
+;; 文件尾
```

`replace` 与 `insert` 两个名字**退休**（不再是工具名，也不再出现在任何工具表里）。读一个文件、
一次 `edit` 改一处、内容是新的、答案是**新的锚点行**——这条路径要端到端通。

**Blocked by:** None — can start immediately

**Status:** ready-for-agent

## 为什么是这个形状

`replace` 改一个区间、`insert` 在某行前/后插一段：**同一张锚点表、同一条落盘路径、同一个批计划器、
同一套陈旧判定**，差别只在「你指的是一个区间还是一个缝」。omp 的策略把这一类判给合并
（它的 `PUT N.=M:` / `PUT <N:` / `PUT >N:` / `PUT >$:` 就在一个载荷里），本仓照它。

**段头是 `[path]`，没有 `#TAG`**（spec 决策 2）：omp 的 tag 是按文件的，本仓的锚点按行，
照抄 tag 等于把锚点降级成文件版本号。行由锚点指，**一行不动它的锚点一直是它**。

## 要做的事

- **解析是纯函数**：`input` 字符串 → 动作序列，或**指名**的错误。它要能答两个层次：
  「这段载荷指的是哪个文件」（给围栏用，见票 02）与「逐条动作」。畸形载荷答 nil/错误，
  **不抛**——围栏那条路今天就是这么写的（`fenced-path` 的注释：它跑在参数检查之前）。
- **应用走今天的实现**：锚点校验、陈旧判定、`[E_RANGE_STALE]` 回带新锚点、落盘、
  已展示行的规则——**一处都不新写**。今天的 `cap.hashline.replace` / `insert` 的算术搬进来，
  不是复制。
- **body 的规矩照 omp**：每一行 body 都是 `+TEXT`（是**最终内容**，不是 diff 的 before/after）；
  单独一个 `+` 是空行；内容本身以 `-` / `+` 开头时写成 `+-...` / `++...`。
  只有**带冒号**的 `PUT ...:` 吃 body。
- **`N*`（整块）指名拒绝**（spec 决策 3）：本仓没有语法树，话里说清「按行寻址，请给显式区间」。
  这条要写进描述——模型会试，因为参考实现有它。
- **描述（`:describe` 的锚点脸）自带动作表与两个例子**：模型关于这门语言的全部知识都从这里来
  （omp 的 prompt / schema / 例子是随模式整体换的）。
- **批计划器改认 `edit`**：`cap.hashline.replace/batchable?` 从 `#{"replace" "insert"}` 变成
  `#{"edit"}`，`group-key` 改为从段头取路径（它今天从锚点反推文件）。
- **UI**：`subjectOf` 的 `edit` case 改成读载荷的第一个段头（`TOOL_ICONS` 已经有 `edit` 的铅笔图标）。

**跟着改的（不改当天就撒谎）**：`cap.editing/families` 里锚点模式的名字集合、三处硬编码名字清单
（`kernel/tools_test`、`editing_mode_tools_test`）、`CONTEXT.md` 的工具名清单、两页文档的工具数与
名字、`README.md` 编辑那一节、以及**所有**按 `replace` / `insert` 名字写的旧用例（它们改成载荷）。

## 不要做的事

- 不实现多段、寄存器、`REM` / `MV`（票 02 / 03），不实现跨模式同名（票 04），不做守卫清单（票 05）
  ——但**畸形载荷的报错不能等**：本票就要让「未知锚点 / 逆序区间 / 空 body」有指名话术。
- 不动锚点的铸造、归属、已展示的规则；不动 `read` 的输出形状；不动 `write`。
- 不新增第三个编辑模式；不引入环境变量选模式（spec 非目标）。

## 验收

- [ ] 一条真会话（或直调缝的用例）：`read` → 一次 `edit` 的载荷用 `PUT a..b:` 换两行 →
      文件内容对，答案是**新的锚点行**（能直接接着编辑）
- [ ] 四种 `PUT` 各有用例：区间替换（含 `a..a` 换一行）、前插、后插、尾插；`PUT >$:` 插在文件尾
      （不是最后一行之前）
- [ ] 解析是纯函数且可单测：一段好载荷解成期望的动作序列；畸形载荷给出**指名**错误——
      `clojure -M:test -e "(require 'harness.cap.hashline.edit-test) ..."` 或直接注册进
      `harness.test-runner` 跑单文件
- [ ] 逐字节无变化（`PUT` 换成了完全相同的内容）**是错误**，话里点明「改了等于没改」
- [ ] 陈旧锚点仍然逐行回带新锚点（`[E_RANGE_STALE]` 那套行为一个字没变，改成载荷后仍成立）
- [ ] `replace` / `insert` 在 `src/` 里不再作为工具名出现：
      `grep -rn '"replace"\|"insert"' src/harness/cap/tools.clj src/harness/cap/editing.clj` 无输出
- [ ] 工具表跟随：`specs-expose-every-base-tool` 与 `editing_mode_tools_test` 的清单改成新名字集合
- [ ] UI：`cd ui && npm test` 过，且锚点模式的 `edit` 一行显示**第一个段头**（套件里断言 `subjectOf`）
- [ ] `timeout 900 clojure -M:test -m harness.test-runner` 通过，失败**用例名**与基线一致
      （基线 852 / 11233，见 spec「状态」）
