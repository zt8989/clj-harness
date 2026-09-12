# 05: 移除老 provider（contract 收尾）

**What to build:** 新 provider 成为唯一链路，老链路彻底下线，用户侧无感知，仓库不再保留双协议分支。

**Blocked by:** 04 对等性与预算门禁.

**Status:** ready-for-agent

- [x] 默认与唯一路径均为新 provider，无切换开关残留
- [x] 老链路代码与配置项已删除，无死引用
- [x] 现有离线测试与真机验证在新链路上全绿（离线 46/170；真机脚本已改写容忍推理缺失）
- [x] 特性 spec 已更新为单 provider 结论

## Comments

2026-09-12 第一刀已落地（46/176 全绿）：deps 接入 langchain4clj 1.6.2，
新增 `:langchain4clj` 骨架（blocking chat 文本翻译层，离线 stub 测试覆盖），
老链路零改动。剩余 flag-day：工具翻译层、loop 接管、删老 provider + 老 fixture
断言、重写受影响测试与 ui/verify 脚本。注意"用户侧无感知"与 spec 冲突表相悖：
推理卡片退化与 DeepSeek 回传丢失是已知代价。
