# 05 — 写入口与面板跟随（含 subagents 那个第二写入者）

**What to build:** 所有**写**配置的地方改写成新段，并且**只留一条写路径**。

**Blocked by:** 04

**Status:** ready-for-agent

## 背景（实测）

今天配置家有两个写入者：

- `harness.cap.providers/write-config!`（私有）：**整份 `check-config` → 原子写 → 留一代 `.bak`**，
  `set-language!` / `put-provider!` / `put-defaults!` / `set-sensitive-paths!` 都走它；
- `harness.cap.subagents/put-definition!`（+ `remove-definition!`）：**自己**拼文本、自己 `spit`、
  自己留 `.bak`（`subagents.clj:805-815`）——它写的是 `harness.edn` 的 `:subagents` 键。

两套写法就是两个会漂的规矩。收口时顺手合成一条。

## 验收

- [ ] `providers` 提供一个**公开**写入口，形如「按一个函数改整份 config，校验后原子写、留 `.bak`」
      （`change-providers!` 已经是这个形状，只是私有）；`subagents` 的写入改走它，**不再自己 spit**
- [ ] `:session :subagents` 的形状与今天 `:subagents` 完全一致（设置面板读写的是它）
- [ ] `POST /api/subagents` 与 `.../remove` 的**答案形状与今天逐字节相同**（面板不跟着改）
- [ ] `GET /api/settings` 的 `home.files` 复核：今天列的是 `config.edn` / `hooks.edn` / `.env` / `harness.db`
      ——合并后仍然只有这四份（`harness.edn` / `mcp.edn` 本来就没列，别顺手加回来）
- [ ] 设置面板里凡是**提到文件名**的句子（中英两份 `ui/src/locales/*/settings.json`，
      以及 `settings-panel.tsx` 的注释）改成说 `config.edn` 的哪一段；
      `update-harness-edn` / `harness.edn` 这类字样不许留
- [ ] 面板「Subagents」那一页的删除说明（今天写「从 harness.edn 里拿掉这条」）说新住址
- [ ] `npm run typecheck` 与 `npm run build` 绿

## 不做

- 不改面板的交互与布局，只改它指向的文件与段。
- 不给 `:session` 加界面（今天也没有）。
