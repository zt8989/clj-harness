# evidence: 一场真会话的字节去哪了（2026-09-25）

主人报「加载会话很慢」时，机器上最大的那一场：

```
~/.clj-harness/projects/C__Users_zhouteng_Documents_workspace_lisp-harness/fa35f356-1315-4a9d-8154-dfb1c7ee8dee.jsonl
68,188,479 字节（约 65 MB）   237,012 行    9 条 user 行    497 次工具调用
```

同一台机器上还有 52 MB / 49 MB / 37 MB 的几场，形状一样。

## 按种类拆（`split_jsonl.py`、`other_jsonl.py`）

| 记录里的东西 | 字节 | 占比 | 行 |
|---|---|---|---|
| `event:REASONING_MESSAGE_CONTENT` | 42.66 MB | 65.6% | 222,996 |
| `event:model/start`（**这一行是旧日志的形状，见下**） | 16.61 MB | 25.5% | 392 |
| `message:*`（user/assistant/tool/system） | 1.94 MB | 3.0% | 745 |
| `event:TEXT_MESSAGE_CONTENT` | 1.28 MB | 2.0% | 6,836 |
| `event:TOOL_CALL_RESULT` | 1.06 MB | 1.6% | 497 |
| `event:TOOL_CALL_ARGS` | 0.25 MB | 0.4% | 497 |
| 其余（`model/end`、`tools/*`、`context/pruned`、START/END 帧…） | 约 1.2 MB | 约 1.9% | 约 43,000 |

`message` 行按角色：user 0.02 MB（9 行）、assistant 0.89 MB（321 行）、tool 0.81 MB（408 行）、
system 0.03 MB（7 行）。

**`model/start` 那一栏只对旧日志成立。** 这份日志写在 2026-09-24 之前：那时每次模型调用都把整张工具表
（45,410 字节）再记一遍，392 次就是 16.61 MB。**改动已经落地**（`harness.kernel.event/model-start` 的
docstring 把那次事故记下来了：`bbcd4ae4-…` 的日志 129.7 MB 里 50.2 MB 是同一张表），今天它只带 signature
（`:tools-names-hash` / `:tools-bytes` / `:tools-count`），整张表改成写在 **system 那一行 `message` 的
envelope 的 `:tools`** 上，一次 run 一份，`harness.edge.trajectory` 从那里读。

实测最近写下来的一场（`ed334c9c-…`，39.93 MB）：`model/start` **594 行共 0.22 MB，0 行带表**；
同一场里推理帧 **32.61 MB = 81.7%**。所以今天日志的大头是推理帧，不是工具表。

（`modelstart_jsonl.py` 的取法：同一行里 `tools` 键的字节数。）

## 读一遍要多久（`time_jsonl.py`，Python 是下限；Clojure 的 `data.json` 更慢）

```
整份 237,012 行解析   0.97 s
最后一轮 12,437 行    0.04 s   （3.24 MB，从最后一条 user 行到文件尾）
最后一轮里非推理的行  1,277 行
```

## 结论

- 一场**9 轮**的会话，屏幕上要看的东西（user 文本 + assistant 答复 + 工具调用）**约 5 MB / 8%**；
  其余 92% 是折起来就不该传的（推理 delta 66%、只服务轨迹的工具表 25%）。
- **最后一轮是 3.24 MB**——这就是「最后一次 turn 全量、不折叠」的代价上限，可以接受。
- 三扇门（`rebuild` / `sofar` / `page`）加上每来一条消息重问一次的 `stats`，**各自从头解析整份**。

## 怎么重跑

```bash
python evidence/scan_jsonl.py   <那份 jsonl>   # 行/种类/角色计数
python evidence/split_jsonl.py  <那份 jsonl>   # 按内容类别的字节占比
python evidence/other_jsonl.py  <那份 jsonl>   # 按 type:name 的字节占比
python evidence/time_jsonl.py   <那份 jsonl>   # 整份 vs 最后一轮的解析耗时
python evidence/modelstart_jsonl.py <那份 jsonl>  # model/start 里各键的字节数
```
