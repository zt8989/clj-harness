# spec: opencode 这个 provider —— 一个额外的层，和它要的那道门

Status: ready-for-agent

**一句话**：给 `opencode`（一个 OpenAI 兼容的网关，**每一个 run 请求**都要求
`x-opencode-session: <thread-id>`）当第一个**扩展**。四层只把它要的能力**暴露出来**——一次解析可以带
一组出站请求头，wire 把它们加上——provider 本身住在一个**额外的层**里：仓库根的独立源码根
（本 spec 拟名 `ext/`，ns 前缀 `ext.`）。四层里一个字都不出现 `x-opencode-session`。

**主人原话**（2026-09-24）：「增加 opencode 这个 provider，放到额外的层，plugin 或者 extend，
名字你来定，因为需要请求头加上 `x-opencode-session: {thread-id}`，可能需要改造那四层代码，
将能力暴露出来。」

## 为什么是「独立源码根」，不是第五层

`harness.layers-test` 盯着 `src/` 下**每一个**命名空间：四层（infra / kernel / cap / edge）、允许的
require 方向、路径与名字一致。所以：

- 在 `src/harness/` 下加第五层，要同时改守卫、`layers.md` 的允许边表，还要回答一个难问题——
  组合根（edge）必须装载这层，而「不许有边指向顶层」会当场被自己破掉。
- 在仓库根开一个**独立源码根**（`ext/`），守卫**不扫它**（它扫的是 `src/` 与 `test/`），
  四层法一个字不改，扩展也不在「谁可以 require 谁」那张表里跟核心层搅在一起。

主人选了后者。

## 决策（主人已答，逐条）

1. **`opencode` 是 OpenAI 兼容的**（`/chat/completions`）。形状不缺，缺的只有一个头：
   `x-opencode-session: <thread-id>`。
2. **额外的层 = 仓库根的独立源码根**：`ext/`，ns 前缀 `ext.`，加进 `deps.edn` 的 `:paths`
   （`:test` / `:dev` 两个别名也要看得见它）。
3. **探询不带这个头**：设置面板「获取可用模型」那条 `GET <base-url>/models` 手里没有 thread-id，
   主人说 opencode 那头不要求它。**「这个头只长在一次 run 的请求上」因此是一条写下来的规则**，
   不是碰巧。
4. **`config.edn` 顶层多第四节 `:ext`**，取值是**字符串向量**（`:ext ["opencode"]`）：那是这个家
   要的扩展清单。**按需加载**——没点名的扩展连 `require` 都不发生。
5. **字符串是短名，约定映射到命名空间**：`"opencode"` → 命名空间 `ext.opencode`，入口固定叫
   `install`（`"ext.<短名>/install"`）。名字必须是合法的命名空间段，否则**读侧**（require 之前）就
   指名失败。
6. **只在开机装载**：改了 `:ext` 要**重启**。`require` 一加载就收不回，所以「卸装」永远不等于
   「卸载类」——不做运行中调和。
7. **装载那一刻就指名失败**：名字不存在 / 没有 `install` / `install` 抛了 ⇒ 开机失败、服务起不来，
   句子里带配置文件路径、那个字符串、以及命名约定。
8. **四票**：机制（01）→ 扩展层与那道门（02）→ opencode 本身（03）→ 文档与全量验收（04）。

（**`:ext` 放 `config.edn` 而不是 `harness.edn`，是主人的选择**，而 `harness.edn` 那一节自称「与厂商、
model、密钥无关的旋钮」——扩展名义上属于它。取舍写进 `providers.md` 与 `config.edn.example`，
别让下一个人重新猜。）

## 机制的形状：四层各让出什么

| 层 | 改什么 | 为什么在这一层 |
|---|---|---|
| infra / 构建 | `deps.edn` 的 `:paths` 多一个根（`:test` / `:dev` 也要） | 构建的事，与「harness 会做什么」无关 |
| kernel | `harness.kernel.llm` 的 `:openai-completions` 把解析结果上的 `:headers` 并进出站请求 | 「一个请求可以带任意头」是机制；**头叫什么是能力** |
| cap | `config.edn` 的**形状**多一节 `:ext`（`config-sections` + `check-config` 那条「每节必须是 map」的唯一例外）；`harness.cap.providers` 多一道安装门（照 `kernel.tools/install!` / `kernel.hooks/install!` 的四条规矩）；解析结果带上 `:headers` | 「能跟哪个厂商说话」是能力层的事；**读侧仍然纯读**，只回答那份清单 |
| edge | 组合根（`harness.edge.http/start!`）读 `:ext`、逐个 `require` 并调 `ext.<短名>/install`，teardown 进 `teardowns` | 组合根是「把能力装进核心」唯一被允许的地方 |
| kernel | `harness.kernel.llm` 的 `:openai-completions` 把解析结果上的 `:headers` 并进出站请求 | 「一个请求可以带任意头」是机制；**头叫什么是能力** |
| cap | `harness.cap.providers` 多一道安装门（照 `kernel.tools/install!` / `kernel.hooks/install!` 的四条规矩）；解析结果带上 `:headers` | 「能跟哪个厂商说话」是能力层的事 |
| edge | 组合根（`harness.edge.http/start!`）显式装载 `ext` 这个根，`stop` 带回 teardown | 组合根是「把能力装进核心」唯一被允许的地方 |

