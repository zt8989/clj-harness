# 03: langchain4clj Tracer（双协议并存）

**What to build:** 用户可一键切换到 langchain4clj provider 并跑完一轮含工具调用的真实对话，默认仍走自建链路，老用户无感知。

**Blocked by:** 02 provider 缝隔离.

**Status:** ready-for-agent

- [ ] 切换配置即可选用新 provider，无需改代码或重启之外的手工步骤
- [ ] 真机一轮含文件读取类工具调用成功，二轮同会话续写不中断
- [ ] 推理折叠在客户端正常显示，工具调用挂载关系正确
- [ ] 默认配置下老链路行为 unchanged，老测试全绿

## Comments

2026-09-12 Tracer 探针结论：双协议并存做不出来（jar 实证，未改 src）。

- streaming 句柄只实现 onPartialResponse/onCompleteResponse/onError，无 reasoning 通道、无 tool 请求流；且签名是单 string message，不是历史向量 + tools，loop 的多轮工具循环表达不了。
- :base-url 虽透到 OpenAI builder（OpenRouter 地址技术上可填），但 DeepSeek/OpenRouter 的 reasoning_content 回传语义 LangChain4j OpenAI 模块不识别，带 tools 多轮必丢字段。
- 单依赖拉入约 150 个 jar（含 tika/poi/pdfbox/grpc/vertexai），极简体积命题即死。真机验证未烧额度：契约层面已红。
