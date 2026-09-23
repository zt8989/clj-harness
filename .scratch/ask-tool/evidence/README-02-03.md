# 票 02/03 的证据：候选、自己填、多选

`AGENTS.md` 要的那一层（渲染看不到布局，只有真浏览器说得清）。起法与 01 同一条命令，脚本换成
`.scratch/ask-tool/walkthrough-0203.json`：

```
node scripts/dev.mjs --scripted .scratch/ask-tool/walkthrough-0203.json --ui-port 5211
```

题面四问，一次调用里四种形状：

| 键 | 候选题 | 形状 |
| --- | --- | --- |
| `db` | 有候选 + `allow_other` | 下拉 + 一格「或自己填」 |
| `targets` | 有候选 + `multiple` | 三个勾选框，没有自己填 |
| `extras` | 有候选 + `multiple` + `allow_other` | 两个勾选框 + 一格「或自己填」 |
| `note` | 自由文本 | 一个输入框 |

项目与会话用 `POST /api/projects` + `POST /api/project` 造（不走会开原生对话框的那个按钮），
然后在新会话里发一句话，脚本按 thread 重放。

- `t02-01-card-options.png` —— run 停在卡上，四种形状同屏。数得到的：`elicitation-select` × 1、
  `elicitation-checkbox` × 5（3 + 2）、`elicitation-input` × 1、`elicitation-other` × 2。
  标题仍是「模型在向你提问」，题面四问用 ` / ` 连成一行；输入框是灰的，侧栏那行写着「等你回应」。
- `t02-02-card-filled.png` —— 填完，且是按"故意拧着来"的顺序填的：`db` 先在下拉里选了 `sqlite`，
  再往「或自己填」那格打字，于是下拉自己清回 `—`、那格写着 `mysql 8`；`targets` **先点 docs 再点
  api**；`extras` 一个都没勾；`note` 空着。
- `t02-03-answers-in-detail.png` —— 发送后 run 接着跑完，轨迹视图里 `ask` 那一行的详情：参数是原样的
  JSON，结果是人话四行（等待 2 分 32 秒，执行不到 1 秒——那个"等待"就是人在填表）。

## 它证明了什么

从 `ask` 那条工具结果里读出来的原文（同一份原文也在本次 run 的 jsonl 里，`role: "tool"` 那条
`message`）：

```
- 用哪个数据库？ -> mysql 8
- 这几个方案里你要哪些？ -> api, docs
- 还要带上哪几样？ -> (nothing chosen)
- 还有什么要交代的？ -> (no answer)
```

1. **候选是点得动的，不是要人照抄的文本框。** 四问里三问有候选，卡上就是下拉与勾选框；`note` 没有
   候选，就是一个输入框。三态同屏，比对着看比单看一张卡可信。
2. **「自己填」是明写的开关，不是长在每个候选列表下面的默认物。** `db` 与 `extras` 各有一格，
   `targets` 没有——而 `targets` 与 `extras` 的候选形状完全一样，差的就是题面里有没有
   `allow_other`。这也是"服务器自己的 enum 逐字不变"在真浏览器里的那一面：那个键是 `ask` 的题面
   才会写的，卡按它画。
3. **点选与手填是同一个答案的两个来源，不是两个答案。** 下拉里先选了 `sqlite`，再往那格打字，下拉
   自己清回 `—`（截图 02 可见），最后模型拿到的是 `mysql 8` 一个值。
4. **顺序按候选走，不按手速。** 先点的是 docs，后点的 api，答案写出来是 `api, docs`——候选表里
   `api` 在 `docs` 前面。两次一样的勾选给一样的答案，答案不取决于鼠标先后。
5. **「一个都没勾」与「这一问题没答」是两句不同的话。** `extras` 一个没勾 ⇒ `(nothing chosen)`；
   `note` 一个字没填 ⇒ `(no answer)`。两个都留在结果里，模型不用从缺席去猜。
6. **一次调用一列问题、一张卡、一次停。** 四问共用一条 interrupt、一张卡、一次 `resume`；答案按问题
   逐行对上来，不用模型自己去配。

## 它没有证明什么（如实写在这里）

- **结果全文在浏览器里要展开才看得见。** 轨迹视图每一行只画 `preview()`——"取第一行有字的，裁到
  一眼"（本票没碰它），所以那一行上是 `- 用哪个数据库？ -> mysql 8`，全文在点开之后的详情面板里，
  也就是 `t02-03`。对话视图那边，一轮的调用与消息收在一个可展开的分组里
  （`1 次工具调用 · 2 条消息`），本次走查里它是收着的（那格带 `hidden`），所以这次是从轨迹详情里读的；
  `01` 的走查（`t01-03-answers-as-tool-result.png`）里那个分组是展开的，同一份文本直接看得见。
  两处说的是同一件事，差的只是展开状态——两个视图本票都原样。
- **`done` 之后的"再 replay 一次"没在浏览器里走**：那是后端套件里 `an-answer-cannot-be-spent-twice`
  与 `answered-line` 守的事。
- **控制台三条噪声与 01 同一批**（`GET /api/threads/<id>/stats` 404 × 2 与 `favicon.ico` 404），
  都与本票无关。
- **这一层不是逻辑证据。** `array` 的判定、按候选排序、`[]` 与缺失各是什么、扩展键的有无，纯规则在
  `ui/test/suites/elicitation.ts`；四种形状画成哪个控件，在 `ui/test/suites/elicitation-card.tsx`
  （把字段渲成字符串再数 `data-slot`）。这里补的是"画出来了、点得动、填得进、答案看得见"。

## 家目录

走查走的是 `--scripted` 的那对**临时家**（本次是
`C:\Users\zhouteng\AppData\Local\Temp\clj-harness-dev-4RjMHP\{home,user-home}`，退出即删），
`~/.clj-harness` 一个字节都没动。上面那份工具结果的原文从
`.../home/projects/C__Users_..._ask-0203-demo-xnhBdo/3e4f3575-....jsonl` 里读出来，路径在启动横幅里报过。
