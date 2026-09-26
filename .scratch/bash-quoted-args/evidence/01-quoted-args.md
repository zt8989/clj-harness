# 走查 01：真浏览器里发一条含双引号的命令

怎么跑（本机 Windows / Git Bash，2026-09-26，main 上带着本次修法）：

```
cd ui && npm run build
node scripts/dev.mjs --scripted .scratch/bash-quoted-args/evidence/01-quoted-args-walkthrough.json
```

脚本（同目录的 `01-quoted-args-walkthrough.json`）让脚本 provider 回放一发 `bash`：

```
printf '[%s]' ONE "TWO THREE" FOUR; echo; echo END-MARKER
```

## 页面上（`01-quoted-args-browser.png`）

- bash 工具卡的**参数**：`{"command":"printf '[%s]' ONE \"TWO THREE\" FOUR; echo; echo END-MARKER"}`
  —— 与 caller 送的字节一致；
- 工具卡的**结果**：`[ONE][TWO THREE][FOUR] END-MARKER` —— 四个词各自成段，**且同一行后面的
  END-MARKER 也跑到了**。

修之前同一发只印 `[ONE][TWO]`，后面的一切（`; echo …`）都被 shell 当成了自己的 argv。

## 同一发的记录（临时 config root 的 `projects/.unbound/<thread>.jsonl`）

- 工具调用那一行：`printf '[%s]' ONE \\\"TWO THREE\\\" FOUR; echo; echo END-MARKER\"}`
- 结果那一行：`[ONE][TWO THREE][FOUR]\nEND-MARKER\n"`

命令是**原文**，不是拼回来的 —— 通知要靠它认出是哪个作业。
