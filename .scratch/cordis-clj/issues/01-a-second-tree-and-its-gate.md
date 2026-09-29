# 01 — 一棵并存的树与它的测试门

**What to build:** `src/cordis/` 这棵树能加载、`test/cordis/` 有一个真会跑的测试命名空间，而且**全量套件
在这个新树存在之后照旧绿**。这一票不写任何插件机制——它写的是「另一棵树在这个仓里怎么合法存在」，
因为今天有一条守卫测试盯着**整个 `src/`**。

`harness.layers-test/every-namespace-is-in-a-layer` 扫 `src` 下每一个 `.clj`，判据是「归入
`harness.{infra,kernel,cap,edge}` 之一」——`src/cordis/core.clj` 会让它红。处理方式是**扩判据**，
不是放宽或删掉：`harness.*` 仍按四层判（原断言一字不改地留着），`cordis.*` 按「属于 `cordis` 这棵树、
且不 require 任何 `harness.*`」判。理由写给下一个人：那条守卫的价值在于「新文件一落地就有人告诉你它归哪」；
一棵并存的树只是**多一条要回答的判据**，不是一条可以豁免的例外——把断言删掉是最省事也最坏的解法。

**Blocked by:** None — can start immediately

**Status:** ready-for-agent

## 形状

```clojure
;; src/cordis/core.clj
(ns cordis.core
  "数据驱动的插件内核。这一票只是骨架：加载得起来、守卫容得下这棵树。")
```

```clojure
;; test/harness/test_runner.clj 的 test-namespaces 字面量里加一行，与文件同一次提交
cordis.core-test
```

## 验收

- [ ] `src/cordis/` 有一个能 `(require 'cordis.core)` 的命名空间（内容可以是骨架 + docstring）
- [ ] `test/cordis/core_test.clj` 在 `harness.test-runner/test-namespaces` 字面量里有条目，整轮**真的**跑它
      （漏进字面量 = 静默不跑而输出照旧说全绿，这是本仓写明的坑）
- [ ] `harness.layers-test` 仍钉着 `harness.*` 的四层判据（原断言一字不改）；新增判据管 `cordis.*`：
      在 `cordis` 这棵树里、且不 require 任何 `harness.*`
- [ ] 新判据真的会红一次：拿一个故意 require `harness.infra.home` 的临时 cordis 命名空间验过，然后撤掉
- [ ] 命名空间与路径一致这条照旧对 `test/` 与 `src/` 都成立（`test/cordis/core_test.clj` 声明 `cordis.core-test`）
- [ ] `clojure -M:test -m harness.test-runner` 退出码 0；`src/harness/` 下改动为空
- [ ] 唯一被动到的 harness 文件是那两条清单/判据，改动的理由进了 commit message
