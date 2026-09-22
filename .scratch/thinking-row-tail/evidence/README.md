# 走查记录（2026-09-22）

判据、决策与代价写在 `../spec.md`。这里是**这次运行**的现场：命令、环境、原样输出、四张图。

## 怎么起

```bash
# 一个隔离家、脚本厂商、OS 挑后端端口、前端 5393（跑完收摊）
node scripts/dev.mjs --scripted .scratch/thinking-row-tail/script.json --ui-port 5393
# 另开一个终端
node .scratch/thinking-row-tail/walkthrough.mjs http://localhost:5393/
```

* 环境：macOS 15.7.3 / aarch64，Chromium（全局 `playwright@1.62.1`，`npm root -g` 解析），
  窗口 1200×900。**限速**：页面加载之后、发消息之前，用 CDP 把这条 page 的带宽压到 `6 KB/s`
  （`Network.emulateNetworkConditions`）——`harness.fake` 一次吐完 5 字符一块、中间不歇，
  不压速的话整段流在采样之前就结束了。1,058 字 ≈ 25 KB 的 SSE，压到 6 KB/s 就是四秒左右、约 250 字/秒，
  也就是一个快厂商的样子（`TAIL_SPEED` 的封顶是 ~300 字/秒，所以这个速率下拖动能跟上）。
  压速用的是**同一段字节**，夹具一行没改；跑完立刻恢复（后面那次刷新要重新下载 dev bundle）。
* 期望值**从 `script.json` 现算**：首行 = 那段思考第一个非空行，流式那一半 = 同一段字压成一行。
  改夹具不会让判据变得空洞。

## 原样输出（GREEN，23 条）

```text
ok   the row shows a live window while the model is thinking
ok   the row is PAINTED while the thought arrives (ink, not just geometry) -- 2208 ink pixels in the row's own 656x28 box
ok   the panel never opens itself while the tokens arrive -- state "closed", content "closed"/0px
ok   the row is still ONE line while it runs -- 28px tall
ok   the line is the arrived text, whole -- not a window cut out of it -- line: 45 characters of 1058, ends "一段只是把窗口往前推一点。第 2"
ok   the line keeps growing while it runs -- 92 live samples, 18 distinct lines
ok   the words do NOT change between every pair of samples -- so the position must -- at least one hold
ok   while the words hold still the line is still TRAVELLING (interpolated, not a jump) -- 65 of 74 holds moved
ok   the line slides left, so characters leave at the left edge -- track left: 507 -> -10487 (92 samples)
ok   the window cuts at its LEFT edge: the beginning of the thought is behind it -- line starts -206px left of the window
ok   the right edge always has text under it -- the newest characters arrive there -- lag: max 1275px, last 0px, 92 samples
ok   the drag catches up: the newest characters come back into view -- lag: max 1275px, median 251px, last 0px, 26 of 92 samples caught up
ok   every sample is the thought so far, and shorter than the thought -- line 45 -> 1004 of 1058 characters
ok   the thought ends, and the row stops being live
ok   the panel is still folded when the thought has stopped -- state "closed"
ok   the row is still there when the thought has stopped
ok   it says the FIRST line again -- subject: " · 先读 deps.edn，确认依赖有没有变。"
ok   and it is still folded
ok   and it is painted then too (the control for the number above) -- 1097 ink pixels settled vs 2208 while running
ok   a click opens the thought -- content "open"/111px
ok   and the opened panel holds the whole thought -- 1058 chars
ok   a second click closes it again
ok   the conversation comes back after a reload
ok   a restored thought arrives folded, saying its first line -- subject: " · 先读 deps.edn，确认依赖有没有变。", live: false, content: "closed"

GREEN -- screenshots in .../.scratch/thinking-row-tail/evidence
```

## 四张图

| 文件 | 拍的是什么 |
| --- | --- |
| `01-while-thinking.png` | 流式期间：折着的一行，行上是那一段字（右边缘是最新到达的字） |
| `02-after-thinking.png` | 停下来：同一行，回到首行 |
| `03-opened-by-hand.png` | 点开：整段 |
| `04-restored.png` | 刷新之后：恢复出来的会话仍是折着的一行首行 |

## 这次走查量到的、并在下一轮用上的三件事

留在这里，因为下一个人会踩同一脚：

1. **「抽屉关着」不能用元素个数判**。Radix 关着时保留一个空的、0 高度的内容元素，
   `document.querySelectorAll('[data-slot="reasoning-content"]').length` 开着关着都是 1。
   判据换成 `data-state === "closed"` + 高度 0——顺带量到一件真事：关着时**子节点根本没渲染**，
   所以折着的那段思考对页面是零成本。
