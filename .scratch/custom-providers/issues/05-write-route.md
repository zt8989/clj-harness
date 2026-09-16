# 05 — 写入三条动作：新建 / 改写 / 删掉一条 provider（含密钥一行）

**What to build:** 三个动作在服务端落地，web 与 curl 走同一条路：

- `POST /api/providers` —— 新建或改写一条。body 带 `id`、可选的 `display-name`、`protocol`、
  `base-url`、`model`（默认 model 的 id）、`models`（数组，每条 `id` + `input` + `output` +
  可选的 `context-window` / `max-output-tokens`），以及可选的 `api-key`。
  **`api-key` 缺席 = 不碰 `.env`**；给了值就写 `.env` 的那一行（02 派生的名字）；给了空字符串当场拒
  （一句话说清「不写就别传这个字段，要删那行自己编 `.env`」）。
- `POST /api/providers/<id>/remove` —— 删掉一条（走既有的 `/api/<collection>/<stem>/<verb>` 形状，
  不新造路由形状）。**不删 `.env` 里那行密钥**：密钥可能是人手加的，且留着无害。

三条动作都是同一台机器：读 → 改 → **拿新内容整份跑一遍目录校验** → 通过才落盘（临时文件 + rename，
照 `hashline/files` 的写法）→ 失败什么都不写、服务端那句原话回给调用方。

从用户视角：在界面（或 curl）上填六个字段，一家厂商就进目录了；填错的时候**家目录一个字节都没变**，
而错误说的是哪一处不对。

**Blocked by:** 01（写的是 `config.edn` 的 `:providers`）、02（密钥那一行写哪个名字）

**Status:** ready-for-agent

## 验收

- [ ] **校验先于写入，且是一份实现**：把「校验一份 `:providers` 映射」抽成一个纯函数，
      `catalog` 与写入侧都调它——于是「先校验后写」不是两份会漂移的实现。一条用例断言：
      被拒的那次调用之后 `config.edn` **逐字节不变**（含 mtime）。
- [ ] **id 判据只对新的 id 严格**：新 id 必须合 `^[a-z][a-z0-9-]*$`，不合当场 400，原话照参考界面的
      意思写（小写字母开头、用于派生凭据名）。**改写一条已经存在的**（包括手写的、不合判据的
      `:My_Vendor`）允许——ID 不变、派生名不变，判据在这一路上没有要保护的东西。
- [ ] 目录纪律照 01 之后的样子当场拒，逐条有用例与句子：`models` 为空、某条 model 没声明
      `:input`/`:output`、`model`（默认 model）不是 `:models` 的键、条目带未知键、`protocol` 不在闭集里。
- [ ] **protocol 的闭集一处定义**（今天只有 `:openai-completions` 一个真实现，就是 `llm/stream!` 那一个
      method），并让 `GET /api/providers` 的答案多带一个 `:protocols`——界面那个下拉要它，而且它必须与
      服务端真正认的集合是同一份（这是对 04 答案的一行增补）。
- [ ] **`config.edn` 的其它部分原样保留**：用例写一份带 `:default` 和一个将来才有的键的 `config.edn`，
      写一条 provider 之后两个都在（`:default` 的三旋钮逐字段相同）。
- [ ] **落盘是替换不是截断**：`config.edn.bak` 是**改写前**那份原文（一次一代），
      新文件是「服务端写下的一段头注释 + 整份 pprint 的 EDN」——注释会因为重排而丢，这是 01 里
      写明的代价，头注释就是那句话。
- [ ] **`.env` 只动一行**：命中 `^<NAME>=` 就地替换，没命中就追加（末尾少换行先补一个）；
      用例里那份 `.env` 带着别的变量、`#` 注释、`export` 前缀与带引号的值，写完之后**除了那一行，
      逐字节不变**；值里有换行当场 400。
- [ ] **写完立刻生效**：写入之后同进程里再 `resolve-provider`，解析出来的就是这个新厂商
      （`catalog` 每轮重读，没有缓存要清）——一条用例，这一条也是「配置不进库」最直接的证明。
- [ ] **没有任何密钥值出现在回答里**（成功与失败两条路都断言一遍）；`api-key` 缺席时 `.env` 不变。
- [ ] **不留审计行、不进库**：调用前后 jsonl 一个字节不动、`harness.db` 不动（路由表那条
      「审计行跟着日志走，不跟着写入走」）。**不新增 jsonl 行种类、不动 AG-UI 帧、不动 CORS**。
- [ ] 真跑一遍三条动作（curl，对着 `with-resolved-config` 起的那台或在真机上）：
      新建 → `GET /api/providers` 里出现 → `POST /<id>/remove` → 没了，两边的 `config.edn` 差异看得出来；
      这段输出贴进本票的落地记录。
- [ ] `clojure -M:test -m harness.test-runner` 失败名单逐条不变；
      `docs/architecture/edge.md` 的路由表加两行（写入 → 审计栏写「无」，理由一句话）。
