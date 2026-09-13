# 05: jsonl 重建端点（replay 晋升）

**What to build:** dev 侧 wire 的 frame-applier（apply-frames / terminal?）与 replay 重建逻辑迁入 src，作为显式的重建读侧——「run 中内核不读自己的日志」铁律不动，重建是管理动作不是 run 行为。violations / frames-from-sse 留 dev 供测试工具使用；evals / dev 用法零变化；replay 测试随迁。新端点：GET /api/threads 扫日志目录返回会话清单（thread id、最后活动时间、规模）；POST /api/threads/<id>/rebuild 返回重建的完整消息列表（种子 = 第一条 input，折叠全部 event 帧，context 带回，reasoning 与 tool calls 都在），截断/损坏日志拒绝并指名；重建动作落 jsonl 审计行。

**Blocked by:** None (can start immediately)

**Status:** ready-for-agent

- [ ] 现有全量测试绿（基线 103 tests / 478 assertions，含随迁的 replay 测试）
- [ ] GET /api/threads 返回会话清单（id、最后活动、规模），空目录返回空列表
- [ ] POST rebuild 对完整日志返回可直接续聊的消息列表（含 reasoning、tool calls、tool results、context）
- [ ] 截断日志（末帧非 RUN_FINISHED/RUN_ERROR）与坏 JSON 行拒绝，错误指名行号与原因
- [ ] 重建动作落一条 jsonl 审计行（kind 写进 spec 契约段）
- [ ] dev 的 evals 用法不受影响