2. **「位置在动」不等于「在滑」**。第一版的判据全是几何的（左边出去了、窗在往前挪），四条 `GREEN`，
   而屏幕上是一跳一跳的——因为**布局位移没有中间态**。这一版加进来的判据是「相邻两个采样**字完全相同**
   而**位置变了**」：99 个采样里 81 对字是相同的，其中 67 对位置仍在动。
3. **字是成批到的，不是一 token 一帧**。AG-UI 客户端与 React 把一串 SSE 帧并成一次渲染：四秒里行文只换了
   **18 次**（每批 ~60 字 ≈ 780px）。所以「每个 token 挪一点点」是错觉，真实的一步是 780px —— 这正是
   `TAIL_SETTLE_MAX` 与「按速度算时长」存在的原因，也是第一版看着像跳的原因。
## 像素那一层：行是**画出来的**吗（2026-09-22 追加）

上面每条判据都在谈**几何**与**DOM 文本**——而主人看到的是一行**空白**：*「现在思考。空白，然后结束，
瞬间出现思考+一行字」*。那一条所有判据全绿。

**抓到它的顺序**（三个诊断脚本都在这个目录里，都不是门）：

1. `scratch-ink.mjs`——把行自己的盒子截下来、数比页面底色深的像素：流式期间与停下来**数一样**
   （1097 / 18368），这就是「像素与文本无关」的签名。
2. `scratch-ascii.mjs`——把那一行按亮度画成 ASCII（2×2 一格）。流式那张**几乎是空的**，只有零星几个浅点；
   停下来那张是一整行字。**这就是「空白」两个字的样子。**
3. `scratch-which.mjs`——在一行**静止**的字上分别加一样东西：加 `shimmer` → 墨从 1097 掉到 583；
   加一个 `transform` → 不至于抹掉。**结论：`shimmer`。**

**根因**：`shimmer`（tw-shimmer）画字的办法是**把文字当蒙版**（`-webkit-mask-clip: text`），而蒙版照**布局**取；
这一行字是用 `transform` 挪的——蒙版停在原地，被挪走的字就被蒙掉了。停下来之后那一行换回普通文本
（没有 transform，也没有尾窗），蒙版重新对齐，于是「瞬间出现思考+一行字」。

**修法**：`shimmer` 从 label（它装着名字**和**那一行字）挪到**名字**上。同一行实测：戴在 label 上 583 墨、
戴在名字上 1032 墨（静止时 1097）。

**两张 ASCII（同一台机器、同一段脚本流，第一张是修之前）**——每格 2×2 像素，`.` 越浅、`#` 越深；
左边的 `++++` 是那颗大脑图标，其余全是那一行的字：

```text
修之前（流式期间）：几乎空白——只有零星几个浅点，看不出任何字
  ++++
 ++++++          .   =     .  :-                -: -:        .=--=: -====  =--=: :.=++::-==--
:+====+:    ++++.+   +    .+  :=                 --==-       --:::: -.  :  -::=:.=-:::::--.-:
=+ ++ +-     :+ .+=+ +-+=+.++=-++++:++++        -=:--. ......--:-.=.:.  :  ---=: ::---::=====
+.-++:.+     :+ .+ + +-+.+.++ -++:=++.-+  +:     : -=..-------===== -++++ -=====:==-::.::---:
++.==.++     :+ .+ + +-+ +.++=-++.-+++=+      : :-=-:-       +=:--: :- --  -.-:. :.:  :::-=::
 +.++:+      :+ .+ + +-+ +.+.+-++.-+.:=+      =.++====.      -.:==-.-.  -:::-===.-.===::=====
 :++++:                             -++

修之后（同一时刻）：整行字
  ++++
 ++++++    +=-=- :====  =--=- ::=++-:--=--.       - .-   :.::  - :-          .-..       .=--+=:
:+====+:    ::-.- .:  -  =::-- ==::.-:--.--.=====:--:-=:.======:=:==-.        .---       -:--:-
=+ ++ +-     -=:--. ......--:-.=.:.  :  ---=: ::---::===== :   :. -.::  ===:-. -==== ......
+.-++:.+     : -:=..-------===== -++++ -=====:==-::.::---: :   :.-=:-=: ===:-.==:::..------
++.==.++      :-=-:-       +=:--: :- --  -.-:. :.:  :::-=:: :   :. : ::  -:-:-. -:===  :::..
 +.++:+      =.++====.      -.:==-.-.  -:::-===.-.===::===== =---=. :=++=.---:=.=-:=--.
```

（`scratch-ascii.mjs` 每次运行会把两张图写成同目录的 PNG；PNG 不提交，ASCII 才是能读的那份。）

**留在门里的是 `inkOf`**：走查现在会截下那一行的盒子、数墨，要求流式期间 ≥ 300 像素（实测 2208），
停下来也 ≥ 300（实测 1097）。几何全对而像素全空这一类错，从今天起有判据。
