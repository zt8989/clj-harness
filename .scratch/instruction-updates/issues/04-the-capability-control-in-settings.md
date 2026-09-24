# 04 — 按模型配置能力位的那个控件：设置 → Models 页的三态

**What to build:** 设置 → Models → 展开一个 provider → 每个模型行（`ModelRowEditor`）里多一个**三态**
控件：**未声明**（缺省档，行为等于今天的替换）/ **`in-place`**（对话中途送一条 `developer` 消息）/
**`replace`**（每轮替换 system 消息）。选中的值原样写进那一行的 `:instruction-updates`，未声明档
**不写这个键**。

外加**自动获取模型列表时的内置前缀预填**：表单按 Fetch 问 `/models`，命中内置前缀表的 id 在候选
清单里带着值，take 进来的行就预填成 `:in-place`（决策 8）。

这一票只**搬名字**：「变了怎么送」是 02/03 的事，「哪些值合法、缺省是什么」是 `check-model` 的事，
前端一条都不复刻。服务端那一半只是那张前缀表与探测答案多说的一句话（判断 7/8）。

**Blocked by:** 02（能力位得先在 catalog 里有名字：键、闭集与拒话、报告里那一条读法）

**Status:** ready-for-agent

**先不落地（2026-09-21 的口径）：** 票已经写全，但按人的话，等 session live server 那件事完成之后
再实现。这不是一条 `Blocked by`——本仓里没有那条依赖，它是一次排期。

## 要落地的判断

1. **控件长在模型行里，不长在 provider 行里。** 能力位是**模型条目**的键（票 02），同一家 vendor 的
   两个模型可以一边支持一边不支持。所以它进 `ModelRowEditor`（`ui/src/components/settings-panel.tsx:487`），
   与 `text` / `image`、`limits` 挂在同一处——它就是「这个模型是什么」的又一句陈述，与端点无关。
   - **它够得着内建的那张表**：页面给每一个 provider 行都开同一张表单，保存一条内建的会写成
     `:builtin-patched`（`cap/providers.clj` 的 `put-provider!`：「a patch of a built-in is checked
     against what the built-in provides」），所以预置目录里的模型也能在这栏里被声明。不要给内建行加
     一个「只读」守卫——今天没有那条路，加上去就是新造一条。
   - **patch 是整条替换**，所以表单必须把读到的值**原样带回**：一次只想改 display-name 的保存，不该
     把某一行的能力位抹掉。这是下面「一次无关的编辑」那条验收盯着的东西。

2. **三态，不是开关，也不是两态。**
   - **未声明是一个真实的答案**，不是空档：它说的是「这一行没说」，而缺省（`:replace`）落在
     **解析**那一步（票 02），不落在文件里。把它做成两态（支持 / 不支持）就等于每次保存都替人补一句
     `:replace`，那是一个他没说过的声明。
   - 表单**不许替人写下他没说过的话**：一个 provider 里五个模型，人只想给其中一个开 `in-place`，其余
     四个必须**原样保持未声明**——否则一次保存把四行都写成显式 `:replace`，`config.edn` 的 diff 会替
     人答一道他没答过的题。
   - 所以第三档的实现是**删键**：`delete next["instruction-updates"]`，与 `limits` 空输入同一个习惯
     （`settings-panel.tsx:563`：空字符串＝不说），也与 `ProviderPayload` 的 `api-key` 缺省同一个习惯
     （`ui/src/lib/providers.ts:88` 的注释：absent ＝不碰 `.env`）。

3. **词是界面的，值是 catalog 的，判据是服务端的。**
   - 选择项的文字进 `ui/src/locales/{zh,en}/settings.json` 的 `form` 面，**两边都写**——i18n 套件
     （`ui/test/suites/i18n.ts`）盯着「每个键两种语言都有、都不空」。键**字面量**写在调用点，不许拼
     （`ui/src/lib/catalogs.ts` 的规矩，也是 `ui/src/i18next.d.ts` 能编译的前提）。
   - 值的那两个字（`in-place` / `replace`）**照印**，与模型行印 `text` / `image` 同一习惯
     （`settings-panel.tsx:536` 印的是 catalog 的词）；**未声明那一档没有 catalog 的词，它的文字是
     界面的一句话**。三条键就够：select 的 `aria-label`（这一栏问的是什么）、第三档那一句、以及
     select 下面一行 `text-[10px]` 的 hint（形状照 `form.limitsNote`，`:571`）——再加一条给候选清单
     里那个小标记（判断 8）。hint 里两档各自的意思都要说（`in-place` ＝对话中途送一条 `developer`
     消息，`replace` ＝每轮替换 system 消息），并点名它写的是 `config.edn` 的哪个键——Models 页是唯一
     会把这件事写进文件的手。
   - **前端不认识 `in-place` 的语义**：它把服务端给的名字搬回去，仅此而已。合法值与缺省都只在
     `cap/providers.clj` 说；这里复刻一条更弱的规则，就是第二份会漂的答案（`lib/providers.ts` 开头
     那段话的同一个理由）。
   - DOM 给回来的值是**不可信**的——先按固定的三档清单核一次再写进 draft，照 `isLanguage` 那条守卫
     （`settings-panel.tsx:396`）写；核不上就不写，而不是写一个我们自己拼出来的值。

