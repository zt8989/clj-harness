# 01: 会话绑定项目目录：选择入口与工具重根

**What to build:** 用户在 UI 为当前会话选择一个项目目录并绑定。此后该 thread 的文件工具（read/write/edit）相对路径解析到项目目录，bash 以项目目录为 cwd。绑定落 jsonl 审计行；agent 能经自省「问出来」自己绑定的项目目录（对齐 mem/active-provider 的风格：问出来，不抄副本）。harness.project 从本票开始存在：thread-id → 项目目录的会话状态是它的第一块职责。

**Blocked by:** None (can start immediately)

**Status:** ready-for-agent

- [ ] UI 有项目目录入口（输入路径）；后端校验目录存在后才绑定，不存在的路径指名报错
- [ ] 绑定后 agent 用相对路径 read/write/edit 都落在项目目录内
- [ ] bash 以项目目录为 cwd 执行（pwd 可证）
- [ ] 绑定动作在 jsonl 落一条审计行（新 kind，命名随实现，写入 spec 契约段）
- [ ] agent 经自省能问出当前 thread 绑定的项目目录；未绑定的 thread 得到明确的「无绑定」而非报错
- [ ] 未绑定时行为与现状完全一致（回归现有测试基线，绿）
- [ ] UI 显示当前会话绑定的项目目录
