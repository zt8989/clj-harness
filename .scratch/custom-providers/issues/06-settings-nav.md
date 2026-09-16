# 06 — 设置面板长出左侧导航：四页，先把现有的报告搬进页里

**What to build:** 设置面板照参考界面变成**两列**：左边一列导航，右边是选中那一页的内容。四页——
**General**（本会话现在生效的三个旋钮与各自来自哪一档；默认档的三个控件由 08 加进去）、
**Models**（厂商列表与表单，07）、**API key**（现有的 key 那一节）、**Config home**
（现有的路径 / 哪条规则给的 / 文件清单）。打开默认停在 General（参考界面也是第一个）。

**这一票只搬东西、不添能力**：现有的三节报告（`settings-model` / `settings-key` / `settings-home`）
原样进各自的页，「Re-read」按钮从页脚挪到**弹窗自己的页脚**（它是整面板的动作，不是某一页的），
加载中 / 读失败那两种状态也留在面板一层（一个人不该在四页里各学一次错误长什么样）。

导航项**只列这四页**：参考界面那些（插件 / Agent 预设 / 侧边卡边 / 皮肤 / 宠物 / 创意工坊 /
使用统计 / 会话归档管理）在这个仓库里没有对应的东西，**不给不存在的功能造页面**——那是这个仓库一直在删的
那种界面件。以后每多一块设置，先想它挂哪一页。

从用户视角：一次能看的东西从「一长条」变成「四页」，找东西一下就到了；所有旧的事实一件没丢。

**Blocked by:** 02、03（这两票都要动 `settings-panel.tsx` 里的 key 一节与显示名那一行；
本票要把那两节**搬家**，先落地再搬比反着来省一次冲突）

**Status:** ready-for-agent

## 验收

- [ ] `ui/src/components/settings-panel.tsx` 变成「导航 + 一页」：页面选择是组件里的一个
      `useState`，**不引路由依赖**（`ui/package.json` 逐项不变）。导航项与页面名**英文**：
      `General` / `Models` / `API key` / `Config home`（全站文案是英文，中文只进文档与票面；
      与 `sidebar.tsx` 的入口文案一致）。
- [ ] **旧的东西一件不丢**，这些 `data-slot` 在搬家后仍然渲染在各自的页里：
      `settings-provider`、`settings-model-id`、`settings-reasoning`、`settings-key-present`、
      `settings-home-path`、`settings-home-origin`、`settings-home-files`、`settings-refresh`、
      `settings-error`、`settings-loading`。新增：`settings-nav`、`settings-nav-general`、
      `settings-nav-models`、`settings-nav-key`、`settings-nav-home`、`settings-page-general`、
      `settings-page-models`、`settings-page-key`、`settings-page-home`。
- [ ] **弹窗变宽**（参考界面是一张大弹窗）：`sm:max-w-lg` 那一个限制放开到能装下两列，
      两列在窄屏下不重叠（真机截图在 1280 与 ~900 两个宽度各一张）。
- [ ] **文件头注释跟着改**：那条注释今天写着「这里什么都不写」/「Nothing here writes anything」，
      本票改成描述导航与四页，并**明说这一票还没有写的一侧**（07 / 08 才加）——
      免得注释在中间这两票里说假话。`DialogDescription` 同样处理。
- [ ] 真机：四页各截图一张（`evidence/t06-nav-*.png`），General 那张要能同时看到
      三个旋钮的现状与它们各自的档；页面之间点来点去不丢状态、不重发请求
      （`GET /api/settings` 每次打开面板一次，切页不再打）。
- [ ] `cd ui && npm run typecheck` 0 error、`npm run build` 全绿、`npm test` 通过
      （用例数与基准一致；本票不该动 `ui/test/`）。
- [ ] `clojure -M:test -m harness.test-runner` 失败名单逐条不变（本票改的是 `ui/`）。
- [ ] `docs/architecture/client.md` 的设置面板那一节跟上（四页、哪一页放什么；这一节在 07/08 之后
      还要再改一次，本票只写导航这一层）。
