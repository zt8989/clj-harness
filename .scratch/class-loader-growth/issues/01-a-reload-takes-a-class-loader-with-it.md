# 01 — 一次 eval / `:reload` 就留下一个类加载器，JDK 不会还回来

**Status:** needs-triage
**Blocked by:** None

**症状（2026-09-30 实测）**：进程起来 **10 分钟**，`jcmd VM.metaspace` 已经是

```
3765 loaders, 13792 classes (1552 shared)
Non-Class: 53.67 MB capacity, 45.80 MB used
Class:     16.33 MB capacity, 12.57 MB used
```

`.scratch/memory-hygiene/spec.md` 在 2026-09-29 就量过同一件事（当时是 6 小时 / 12,802 个类 /
2,756 个 loader / 54 MB metaspace），并把它写进了「不做的事」：**"真要紧时单独开一票"**。
这一票就是那一张。

**为什么单调**：Clojure 每编译一次（`eval`、`require ... :reload`）就挂一个新的
`DynamicClassLoader`，而 JDK 只在整个加载器**不可达**时才卸载它的类；本进程里这些加载器被
引用链留着（`eval` 的返回值、var 的根、`*e` 之类）。

**要定的（所以是 needs-triage）**：这一票要回答的是"接不接受"。三条路：

1. **接受**：写清楚这是本进程的既知成本（一个长跑的开发会话吃几十 MB metaspace），
   并把 `.scratch/memory-hygiene/spec.md` 的「不做的事」从"要开票"改成"已裁定接受"；
2. **收窄**：让反复 `:reload` 的路径不留加载器（比如热修复走一个固定的加载器——见
   `docs/rules/hotfix.md`——或让编译产物之外不留引用）；
3. **量化**：把 loader 数当指标（`jcmd VM.metaspace`），超标就重启进程。

**验收**：按选定那条写出机器可查的判据——要么一份"接受"的裁定落进文档，要么一条证明
loader 数不再随 `:reload` 次数增长的用例。
