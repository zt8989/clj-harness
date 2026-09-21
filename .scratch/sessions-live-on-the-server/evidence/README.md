# 走查证据：记录写不进去，页面上说出来（票 02）

跑法（真后端 + 真前端 + 隔离家，端口由脚本自己挑）：

```bash
node scripts/dev.mjs --scripted .scratch/sessions-live-on-the-server/evidence/go.json --ui-port 5221
# 它会把临时的配置根打出来，复制那一行里的 <HOME>：
node .scratch/sessions-live-on-the-server/evidence/walkthrough.mjs http://localhost:5221/ <HOME>
```

`go.json` 两轮，一句话一轮：第一轮让这个会话的 jsonl 长起来，第二轮之前把**那个文件**改成只读
（`chmod 444`），于是第二轮的逐帧 append 全部失败。脚本自己找文件（按 thread id 在 `projects/` 下
递归找 `<id>.jsonl`），改权限，发第二轮，然后**不刷新**等那条红条出现。它顺带把那张图拍下来：
`02-record-notice.png`。

## 走查当天看见的（2026-09-21）

- **第一轮**：正常落盘（14 行），页面上**没有**任何提示——「正在存」不需要说。
- **第二轮之前把文件改成只读**：第二轮照常跑完、回答照常画出来（内存是权威，run 不受影响），
  跑完之后页面上**多出一条常驻的条**，就在视图切换下面、对话上面：

  > This conversation could not be saved to disk: …/projects/.unbound/<id>.jsonl (Permission denied).
  > 16 lines are waiting to be written.

  （浏览器语言是英文这次就出英文；前一次同一场景是中文的
  「这段对话没能存到磁盘上：…。还有 11 行在等着写。」，两种语言的句子都在
  `ui/test/suites/record.tsx` 里有渲染用例。）
- **`GET /api/threads/<id>/sofar` 同时说**：`"record":{"state":"degraded","reason":"…（Permission
  denied）","pending":16,"at":…}`——页面上的字就是服务端那一句，没有转述。
- **记录一个字节没长也没短**：14 → 14 行。写不进去的那一行停在队首，后面的还排着——这就是
  「降级 ≠ 丢行，记录永远是有序前缀」。

## 这次走查抓到了什么（所以它被留下）

第一版只在**挂载那次读**和**盯着别人跑的轮询**里上报记录状态。而**自己驱动这一轮**的页面两者都不走：
它没有一个「正在跑」的读要去轮询（轮询的条件是读回来的 state 是 `running`），所以跑中写失败
**谁都收不到**，页面安静地继续，记录安静地落后——正是 ADR 0002 决策 6 不许的那件事。

修法是在**这一轮跑完之后**再读一次记录（`app.tsx` 里那条 effect，用挂载同一条读、同一个 fallback），
于是这条红条在这一轮结束的那一刻出现在屏幕上——**不用刷新**。上面那条 GREEN 就是这么来的：
两次 turn 之间没有 reload。

## 这个脚本看不见的

布局好不好看（那条红条会不会把对话挤走）、以及**很久的一轮跑在中途**盘就坏了的时候，条要等到
那一轮结束才出现（现在是这一票的取舍，写在票 02 的落地记录里）。前者是人的眼睛，后者是票据上的
已知边界。
