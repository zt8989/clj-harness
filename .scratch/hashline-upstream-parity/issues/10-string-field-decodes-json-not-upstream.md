# 10: 字段本身是字符串时，本仓解 JSON，上游当字面文本

**Status:** needs-triage
**Blocked by:** None

**事实（2026-10-01，上游 5.0.0 包内实测）**：`replace` 的 `replacement_lines` 传一个**字符串**时，
上游走的是 payload 分支（`src/hashline/resolve.ts:198-200` → `parsePayloadText`），也就是
`src/payload-contract.ts:5` 那句契约本身——「the exact text to write in place of the removed range」：
按换行切行，**不解 JSON**。

| 传入（字符串） | 上游写下去的 | 本仓 |
|---|---|---|
| `"[]"` | 一行 `[]` | 一行 `[]`（`hashline-edit` 票 01 改后）；改前是**删掉这一段** |
| `"[\"a\",\"b\"]"` | 一行 `["a","b"]` | 两行 `a`、`b` |

实测方式：上游仓 `npm i` 后写一条一次性 vitest，走**真实的 `replace` 工具**（`test/support/fixtures.ts`
的 `setupIntegrationTest`），把落盘字节打出来；探针跑完即删，`package-lock.json` 已还原。
`insert` 的字符串字段走同一条路（`src/insert.ts:73` 也是 `parsePayloadText`），但**这一条没有单独实测**。

**本仓为什么会解 JSON**：`replacement-arg` 的字符串分支是
`.scratch/hashline-upstream-parity/spec.md` 票 03 落地的，票面写的上游依据是
`src/utils.ts:347-387` 的 `decodeStringArray` + `normalizeEditLines`。那条依据**在 replace 路径上走不到**：
字符串字段在 `resolve.ts:198` 就分流进 payload 了，`decodeStringArray` 的**字符串分支**只有单测在调
（全仓除 `utils.ts` 自己，只有 `resolve.ts:203` 一处调用，而它拿到的永远是数组）。
差额表 03 行说的「字段本身是 JSON 串…解回数组」是**读错了分支**；已在 spec 里加注。

**要定的**（所以是 needs-triage）：

1. **跟上游**：字符串字段一律当字面文本，`replacement-arg` 的字符串分支只剩「按换行切行」。代价是
   「整个数组塞进字段」这种手滑回到「写一行 JSON 文本」——正是票 03 当初要修的那种手滑；
2. **留本仓**：字符串字段仍是「非空 JSON 字符串数组就解回数组」，把差额表 03 行从「parity」改成
   「有意偏离」，并写清楚代价（`"[\"a\",\"b\"]"` 会被拆成两行，而调用方要的可能是这一行本身）；
   票 01 已经把**空数组**这一格从「删范围」改成「字面量」，所以 `"[]"` 不再有第二种读法;
3. 无论选 1 还是 2，都得把「字符串字段和数组字段的读法不同」这一条写进 spec 的显眼处——它会让人
   以为 `["a","b"]` 与 `"[\"a\",\"b\"]"` 是同一件事，而它们不是。

**验收**

- [ ] 差额表与 spec 里「上游如何如何」的说法与实测一致（03 行已在 spec 加注）
- [ ] 字符串字段的行为有用例钉着，`:strict-input` 的口径写明
- [ ] `replace` 与 `insert` 对字符串字段口径一致（两处共用 `replacement-arg`／`replacement-field`）
