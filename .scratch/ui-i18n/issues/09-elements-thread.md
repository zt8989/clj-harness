# 09 — 抄来的元素：对话外壳

**What to build:** 从用户视角：切成中文后，对话本身那一圈说中文——空对话页的欢迎语、输入框的占位
文字、滚动到底、导出为 Markdown、「助手正在工作」、「正在载入对话」、停止听写，以及代码块上那个
`Copy` 与关闭按钮的读屏文字。

**这推翻 `flat-step-rows` 决策 9 的「抄来的 12 份一个字节不动」**（spec 决策 5）：一半英文一半中文的
界面正是本特征要消掉的东西，而这一份里的句子（`How can I help you today?`）恰恰是最显眼的那一句。

**Blocked by:** 01 — 机制。本票不依赖 02。

**Status:** ready-for-agent

## 验收

- [ ] `thread.aui`（约 22 条）、`markdown-text`（`Copy`）、`dialog`（`Close`）的文案进目录
      （`elements` namespace）。`thread-list.aui` 归 03，不在这里。
- [ ] **每一处改动逐处标 `LOCAL:`**——`thread.aui` 已有 11 处先例，照那个写法；`markdown-text` 与
      `dialog` 今天没有任何标记，所以是新增的标记。
- [ ] **代价记进 `docs/architecture/client.md`**：这几份与上游不再能直接 diff 对账，`LOCAL:` 标记是
      替代品——它说明「这里是有意改的」，不说明「上游改了什么」。
- [ ] **「与上游逐字节相同」这句话在五个地方都写着，其中几句从本票起不成立**，逐处改掉
      （`rg -n "byte-comparable|逐字节|与上游"` 就是那份清单）：`docs/architecture/client.md`
      那句是**当前状态**，必须改；`composer-chrome` / `composer-stats` / `styles.css` /
      `message-parts` 里那四句是**说「所以我不改它、我另开一处」的理由**——理由本身照旧成立，但
      「因此它与上游一致」这半句不成立了，改成「因此这块改动不落在抄来的文件里」这种**不依赖**
      逐字节事实的说法。**不改的那一半要留住**：另开一处而不是就地改，这条取舍没有被本特征推翻。
- [ ] 输入框的占位文字**不翻模型的词汇**，就是界面自己的话。
- [ ] 真机：中文下的空对话页 + 一次正在跑的对话（欢迎语、占位、滚动到底、正在工作三处可见）、
      一次代码块的复制。中英各一张截图进 `evidence/`。
- [ ] `cd ui && npm run typecheck && cd ui && npm test` 全绿。
