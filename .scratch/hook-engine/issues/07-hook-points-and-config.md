# 07 — hook 点表与 hooks.edn 的两级装配

**What to build:** 用户能在配置家（或绑定项目的 `.harness/`）写一份 `hooks.edn`，声明某个 hook 点下要跑的
命令；装配是两级的——项目级优先，顶层浅合并、项目级整键替换，每次现读（进程跑着也能改，与 `config.edn`、
`.harness/harness.edn` 同一套纪律）。26 个 hook 点**全部登记为数据**（名字 / 时机 / payload 形态 /
是否门禁），没有触发源的点永不触发——这不是遗漏，是设计。缺失文件 = 空表不报错（全新安装是正常态）；
坏文件（EDN 语法坏或不是 map）指名绝对路径硬失败。

声明形状（沿用 general-harness spec，不再自己发明一套）：

```edn
{:pre-tool-use      [{:matcher "bash|write" :command "scripts/gate.sh" :timeout 10000}]
 :session-start     [{:command "scripts/notify.sh"}]}
```

**Blocked by:** 06（prefactor 先落地：hook 的接线票要改执行缝，而工具表与审批状态此刻还在 `harness.memory`
里；搬完再动手，才不会出现两张票同时改同一批调用点）

**Status:** ready-for-agent

- [ ] 点表是数据：26 个点（对齐 general-harness 的清单与命名）各自带名字、触发时机、payload 形态、
      是否门禁；**新增一个点 = 加一条数据，不是一段新代码**
- [ ] `hooks.edn` 两级装配：配置家 + 绑定项目的 `.harness/hooks.edn`；项目级优先、顶层浅合并、
      项目级整键替换；无绑定会话只答用户级；每次现读
- [ ] 缺失文件 = 空表，不报错（含 `.harness` 目录在而文件缺）；坏文件指名绝对路径与原因硬失败，不静默回退
- [ ] 每条声明接受 `matcher` / `command` / `timeout` 三个字段；未知字段**指名报错**（拼错的 key 不被静默丢弃），
      失败信息列出该条目认识的字段
- [ ] `matcher` 的匹配对象逐点写明：工具类的点匹配 `tool_name`，其余点没有匹配对象（声明了 matcher 的含义
      写清楚，不自造第三种语义）
- [ ] 有一条读取入口能拿到"当前生效的 hook 表"（点 → 声明列表），供 dispatch 与 09 号票的会话级 overlay 使用
- [ ] 离线全量 `harness.test-runner` 全绿（基线 189 tests / 930 assertions，本票只增）
