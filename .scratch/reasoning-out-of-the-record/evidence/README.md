# evidence: 推理帧到底占了多少（2026-09-25）

**两场，一新一旧**，因为这份记录里已经有一件事被主人改掉了（`model/start` 不再抄工具表，见下）：

```
新（照今天的代码写的，主人今天还在用）
~/.clj-harness/projects/C__Users_zhouteng_Documents_workspace_lisp-harness/ed334c9c-9b6c-4a8e-bd0e-1f695fbd37fe.jsonl
41,860,000 字节左右（39.93 MB）   594 次模型调用

旧（2026-09-24 之前写的，`model/start` 还抄表）
~/.clj-harness/projects/C__Users_zhouteng_Documents_workspace_lisp-harness/fa35f356-1315-4a9d-8154-dfb1c7ee8dee.jsonl
68,188,479 字节（约 65 MB）   237,012 行   9 条 user 行   497 次工具调用
```

## 新的那一场按内容拆（`split_jsonl.py`）

```
reasoning     32.61 MB   81.7%    170,415 行       ← 推理帧
message        2.84 MB    7.1%      1,159 行
toolcall       2.38 MB    6.0%      2,753 行
other          1.49 MB    3.7%      4,511 行       ← 含已经变小的 model/start
text           0.60 MB    1.5%      2,903 行
total         39.93 MB
```

**今天日志的大头是推理帧（82%），不是工具表**——工具表那件事 2026-09-24 已经改掉了：`model/start` 只带
signature（`:tools-names-hash` / `:tools-bytes` / `:tools-count`，`harness.kernel.event/model-start` 的
docstring 记着那次事故：`bbcd4ae4-…` 里 129.7 MB 的日志有 50.2 MB 是同一张表），整张表挪到 system 那一行
`message` 的 envelope 上，一次 run 一份，`trajectory` 从那里读（`system-item`）。实测（`recent_jsonl.py`）：
新那一场 594 行 `model/start` 共 **0.22 MB，0 行带表**。
## 推理那一族（`reason_jsonl.py`；下面是**旧**那一场的数）

```
REASONING_MESSAGE_CONTENT    222,996 行   42.66 MB
REASONING_START                  328 行    0.05 MB
REASONING_MESSAGE_START          328 行    0.06 MB
REASONING_MESSAGE_END            328 行    0.06 MB
REASONING_END                    328 行    0.05 MB
                            ----------------------
                             224,308 行   42.89 MB

其中 CONTENT 那 222,996 行里：
   delta 的文字合计      830,351 字符 ≈ 0.79 MiB   （占这些行的 1.9%）
   帧的壳               ≈ 41.85 MB                 （98.1%）
   每行开销             平均 196.8 字节
   delta 长度           中位数 3 字符，p90 7，最长 64，平均 3.7
推理消息数（不同 messageId）  328
```

一帧的全部内容（197 字节）：

```json
{"ts":1790161130998,"runId":"65837d52-3f43-4d38-a3e0-f8b1bd5cf789","type":"event","payload":{"type":"REASONING_MESSAGE_CONTENT","messageId":"65837d52-3f43-4d38-a3e0-f8b1bd5cf789-r0","delta":"Let"}}
```

## 同一段文字的第二份（`dup_jsonl.py`）

```
assistant message 行                     321
   其中带 reasoning_content 的           276
reasoning_content 字符（message 行）      705,703  ≈ 0.69 MiB
推理 delta 字符（帧）                     830,351
```

`harness.edge.trajectory` 读的是**前者**（`trajectory.clj:524`、`:815`）。所以记录里那段思考是两份，
而帧那一份被逐 token 撑大了 52 倍。

## 速率（`rate_jsonl.py`、`burst_jsonl.py`）

```
全程              222,996 帧 / 11,423.9 秒 = 19.5 帧/秒   ← 这个数是被空闲稀释过的
有推理的那些秒     2,177 秒
  每秒帧数         p50 102   p90 170   p99 186   峰值 290
```

所以**浏览器每秒要收 100–290 条、每条 3 个字符的 WebSocket 帧**——这是帧真正压着的另一头。
「线上照发」这条决定因此也意味着这一头**保持原样**（本特征只动记录）。

## 去掉之后（估）

```
新（ed334c9c）  39.93 MB  −  32.59 MB（推理五族）  ≈   7.34 MB
旧（fa35f356）  65.03 MB  −  42.89 MB（推理五族）  ≈  22.14 MB
                （那 22.14 MB 里还有 16.61 MB 是**旧** model/start 抄的工具表）
两场里那段思考文字（0.6–0.69 MB）都仍在模型那一行上
```

## 怎么重跑

```bash
python evidence/reason_jsonl.py <那份 jsonl>   # 推理五族：行数、字节、delta 文字量与长度分布
python evidence/dup_jsonl.py    <那份 jsonl>   # message 行的 reasoning_content 与帧里的文字对比
python evidence/rate_jsonl.py   <那份 jsonl>   # 全程帧率 + 一帧的原文
python evidence/burst_jsonl.py  <那份 jsonl>   # 只看有推理的那些秒：p50/p90/p99/峰值
python evidence/split_jsonl.py    <那份 jsonl>   # 按内容类别（推理/消息/工具调用/文本/其余）的字节占比
python evidence/recent_jsonl.py   <会话目录>     # 最近几场：model/start 还带不带表、带不带 signature
```