4. **读的路：报告照文件说，解析按缺省补。**
   - `ModelRow`（`ui/src/lib/providers.ts:43`）与 `ProviderPayload.models`（`:97`）加可选
     `"instruction-updates"?: "in-place" | "replace"`；`draftOf`（`:588` 起的 `{...m}`）本来就整行拷贝，
     所以值会原样跟着走。
   - 服务端那一半是票 02 的：`registry-report` 的 `model-row`（`cap/providers.clj:1531`）**只在该模型
     自己写过这个键时带上它**，与那两个计数「有才带」同一写法。未声明 ⇒ 键缺席 ⇒ 控件停在第三档。
   - **报告里不许填缺省**：填了，页面就再也分不出「没说」与「说了 `replace`」，而保存时会把缺省写成
     显式——正是第 2 条不许发生的事。缺省属于解析：报告答「文件里怎么写的」，解析答「这次 run 照哪一档
     送」，两个读者两个答案（票 02 的判断 8）。
   - 值集合是**闭集**（票 02 的 `check-model` 按名字拒别的），所以表单读到的值一定在这三档里；哪天
     目录多一档而前端没跟上，先坏的是 `check-model` 那句拒话，页面显示拒绝而不是半张表——闭集按名字
     失败该有的样子。前端**不为「不认识的值」留一条猜的路**。

5. **本票不碰送达那条路。** 控件只写配置：`:in-place` 的送达是 02，`:replace` 的显式化是 03。所以
   这一票的验收是**一次保存的往返**（文件里前后差什么），不是一条请求体的形状。涉及后端的只有探测
   那一处（判断 7/8），而它也只是让答案多说一句话。

6. **内置前缀表只在「自动获取列表」那条路上说话，不参与解析。** 表单按 Fetch 走的是现成的
   `POST /api/providers/models`（`edge/http.clj:2360` 的 `provider-models-post` →
   `cap/providers.clj:1970` 的 `probe-models`）；命中的 id 预填成 `:in-place`，没命中什么都不说。
   - **运行时那条路不认它**：一个模型条目没写这个键、id 又命中规则 ⇒ run 照旧走 `:replace`。理由：
     「这次 run 照哪一档送」不许有一个不在 `config.edn` 里的主人——同一个**没写**这个键的模型，配上
     一旧一新两版表，会在两个进程里送出两种请求，而文件里读不出为什么。所以**不要**去动 `selection`
     / 送达那一处。
   - **它也不追认已经写下的行**：不为老配置补值、不重写 `config.edn`——文件是那个人当时说的话。

7. **那张表住在服务端，形状与读法一处说了算。**
   - 家：`cap/providers.clj`，内建 provider 那张表旁边（它是 catalog 的一件产品事实，不是界面文案）。
   - 形状：**前缀 → 一个值**，值取自同一个闭集。今天的行全是 `:in-place`；`replace` 行只在要给一条
     更宽的前缀开例外时才写。
   - **最长前缀赢**：`gpt-4o…` 同时命中 `gpt-4o` 与 `gpt-4` 时，较长的说话。不许「先遇到的赢」——
     map 的迭代顺序不是一个事实，而一个要读者去猜顺序的表就是两份答案。
   - **每一行都要有依据**，而且是有方向的那种：这张表是对厂商的断言，猜错的方向是**整个 run 起不
     来**（票 02 判断 1 同一条理由）。第一版按通行几族起（`gpt-` / `o1` / `o3` / `claude-` /
     `deepseek-` / `kimi-` / `moonshot-` / `qwen-` / `glm-` 之类），**逐行附一句依据**；拿不出依据的
     家族不进表——与决策 6「不预置看不到依据的行」同一条纪律。表旁边写明这件事，免得下一个人往里
     添一行凭印象的前缀。
   - 匹配只对**模型 id**：网关常把 vendor 写进 id（`moonshotai/kimi-k2`），照样命中。**不做大小写
     归一、不 trim**——那是「这个模型是谁」的第二个答案。

