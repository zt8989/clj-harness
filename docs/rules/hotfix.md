# 热修复细则

「怎么跑」那两句在 `AGENTS.md`；这里放细则：**为什么能这么做**、怎么验、陷阱在哪，以及改了什么
该走哪扇门。

改完源码，**正在跑的那个进程手里还是旧代码**——它在启动时读过一次盘，此后不再读。热修复就是把
那个进程里的零件换掉：一次 `:reload`、一次 `install!`，不重启。

它是**人在 dev 里的手法**（REPL，或让 agent 通过 `eval` 做），不是一条运行时机制。所以它
**替代不了测试**，也**不是**让红灯变绿的手法。

## 为什么能这么做

两件事凑在一起才有这条路：

1. **`eval` 跑在服务自己的进程里。** `harness.kernel.tools` 建了一个常驻命名空间
   `harness.user`，`def` 跨调用还在——「在这个进程里求值」是工具表自己的一等公民，不是外挂的
   调试通道。
2. **内核的表是装进去的，不是加载时写死的。** `harness.cap.tools` 的 `register!` 只往自己那张
   表里攒，`install!` 才把它交给执行缝（[layers](../architecture/layers.md) 的「能力怎么进核心」）。
   于是「重新编译一个命名空间」与「让新定义生效」是**两步**，而第二步有名字、可以单独叫。

## 三步

```clojure
;; 1. 重新编译：改动进内存；同一个命名空间里的 register! 顺带把本层那张表刷成新的。
(require 'harness.cap.tools :reload)

;; 2. 交给执行缝。返回的 teardown 留着 —— 下一次热修先撤掉它，层栈就不会越堆越高。
(def hotfix-teardown (harness.cap.tools/install!))

;; 3. 这一轮动的若是 prompt.md（冻结的开头）：让它重读。代价见「陷阱」。
(harness.kernel.llm/reset-prompt!)
```

第 2 步会**多叠一层 `built-ins`**，而层是**按到达顺序折、后来的赢**——所以新定义立刻生效。叠着
不收拾也能跑；想收拾干净，把陈旧的那层摘掉（把 `"bash"` 换成这轮改的那个名字）：

```clojure
;; `layers` 与 `recompute!` 都是私有的，而且没有一个公开的门说「按名字去掉一层」——
;; install! 只回一个 teardown，人在 REPL 里手里没有。所以只能这么拿。
(let [bi    @@(ns-resolve 'harness.cap.tools 'built-ins)     ; 现在这一份表
      la    @@(ns-resolve 'harness.kernel.tools 'layers)        ; 层栈
      ;; 「陈旧」= 名字叫 built-ins，但那层里的表不是现在这一份
      stale (first (filter #(and (= "built-ins" (:name %))
                                 (not (identical? (:run (get (:tools %) "bash"))
                                                  (:run (get bi "bash")))))
                           la))]
  (swap! la (fn [ls] (filterv #(not (identical? % stale)) ls)))
  ((deref (ns-resolve 'harness.kernel.tools 'recompute!))))
```

层叠着不收拾**不算错**（后装的赢），但 `harness.edge.http` 的 `start!` 里有一句注释值得抄在这里：
一个进程起过上百次服务，就是上百个没人认领的层。热修一次收拾一次，别攒。

## 怎么验

**最硬的证据，是调一次改过的那个工具、看它的答案**：热修只有「进程里真的换了」这一条判据，而工具
的答案就是那句话本身。（2026-09-27 那次就是照这条路把 `bash` 的结束行换进去的，进程没重启。）

另一个判据是直接问注册表——**它手里那一份，是不是刚编译出来的那一份**：

```clojure
(identical? (:run (get @harness.kernel.tools/registry "bash"))
           (:run (get @(deref (ns-resolve 'harness.cap.tools 'built-ins)) "bash")))
;; => true
```

`install!` **之前**它是 `false`：文件改了、表也刷了，可注册表还指着旧闭包。这就是热修里唯一容易
漏掉的那一步。

判据问的是「注册表里那一份是这个名字的最新定义吗」。若那个名字同时被别的层定义过（后装的赢），
答案可能属于那一层——这时它答 `false` **不是**说你装错了。

## 陷阱

- **`defonce` 不重置。** `:reload` 不会把 `defonce` 的 atom 推回初值——`built-ins` 正是靠这一点
  「累积刷新」而不是「从头再来」。**改 `defonce` 的初值不等于改了**，那要重启。
- **先 `require` 成功，再 `install!`。** `require` 抛出时表只刷了一半；别对着一半的表装机。
- **`reset-prompt!` 不是免费的。** 冻结的开头是前缀缓存的锚，重读它用**一次冷 prefill** 换这句话
  变新（它同时把 `prompt-epoch` +1，让派生的指令文本重建）。**只改了别处的零件，就别动它。**
- **说明与参数跟着注册表走，不必额外做什么。** 给模型的那张工具表是每次调用现算的
  （`harness.kernel.tools/specs`），所以改过的 description / parameters 下一个调用就带上了；而
  派生的指令文本按**名字集合**记账（`harness.cap.instruction-updates`），只改一个工具的说明
  **不会**让它重建——这是设计，说明不写在那个文本里。
- **落盘的东西不追认。** 记录 / jsonl / 库里的旧字节是旧字节；热修只改往后写出来的。
- **别在并发里拆零件。** 注册表是 atom，`install!` 一次 `swap!` 加一次重算，换本身是原子的；但
  **已经在飞的那次工具调用手里是旧闭包**，它跑完还是旧行为。要的是「下一个调用起生效」，不是
  「立刻全世界都变」。
- **热修不是第二份真相。** 重启即失，重启后照源码来。机器门照旧走 `harness.test-runner`
  （见 [testing](testing.md)），而**测试期间 `~/.clj-harness` 只读**那条铁律在这里照样成立。
- **前端不在这一路。** `ui/src` 归 vite；`ui/dist` 变了要重新 build 才由后端发出去。

## 改了什么 → 走哪扇门

| 改了什么 | 走哪扇门 |
| --- | --- |
| `harness.cap.tools` 里某个工具的定义（名字、说明、参数、实现） | `(require 'harness.cap.tools :reload)` + `(harness.cap.tools/install!)` |
| `prompt.md`（冻结的开头） | `(harness.kernel.llm/reset-prompt!)`——一次冷 prefill |
| `harness.kernel/*` 的机制（`loop` / `llm` / `frames` …） | `(require 'harness.kernel.loop :reload)` 这样重编译即可：那份代码没有注册表要重装。但**已经在跑的 run 仍跑旧栈帧**，新轮次才吃到 |
| 别的能力（`cap.hooks` / `cap.mcp` / `cap.system-prompt` / `cap.subagents`） | 各有自己的 `install!`——`harness.edge.http` 的 `start!` 就是照这份名单装的。**先读它的 docstring**：有的要参数，有的把整张表重装一遍 |
| `ui/src` | 不适用：vite / `npm run build` |

拿不准就**重启**。热修省的是那一次重启，不是那一次核对——每一步都回头对着上面「怎么验」看一眼。

## 日常那个变体

`clojure -M:repl`（`dev/harness/repl.clj`）起服务后直接落进**已经在这个进程里**的 REPL，所以上面
这套在那边是同一件事的手工版：`(require 'harness.kernel.loop :reload)` 换内核，服务照旧在发。
