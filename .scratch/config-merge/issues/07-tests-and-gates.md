# 07 — 测试收口 + 全量机器门

**What to build:** 把夹具从「写 harness.edn / mcp.edn」改成「写 config.edn 的段」，跑满机器门，
把**这台机器上跑不动的那几个**如实记进 spec。

**Blocked by:** 05, 06

**Status:** ready-for-agent

## 背景（实测）

今天有 **24 个测试文件**提到 `harness.edn` / `mcp.edn`（`project-test` / `editing-test` /
`hashline/*` / `mcp-test` / `mcp-wired-test` / `preamble-test` / `skills-test` / `subagents-test` /
`system-prompt-test` / `approval-test` / `http-test` / `kernel/*` …），其中一部分只是**注释**里提到，
真正**写文件**的是夹具（`test_support.clj` 的 `config-text` 与各命名空间里自带的 `spit`）。

## 验收

- [ ] 真正**写文件**的夹具改成写 `config.edn` 的 `:session` / `:mcp` 段；只提名字的注释跟着改
      （注释说错文件，是下一张票的源头）
- [ ] `test_support` 里那份「写一份 config.edn」的口子能表达新六段（别让每个用例自己拼 EDN 字符串：
      拼错一个括号，红的是别的测试）
- [ ] 迁移本身有测试：老的家 → 新段、重叠键 config.edn 赢、`.bak` 改名、坏文件一动不动、幂等
- [ ] `clojure -M:test -m harness.test-runner` 全量：**只增不减**，红的一律说明白
- [ ] 这台机器上**已知跑不动**的三个（`harness.edge.http-test` 撞 300s / `harness.infra.shell-test`
      撞 300s / `cd ui && npm test` 因 `SQLITE_BUSY` + 超时红）按
      `.scratch/security-sensitive-paths/spec.md` 的记法如实在 spec 里对齐一遍：**改前基线也红**才算
      「环境问题」，否则当自己弄坏的查
- [ ] `cd ui && npm run typecheck && npm run build` 绿；动过 `ui/src/` 就走一次
      `node scripts/dev.mjs --scripted` 并自己开浏览器看一眼
- [ ] 合进 main 之前跑一次**改前基线**对照（可用的那部分），把两份数字都写进 spec

## 不做

- 不为了变绿去放宽 `CLJ_HARNESS_TEST_*` 的时限（那是掩盖，不是修）。
