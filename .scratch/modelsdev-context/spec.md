# model facts：上下文/最大输出由 models.dev 填，供应商自己的清单兜底

**日期**：2026-10-03 · **状态**：已落（后端 + 设置页；真机走查待做）

## 主人要什么

> 后端增加 model 上下文和最大输出匹配，https://models.dev/ ，默认 7 天缓存。
>
> 如果一个供应商没有设置模型，则调用它的 models 接口并缓存。如果一个模型没有设置文本、图片这种
> 输入输出，或者没有输入上下限，则优先使用那个 model dev 的返回。然后你在设置里面只需要设置那个
> 模型 ID 和名称就行了，其他的话作为扩展，而不是必填要素。

四个只有他能定的地方，问过、答了：

| 问题 | 答案 |
| --- | --- |
| 供应商没写 `:models` 时抓来的清单算什么？`:model` 能缺省吗？ | **清单就是整张可选表，`:model` 也可缺省（取清单第一个）** |
| 「设置里只需要模型 ID 和名称」的名称指哪个？ | **每个模型也要有可选显示名（取 models.dev 的 name，缺省回退 id）** |
| 文件没写模态、两边都不知道时，解析成什么？ | **兜底成纯文本（`#{:text}`，明确不接受图片）** |
| 供应商自己的清单什么时候抓、缓存多久？ | **和 models.dev 一样：后台抓、7 天过期**（实际是「第一次必需时同步抓一次」，见下） |

## 两条外部事实，两种脾气

| | models.dev（`api.json`） | 供应商自己的 `/models` |
| --- | --- | --- |
| 是什么 | 公开数据库：模态、两个计数、显示名 | 只有「它服务哪些 id」 |
| 缺了会怎样 | 少一点事实，run 照跑 | **没有表就没有 id 可跑** |
| 所以 | **永不在调用线程上抓**：读的人拿当下缓存，过期就背后刷 | 第一次真需要、缓存又没有时**同步抓一次并等它** |
| 失败时 | 照旧用旧的那份，记一行 warn，一小时内不重试 | 留一张空表；由 `assemble` 指名拒绝那一次运行 |
| 匹配 | 幂等：同一家族的多行先按 canonical id 收敛，再优先**拥有者自己那一行**，最后多数票 | 原样照收（厂商自己发布的顺序就是默认模型的顺序） |

两份都落成配置家下的镜像：`models-dev.json` / `provider-models.json`，7 天（`stale-after-ms` 是个 def），
`home/spit-atomically!` 写，随时可删。**每行带自己的抓取时间**——刷一家不会让另一家显得新鲜。

## 曾经写错、被数据抓住的地方（留证）

1. **`json/read-str :key-fn keyword` 会把 model id 也变成关键字**：`(str :cn:glm-5.3-flash)` 带回一个
   前导冒号，于是 `zhipuai` 那一行永远匹配不上。现在外部文档一律保留字符串键，缓存里的索引写成
   `[id facts]` 对，不写成 JSON 对象。
2. **拥有者自己那一行往往没有 `canonical_model_id`**（它不用给自己命名），而按「canonical 一致」筛出来的
   恰好全是转售商的行。实测证据：`glm-5.3-flash` 从 zhipuai 自己的 `1000000/131072` 变成 OpenRouter 的
   `1048576/943717`。修法：先在整个家族里找「provider 名 == canonical 的 owner」那一行。
3. **空模态对（`[]`）不是「什么都不收」而是「没说」**：表单不勾任何框时就是这个形状，读成声明会让每个新
   模型都收不了任何东西。

## 落地清单

- `harness.cap.model-data`（新）：两条事实的抓取、索引、缓存、后台刷新、失败冷却。
- `harness.infra.home`：两份镜像的路径访问器。
- `harness.cap.providers`：
  - `check-model` 的模态对**可缺**（成对，空=沉默），新增可选 `:name`；
  - `check-provider` 的 `:models` / `:model` **可缺**，`:model` 缺省 = 表里排序第一个（手写表）/清单第一个（抓来的）；
  - `catalog` 先 `vendor-model-tables` 填表；
  - `assemble` 用 `modalities-with` / `counts-with` 补沉默（文件写的永远赢），多一个 `:model-name`；
  - `model-row` 照文件说（空模态渲染 `[]`），另给一个 `:name-suggested`。
- `harness.edge.http` 没动：路由与形状都没变。
- 设置页 Models：每个模型行多一个可选的**名称**框（占位符就是数据库的建议），勾选框/上限本来就可空，
  最后一个模型行现在也**可以删掉**（一个都不声明 = 让清单说话）。
- 测试：`harness.cap.model-data-test`（11 个）、`providers_test` 里 7 个旧规则用例改成新规则、新增 2 个
  填充用例；`http_test` 两处（稀疏配置、供应商写入被拒的用例）跟着改。**runner 的 `isolate!` 关掉两扇门**
  （`providers/*list-models*` 答空、`model-data/*http-get*` 抛错），所以任何测试都不会出网。

## 没做

- 选择器**行上显示名称**：`choices` 仍然只发 id 列表，所以 composer 那个菜单还是印 id；解析里已经有
  `:model-name`，要显示它是下一票（要改 `GET /api/choices` 的形状 + `ui/src/lib/model-rows.ts`）。
- 真机走查（`node scripts/dev.mjs --scripted` + 浏览器）：设置页那个名称框还没在浏览器里点过。
- 表单里没有「从 models.dev 预填名称」的按钮：名称是**占位符**（建议）而不是值，保存时会进文件。
