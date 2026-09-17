# 04 — 收口：词、文档、spec、整跑一遍

**What to build:** 这一票不新增行为。它是本特征离开「一直在改」状态前必须落下的几件事：把用词定下来、
把现在已经不成立的文档改口、把决策与非目标写下来，然后真机把三条路各走一遍。

**Blocked by:** 01, 02, 03

**Status:** ready-for-agent

## 要落的四件事

1. **`CONTEXT.md` 加词条。** 今天表里没有「附件」这个词，而这一票之后它在界面、代码与票面上都要用。
   要写清楚的是**它不是一个上传**：本仓没有上传，图是随请求走的字节，没有中间存储、没有 URL、没有生命周期。
   同一条要挡住的是「upload / 上传 / 文件」这几个洋名兼近义词。
2. **`docs/architecture/client.md` 改口。** 那份文件里两处现在不成立：抄来的 `attachment` / `image` 元素
   被写成「槽位不主动接」，以及那份「12 份抄自 assistant-ui、一字未改」的清单。还要写清附件适配器住在哪、
   以及为什么它的判据必须与服务端的 `undeclared-input` 是同一条（两个读者、一条规则）。
3. **`.scratch/composer-image/spec.md`。** 决策、非目标、已知局限三节，下面两条原文写进去。
4. **真机整跑。** 三条路（粘贴 / 拖放 / `+`）各走一次，各留一张截图在 `evidence/`。

## 非目标（写进 spec）

- 不做文档 / 语音 / 任意文件附件：今天只有图片一种（`image/*`）。
- 不做 OCR、不做图片生成、不做按轮去重。
- 不做客户端压缩或缩放（03 的理由：超限就是拒，不偷偷改字节）。

## 已知局限（写进 spec，不假装它不存在）

重建只从**首个 `input`** 取种（`harness.edge.replay/records->messages`，它的测试
`seeds-from-the-first-input-and-ignores-later-ones` 把这条明写下来），所以**第 2 轮以后发的图会跟着那条
用户消息一起不进重建**。首轮发的图不受影响——它就在种子里。这不是本特征能修的，也不在本特征的范围里。

## 验收

- [ ] `CONTEXT.md` 有新词条，且**没有**把「附件」与「上传」混成一个词（本仓没有上传）。
- [ ] `docs/architecture/client.md` 里那两处不成立的说法已改口；新增的那段说得清「判据一条、两个读者」。
- [ ] `.scratch/composer-image/spec.md` 存在，含决策 / 非目标 / 已知局限三节。
- [ ] 三条路各留一张截图在 `.scratch/composer-image/evidence/`（`t04-01-paste.png`、`t04-02-drop.png`、
      `t04-03-picker.png`）。
- [ ] 离线全量 `clojure -M:test -m harness.test-runner` 全绿；`cd ui && npm run typecheck`、`npm run build`、
      `npm test` 全绿。
- [ ] `git diff --stat` 里本票只动文档与 `.scratch/`：`src/` 与 `ui/src/` 零改动（真机验收要起的 dev server
      不算改动）。
