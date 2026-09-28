# 真记录走查（2026-09-28）

## 记录在哪（先把地方找对）

- `~/.clj-harness/logs/*.jsonl` —— **旧格式**（`kind`，无 `source`/`producer`），unbound 会话。
- `~/.clj-harness/projects/<project>/<uuid>.jsonl` —— **新格式**（`type` + `payload` 信封 + `source`）。

## 行形状普查（12 条真记录、约 1.4 万行）

```
2648 tool / tool      2109 assistant / model      432 reasoning / -
4464 tool / -         3901 assistant / -          111 user / -
  49 user / client      24 user / opening    15 user / job   1 user / skill
  65 system / system-prompt
```

⇒ **真正产出答复的行是 `role` = `tool` / `assistant` / `reasoning`**（新格式再带 `source tool`/`model`）。
票 05 的 `run-produced?` 就是照这张表定的。

## 出生序（真记录 `2c380f7f-…jsonl`，2.7MB）

```
5: "type":"message" "source":"system-prompt"  role=system
6: "type":"message" "source":"client"         role=user
7: "type":"message" "source":"opening"        role=user
8: "type":"message" "source":"opening"        role=user
```

⇒ **客户那条在开篇块之前**（`run-agent!` 的注释同此）。出生记录与 http 用例自己的记录**都**是这个序。

## 一条 resume 的真记录（`http_test` 那条用例自己的，43 行）

```
 7 message src=system-prompt role=system      17 message src=model role=assistant
 8 message src=client        role=user        29 event   role="tool"          ← 答复只有帧
 9 message src=opening       role=user        32 message src=system-prompt   ← resume 段
10 message src=opening       role=user        33 message src=client
                                              38 message src=model
SEGMENTS [["172308fc…" 4 1] ["2d36ed70…" 2 1]]
```

⇒ **整份记录没有任何 `message role="tool"` 行、也没有任何 `tool_call_id`**——这就是票 05 的根因
（被 resume 重放的答复从未落成行），已修：`answer!` 经 `write!` 写那一行。

## 真 run 的机检（这一刀）

- main 上：`trajectory-test` + `loop-test` + `http-test` = **172 / 1488 / 0**
- `replay` / `sessions` / `fork` / `fork-http` / `stats` / `context` = **104 / 506 / 0**

（http-test 起真服务器、跑真 run；「轨迹跑着时看得见调用与结果」的机检部分就是这些数字。
窗口与断线补齐的**人肉**走查未做——UI 这一刀一个字节没动。）
