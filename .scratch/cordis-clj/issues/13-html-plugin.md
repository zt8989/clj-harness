# 13 — `:render/html` 插件：一页真页面

**What to build:** 一个把 Clojure 数据结构渲染成 HTML 的渲染器，inject `:http/router` 给一条 route 装上，
浏览器打得开。渲染那一半是纯函数（数据进、字符串出），所以它不必启服务器就能测。

**Blocked by:** 11 — `:http/server` 插件

**Status:** ready-for-agent

## 验收

- [ ] 一条 route 答 HTML：Content-Type 是 `text/html` 且带字符集，body 是渲染出来的结构
- [ ] 插进 HTML 的字符串**被转义**：往页面里塞一段 `<script>…</script>` 或 `&`，出来的必须是转义形式
      （这一条是安全判据，不是格式好看——必须有断言）
- [ ] 渲染是纯函数那一半：不启服务器就能测
- [ ] 中文按 UTF-8 出去（本仓纪律：每个字节边界显式 UTF-8）
- [ ] 撤销后这条 route 不再服务；别的 route 照旧
- [ ] 零新增依赖：渲染由数据结构拼字符串，不引模板库
