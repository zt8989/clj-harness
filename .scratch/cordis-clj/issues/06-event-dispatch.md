# 06 — 事件 dispatch：emit / waterfall / parallel

**What to build:** 插件能挂 listener（`(ctx :on :事件名 f)`），listener 属于插件、随插件一起撤出。
三种用法各有各的语义：`emit!` 通知（无返回值）、`waterfall!` 是 around 中间件（listener 收 `(…, next)`，
不调 `next` 就短路，调了就把值传下去）、`parallel` 并发但答案顺序稳定。`serial` 就是顺序 `doseq`——
不单独立一个动词。

**Blocked by:** 02 — 服务注册表与可逆 effect

**Status:** ready-for-agent

## 形状

```clojure
(ctx :on :request/pre (fn [req next] (next (assoc req :seen true))))

(emit!       :job/ended id)                  ; 谁都不返回值
(waterfall!  :request/pre req)               ; 有人不调 next ⇒ 短路，答案就是它返回的
```

- 顺序要分清：**listener 被调用的顺序 = 挂上的顺序**；**撤销的顺序是它反过来**（LIFO）。
  这两个顺序在这套东西里是不同的东西，别混。

## 验收

- [ ] `emit!` 叫到所有 listener，参数原样到达；**没有 listener 时不报错**
- [ ] `waterfall!`：某个 listener 不调 `next` ⇒ 它**后面**的 listener 一个都不跑，答案是它的返回值
- [ ] `waterfall!`：listener 调 `(next (f current))` ⇒ 下一个看见的是改过的值
- [ ] `waterfall!`：最早的 listener 改过值之后，短路发生在后面 ⇒ 传出去的仍是改过的那个
- [ ] listener 抛异常：兄弟 listener 照跑完，异常**看得见**（不是被吞掉），这次 dispatch 的答案
      说得清失败了什么
- [ ] 插件卸载后 `emit!` 不再叫它的 listener；两个插件挂同一个事件名互不干扰（撤一个另一个照旧）
- [ ] `parallel` 的答案顺序与 listener 挂载顺序一致（并发跑、顺序稳定）
