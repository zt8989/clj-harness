# 03 — fiber 与 `:inject` 就绪门

**What to build:** 插件可以声明它要什么（`:inject [:config :http/router]`）。依赖没齐时它**不动**——
`apply` 一行没跑，但注册表里看得见它停在 PENDING；依赖齐了（哪怕晚几步）它**自己**启动，且只启一次。

这是这套东西与「一上来就 require」最大的分别：装配顺序由**数据**说，不是由加载顺序说。fiber 就是
描述这件事的那个 map：id、声明的依赖、disposer 栈、状态。

**Blocked by:** 02 — 服务注册表与可逆 effect

**Status:** ready-for-agent

## 验收

- [ ] 声明 `:inject [:x]` 而 `:x` 不存在：`apply` 没跑（计数器为 0），注册表里这一条说得清
      状态是 PENDING、等的是 `:x`
- [ ] 随后注册 `:x`：它**自己**启动（没有人再叫一次 mount），`apply` 恰好调用一次
- [ ] 缺两个依赖时，两个都在才启动；只补一个不启动
- [ ] 空 `:inject` 的插件立即启动（不是等谁叫它）
- [ ] 启动失败的 fiber 在注册表里说得清失败原因（不是 `nil`、不是只剩一个空 map）
- [ ] 注册表是 `defonce` 保护的 atom：`(require 'cordis.core :reload)` 之后注册表**还是同一个对象**，
      里面已有的 fiber 与它们的 disposer 栈都还在
