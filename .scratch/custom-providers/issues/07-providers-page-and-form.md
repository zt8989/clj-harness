# 07 — 模型页：Providers 列表 + 创建/编辑表单

**What to build:** 设置面板的「模型」那一页（导航壳见 06）里，列出这个家里有哪些厂商，并能新建、
改写、删掉一条。表单照参考截图那一张收字段：

- **列表**：每行一个厂商——**显示名（没有就 id）**、一个来源徽标（`built-in` / `your patch` /
  `yours`）、endpoint、几个 model、密钥配没配；右上角 **Add provider**。行可点，点开进编辑。
- **表单**（同一页里换视图，不是嵌套 Dialog）：Provider ID、Display name、API address、
  API protocol（闭集下拉）、API key、Model catalog（每行：id、输入模态、默认 model 单选、删除；
  两个计数（context window / max output tokens）收在一处不挡路的「Limits」里；下面 **Add model**）、
  底部 Cancel / Create provider（编辑时 Save provider）。
- 提交成功回到列表并**看见刚那条**；失败时**视图不关**，把服务端那句原话显示在表单里，
  家目录一个字节没变。
- **编辑一条已存在的**：ID 只读，旁边一句话说明「改名 = 删了重建」，因为凭据名跟着 ID 走。
- **删一条**：行上的 Remove。**不弹二次确认**——这个仓库删的正是那种「只确认你刚做的那个选择」的
  界面件；界面上把「上一步的文件在 `config.edn.bak` 里」这句话写出来，所以删错捞得回来。

从用户视角：**不手编任何文件就能加一家厂商**，加错的当场看得见错在哪，加完在选择器里就能选。

**Blocked by:** 04（目录）、05（写入）、06（导航壳：这一页挂在它下面）
（03 不做的话列表与表单少一栏显示名，其余照做）

**Status:** ready-for-agent

## 验收

- [ ] 新的客户端模块 `ui/src/lib/providers.ts`，照 `lib/skills.ts` / `lib/settings.ts` 的规矩：
      一个 `reasonFrom(res)` 把服务端那句 `{error}` 原话抠出来，类型写清每条字段的含义，
      `AGENT_URL` 仍只有一处。**不引入任何新依赖**（`ui/package.json` 的依赖表逐项不变）。
- [ ] 用的都是现成件：`@/components/ui/dialog`、`@/components/ui/button`、`@/components/ui/input`
      （今天还没人用过它）、协议下拉照 `composer-chrome.tsx` 里那个本地原生 `<select>` 的写法
      （仓库没有 Select 原语，不为这个引一个）。**抄来的 assistant-ui 文件一个都不动**
      （`git diff --stat ui/src/components/assistant-ui/` 空）。
- [ ] `data-slot` 齐，且这批名字写进本票：`settings-providers`、`settings-provider-row`、
      `settings-provider-add`、`settings-provider-form`、`settings-provider-id`、
      `settings-provider-display-name`、`settings-provider-base-url`、`settings-provider-protocol`、
      `settings-provider-key`、`settings-provider-model`、`settings-provider-model-add`、
      `settings-provider-model-default`、`settings-provider-error`、`settings-provider-submit`。
- [ ] **文案英文**（与 06 那套页面名一致）：Provider ID / Display name / API address / API protocol /
      API key / Model catalog / Add model / Limits / Cancel / Create provider / Save provider / Remove。
- [ ] 真机走一遍（`harness.e2e-server` + 假 `CLJ_HARNESS_HOME`，照既有的那套缝）：
      设置 → Models → Add provider → 填 `acme-gateway`（endpoint / 协议 / 密钥 / 一个 model）
      → Create → 列表里出现 → 打开 composer 的选择器，**它立刻在那里**（`catalog` 每轮重读，
      没有重启这一步）→ 选中它、跑一轮拿到回答。截图落 `evidence/`，文件名带 `t07-`。
- [ ] 一次**故意的失败**也走一遍：模型目录留空提交 → 表单里出现服务端那句原话、视图不关，
      随后 `cat` 家目录的 `config.edn` 证明没变（这条证据写进落地记录）。
- [ ] 编辑一条、删一条各走一遍；删完之后列表里没了、`config.edn` 的 `:providers` 里也没了。
- [ ] `cd ui && npm run typecheck` 0 error、`npm run build` 全绿、`npm test` 通过
      （用例数与基准一致；本票不改 `ui/test/`，套件数应当不变）。
- [ ] `clojure -M:test -m harness.test-runner` 失败名单逐条不变（本票改的是 `ui/`，不该动后端用例）。
