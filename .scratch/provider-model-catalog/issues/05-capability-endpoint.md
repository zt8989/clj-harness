# 05 — 能力查询端点

**What to build:** 客户端问得出「这个会话现在服务的模型收什么、出什么」，于是 UI 将来能据此决定要不要显示
图片选择器。管理边上多一个只读端点，答案从**活解析**的 provider 取（不缓存、不含 key）。

**Blocked by:** 02（内置目录；否则真实部署下答案多半是空的）。

**Status:** ready-for-agent

- [ ] `GET /api/model?threadId=..` → `{:provider :openrouter :model "anthropic/claude-sonnet-4.5"
      :reasoning-effort "high" :input ["image" "text"] :output ["text"]}`。
- [ ] 三旋钮与 `:input`/`:output` 走 `mem/active-provider`（live 解析、每次现算），`:input`/`:output` 渲染为
      排序后的字符串数组；**任何深度都不含 api-key**（有测试按现有 `active-provider` 的写法钉住）。
- [ ] 未绑定的 threadId、未声明模态的 inline provider、以及缺 `:reasoning-effort` 的档：各自是**答案**
      （字段 absent 或空数组），不是错误。
- [ ] 只读：不落任何审计行（对齐 `GET /api/project` —— 只有会改绑定的路由才落痕）。
- [ ] 文档串说明回答的是**本仓自己的形状**，不是 AG-UI 的 `MultimodalCapabilities`；若客户端将来要走 AG-UI 的
      connect/能力握手，映射目标在那边（`input.{image,audio,video,pdf,file}` / `output.{image,audio}`），本票不做。
- [ ] UI 选择器明确不做（本票只交付端点这个接缝）。
- [ ] 离线全量除既有 `bash-runs-git-bash-not-wsl` 外全绿。
