走查记录（2026-09-18，`node scripts/dev.mjs --scripted composer-walkthrough.json --ui-port 5212`）。
下面是从那一轮的 backend.log 里摘出来的几行 —— 注意**没有任何 `--ui-origin`**：后端不知道前端在哪个端口，
放行是按请求里的 `Origin` 自己判的。

```
logging to C:\Users\zhouteng\AppData\Local\Temp\clj-harness-dev-506Y5L\home/logs/harness.infra.log
harness listening on http://localhost:6568 -- POST an AG-UI RunAgentInput to /api/agent
22:06:36.183 INFO  harness.infra.log - listening port=6568 root=...\clj-harness-dev-506Y5L\home
PRINT-READY {:port 6568}
22:13:23.947 INFO  harness.infra.log - start model=scripted run-id=L3KELOm thread-id=646306d6-...-f17c
22:13:24.949 INFO  harness.infra.log - stream-closed run-id=L3KELOm status=:server-close terminal=RUN_FINISHED
22:13:24.950 INFO  harness.infra.log - terminal event=RUN_FINISHED run-id=L3KELOm thread-id=646306d6-...-f17c
```

同一轮在浏览器里采样 `.aui-composer-send` / `.aui-composer-cancel` 两个按钮（见
`.scratch/composer-never-stops/evidence/composer-back-to-send.png` 那张图）：

```
0ms     cancel=true   send=false           （发送后，运行中）
1000ms  cancel=true   send=false  toolCard=true（工具卡片到了，还在跑）
1600ms  cancel=false  send=true            （本轮结束，composer 回到「发送」）
```

页脚随后显示「1 轮 · 2 次模型调用」，计数条是「1 次工具调用 · 2 条消息」——即一个**完整带工具调用**的
回合，跑完后 composer 自己回来了。
