# 01 — 配置收成一份：`:default` + `:providers`，`providers.edn` 退休

**What to build:** 一家厂商（provider）从今往后只写在 `config.edn` 里——默认档进 `:default`，
厂商目录进 `:providers`：

```clojure
{:default   {:provider :acme-gateway :model "gpt-x" :reasoning-effort "high"}
 :providers {:acme-gateway {:protocol :openai-completions
                            :base-url "https://gateway.example/v1"
                            :model    "gpt-x"
                            :models   {"gpt-x" {:input #{:text} :output #{:text}}}}}}
```

从用户视角：**配置家只有一个地方要打开**。`providers.edn` 不再被读；那份文件还在时当场指名失败，
并说清「把条目搬进 `config.edn` 的 `:providers`，然后删掉这个文件」。今天的扁平形状（顶层就是三旋钮）
同样当场失败，说清 `:default` 这一节——**不读两种形状、不自动迁移**，与这个仓库对旧的
「provider 就是 model」形状的处置是同一条立场。

合并规则一个字不改：内置表仍是地板，`config.edn` 的 `:providers` 仍是**逐字段、逐 model** 的 `over`，
所以 `{:providers {:openrouter {:base-url "…"}}}` 仍然是一条合法的「补丁」。

**Blocked by:** None — can start immediately（02 与本票互不阻塞：本票只动配置文件的形状与合并，
02 只动 `.env` 与凭据查找）

**Status:** ready-for-agent

## 验收

- [ ] `config.edn` 顶层只认 `:default` 与 `:providers` 两个键；别的键**指名失败**，句子里有
      `:default`、`:providers` 两个词，并说清「三个旋钮写在 `:default` 里」。这一条就是扁平形状的
      失败路径，用例逐字断言这句话。
- [ ] `:providers` 里的条目走今天的 `check-provider`（键集、endpoint、model 表、默认 model 必须是
      表里的键），错误句子里的位置词从 `providers.edn` 换成 `config.edn 的 :providers`。
- [ ] `default-selection` 读 `:default`：`{:default {:provider :acme-gateway}}` 解析成那个厂商；
      `{:default {:protocol :fake :base-url "http://offline.invalid/v1" :model "seeded"}}`
      （**inline 描述**）照旧解析成描述的那种。
- [ ] `providers.edn` 存在 → 指名失败，句子点名 `:providers` 并给出「删掉这个文件」的动作。
      `harness.infra.home/providers-file` 保留（失败句与用例要用它的路径），但**没有任何读侧拿它当目录**。
- [ ] `GET /api/settings` 的家目录文件表里**不再有** `providers.edn`；`providers_test` 里那条
      逐项列文件名的断言跟着改（今天硬写着 `["config.edn" "providers.edn" "hooks.edn" ".env" "harness.db"]`）。
- [ ] **三处测试装置**改成新形状，逐处点名：`test/harness/test_runner.clj` 的 `seed-config`、
      `ui/test/support/harness.ts` 的 `SEED_CONFIG`，以及 `test/harness/edge/http_test.clj` 的
      `with-resolved-config`（它今天 `spit` 一份 `{:provider :alpha}` 的 config.edn **加一份真
      providers.edn**——那份目录要搬进 config.edn 的 `:providers`，`home/providers-file` 那一行删掉）；
      同一文件里设置面板那条用例（写 `config.edn` + `providers.edn` + `.env` 三份、并断言文件名清单的
      那一条）跟着改。
- [ ] 真跑一遍：`clojure -M:test -m harness.test-runner`，**失败名单**与本票动工前逐条相同——
      动工前是 626 条 / 9960 断言，失败**数不稳**（同一天量到 2 也有 6），所以判据是**名字**：
      `test/harness/cap/project_test.clj` 那条 JDK 25 的常驻失败，加上 `http_test` 里两条竞态；
      三条都在 `spec.md` 的状态一节列着。量之前先确认没有别的会话在同一个仓库上跑套件。
      本次实数与名单写进 `spec.md` 的落地记录。
      `cd ui && npm test` 通过（**14 passed**，本票不该动它）。
- [ ] 文档与示例跟上，逐份：`docs/architecture/providers.md`（目录的形状 / 三个旋钮，三档 /
      只读的生效配置）、`docs/architecture/home-and-storage.md`（家目录树、手编配置清单、
      「不搬进库」那条）、`README.md`（家目录树与配置那两段）、`config.edn.example`
      （新形状 + `:providers` 那一节的注释）、**`providers.edn.example` 删掉**（它的说明折进
      `config.edn.example` 的注释里）。
- [ ] `rg -n "providers\.edn" docs README.md src test ui --glob '!node_modules'` 剩下的命中**都是
      「它退休了」的句子**，没有一处还把它当目录读。

**落地后把实数写进 `spec.md`**：失败名单、用例数（`clojure -M:test -m harness.test-runner` 与
`cd ui && npm test` 两边）。
