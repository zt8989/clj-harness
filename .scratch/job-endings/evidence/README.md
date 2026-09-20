# 走查证据：作业结束了，模型下一句话就知道

跑法（真后端 + 真前端 + 隔离家，端口由脚本自己挑）：

```bash
node scripts/dev.mjs --scripted .scratch/job-endings/evidence/go.json --ui-port 5211
```

`go.json` 三轮，正好一次「起一条没人等的作业 → 去干别的 → 收尾」：

1. 模型调 `job` 起一条 1 秒就结束的作业（`sleep 1; echo JOB-SAYS-SO`），拿到句柄就继续；
2. 模型调 `bash {sleep 3}`——这一轮里作业跑完了，而模型在忙；
3. 模型的第三次调用（就是它下一次开口）之前，前置步骤把 `<job-ended id="j…">` 注入进历史。

看什么：会话界面的「轨迹」那一栏，那一轮里应当有一格 **注入的 context（during run）**，正文是
记录尾部、末行 `[exit 0]`；「会话」那一栏里**没有**它（注入物不发帧，客户端从不持有）。

## 走查当天看见的（2026-09-18）

一轮（用户只说了一句「起一条作业，然后去干别的」）：

- 对话那一栏：`job · sleep 1; echo JOB-SAYS-SO` → `完成`；`bash · sleep 3` → `完成`；助手收尾一句。
  页脚 `1 轮 · 3 次模型调用`。**`job-ended` 在对话那一栏一个字节都没有**（注入物不发帧，客户端不持有）。
- 轨迹那一栏：同一条工具之后多出一格 —— 行上写 `上下文`，标题就是那段字节
  `<job-ended id="j1" path="…/jobs/<会话>/j1.log">`；点开右栏是
  `注入: 运行途中` / `来自: 第 1 次模型调用`，正文：

  ```
  <job-ended id="j1" path="…/jobs/<会话>/j1.log">
  JOB-SAYS-SO
  [exit 0]
  </job-ended>
  ```

- 后端日志（那一场的 jsonl）里同一条：`message` 行，`role: user`，`content` 就是上面那段，
  位置在两次工具结果之后、收尾那条 assistant 之前。
