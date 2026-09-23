# 05 — 设置面板的「子agent」页：编辑内置、新建自定义

**What to build:** 设置面板多一页「子agent」（与既有的几页同一套导航与版式），干三件事：

- **看见**：内置的通用与探索，加上人自定义的；每行名字、描述、工具范围（基线 + 排除项）。
- **改内置**：内置两个可以改描述、改排除项、把基线在全部/只读之间换。**删不掉**（它们由代码提供），
  界面上要说清"改的是这个家的一份覆盖"以及它落在哪个文件里，改错了捞得回来（既有那套 `.bak`）。
- **新建 / 删自定义**：新建一个子agent（名字、描述、基线、排除项），自定义的可以删。

写入是**用户级 `harness.edn`**：同名条目覆盖内置那条。保存时**按名拒绝**这些：空名字、与已有名字重复、
排除项里写了根本不存在的工具名、把 `eval` / 委派工具写成"可用"（01 定下的后置条件，这里守住）、
基线取值不认识。拒绝时**视图不关**，把服务端那句原话显示在表单里，家目录一个字节没变。

改完**下一轮生效**：委派是现算的，没有重启这一步。

**Blocked by:** 01（可与 03/04 并行）

**Status:** ready-for-agent

## 实现记

**第四页：** `PAGES` 加 `settings-nav-subagents` / `page.subagents`。zh 下这一页叫「子agent」
（其余三页的 zh 是英文词，这一页按本票的名字来）。页面本身 `components/settings-panel.tsx` 里的
`SubagentsPage`，**自己读自己的端点、自己重载**——上面两页共用 `GET /api/settings` 是因为它们读
同一个 config.edn，这一页的文件是 harness.edn，去刷别人的读数等于声称一种不存在的关系。

**`data-slot`（名字，认领）：**

| 位置 | slot |
| --- | --- |
| 导航项 | `settings-nav-subagents` |
| 页 / 读取中 / 读取失败 / problem | `settings-page-subagents` · `settings-page-subagents-loading` · `settings-subagents-error` · `settings-subagents-problem` |
| 新建按钮 / 返回 | `settings-subagent-add` · `settings-subagent-back` |
| 名单 / 一行 | `subagent-roster` · `subagent-roster-row`(+`data-name` `data-builtin`)（行内三个字段与侧边栏同一批 slot，见 04） |
| 表单 | `settings-subagent-form` |
| 名字 / 描述 / 基线 / 排除 | `settings-subagent-name` · `settings-subagent-description` · `settings-subagent-baseline` · `settings-subagent-exclude` |
| 拒绝 / 落在哪个文件 | `settings-subagent-error` · `settings-subagent-where` |
| 保存 / 取消 / 删除 | `settings-subagent-save` · `settings-subagent-cancel` · `settings-subagent-remove`（内置那一档**不画**，不是画成灰的） |

**四类拒绝怎么走：** 表单里只有「排除」一个能写工具名的地方，所以第四类是从这儿进的——
把 `eval` 写进排除项，服务端按名字拒（`eval` 永远不给任何子agent，所以「排除它」读起来像是它本来可用）。
空名 / 重名 / 未知工具名 / `eval`，四条都把服务端那句原话显示在表单里、视图不关、家目录零字节变化
（后者的逐字节断言在后端用例 `every-refusal-a-form-can-show-leaves-the-home-byte-for-byte`）。

**行文案共用：** 名单那一行的名字/描述/范围来自 `components/subagent-list.tsx`，与侧边栏同一份措辞
（shell catalog）。表单自己的字段话在 `settings`，两条基线句读 `shell`——一句范围只有一个说法。

## 验收

- [ ] 设置导航里多一页「子agent」，能列出内置两个与自定义的。
- [ ] 把「探索」的描述改掉、把 `read` 加进它的排除项：保存成功，文件里出现同名覆盖；
      下一轮委派里，这个子agent 的工具表与它自己的自述都跟着变（端到端走一遍，不只是文件变了）。
- [ ] 新建一个子agent（例如只留 `read` + `glob`），保存后它在侧边栏那一组里出现（04 那一组读同一份定义），
      并且能被委派到。
- [ ] 删掉一个自定义子agent：设置页没了、侧边栏那一组也没了；内置两个没有删除入口。
- [ ] 四种拒绝各走一遍且视图不关：空名、重名、未知工具名、把 `eval` 写成可用——
      每次家目录文件与改之前逐字节相同。
- [ ] `data-slot` 齐、名字写进本票；中英两语文案齐。
- [ ] `node scripts/dev.mjs --scripted` 走查一遍设置页（改一条、建一条、删一条、故意失败一次）。
- [ ] `node scripts/test.mjs` 全绿（后端写入路径的用例 + `ui/test/` 里设置页那套）。
