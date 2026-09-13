# 02: 出界审批

**What to build:** 文件工具的 path 解析后落在项目目录与配置家之外的绝对路径时，park 审批——复用既有 :requires-approval 的 park/resume 机制与 interrupt 形态，客户端 resume 流程不变。批准则执行，否决按既有 veto 语义回喂模型。bash 只约束 cwd 在项目目录，不判命令内容：已知逃逸面，边界声明写进 spec（审批是流程约定不是安全边界，哲学不变）。

**Blocked by:** 01

**Status:** ready-for-agent

- [ ] 项目目录内的相对路径与解析后在项目内的绝对路径，read/write/edit 不 park
- [ ] 解析后落在项目目录与配置家之外的路径 park，interrupt 形态与既有审批一致
- [ ] 批准后正常执行（:approved 审计）；否决按 veto 语义回喂（含 payload reason）
- [ ] 配置家自身路径（config/providers/.env 语义）不因出界判定被误伤
- [ ] spec「已验证到什么程度」段记录 bash 逃逸面的边界声明
