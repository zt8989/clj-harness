# 09 — 收口：文档跟上新的存储布局

**What to build:** 仓库里关于"东西放在哪"的每一处说法与真实情况一致：home 的目录清单、日志的落点、
新增的 sqlite 与它的两个使用者、侧边栏取代了哪个面板。这一票是扇入点——只有 04–08 都落地了，
README 与各 spec 的现状描述才真的过时到了可以一次性改对的程度。

**Blocked by:** 04, 05, 06, 07, 08

**Status:** ready-for-agent

- [ ] README 的配置家目录清单改到与磁盘一致：`config.edn` / `providers.edn` / `.env` / `harness.edn` /
      `harness.db` / `projects/<workspace>/*.jsonl`；**`logs/` 从清单里删掉**
- [ ] README 里凡是指向 `~/.clj-harness/logs/` 的段落与示例命令一并改掉（含调试与"读日志"的说明）
- [ ] README 说清**库与文件的边界**：库装会变的状态（项目、会话归属、归档、锚点），文件装只追加的记录
      （会话 jsonl）与配置（`config.edn` / `providers.edn` / `harness.edn`，仍是现读、仍不需重启）。
      同时写明**库里没有消息表**，以及**既有 `logs/` 下的会话不会被导入**
- [ ] README 的前端章节补上侧边栏：三段位、新建任务必须先有项目、会话按项目分组、归档、移除项目、设置
- [ ] 写清 sqlite 的位置与它装的两类东西（界面元数据 + hashline 的锚点状态），并指向 hashline 的 spec
- [ ] **改写过后的 hashline spec / 票与本特征对得上**：位置（`harness.db` 而不是 `hashline/` 目录）、
      迁移链归 01、跨进程那条非目标已推翻，并且它的三张表**也是状态而不是日志索引**。这一条要**逐处
      核对**，不是改一处了事
- [ ] assistant-ui 07（项目目录面板）在 spec 的状态与风险里记下"被本特征取代"，并指向本特征——
      它的票面已经置 `wontfix`，文档里不能还写着它要做
- [ ] assistant-ui 08（CopilotKit 出局）的阻塞边去掉 07，并注明 07 的去向；它的其余条目不受影响
- [ ] `CONTEXT.md`（若存在）里与项目 / 会话 / 日志相关的词汇表条目跟上；不存在就跳过，不为此新建文件
- [ ] 全仓搜索一遍 `logs/` 与 `hashline/` 的残留引用（`README.md`、`docs/`、`.scratch/*/spec.md`、
      代码注释），逐处判断是"历史记录"还是"现状描述"——**历史记录留着，现状描述改掉**
- [ ] `clojure -M:test` 全绿；`cd ui && npm run build` 全绿；`cd ui && npm test` 全绿
- [ ] 真 Chromium 把 spec 的验收主线 1–11 走一遍，截图留档
