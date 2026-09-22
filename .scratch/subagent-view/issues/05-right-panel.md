# 05 — 右侧面板：并排、一次一个、没有 composer

**What to build:** 主对话右边一块**并排**的面板，里面是那个子agent 自己的对话——消息、思考、工具卡
都用主对话那套渲染，**边跑边长**，**没有** composer。顶部一行说明这是谁，和一个关闭。

要点：

- **机制：面板的 agent 是一个不 POST 的 AG-UI 客户端。** 这是「没有 composer」在机制上的另一面：
  没有输入，但**有一次由页面发起的 run**——面板挂载时自己去开 02 那条跟随通道，把帧按 AG-UI 交给
  运行时。**不要**为此重写一套消息渲染：`Thread` + `THREAD_COMPONENTS` 整块复用，才谈得上「和
  agent 流式聊天界面一样」。
  - 于是它还需要**水合**：先 `rebuildThread(<子会话 id>)` 拿到已经写下的部分（这也是「跑完之后
    右栏仍然读得到」的由来），再开跟随。跟随那边说「不在跑」就到此为止，不用报错——一份跑完了的
    委派本来就该是这个样子。
- **`Thread` 要能不带 composer，而不是分叉一个。** `thread.aui.tsx` 已经把 composer 硬编在
  `ViewportFooter` 里，而且**已经有可选 prop 的先例**（`Welcome` / `ComposerFrame` /
  `ComposerTools` / `ComposerAddAttachment`）：照那个样子加一个开关。分叉会让两边的消息渲染开始
  各自漂。
- **没有 composer，不是被禁用的 composer。** 「一面镜子」要落到可见处：不渲染输入框、不渲染发送键、
  也没有那块「审批中所以关着」的禁用态——那些都是「这个会话可以说话」的说法，而这里没有这件事。
- **并排，不是覆盖。** `app.tsx` 的根是一个 `flex h-dvh`：左边 `Sidebar` 是 `shrink-0`，中间是
  `min-h-0 min-w-0 flex-1`。面板是**第三个 `shrink-0` 的孩子**。注意那两处 `min-w-0` 的注释写的
  原因（flex 子项的自动最小宽度是内容宽度，轨迹那行是**没有空格**的单行 mono JSON，少了它整页会
  横向滚）——面板一来，主栏更窄，这条纪律更要紧。
- **一次一个。** 面板的状态是 `{threadId, subagent} | null` 这样一个单值；再点另一个子agent 就是
  换掉它（顶部那行随之改字）。**不做**多开。
- **关掉要真的收干净**：跟随那条连接要断（01 的退订在这一侧收口），面板的 host 卸载。
  再打开就是重新水合一次——记录才是那份记录，面板不是。**不要**为了「再打开更快」把 host 留在后台
  跑着：那会让一个已经关掉的面板继续占着一条订阅。
- **面板里的 host 不许往侧边栏报到。** `SessionHost` 现在会通过 `onStatus` / `onForget` 把状态报给
  页面、由侧边栏画到某一行上；一个子agent **不是**侧边栏里的会话（那一版就是这么决策的）。要么给
  面板一个自己的 host 组件，要么把那个上报做成可选——但**不能**让子agent 的行因此冒进侧边栏。
- 中英两套文案都要在。
- 宽度固定、并给主栏一个下限（窗口再窄也不许把主对话压到不能用）；窄窗口下面板怎么让位，属于走查
  要回答的问题，别只在脑子里定。

**Blocked by:** 02, 04

**Status:** done

**落地情况（2026-09-22）：** 右栏是页面 flex 行的第三个 `shrink-0` 子元素（主栏 `min-w-0 flex-1`
让出宽度），一次一个（`{threadId, subagent} | null`），`key` 是子会话 id，换人 = 换挂载。
`Thread` 多了一个可选 `composer`（`: false` 时 composer **和**新会话欢迎屏一起不画）；
`lib/follow.ts` 是 `HttpAgent` 的子类，只换 transport（`GET`、无 body、保留 signal）。
没有 history adapter：补发 + 快照就是水合（与 `rebuild` 一起用会把子agent 的答案画两遍）。

**走查实测：** 主栏 896px + 右栏 416px 并排；右栏里 `textbox/form/发送按钮 = 0`；子agent 在
`bash sleep 10` 里时右栏显示 `bash · sleep 10 && echo done | 运行中 | ●`，10.5 秒后**没人碰页面**
它自己长成折叠组 + 结论；关掉右栏时在飞的 `GET .../follow` 是 `net::ERR_ABORTED`，而委派本身继续跑。
跟随通道第一帧必须是 `RUN_STARTED`（先发快照会被 `@ag-ui/client` 拒），这条是浏览器当场抓到的。
