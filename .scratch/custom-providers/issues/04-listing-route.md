# 04 — `GET /api/providers`：给表单看的一份目录

**What to build:** 一条**只读**路由，把表单与列表要的东西一次答完：

```
{:providers [{:name "acme-gateway" :display-name "Acme Gateway" :origin :user
              :protocol "openai-completions" :base-url "https://gateway.example/v1"
              :model "gpt-x"
              :models [{:id "gpt-x" :input ["text"] :output ["text"]} …]
              :credential "ACME_GATEWAY_API_KEY"
              :key {:present? true :source "env-file"}} …]
 :default {:provider "acme-gateway" :model "gpt-x"}}        ; 默认档现状（可能什么都没有）
```

`origin` 三态说清**这一条是谁写的**：`:builtin`（只有内置表有）、`:user`（只有 `config.edn` 有）、
`:builtin-patched`（两边都有——`config.edn` 的条目补在内置厂商上）。这一栏是给界面说实话用的：
「你正在改的是内置厂商 openrouter 的一个补丁」，与「这是你自己的一家」是两件不同的事。

从用户视角：打开面板就知道**家里有哪些厂商、每一条是谁写的、密钥配没配**，不必去翻文件。

**Blocked by:** 01（目录的合并与来源）、02（凭据名）、03（显示名）

**Status:** ready-for-agent

## 验收

- [ ] 路由挂 `/api/providers` 的 `:get`，405 照既有形状答；**只读**，一行审计都不写
      （用例照 `/api/settings` 那条：调用前后 jsonl 一个字节不动、mtime 不变）。
- [ ] 一个答案里的每条都是**这里自己拼的字段**，不是把解析结果整个并进来（`choices` 的写法）；
      **任何深度都不出现密钥值**：一条断言遍历整棵树找不到 `:api-key`，另一条找那把值的字符串。
      `:key` 只有 `{:present? .. :source ..}`（02 之后还可以带名字）。
- [ ] 三种 `origin` 各有用例，都用 `with-resolved-config` 写一份真 `config.edn`：
      只在内置表里的 → `:builtin`；`config.edn` 里补了 `:base-url` 的 `openrouter` →
      `:builtin-patched`（**且它的 model 表仍是内置那张**，补丁逐字段合并的证据）；
      只有 `config.edn` 有的 → `:user`。
- [ ] `:models` 每条带 `:id` / `:input` / `:output`（**排过序的字符串向量**，走既有的 `wire` 规矩），
      两个计数有就有、没有就没有（不是 0）；`:model` 是默认 model 的 id。
- [ ] `:credential` 就是 02 那个派生函数的结果（同一个函数，不是这里的第二份实现）。
- [ ] `:default` 报的是 `config.edn` 的 `:default` 那一节**当下解析出来的三个旋钮**
      （`:default` 缺席时这个字段缺席，不是 `null`）。
- [ ] 真跑：`clojure -M:test -m harness.test-runner` 失败名单逐条不变。
- [ ] `docs/architecture/edge.md` 的路由表加这一行（只读 → 审计栏写「无」），
      `docs/architecture/providers.md` 跟上这条读侧。
