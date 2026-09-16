# 票 03 的证据：状态条在真浏览器里

对象：`.worktrees/composer-status` 里的 `ui/`，跑在**真的 vite 开发服务器**（:5173）上，后面接的是
**真的 e2e 后端**（`harness.e2e-server`，:8080，脚本厂商），家目录与 OS 家都是临时目录。

起法（三件事，家目录一律临时）：

```
EV=$(mktemp -d)                       # 见本目录的 setup 记在下面
printf '%s\n' '{:default {:protocol :fake :base-url "http://offline.invalid/v1" :model "seeded"}}' > $EV/home/config.edn
CLJ_HARNESS_HOME=$EV/home clojure -M:dev -m harness.e2e-server \
    --script-file $EV/script.json --port 8080 --user-home $EV/userhome   # 8080 是 ui/src/lib/threads.ts 里写死的那个
cd ui && npm run dev                                                     # :5173（CORS 契约）
```

`script.json` 的两轮：第一轮带一次工具调用（一轮工具 = **两次模型调用**）与用量
`prompt 1000 / completion 40 / cached 900`，第二轮是收尾与用量 `1200 / 8 / 1100`。

## 证据一：截图

`strip-in-the-browser.png`。输入框下面那条，读作：

    ⏱  2 turns · 2 steps · 1371 tok/s            🗄  2k tok · 91% cached

从左到右就是参考图那五格：轮 / 步 / 输出速度 ／ 总用量 / 缓存命中。
91% 与 `2k` 与那一轮脚本报的数对得上（2000/2200，1040+1208）。

## 证据二：DOM

`strip.dom.html`，是那一刻 `[data-slot="composer-stats"]` 的 `outerHTML`（`<svg>` 折成 `<svg…/>`）：

```html
<div data-slot="composer-stats" class="text-muted-foreground flex items-center justify-between gap-4 px-1.5 pt-0.5 pb-1 text-xs tabular-nums">
  <span class="flex items-center gap-1.5"><svg…/><span data-slot="stats-turns">2 turns</span>
    <span aria-hidden="true">·</span><span data-slot="stats-steps">2 steps</span>
    <span aria-hidden="true">·</span><span data-slot="stats-rate">1371 tok/s</span></span>
  <span class="flex items-center gap-1.5"><svg…/><span data-slot="stats-usage">2k tok</span>
    <span aria-hidden="true">·</span><span data-slot="stats-cached">91% cached</span></span>
</div>
```

## 顺手撞上的那条缺数：只画得出来的那一格

同一次会话的**第一次**发送跑失败了（那份临时家目录里还没有 `config.edn`，请求根本没到模型那儿）。
那一刻条子画的是 `1 turn`——**只有轮数，没有步、没有速率、没有用量**：记录里只有 `input` 行，
一个 `model/start` 都没有。这不是设计出来的演示，是撞上的；但它正好是 spec 决策 6
（「缺就是缺，不填 0」）在真页面上的样子，所以如实留在这里。
上面那张截图的会话里，那条红字错误也还在——**条子照旧只报记录里有的东西**。

## 它没有证明什么

- **厂商还是脚本替身**（同票 01、02 那条）：真机（活厂商）没跑，理由写在票 01 的证据 README 里。
- **没在多会话之间来回切**：刷新时机按 spec 决策 10 实现（会话切换、助手消息多一条、run 结束各取一次），
  套件里有一条真 HTTP 的用例覆盖取数，但「切会话时旧答案不会落到新会话上」只有代码里那个 `live` 标志守着。
