# spec: 新建会话那条条子 —— git 从目录读，并且不要抖

**来源**：主人 2026-09-30 报的两条（真 Chromium、隔离家、一个真 git 仓库的走查）。

**一句话**：composer 上面那条「在哪、在哪个分支上跑」的条子，在新会话（**页面自己铸的 id、还没发过第一句话**）
里要**画得出分支**，而且**任何来晚的数据都不许把它顶得动一下**。

## 症状

1. **新建会话里选好目录之后，只有目录，没有分支。** 条子读出来是 `lisp-harness`，可那个目录是 git 仓库、
   在 `main` 上——分支那一截根本不画。两条路一样：composer 里的目录选择器、侧栏项目行上的「New session」。
2. **focus / 打字那一下，composer 会抖。** 真浏览器量到的三种移动：

| 什么时候 | 动了多少 | 为什么 |
|---|---|---|
| 页面刚加载、listing 到了 | 上跳 8px | 条子空着（6px 高），数据到了才长出选择器（22px），而新会话的 composer 是**居中**的 |
| 打完第一个字（清空又回来） | 下跳 8px | 建议行那个 0 高的壳还在吃 footer 的 `gap-4`（16px），有字就卸载 |
| 打多行字 | 每行上移约半行 | 输入框自己长高，居中那一块跟着重算 |

前两条是「你没动、它自己抖」；第三条是居中布局的自然算术（多数产品也这样），本特征不动它。

## 机制（实测）

**第一条**：新建的会话是**页面自己铸的 id**（`点击新增不立刻会话，发送才新建`），服务端连一行都没有。
条子问的是 `GET /api/git?threadId=<那个 id>`，服务端按 `project/binding-for` 找不到绑定，
答 `{:dir nil :repo? false}`——**对**，但什么都不画。而页面手里明明握着刚选好的目录（`HeldSessionContext`）。
实测：`curl '/api/git?threadId=nope-1234'` ⇒ `{"repo?":false,"dir":null}`。

**第二条**：`justify-center` 的容器里，**块高变一像素，整块就动半像素**：

- 条子原本是空的（6px），listing 到了才画出选择器（22px）⇒ 8px；
- `aui-thread-welcome-suggestions` 那个壳**在没有建议时也在 DOM 里**（高 0），而 footer 是
  `flex flex-col gap-4`——它占的是**一段 16px 的 gap**，不是 0；第一个字一打，条件翻转、节点卸载 ⇒ 8px。
  实测：把那 0 高的节点 `display:none`，打字就不动了。

## 决策

1. **分支这件事按目录问。** `GET /api/git` 收 `?dir=`（答同一形状），`POST /api/git` 收 `{dir, branch}`
   （切的就是那个目录，**真的 checkout**——分支属于工作树，不属于会话）。页面**自己**握着目录时（held）问目录，
   有行时（store 认识的会话）问自己，**一处 loader 二选一**。
2. **门是「这个家列出来的项目」。** 没有门，这条路由就成了「随便哪个路径是不是仓库、在哪个分支上」——
   那是这台机器上的任意路径。项目列表是页面**本来就拿到**的那份（`GET /api/projects` 每次 listing 都给），
   所以门不缩小任何东西：它画的正是选择器那份菜单（`cap.project/listed-dir`）。
3. **`dir` 那一形不写审计行。** 那条行只属于一份会话日志，而这场会话**不存在**；为一个铸出来的 id 写日志，
   正是 `POST /api/project` 当初不再做的事。checkout 本身不隐蔽——`git reflog` 在那个目录里记着。
4. **条子那一行从第一笔起就占住它最高的高度**（`min-h-5`，20px = 有字时 `text-sm` 的行盒）。
   规矩和理由是**状态条**那条（`.scratch/mobile-adaptation` 04：「条子从第一笔就占住自己那一行，
   迟到的数字就顶不动 composer」）。
5. **空的建议行 `display: none`**（`empty:hidden`）：0 高不等于不占地方，`gap-4` 那份就是它占的；
   有建议时那行照旧在。
6. **不碰居中的多行上移**（第三条）：那是「新会话居中」的自然算术，且与用户自己的输入同步，不是抖动。

## 非目标

- 不改「点击新增不立刻会话，发送才新建」：目录与分支都不写任何行。
- 不给 `/api/git` 开任意路径（门见决策 2），也不改 `git/switch!` 的语义（无 `--force`、原话回传）。
- 不动「新会话居中」这个布局本身，也不动未绑定会话的目录选择器。

## 验收主线

1. 新会话里选一个 git 仓库目录 ⇒ 分支那截**当场出现**并显示当前分支；换一个目录 ⇒ 跟着换。
2. 在那里选另一个分支 ⇒ **真的 checkout**（`git rev-parse --abbrev-ref HEAD` 变了），
   且**没有**为那条铸出来的会话写任何行。
3. 没列出来的目录（哪怕它真是仓库）⇒ 读不到、也切不动（400，点名）。
4. 页面加载、聚焦 / 失焦、打第一个字、清空、选目录——composer 顶边**一动不动**。
5. `npm run typecheck` / `npm run build` 绿；UI 套件绿；`harness.edge.http-test` 里那两条 git 用例绿。

## 落地（2026-09-30）

- **服务端**：`cap.project/listed-dir`（门）；`edge.http/git-get` 收 `?dir=`，`git-post` 收 `{dir, branch}`
  （这一形不写审计行）。
- **客户端**：`lib/composer.ts` 加 `gitStateIn` / `switchBranchIn`；`ComposerContextBar` 的 loader 按
  `heldDir` 二选一，**并以它为 effect 的依赖**（所以选目录会重问、分支当场出现）；`switchTo` 同样二选一。
- **抖动**：`composer-chrome.tsx` 那条 row 加 `min-h-5`；`thread.aui.tsx` 的建议行壳加 `empty:hidden`（带 LOCAL 标注）。
- **套件**：`ui/test/suites/composer-context-bar.ts`（2 条，读源码）；`http_test` 加一条 deftest。
- **走查**（真 Chromium + 隔离家 + `/tmp/walk-repo`）：选目录 ⇒ `walk-repo | main` 出现；
  切 `side` ⇒ `git rev-parse --abbrev-ref HEAD` 答 `side`、会话无行；composer 顶边全程 **332**
  （加载 / 聚焦 / 失焦 / 打字 / 清空 / 选目录都不动，改前这五处分别是 8px 上跳、下跳、下跳、下跳、2px）。
- **机器差异**：`harness.edge.http-test` 整只 namespace 太长（118 条，本机 baseline 红），本改动只跑那两条
  deftest —— `dev/scratch_git_dir.clj`，**2 tests / 34 assertions / 0 失败**。
