# 02 — harness.models 并入 harness.providers

**What to build:** 两个 ns 讲的是同一件事，合成一个。`harness.models`（675 行：厂商 → model 表的形状与校验、
选择形状与三档折叠、把选择装配成 provider、wire）并进 `harness.providers`；合完之后 `harness.models` 文件
消失，全仓不再有这个名字——**provider 这件事在一个文件里讲完**：厂商有哪些、每个厂商服务哪些 model、
每条 model 能收什么出什么，以及（03 号票落进来之后）此刻哪一档赢。

**纯搬家**：每个函数的代码一字不改，改的只有 ns 名、文档里指向旧 ns 的句子、以及三处调用方的别名。

先做这一步的理由：03 号票（provider 的解析与密钥）要落进同一个 ns。**先把家并好，03 就只是往里搬**，
不必一边合并一边搬同一个文件的同一批调用点。

**Blast radius 很小**：`harness.models` 的引用只在三个文件里——`src/harness/http.clj`（`wire` / `knobs`）、
`src/harness/memory.clj`（`selection` / `fold-selection` / `assemble` / `catalog` / `inline-fields` / `knobs`，
这个 ns 本身也快没了）、`test/harness/provider_test.clj`（`wire` / `builtin-raw` 等）。全仓 `models/` 前缀
的调用点约 31 处。所以不需要 expand–contract 分批：一次改完就能保持绿。

**Blocked by:** None — can start immediately.

**Status:** ready-for-agent

- [ ] `harness.models` 的全部内容住进 `harness.providers`：函数代码一字不改，只有 ns 名与调用方式变
      （`models/knobs` → 本 ns 的 `knobs`）
- [ ] 调用方改指：`http.clj`、`memory.clj`、`provider_test.clj`；**断言内容不动**
- [ ] `harness.models` 文件删除，`rg 'harness\.models|models/'` 归零
- [ ] ns docstring 按合并后的事实重写：SHAPE 段照搬；两处指向 `harness.memory` 的句子必须处理
      （"哪一档赢是 harness.memory 的事"、"密钥是 memory 的，且只属于 memory"）——**03 号票落进来之前
      这个 ns 只有目录那一半，所以先如实写成"只有目录"，不许留着指向一个即将消失的 ns**；
      `wire` 的 `never-rendered` 那句"密钥永不离开 harness.memory 是 prompt.md 的纪律"同理改写
- [ ] 文件按节组织：分节抬头（the vocabulary / named failures / one provider entry / the file / the fold /
      assembly / wire）保留，别让近七百行内容变成一坨
- [ ] 名字一致性：测试 ns `harness.provider-test` 改名 `harness.providers-test`（文件
      `test/harness/providers_test.clj`），`harness.test-runner` 的清单随改——现在它是这个 ns 唯一的测试文件，
      单数名字只会让人以为还另有一个
- [ ] 全绿，断言数与基线持平（189 tests / 930 assertions）
