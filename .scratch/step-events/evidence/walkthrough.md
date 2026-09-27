# 走查：折叠那一行按步计（票 05）

**怎么走**（2026-09-27，Windows 10 / 4 核）：

```bash
cd ui && npm run build
node scripts/dev.mjs --scripted      # 脚本 provider 回放 scripts/example.json；它报出地址
# 浏览器打开那个地址（本轮是 http://127.0.0.1:4749），发一句「看看这个项目。」
```

**看到什么**（`summary-line-2-steps.png`）：

- 折起来那一行是 **「2 步」**——`scripts/example.json` 回放两步：第一步带一个 `read` 工具（`deps.edn`），
  第二步是答案。步数是对的：**一次请求一步**，工具在那一步之内。
- 展开后两步各自的行都在：`思考 · 先读一眼 deps.edn，确认依赖有没有变。` 与 `read · deps.edn 完成`。
- 上下文圈照旧（`上下文已用 1%`），状态带照旧。
- 控制台三条 404，都与本次改动无关：`favicon.ico`，以及两条
  `/api/threads/<新会话>/stats`——这一场会话还一个字都没写，那条路由手上没有记录可读
  （新建会话第一次问它就是这个样子）。

**为什么非要走这一格**：`npm test` 里那几条只测纯函数（`turnCounts` / `turnSummaryLabel` 的输入输出）。
「页面上那一行真的写着 2 步、展开真的还有两步行」是另一个问题，机器门看不见
（2026-09-18 那次 i18n 合并就是 900 多条全绿而侧栏标题全空）。
