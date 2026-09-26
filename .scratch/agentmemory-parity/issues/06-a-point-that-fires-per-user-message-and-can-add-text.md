# 06 — `UserPromptSubmit` 接上触发源，并给它一个内容落点

**What to build:** 两件事，因为只有一件时它没有用：**触发源**（`UserPromptSubmit` 现在没有，
而 prompt 就在 set-up 手里）与**落点**（它是门禁，stdout 只被当 JSON 答案读，所以哪怕脚本说了
什么也没人接）。做成了，agentmemory 就能**每条用户消息实时入库**（并从此取代 SessionEnd 那条
transcript 回捞路，见 spec 四），同时给「按这一句话召回」开了一个位置。

**Status:** ready-for-human（这张票里有一个要人定的设计选择）

## 触发源

`src/harness/edge/http.clj` 820–856 那一整段 set-up 里，`input`（AG-UI 的 RunAgentInput）就在作用域
里：`system-prompt/assemble` 在 855 被调用、`opening-blocks!` 在 826、`hook/*sink*` 由更外层的
binding 装着。**最后一条 user 消息的文本就是要捕获的 prompt**——不需要新的管线，只需要在这段里
多一个 emit。

## 落点：两个候选，请挑一个

**(a) 并进 system 消息。** 把 `:user-prompt-submit` 也标成 `:stdout :content`（今天只有
`:system-prompt` 是），让它的 stdout 像 system 块一样被收成有序块、追加到 `assemble` 的产出上。
- 好处：注入落在 system 侧，最「重」。
- 代价：这门手艺目前是**一个点一条特例**（`dispatch` 里 `:stdout :content` 是逐点属性）；要它通用，
  得先想清楚「内容点」是不是一个概念而不是一个例外。

**(b) 走既有的注入通道（推荐）。** `opening-blocks!` 是「这个会话开场带哪些块」的既有通道
（`InstructionsLoaded` 就在它里面发，`http.clj:780`），它读出来的块会**折进这次 run 的 history**
并且**在界面上是一张卡**（`injected (subvec applied (count assembled))` 那条，见
"every injection is a card"）。
- 好处：既有的、已经带卡的、不动 system 消息形状的一条路。一条召回如果进了对话，**就该看得见**。
- 代价：它进的是 user 侧开场块，不是 system 消息。

**建议 (b)**：先按既有的形状做，等真的发现「必须在 system 侧」再回来动 (a)。

## 无论挑哪个都要守的

- **它是门禁（`:gate? true :on-error :block`）**：非零退出或超时 = **这次 run 不开始**。所以声明
  的脚本必须「失败也说 exit 0」——agentmemory 的 `prompt-submit.mjs` 本来就是
  （`main().catch(() => process.exit(0))`），与票 05 同一条纪律。
- **入库要包含原话**：`prompt-submit.mjs` 发的是 `data.prompt`，`prompt` 字段必须是真的那条用户
  消息（不是拼过的、不是截过的——截断是消费者的事）。
- **配置**：`~/.clj-harness/hooks.edn` 加 `:user-prompt-submit`（`node "…/agentmemory.mjs"
  prompt-submit`，`:timeout 4000`），并把它与 `:stop` 的关系写进文件头：**提问实时入库之后，
  SessionEnd 那条路就不需要了**。

## 验收

- [ ] 真跑两轮对话 → 库里每条用户消息一条 `prompt_submit` 观察，`data.prompt` 是**原话**
- [ ] 注入（按挑的那个落点）：模型这次真的看到了那段文本；走 (b) 的话**界面上是一张卡、刷新后还在**
- [ ] 门禁真的说得出话：把脚本换成 `exit 2`（或让它抛）→ 这次 run **不开始**，理由原样给到人
      （而不是静默地少一段记忆）
- [ ] 一条**超时**的脚本 → 同样不开始（这是门禁的既定语义，确认它在这里也是这个语义）
- [ ] 全量绿 + 定向一条「emit 收到的是最后一条 user 消息」的断言
