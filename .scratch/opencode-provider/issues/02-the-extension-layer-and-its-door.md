# 02 — 扩展层：一个新源码根，`config.edn` 的 `:ext`，和装载它的那一步

**What to build:** 一个**额外的层**（仓库根的独立源码根 `ext/`，ns 前缀 `ext.`），由 `config.edn` 顶层的
**第四节 `:ext`** 点名装载：`:ext ["opencode"]` 就是这个家要的那几个扩展，**没点名的连 require 都不发生**
——「按需加载」就是这件事。四层因此各让出一点：

- **infra / 构建**：`deps.edn` 的 `:paths` 加这个根，`:test` 与 `:dev` 两个别名也看得见它，
  否则测试装置、evals 和真跑看到的世界是三份。
- **cap**：两件事。一是 `config.edn` 的**形状**多一节：`config-sections` 加 `:ext`，而 `check-config` 那条
  「顶层的每一节都必须是 map」要为它开一个口子（`:ext` 是一个**字符串向量**，唯一不是 map 的一节）。
  **读侧仍然纯读**——`(config)` 只回答那份清单，不 require 任何东西（读侧有副作用是这个仓库的禁手）。
  二是 `harness.cap.providers` 多一道安装门 `install!`，照 `harness.kernel.tools/install!` 与
  `harness.kernel.hooks/install!` 那四条规矩——**同名者覆盖、禁用不是删除、teardown 撤自己那一层、
  测试是 teardown 的真正消费者**。贡献来的目录条目走**同一个** `check-provider`，不是第二个校验器。
- **edge**：组合根（`harness.edge.http/start!`）读那份清单、逐个装载：`"opencode"` → 命名空间
  `ext.opencode` 的 `install`。装出来的 teardown 进 `start!` 那个 `teardowns` 向量，
  `stop` 照旧 `(doseq [td teardowns] (td))`。
- **kernel**：01 已经把「应用请求头」这条机制放好了，本票不新增。

**约定**（主人 2026-09-24 定）：`:ext` 里的字符串是**短名**，映射成命名空间 `ext.<短名>`，入口固定叫
`install`——`"opencode"` → `ext.opencode/install`。名字必须是合法的命名空间段（小写字母起、`[a-z0-9-]`），
不然**读侧**就指名失败（在 require 之前）。

从用户视角：在 `config.edn` 里写一行 `:ext ["opencode"]` 并重启，composer 的选择器里就多一家厂商、
跑一轮时它的头在线上；把那行删掉再重启，两样都没了。

**Blocked by:** 01（门要把「解析之后补头」这件事装进来，01 是这套机制的落地）

**Status:** ready-for-agent

## 验收

- [ ] `config.edn` 顶层多一节 `:ext`，取值是**字符串向量**（`["opencode" "acme"]`）：`config-sections`
      加它；`check-config` 那条「每一节必须是 map」为它开一个口子，句子里说清「`:ext` 是唯一不是 map
      的一节，它是一个扩展名清单」。
- [ ] **那两句给人看的话跟着改**：`check-config` 里「顶层是哪几节」的那句（未知顶层键的拒绝语）与
      「每一节必须是 map」的那句，都要把 `:ext` 说进去。它们是被人读的（设置面板原样显示），
      而且有用例**逐字**断言过——改句子就要改那些断言，两处一起改。
- [ ] `:ext` 的**形状在读侧**就校验，在 require 之前：不是向量、元素不是字符串、字符串是空的、
      或不是合法的命名空间段 ⇒ 指名失败，句子带**配置文件路径**、那个值、以及命名约定。
- [ ] **读侧纯读**：`(harness.cap.providers/config)` 回答 `:ext` 那一节时**不 require 任何命名空间**、
      不装任何层（用一个只在被 require 时才会写一笔的假命名空间钉住这条）。
- [ ] **装载只有一处**：组合根 `start!` 逐个名字 require 并调它的 `install`，返回值进 `teardowns`。
      「`ext/` 里有、`:ext` 里没写」的扩展**一个字节都不加载**——按需加载就是这条判据。
- [ ] **装载失败就是开机失败**：不存在 `ext.<短名>` 这个命名空间、或它没有 `install`、或 `install` 抛了
      ⇒ 指名失败，句子带 `config.edn` 的路径、那个字符串、命名约定；且 `start!` **不留半装状态**
      （它今天就在那儿的那段 catch 要把已装上的层卸掉）。
- [ ] 一道 `install!` 门：贡献里至少说得出**这层叫什么**（`teardown` 的拒绝语、日志与报错要用它）、
      **目录条目**、以及**头**。装上传回 `teardown`；`teardown` 撤自己那一层并还原下面那层。
- [ ] **卸载顺序不 load-bearing**：A 装 X、B 覆盖 X、A 先卸 ⇒ B 的 X 还在
      （与 `kernel.install-test`、`hooks.install-test` 是同一族断言，写法照抄它们的）。
- [ ] 贡献来的目录条目**经过 `check-provider`**：形状错的扩展当场指名失败，句子说得出是哪一层贡献的；
      失败之后目录与今天一样（装上来的东西不留半个）。
- [ ] 一个**假扩展**（`ext/` 下一个只贡献一家厂商 + 一个头的真命名空间）被 `:ext` 点名 ⇒
      `GET /api/choices` 里多这一家、跑一轮时它的头在线上；不点名时两样都不存在；`teardown` 之后
      两样都消失。**不出网**，用本地 stub endpoint。
- [ ] **设置面板不会把 `:ext` 冲掉**：走一遍写配置的那几个动作（改默认档、改语言、增删一家厂商），
      `:ext` 逐字节还在——`write-config!` 今天是 `(assoc raw …)`，这条用例钉住它不被改成「重建整份」。
- [ ] **只在开机装载**：运行中改 `:ext` **不产生任何效果**（用例：改完配置文件再问一次，装的那层仍在，
      而不是悄悄卸掉）。「`require` 一加载就收不回、卸装≠卸载类」写进文档。
- [ ] `GET /api/providers`（`registry-report`）看得见扩展贡献的条目，并说得出它**来自哪一层**；
      「内置 / 你自己加的 / 你补内置的」这三档要添第四档还是复用哪一档，本票定，定了写进 `providers.md`。
- [ ] `ext/` 这个根里放一个真的命名空间（可以很小，但它是编译进来的真代码）；
      `harness.layers-test` **一个字没改**并且绿。
- [ ] **不走组合根的入口说清楚**：`harness.test-runner`、`dev/harness/e2e_server.clj`、
      `dev/harness/evals.clj` 各有自己的装配。要么各处补同一行，要么测试直接调 `ext.<短名>/install`——
      两条选一条，并写进 `spec.md`。这是「注册不是自动的」的代价，别让它变成隐式的。
- [ ] `clojure -M:test -m harness.test-runner` 失败名单逐条不变；失败实数与名单写进 `spec.md`。
