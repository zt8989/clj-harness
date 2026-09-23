# 04 — prompt.md 里 eval 相关的描述清理

**What to build:** `prompt.md` 是模型读到的第一段话，也是**冻结**的（首调读入即冻，热改要
`reset-prompt!` 或重启）。它今天的几处写法**只有手里有 `eval` 才成立**；02 之后 `eval` 默认不在栈里，
那些句子落在没有入口的地方——模型被指去一条走不通的路。本票把它们摘掉或改写成**读得到**的说法，
纪律本身（不许读、不许打印、不许回传 api-key）一个字不少。

**Blocked by:** 02（先有「eval 默认不在工具表里」这个事实，才知道哪些句子落空了）

**Status:** ready-for-agent

## 现场（逐句）

`prompt.md` 今天的形状：一句身份、一段 Secrets discipline（两条）、一段"其余自己读"、一句收尾。

- **Secrets discipline 的第二条**里有一串 Clojure 层的自省路径：var-quote `#'`、`resolve`、
  私有函数 `harness.cap.providers/api-key`，以及"never return a map containing `:api-key`"。
  这些只有 `eval` 够得到。同一条的前半（不许读、不许暴露、不许进日志与工具结果）与**结果**有关，
  和有没有 `eval` 无关——`bash` 今天照样能 `cat .env`。
- **第二条末尾**还写着"those are test seams"（`use-provider!` / `set-override!`），同样是
  eval 才够得着的说法。
- **「其余自己读」那一段**给的三个入口里，两个是**要 deref 才拿得到**的表达式
  （`(harness.kernel.llm/prompt)`、`(harness.cap.providers/config)`）。文件本身读得到
  （`read` / `bash` 都行），"reading beats being told"这半仍然真，**写法**要改。
  同一段里"`read` and `bash` reach both"指代也不清（列了三样）。

## 决策

- **删机制、留纪律。** 只留"不许读 / 不许打印 / 不许写进任何日志或工具结果 / 不许把它交出去"，
  把只有 `eval` 够得到的**路径名**删掉。理由：纪律管的是**结果**（密钥不许出现），不是**手法**；
  手法没了，纪律留着，而且换了实现也还用得着。
- **自省入口改成读得到的说法**：说清那几个事实在**哪几个文件**里（`prompt.md` 自己、`config.edn`、
  本会话的 jsonl），用 `read` / `bash` 去读。冻结那句承诺（"与任何会话都成立"）因此仍然成立。
- **不新增任何关于 `eval` 的说明。** 工具表里没有它，模型不该被 prompt 提醒"这儿本来有个 eval"——
  那会更像一句招人去找的话。要不要重开它是**配置**的事，写在设置的 Tools 页上（03）。
- **冻结的机制不动**：改的是文件内容，不是"读一次冻住"这条纪律；
  `CONTEXT.md` 里「冻结的开头」那条术语仍成立，逐字核对一遍，冲突就改描述、不改机制。
- **不写按模式拼两套文本**：那条既有规矩（锚点语法归工具描述讲、prompt 只中立地点出自省入口）照旧。

## 验收

- [ ] `prompt.md` 里不再出现只有 `eval` 够得到的写法（`#'`、`resolve`、私有函数名、要 deref 的
      Clojure 表达式）——用一个 grep 断言钉住
- [ ] 纪律的四个"不许"一条不少，且**不指名**只有 `eval` 走得到的路径
- [ ] 自省入口改成"文件 + `read`/`bash`"的说法，且提到的路径**真的存在**（一条用例把那几个路径读一遍）
- [ ] `reset-prompt!` 之后新文本生效；既有的 prompt / system-prompt 用例按新文本改写，
      并留一条"同样的组装给逐字节一样的文本"的断言（前缀缓存那条前提）
- [ ] `node scripts/test.mjs --backend` 全绿