8. **答案带行不带裸 id，前端只照搬。**
   - `probe-models` 的答案从 `{:models ["gpt-x" …]}` 变成 `{:models [{:id "gpt-x" :instruction-updates "in-place"} …]}`
     （未命中就是没有那个键，与报告的「有才带」同一条）。`probeModels`（`ui/src/lib/providers.ts:150`）
     的返回类型跟着改；候选清单那一行仍印 id，另印一个小标记说规则命中了什么。
   - take 进来时按这份答案预填（`settings-panel.tsx:852` 那个按钮今天建的是
     `emptyModel(id)`，`:478`）——**前端不做前缀匹配**：那是第二份会漂的表。
   - **预填不是替人写下**：它看得见（select 停在那个值上）、改得动（拨回未声明就是删键），而未声明
     那一档**什么也不预填**。
   - 手打进来的 id（Add model）**不问规则**（那会变成一次按键一次请求），也不在运行时兜底：人要自己
     选。这是规则「只在自动获取那条路上说话」的直接推论。
   - **那道既有缝合线不动**：`*list-models*`（`cap/providers.clj:1955`）答的仍然是「厂商列了什么」
     （一串 id），规则是**加在答案上的 catalog 意见**——所以桩还是返回 id 向量，命中的判断发生在
     桩之外。测试用它的时候，一条 id 选自表里明确命中的那一族、一条明确不命中，两边都断言；别拿
     「碰巧不相干」的 id 冒充「不命中」。既有的探测用例（`test/harness/edge/http_test.clj:3764` 那一段）
     今天断言 `(:models body)` 是一串 id，跟着改。

9. **不做的**：不加页签、不动 `PAGES`（`settings-panel.tsx:1012`）、不新开一页；不做「当场问这个端点
   收不收 `developer` 消息」的探测（那与列表探测是两件事，而且探到的也不是「这条模型声明的档」）；
   不在会话界面或轨迹里加任何东西（那条更新本来就在 `message` 行的 submitted 侧，那是 03 的读侧）；
   README 一个字不加。

## 验收

- [ ] 展开一个 provider：每个模型行里有那三档，当前值就是**文件里的样子**（未声明的停在未声明档）
- [ ] 选 `in-place` → 保存 → `config.edn` 里**那一行**多出 `:instruction-updates :in-place`；同一
      provider 的**其余模型行逐字节不变**（用例读文件，不是读页面）
- [ ] 选回未声明 → 保存 → 那个键**消失**（不是变成 `:replace`）
- [ ] 显式选 `replace` → 保存 → 文件里就是 `:replace`（与未声明在**文件**里不同，在**解析**里相同）
- [ ] 一次无关的编辑（只改 display-name）→ 每一行的 `:instruction-updates` 保持原样：该有的还在，
      没有的长不出来
- [ ] 服务端拒绝一个不认识的值时，表单显示的是**服务端那句拒话**（`lib/providers.ts:29` 的
      `reasonFrom`），不是这里自己编的一句
- [ ] Fetch 一个 vendor → 命中内置前缀的 id 在候选清单里**带着那个值**，没命中的只有 id；take 进来后
      命中那一行的 select 停在 `in-place`，没命中的停在未声明（有用例读探测答案与 draft 两半）
- [ ] 手打一个**命中规则**的 id（Add model）→ **不预填**（规则只在自动获取那条路上说话）
- [ ] 一张测试用的表里两条前缀互相包含（`gpt-4` 与 `gpt-4o`）→ **最长的那条赢**（匹配写成收一个表
      的纯函数，用例因此喂得进重叠的表，不用 `with-redefs` 去改一个 `def`）
- [ ] 运行时：一个模型条目**没写**这个键、id 命中规则 → 请求体里**没有** `developer` 消息（规则不
      参与解析；这条用例住在这儿，是为了让「别把它接到 `selection` 上」有一个会被跑到的断言）
- [ ] `test/harness/edge/http_test.clj` 那段探测用例按新答案形状改过：命中与不命中两侧都断言，桩
      仍然返回 id 向量（`*list-models*` 的契约没变）
- [ ] 两语言 `settings.json` 都有这几条键（`cd ui && npm test` 的 i18n 套件绿）；`npm run typecheck`
      与 `npm run build` 过
- [ ] `node scripts/dev.mjs --scripted` 走查：设置 → Models → 一个 provider → 那一栏**看得见**，选一下、
      保存、退出再进来值还在（这是「渲染看不到布局」那一格）
- [ ] 后端全量绿（`clojure -M:test -m harness.test-runner`）——这票动的是 catalog 的一件产品事实
      （那张前缀表与探测答案），送达那条路一个字节没动
## Comments

2026-09-25 — 后端那一半（`model-keys` / `resolved-fields` / 值闭集校验 / 缺省落解析 / `model-row` 照文件说）
与设置页那个三态控件（未声明 / `in-place` / `replace`，选未声明就删键）都已落地，中英文案一起加。
**决策 8 的内置前缀预填也已落**：`cap/providers.clj` 的 `instruction-updates-hints` +
`suggested-instruction-updates`（收表的纯函数、最长前缀赢）、`probe-models` 的答案带行
（`*list-models*` 那道缝合线不变，仍是 id 向量）、前端只照搬答案预填、手打的 id 不预填。
`http_test` 那段探测用例按新形状改了（`gpt-x` 命中 / `acme-7` 不命中，两侧都断言）。
**没做**：真浏览器走查（那一栏与候选清单里那个建议标记没在浏览器里点过）。详见 `../spec.md` 的「落地记录」。
