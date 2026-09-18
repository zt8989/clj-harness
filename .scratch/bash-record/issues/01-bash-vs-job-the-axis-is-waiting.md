# 01 — `bash` 与 `job` 的轴：接下来会不会等，不是快慢

**What to build:** 模型在两个工具之间做的那次选择，由描述里**真正的轴**决定：**我接下来只会等它 →
`bash`**，把 `timeout` 开到自己估的时长（描述已经明说「写一个很大的数，这次的 run 真的会等那么久」）；
**我接下来要去干别的 → `job`**。今天两条描述写的是**快慢**——`bash` 说「for something that has to
outlive the call, use `job` instead」（`cap/tools.clj:699`），`job` 说「a dev server, a watcher,
**a slow test or build**」（`:720`）——于是「一条跑五分钟的测试」被读成「用 `job`」，而后半程
（谁去等它）没有任何工具负责，模型只好自己拿 `sleep` 拼一个。

**Blocked by:** None — can start immediately

**Status:** ready-for-agent

## 现场

真会话里那对调用：

```
job  {command: "cd <项目> && node scripts/test.mjs --backend > /tmp/backend-probe.txt 2>&1; echo \"EXIT $?\""}
bash {command: "sleep 290; grep -c … /tmp/backend-probe.txt; …", timeout: 330000}
```

- 第一半**照描述做**：一条几分钟的测试确实「outlives the call」，描述就叫它用 `job`。
- 第二半是**补一个不存在的动词**：`job` 的描述从头到尾没有「怎么知道它跑完了」（设计上就没有，
  见 `.scratch/bash-lifetime/spec.md` 决策 10：不阻塞、不通知），而模型的循环是同步的——下一步的输入
  就是这一步的结果。它手里唯一会阻塞的工具是 `bash`，于是 `sleep 290` 被当成 `join`，
  `timeout: 330000` 只是把时限抬到 `sleep` 之上（290000 > 默认 120000）。
- **代价是白付的**：`bash {command: "… > /tmp/x 2>&1", timeout: 330000}` 一步做完同一件事，而且是描述
  允许的。这一票不要求模型「别用 `job`」——它只要求**那条轴被说出来**，说对了，`job + sleep` 这个
  组合自己就没有理由存在。
- **`redirect-note` 不在这张票里改**，理由：它报告的是「命令把输出送去了别处」，那句话是对的；
  它落在事后也不是能靠改措辞修好的东西（要修的是「它为什么需要一个文件」，那是票 03）。

## 要改成什么

1. **`bash` 的描述**（`cap/tools.clj:692-700`）把最后那句指路改成轴，三件事说清：
   接下来只会等它 → 就用 `bash`，`timeout` 开大；接下来要干别的（起个 dev server、再看别的文件） →
   用 `job`；**一条命令要跑多久不决定用哪个工具**。前面那几句（单位毫秒、默认值、到点会怎样）
   一个字不动。
2. **`job` 的描述**（`:718-730`）把 `a slow test or build` 从举例里拿掉或改写——「慢」不是它的判据，
   **「没人等它」**才是；同一段里把「末行 `[exit N]` / 没有那一行就是还在跑」这句留着（它就是
   「你要自己来问」的答案），并在它旁边点明这是**唯一的**完成信号（本仓不给通知、不给 `wait`）。
3. **不加工具、不加参数、不改任何行为**：这一票只动两个字符串。

## 验收

- [ ] `bash` 的描述里那句轴在：一条用例断言它（`harness.kernel.tools-test` 里对描述文本的既有写法，
      或 `harness.cap.tools-test`）；`grep -n "outlive the call" src/harness/cap/tools.clj` 里那个
      「slow → job」的指路已经不在了
- [ ] `job` 的描述里「慢」不再是判据：用例断言它说到「没人等它 / 你要自己来问」这一层
- [ ] 两条描述里的工具名交叉引用还是对的名字（`bash` 指向 `job`，`job` 不再暗示「慢就用我」）
- [ ] `node scripts/test.mjs --backend` 与基线一致（本票只改字符串；失败**用例名**与基线一致）
- [ ] 本票不碰 `ui/`（工具卡读的是参数，描述不进界面）
