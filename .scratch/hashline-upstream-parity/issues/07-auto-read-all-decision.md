# 07 — auto-read-all：明确不做（`wontfix`）

**What to build:** 一个决定。上游 4.3.0–4.3.3 加了一个默认关闭的 auto-read-all：会话开始时把仓库里的
文件**连锚点一起注入**上下文，免去逐个 `read`；本仓完全没有。要不要引入？

**Blocked by:** 人的决定（本票 `needs-triage`）

**Status:** wontfix

## 拍板结果（2026-09-30）

**明确不做。** 上游 4.3 的 auto-read-all 不引入：本仓不走「开局把整仓灌进上下文」这条路，
与已落地的 `receipts-not-echoes`（答案不随文件长大）同向。下面「现场」保留作为它的机制记录；
将来若要翻案，另开一个 `.scratch/auto-read-all/` 的 spec，**不要从这张票直接开工**。

## 现场

上游（4.3.0 起，默认 **off**）：

```ts
// src/constants.ts:22-26
export const AUTO_READ_ALL_CUSTOM_TYPE = "hashline-auto-read-all";
export const AUTO_READ_ALL_MAX_FILES = 500;
export const AUTO_READ_ALL_MAX_FILE_BYTES = 200_000;
export const AUTO_READ_ALL_MIN_BUDGET_BYTES = 200_000;
export const AUTO_READ_ALL_MAX_BUDGET_BYTES = 2_000_000;
// src/config.ts —— AutoReadAllMode = "off" | "on" | "git"，默认 "off"
//   autoReadAllIgnore: string[]（支持目录、文件名、glob）
// src/index.ts —— pi.on("before_agent_start", …) 注入；session 内一次性（custom_message 标记）
// src/auto-read-all.ts（477 行）+ src/auto-read-all-state.ts —— 文件筛选、预算、分块、
//   `[E_AUTO_READ_ALL] … is unchanged since this session's start-of-session auto-read`
// src/grep.ts / read.ts —— 命中与读取路径都带 auto-read-all 的重读拒绝
```

本仓：`src/` 里没有任何等价物；`hashline-edit/spec.md` 与 `receipts-not-echoes` 删掉的是
**另一个东西**——`write` 之后的自动读回（上游的 `autoRead` 键，默认 true），
**不是**开局整仓注入。所以 auto-read-all 在本仓是**从未采纳**，不是「明确否决」。

## 要拍板的问题

1. **不引入**（我的建议）：本仓的立场是「答案不随文件长大」（`receipts-not-echoes`），
   而 auto-read-all 恰恰是**开局吞一份可控但很大的上下文**。它要自己的 spec：文件筛选规则（哪些跳过）、
   预算（`MIN/MAX_BUDGET_BYTES`）、分块注入、会话状态、重读拒绝、配置键（放 `config.edn` 的哪一节）、
   以及 UI（设置面板）。**这不是一张 parity 票能装下的**，若要做应另开一个 `.scratch/auto-read-all/`。
2. **引入**：按上面的清单新开一个特征目录，本票转为它的引子；不要塞进 `hashline-upstream-parity`。
3. **只取一部分**（如仅「`git` 模式」或仅「跳过规则」）：也要先有 spec。

## 为什么值得单独拍

规模与立场都不同级：01–05 是「一处判断」的差额，auto-read-all 是一个**新能力 + 新配置面 + 新上下文预算**，
而且与本仓已落地的「不主动把文件内容灌回模型」方向相反。它需要的是 spec，不是一张实现票。

## 验收（拍板后）

- [ ] 选「不引入」：本票转 `wontfix`，在 spec 的「非目标」里记一句「auto-read-all 未采纳，理由 …」
- [ ] 选「引入」：新开 `.scratch/auto-read-all/spec.md`，本票作为它的来源引用；本特征不再跟踪
