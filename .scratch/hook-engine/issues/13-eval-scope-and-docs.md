# 13 — eval 的定位与文档收口

**What to build:** eval 的对外描述收敛为**hook 引擎的运行期控制面**：动态改一个 hook、动态启停一个 hook，
仅此。`prompt.md` 的 **Self-extension 与 Session tools 两段退场**，改写成 hook 的自助说明（四个入口、两条轴、
关闭不是隐藏）；secrets 段保留但模块名改指新位置；`README.md` 的"会话级自我扩展与晋升路径"段重写；
eval 工具自身的描述同步。工具侧那四个会话级入口**在代码里保留**（测试、http 边、e2e server 仍在用），
只是不再出现在 `prompt.md` 里。

**诚实条款（这一票最重要的一条）：** `prompt.md` 必须写明 eval **仍然能执行任意 Clojure**——
"仅限 hook"约束的是它的定位与文档承诺，不是语言能力。secrets 纪律（api-key 禁读 / 禁暴露 / 禁返回、
禁 deref 那两个私有槽、禁调私有 api-key 函数）是这次收敛里唯一**不因定位改变而失效**的部分，原样保留。

**Blocked by:** 09, 12

**Status:** ready-for-agent

- [ ] eval 的工具描述只说它能做什么：运行期增 / 撤 / 改 / 启停**本会话**的 hook；不再宣称它是通用的自我扩展入口
- [ ] `prompt.md`：Self-extension 与 Session tools 两段删除；新增 hook 自助段，写明四个入口、presence 与
      availability 两条轴、**关闭不是隐藏**（被关的仍在表里、能自己打开）
- [ ] `prompt.md` 的 self-extension 段落如实写明：eval 仍能执行任意 Clojure，"仅限 hook"是定位与承诺，
      不是能力边界；secrets 纪律不因定位收敛而失效
- [ ] `prompt.md` 里指向 `harness.memory` 的路径引用全部改指新家（01–06 已改过一轮，本票复查一遍并归零）
- [ ] `README.md`：「会话级自我扩展与晋升路径」段重写为 hook 的故事；工具表 / 审批 / provider 三处的模块名
      改指新家；架构段与本特征后的模块图一致
- [ ] 落地说明写明 `prompt.md` 是冻结前缀：这次改动需要一次冷 prefill（`reset-prompt!` 或重启）才生效
- [ ] 全绿（基线 189 tests / 930 assertions；本票以文档为主，断言数只增不减；改写过的断言在落地说明里列明）
