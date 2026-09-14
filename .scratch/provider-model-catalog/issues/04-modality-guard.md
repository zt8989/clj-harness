# 04 — 模态守卫：声明的输入类型真的拦得住

**What to build:** 模型声明 `:input #{:text}`（如 deepseek）而入站消息带了图片时，run 以**指名错误**结束
（点名 model id 与越界的 part 类型），而不是把图片打给厂商换回一个看不懂的 400。声明 `:image` 的模型照常放行。

**Blocked by:** 02（内置目录里有「纯文本模型」可当反例）、03（翻译定义了什么算图片 part）。

**Status:** ready-for-agent

- [ ] 一个纯函数（`harness.ag-ui`，与 `inbound` 相邻）回答「入站消息里有哪些类型没被声明的输入集覆盖」，
      签名不依赖服务器：`[messages declared-input] → 越界类型集合`。可单测，不必起服务。
- [ ] 边缘在解析出 provider 之后、调用 LLM 之前检查；越界 → 走既有的 RUN_ERROR 通道，消息里**点名
      model id 与越界类型**，并说明该模型声明能收什么。
- [ ] 未声明（inline provider、或 model 没写 `:input`）→ **一律放行**：没声明就没承诺，拦反而是猜。
- [ ] 有测试钉住「厂商一次都没被调用」——被拦住的 run 不发请求（scripted provider 的调用计数为 0）。
- [ ] 文档串与 README 如实标注性质：**流程纪律，不是安全边界**。把 `:input` 写成 `#{:text :image}` 糊弄过去
      照样打得出去；这道闸只防手滑。与围栏（project fence）的既有定性保持一致，不吹。
- [ ] 端到端：声明图片的 model + 图片 part → 正常 run 通过；声明纯文本的 model + 同一输入 → RUN_ERROR
      点名两者；声明纯文本的 model + 纯文本输入 → 正常通过（回归保证）。
- [ ] 离线全量除既有 `bash-runs-git-bash-not-wsl` 外全绿。