**装载门的名字**：`harness.cap.providers/install!`——与另外两道门同名，是刻意的：读者见过一次
「同名者覆盖、禁用不是删除、teardown 撤自己那层、测试是 teardown 的真正消费者」这四条规矩，
在这里应该一眼认出来。

**头的值算在哪**：解析的时候（`resolve-provider` 手里就有 thread-id），所以解析结果上是**一组已经
算好的头**，kernel 只负责把它们贴上去。这条带来一个不是巧合的后果：**子 agent 的请求带的是父会话的
thread-id**——因为子 agent 的 provider 本来就是从父会话解析的（既有规矩：THE PROVIDER IS THE
PARENT'S, RESOLVED NOW）。于是「一场 harness 会话 = 一场 opencode 会话」自然成立。03 有票面钉住它。

## 注册与加头走的路（写给下一个读者）

**注册是两步，都不是自动的**。编译期：`deps.edn` 的 `:paths` 加 `ext`（`:test` / `:dev` 两个别名也要）
——这一步只让命名空间**可加载**。运行期：`config.edn` 的 `:ext` 点名，组合根
`harness.edge.http/start!` 按约定装载——`"opencode"` → 命名空间 `ext.opencode` 的 `install`，
返回的 teardown 进 `teardowns` 那个向量（与 `cap-tools` / `cap-hooks` / `system-prompt` /
`cap-mcp` / `subagents` 五家同一个向量），`stop` 就是 `(doseq [td teardowns] (td))`。
**没有扫描、没有 require 即注册、没点名的扩展连 require 都不发生。**

**改 `:ext` 要重启**，因为 `require` 一加载就收不回：装上的层可以 `teardown`（可逆），
加载过的类不能卸（不可逆）。所以不做运行中调和，只做开机装载。

**装载那一刻就指名失败**：名字不存在 / 没有 `install` / `install` 抛了 ⇒ 开机失败、服务起不来，
句子里带配置文件路径、那个字符串、命名约定。半装状态由 `start!` 既有的 catch 卸掉。

**由此有一个必须知道的后果**：凡是不经过组合根的入口（`harness.test-runner`、
`dev/harness/e2e_server.clj`、`dev/harness/evals.clj`）扩展不存在——要用它的测试自己装一次。

**加头不是拦截，是一个数据位**，四步一步一个函数：`cap.providers/resolve-provider`（thread-id 在手，
把要补的头算出来挂上解析结果）→ `edge.http` 的 `(loop/run-chan provider …)` → `kernel.loop` 的
`(llm/stream! …)` → `kernel.llm` 那个私有 `request`（今天写死 Authorization / Content-Type / Accept
三个头，改的只有这一处）。

## 为什么不是 onrequest / onresponse

**没有拦截器，也不加。** 今天：`llm/stream!` 是按 `:protocol` 派发的 multimethod（那是「换一整套
请求与响应」的粒度，不是「在请求上加一点」）；`harness.kernel.hooks` 的生命周期点里**没有一条**在
provider 调用内部；`loop` 的 `:before-llm` 是 `run-chan` 的参数、作用在**历史**上；
`harness.infra.llm-debug` 是观察不是策略。

不做的理由（`layers.md` 的「能力怎么进核心」）：**参数优先，安装次之**——能力把事实算出来交给核心。
拦截器是「在核心控制流中间跳进一个能力」，而内核真正被 hook 改道的地方今天只有 `tools/run!` **一处**，
加第二个改道点是设计决定，不该顺手做。

代价不对称，留给将来翻案的人：**onrequest 便宜**（位置只有 `request` 一处）；**onresponse 不便宜**
——响应是懒的 SSE 流（`with-open` + `line-seq` + `consume-sse`），回调要么包住这条流（背压、关闭、
异常从哪层冒），要么缓冲（流式当场变成非流式）。**翻案路径**：opencode 若真要动响应，那是第三个
`:protocol` 实现（`defmethod llm/stream!`），不是拦截器。主人 2026-09-24 定：响应侧不需要。

## 非目标

- 不改目录、三档解析、`GET /api/settings` 的对外语义。
- 不动密钥规则：`opencode` 这个名字按现有派生规则自动得到 `OPENCODE_API_KEY`，不额外做任何事。
- **不做动态加载**：`config.edn` 的 `:ext` 里的名字只按约定映射到**已经在 classpath 上**的命名空间
  （`ext.<短名>`），不按路径去家里读 `.clj`、不在启动时 `eval` 一段用户代码。扩展是**编译进来**的
  源码根，由 `:ext` 点名、组合根显式装载——与这个仓库「不采用 require 即注册」是同一条立场。
- **不做运行中调和**：`require` 一加载就收不回，所以「卸装」不等于「卸载类」；改 `:ext` 一律要重启。
- **不做 `:ext` 的界面**：设置面板不加页、不加栏；它是配置文件里的一行（`config.edn` 会被面板整份重写，
  所以「写完还在」是一条用例，不是一句承诺）。
- 不把任何一处**具体头名**写进四层（守卫测试查不到这一条，靠人守；01 的验收里有一条 grep）。
- 探询（`GET <base-url>/models`）不带 `x-opencode-session`。
- **不做 onrequest / onresponse 拦截器**（理由与翻案路径见上一节）。

## 票面

- `issues/01-headers-on-the-wire.md`
- `issues/02-the-extension-layer-and-its-door.md`
- `issues/03-the-opencode-provider.md`
- `issues/04-docs-and-full-verification.md`
