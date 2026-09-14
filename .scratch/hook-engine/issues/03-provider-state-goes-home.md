# 03 — provider 的解析、密钥与进程侧状态回 harness.providers

**What to build:** "这个进程、这个会话服务在哪个 provider 上"这件事落进 02 号票建好的 `harness.providers`：
`config.edn` 的解析与 `.env`/api-key 的解析、scripted pin 与 session override 两个槽、三档折叠与四个解析入口、
自省的 `active-provider`，以及 provider 时间线的 outbox（记一笔变更 / 由边 drain）。init 记账（"这个 thread
的 init 行写过了吗"）搬去它唯一的读写者——http 边，做私有状态。

名字是 `harness.providers` 而不是退回去的 `opaque`：那个名字是与 `memory` 对偶造的（"不可自省的那一半"），
对偶随 memory 一起没了；留下的是它真正装的东西——本进程、本会话服务在哪个 provider 上。

**合并的代价由这一票承担并写进 docstring**：两个 ns 时"读目录的人不必经过密钥"由模块边界表达（表达，不是
强制——eval 用 var-quote 与 resolve 能到任何 var，那条边界从 `0b131f0` 起就只是写下来的规矩）。并成一个 ns 之后，
读 model 目录形状的人也要路过 `.env` 解析那一段，所以 docstring 要分节说明，并且"api-key 只在
`resolve-provider` 的返回里挂上、其它任何返回路径都不出现"这条纪律必须继续由测试守着。

**Blocked by:** 02（家还没并好之前无从搬入；02 建好了 ns 也改过一次调用方别名，这一票只改自己那一半）

**Status:** ready-for-agent

- [ ] `config.edn` 解析、api-key 解析与挂载、pin/override 两个槽、三档折叠与四个解析入口
      （`resolve-provider` / `resolve-override` / `effective-provider` / `current-provider`）、
      `active-provider` 都住进 `harness.providers`；`harness.memory` 不再含它们
- [ ] api-key 的唯一挂载点仍是 `resolve-provider` 的返回；其它任何路径——**包括 `active-provider` 的自省回答**——
      都不出现 `:api-key`，既有测试继续守着（把"任何深度都没有 `:api-key`"那几条明确指给它）
- [ ] **那个 `providers` 直通函数删掉**（`(defn providers [] (models/catalog))` 的全部行为就是这个调用）：
      02 之后 `catalog` 已经在本 ns 里，再留一个 `providers` 就是同一个东西的第二个名字。
      "读目录"从此只有 `catalog` 一个入口，provider_test 里那些调用点随之改指（约 16 处）
- [ ] provider 时间线的 outbox 与 drain 入口随 provider 侧状态一起搬进来；init 记账搬进 http 边并设为私有
- [ ] 调用方全部改指：http 边的 run 装配与时间线落盘、`session-configure` 的 body、replay 的
      `effective-provider`、e2e server 的 pin 缝
- [ ] 测试里引用这些入口的地方改指新位置，**断言内容不动**（provider_test / http_test / llm_test /
      replay_test 里被引用的部分）
- [ ] ns docstring 按合并后的事实补齐：目录那半 02 已写好，本票补上"谁赢"那半（三档、pin 与 override、
      密钥从哪来、时间线 outbox 是什么），并明确写下 api-key 纪律——**说明它是一条写下来的规矩，
      不是语言结构挡住的**
- [ ] `prompt.md` 里指向 `harness.memory` 的路径引用改为新位置（secrets 段的措辞收敛是 13 号票的事）
- [ ] 全绿，断言数与基线持平（189 tests / 930 assertions）
