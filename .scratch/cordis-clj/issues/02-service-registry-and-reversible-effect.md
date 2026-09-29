# 02 — 服务注册表与可逆 effect

**What to build:** 「装上插件 ⇒ 它注册的服务别人取得到；卸载插件 ⇒ 服务没了，而且撤销是后进先出」。
这是整套东西的地基：插件在 `:apply` 里通过 `ctx` 注册服务，**注册这个动作自己返回一个 disposer**，
插件也返回自己的 disposer，两个都压进同一条栈。

**Blocked by:** 01 — 一棵并存的树与它的测试门

**Status:** ready-for-agent

## 形状

```clojure
(def plugin
  {:id :demo/greeter
   :apply (fn [ctx]
            (ctx :register-service! :greeting {:hello "world"})   ; 服务以 keyword 为键
            (fn [] (println "undone")))})                          ; 插件自己的 disposer
```

- 取服务用 `(get-service :greeting)`；`ctx` 是**一个 map**，四把钥匙：`get-service` /
  `register-service!` / `effect` / `on`（第四把到票 06 才用得上）。
- `effect` 是给「不是注册服务但仍然改了世界」的事留的口子：`(ctx :effect (fn [] …))`，
  它调你的函数、把返回值当 disposer 收进栈。
- 撤销顺序是 **LIFO**：同一插件里先后注册的两个服务，卸载时后注册的先撤。

## 验收

- [ ] 装上插件后 `(get-service :greeting)` 等于它注册的那份
- [ ] 卸载后取不到，而且**「没注册过」与「值就是 nil」分得开**（判据由实现定，但必须可分辨——
      让调用方拿 `nil` 去猜是这套设计最容易犯的错）
- [ ] 一个插件注册两个服务再压两个 disposer：卸载后两个都没了，撤销顺序可断言（用记调用顺序的 atom）
- [ ] `:apply` 抛异常时**不留半个插件**：已压入的 disposer 全撤，注册表里不留下一个自称 ACTIVE 的条目
- [ ] 同名服务被后一个插件覆盖时，**前者卸载不带走后者那份**（本仓 `install!` 的「卸载撤自己那一层
      并还原下面那层」是这条的先例）
