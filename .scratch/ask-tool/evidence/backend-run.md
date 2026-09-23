# 后端全量在**这台机器**上不是绿的——以及为什么它与本票无关

票 01 的验收写着「`node scripts/test.mjs --backend` 与 `--ui` 全绿」。`--ui` 绿了，`--backend` **没有**，
而且在这台机器上从来就没绿过。这一份是证据，不是辩解。

一条命令、两个版本、同一份套件：

```
node scripts/test.mjs --backend          # 改动后（工作区）
git stash push -u -m "ask-tool wip"      # 回到 HEAD
node scripts/test.mjs --backend          # 基线
git stash pop
```

| | 测试数 | 断言数 | failures | errors |
|---|---|---|---|---|
| HEAD（基线） | 903 | 11524 | **169** | **22** |
| 本票改动后 | 903 | 11525 | **169** | **22** |

- `backend-baseline.log` / `backend-run.log` —— 两次的完整输出。
- `backend-baseline-failures.txt` / `backend-failures.txt` —— 从两份日志里抽出的失败用例名
  （去重；after 那份带 `文件:行` 与出现次数）。

## 它证明了什么

1. **两次的 failures / errors 一个不差：169 / 22。** 改动前后同一批红，本票没往里添一条。
2. **失败集合的差只有五条，两边加起来都是计时/IO 抖动**：

   | 只在 HEAD 红 | 只在改动后红 |
   |---|---|
   | `a-pattern-with-no-slash-matches-file-names-at-any-depth` | `a-turn-of-slow-tools-finishes-in-the-max-not-the-sum` |
   | `a-question-nobody-answers-expires-and-nothing-is-invented` | `the-endpoint-answers-the-context-section-over-real-http` |
   | `the-kernel-rows-run-first-then-the-file-then-the-session` | |

   后两条的错话就是抖动的样子，抄在这里：

   ```
   FAIL in (a-turn-of-slow-tools-finishes-in-the-max-not-the-sum) (loop_test.clj:107)
   wall clock is the slower tool, not the sum of both
   expected: (< ms 550)
     actual: (not (< 992.3504 550))          <- 墙钟断言，机器一忙就红

   FAIL in (the-endpoint-answers-the-context-section-over-real-http) (context_test.clj:339)
   expected: (= ["system" "tools" "conversation"] (map :key (:parts ctx)))
     actual: (not (= ["system" "tools" "conversation"] ()))   <- 记录还没落盘就去读了
   ```

   三处主语都不一样（`cap.glob` / `cap.mcp` / `kernel.llm`），没有一处是本票碰过的东西。
3. **红的形状全是 Windows。** 最大的一坨是 `every-namespace-lives-where-its-name-says`
   （`layers_test.clj:122`，99 条，即 110 个命名空间里 99 个）：它拿路径当命名空间比，而这里拿到的是
   `test\harness\...` 而名字是 `harness.test-...`。其余是 CRLF（`system_prompt_test.clj:101` 里
   `\r\n` 对 `\n`）、Windows 上跑不起来的 hook 脚本（`FileNotFoundException: ...\elicitation-hooks.txt`
   一类）、以及 `a-bash-that-is-windows-wsl-launcher-does-not-count-as-a-bash` 这种明摆着与平台有关的。
4. **本票自己的用例全过。** 两次日志里，失败集合**没有一条**是 `ask` 的、也没有一条是那两份名册名单的
   （`specs-expose-every-base-tool` / `the-default-toolset-is-the-anchor-one` /
   `hashline-mode-does-not-serve-edit` / `str-replace-mode-does-not-serve-the-anchor-tools`）。
   单独跑进名单之后的那一套（`ask-test-run.log`）：

   ```
   node scripts/test.mjs --ns harness.cap.ask-test
   Ran 7 tests containing 48 assertions.
   0 failures, 0 errors.
   ```

   另外把本票碰过的五个命名空间一起跑（`ask-test` / `kernel.tools-test` /
   `cap.editing-mode-tools-test` / `edge.ag-ui-test` / `edge.http-test`），红的只有
   `tools-test` 那 5 条 job/bash —— 和 HEAD 上**逐条同名**（`a-command-that-would-hang-is-stopped-at-the-limit`
   ×2、`a-job-says-when-the-command-sends-its-own-output-away`、`the-record-a-job-names-is-readable-with-bash` ×2），
   只是因为我在名单里各加了一个 `ask`，行号整体挪了三行。另外三个命名空间**两边都绿**。

## 顺手修掉的一个洞（否则上面第 4 条是假的）

`harness.test-runner/test-namespaces` 是**手写的**一份名单，没有「文件都列进来了吗」的守卫。
`test/harness/cap/ask-test.clj` 一开始**不在名单里**——`--backend` 那次跑得全绿也不会跑到它，
而它是这一票唯一在跑真 HTTP 边的那套。已经补进名单（第 14 / 50 个）：

```clojure
    harness.cap.mcp-wired-test
    harness.cap.ask-test
    harness.session-tools-test
```

**守卫本身没加**，如实写在这里：加一条「`test/` 下每个 `*_test.clj` 都在名单里」的用例是十行的事，
但它超出了这一票的范围，留给你决定（`ui/test/ui.test.ts` 那边用 `EXPECTED_CASES` 钉死的就是同一个洞）。

## 它没有证明什么（如实写在这里）

- **没在别的机器上跑过。** 这里说的「与平台有关」是这台 Windows 机器的结论；同一份改动在
  macOS/Linux 上该是绿的，但本会话无从验证。
- **没跑活厂商**（同 `README.md` 那条）。后台套件全程离线，脚本替身。
