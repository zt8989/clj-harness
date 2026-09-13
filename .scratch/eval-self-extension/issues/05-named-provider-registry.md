# 05: 多 provider 具名注册表 + 默认档 + 会话继承与请求指定

**What to build:** provider 从「config.edn 里唯一的一套」升格为**具名注册表**，并定义一套明确的解析优先级。

**1. 具名注册表。** 新建 `providers.edn`，命名若干套 provider，每套含 `:protocol` / `:base-url` / `:model` / 可选 `:reasoning-effort`。例如：

```clojure
{:cheap   {:protocol :openai-completions :base-url "..." :model "nvidia/...:free"}
 :smart   {:protocol :openai-completions :base-url "..." :model "anthropic/..." :reasoning-effort "high"}
 :local   {:protocol :openai-completions :base-url "http://localhost:11434/v1" :model "qwen3"}}
```

`config.edn` 改为**默认档**：指向注册表中的一个名字，并可**逐字段覆盖**默认档的 model 与 reasoning-effort。理由：默认档需要能独立调 reasoning-effort 而不新造一个 provider 条目。

**2. 解析优先级（从低到高，后者覆盖前者）：**

```
注册表中的具名项  →  config.edn 的默认覆盖（model / reasoning-effort）  →  会话级覆盖（04 的 per-thread）  →  请求级指定（本次 run）
```

**本次 run 的请求指定**需要**一个新的读点**（实测）：`ag/inbound` 只吃 `messages` / `prompt` / `context` 三参（ag_ui.clj:201-216），**输入顶层无 `forwardedProps` / `threadState` 的读取**。所以请求级指定必须由 `run-agent!` 直接从 input map 取（http.clj:136-152 的 `(let [[provider messages decisions] ...])` 那一处正是解析点），不经过 `ag/inbound`。

**注意别走 `:context`**：它是现成的 per-run 通道，但内容会变成**尾部 user 消息进入对话**——provider 配置不该污染对话与 prompt cache。字段名（`forwardedProps` / `threadState` / 自定义键）实现时定一个并记入票面注释，形态为 `{:provider :smart, :model "...", :reasoning-effort "low"}`——**三字段各自独立可选**，未给的不覆盖。

**3. 会话继承。** 每个会话**初始继承默认档**；首 run 落 `provider/init` 行（见 03），来源标为 `default` 或 `request`（发起时指定）。后续 run 沿用该会话的生效值，直到中途变更（04）。

**4. 逃生门：允许会话内临时构造完整 provider**（自定义 base-url + model，不经注册表）。理由：注册表覆盖不了"我就试一次这个 endpoint"的场景。此路径在 03 的 init/changed 行里标 `source: "inline"` 以便审计区分。

**非目标：** 不做 provider 的运行时增删注册（改 `providers.edn` + 重启/热读即可，与 config.edn 同规矩）；不做 provider 的健康检查/自动 fallback（那是另一个特性）。

**Blocked by:** 04 的 per-thread 存储形状（**与 04 互为阻塞，需同批次落地**）

**Status:** ready-for-agent

- [ ] `providers.edn` 存在且被解析；`config.edn` 改为默认档（指向名字 + 可选逐字段覆盖）；**两份都是磁盘直读、不缓存**（与 `mem/config` 同规矩）
- [ ] 解析优先级按票面四级顺序生效；某级给了某字段就覆盖，没给就落回上一级
- [ ] `config.edn` 未指默认档时的行为在票面注明（报错 / 回退到注册表第一个 / 回退到旧格式）——实现时定一个并测
- [ ] 四个字段（`:protocol` / `:base-url` / `:model` / `:reasoning-effort`）中任意一级缺省时，取值规则明确且被测试覆盖
- [ ] 请求级指定（AG-UI 可选字段）三字段各自独立可选；发起时指定后，`provider/init` 行的来源标为 `request`
- [ ] 请求级指定在 `run-agent!` 里直接读 input map（**不经 `ag/inbound`**，后者签名只收三参）；测试断言请求字段确实影响该 run 的 `provider/init` 与 `active-provider`
- [ ] **不得**用 `:context` 通道承载 provider 配置（会变成尾部 user 消息污染对话与 prompt cache）；票面记录所选字段名
- [ ] 会话首 run 继承默认档；第二次 run **未变更**时 `active-provider` 与首次一致（继承生效）
- [ ] 临时构造的 inline provider 可用，且 init/changed 行标 `source: "inline"`
- [ ] 缺 `providers.edn` 或引用了不存在的 provider 名时，**报错信息要指名缺的是哪个名字**（不吞成 nil provider）
- [ ] 既有测试的 provider 装配（`fake/scripted`、`http_test` 的 `use-provider!`）不受破坏；若签名变动，票面列明改动清单
- [ ] README 记录注册表形态、四级优先级、请求字段名，并给出 `providers.edn` 示例
- [ ] 离线全量 harness.test-runner 全绿（只增不减）
