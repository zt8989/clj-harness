# 06 — session-configure 与 provider/changed 按选择形状重做

**What to build:** agent 运行期改 provider / model / reasoning-effort 这件事，在**新形状下真的成立**：写
`{:provider :openrouter}` 会换到 openrouter 并取其默认 model，写 `{:model "…"}` 在该 provider 下换 model，
写错名字当场指名报错。审计行不再把「换厂商」记成空变更。

**Blocked by:** 01（目录形状与解析）。

**Status:** ready-for-agent

- [ ] `session-configure` 的三个参数语义更新为三旋钮：`:provider` 是 provider 名（`providers.edn` / 内置目录里
      的名字），`:model` 是**该 provider 下的 model id**，`:reasoning-effort` 不变；三个各自独立可选，空调用拒绝。
- [ ] 审批通过后 body 仍只写命中字段；**非法 provider 名 / 非法 model id 在 body 里指名失败**（工具结果带回
      指名错误），既不写 override，也不落 `provider/changed` 行。
- [ ] `provider/changed` 行的切片从「四个解析字段」改为「三个选择旋钮」：`:before` / `:after` 是本次调用移动的
      slice，`:override` 是**按完之后 session 这一档的完整 shape**（`[:provider :model :reasoning-effort]`）。
      今天的 `select-keys` 用旧四字段，换厂商会被记成 `{:before {} :after {}}`——本票修掉这类空变更。
- [ ] 追加 `:resolved` 字段：按完之后**解析出来的** provider（protocol/base-url/model/input/output），对齐
      `provider/init`。理由与 `:override` 当初存在同源——日志活得比目录久，内置表改了之后旧日志仍须自解释。
- [ ] 既有 `:trigger "session-configure"` 与「否决不落行」的语义不动（回归保证）。
- [ ] 测试覆盖：换 provider（endpoint 与 model 随之改变）、只换 model、只改 effort、链式变更的 before/after
      衔接、非法名字被拒且无痕、否决无痕。`http_test` 里端到端那条断言 `:override` 等于合并后的完整选择形状。
- [ ] README「授权变更（session-configure）」段与 `prompt.md` 的 session-configure 措辞同步更新。
- [ ] 离线全量除既有 `bash-runs-git-bash-not-wsl` 外全绿。
