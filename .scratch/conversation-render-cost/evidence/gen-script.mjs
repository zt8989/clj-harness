// The walkthrough scripts the render-cost readings are taken with.
//
//   node .scratch/conversation-render-cost/evidence/gen-script.mjs
//
// Writes `script-18k.json` and `script-36k.json` BESIDE THIS FILE: one turn, a 1,200-character
// reasoning block and a markdown answer of the named size, streamed 5 characters at a time with
// an 8 ms pause between chunks (`harness.fake`'s `chunk-size` / `pace-ms`) -- which is the
// 4,285-frame / 45-second stream `spec.md` measured the current page with.
//
// THE BODY IS PROSE IN PARAGRAPHS, which is what the 2026-09-30 reading used and is load-bearing
// for comparability: `spec.md`'s DOM column moves by "+~100 nodes" over that whole stream, so the
// answer it measured was one long piece of running text, not a document of headings, tables and
// fenced blocks. A body of that shape puts ~10x the nodes on the page and prices the markdown
// parser rather than the message view, and its reading is not the reading in that table.
//
// THE 36k TWIN EXISTS TO ASK ONE QUESTION (ticket 01, step 2): if the streaming message re-parses
// its markdown every flush, the JS per second doubles with the content; if it does not, the two
// sizes cost about the same to keep up with. The larger body is the same text twice over, so the
// only thing that changed between the two readings is how much of it there is.
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const HERE = path.dirname(fileURLToPath(import.meta.url));

const REASONING =
  "先读一眼 deps.edn，确认依赖有没有变；再看一眼 ui/package.json，确认前端这边没有多出" +
  "一个要联网的依赖。两边都对上了，就可以按票上写的那条路改：流式期间只更新那段文本，" +
  "不要再把整条消息的子树重建一遍。量法用脚本 provider 推一场 18k 字的长回答，差值读数，" +
  "不是推算。这条思考本身也要够长，因为它出现的时机和回答不一样——它先来，而且它整段都在" +
  "回答开始之前就到齐了。";

/// ONE SENTENCE of the answer, of a length a person writes rather than a machine emits. Copied
/// and varied rather than generated, so the body is byte-stable across runs and a reading can be
/// reproduced months later.
const SENTENCES = [
  "这一段是正文，里面有 \`inline code\`、**加粗**、*斜体*，还有一条不该被当成 markdown 的 a*b*c。",
  "行文里再塞几个数字 1234、百分比 87%，以及一个全角的逗号、顿号，好让解析器有点活干。",
  "成本全在「画」不在「收」，所以优化要落在 markdown 解析和 React 重建上，不要去做 CSS 与合成层。",
  "先拉一次存量，之后由推送走；推送会丢、存量不会，所以重连之后要补一次存量，这条规矩不能破。",
  "每一帧交付之后就是垃圾，合批窗口有界，隐藏的标签页也只在满两百帧时画一次，这一半不是成本。",
  "窗口由服务端给定，每点一次「更早」就往前接五十条，所以页面手里的这场对话会随阅读历史一起长。",
  "读窗口只约束「读多少」，不约束「画多少」；把窗口里的条目全都挂进 React 树，是今天的样子。",
  "一条回答一个段落，段落之间空一行；这个形状决定了 DOM 节点的数量，也决定了这次读数的口径。",
];

/// A body of AT LEAST `size` characters, in paragraphs of a few thousand characters each -- so it
/// has line breaks a person typed, and tens of nodes rather than thousands.
function body(size) {
  const paragraphs = [];
  let total = 0;
  let n = 0;
  while (total < size) {
    const lines = [];
    for (let i = 0; i < 20; i += 1) {
      const sentence = SENTENCES[(n + i) % SENTENCES.length];
      lines.push(sentence);
    }
    n += 1;
    const paragraph = lines.join("");
    paragraphs.push(paragraph);
    total += paragraph.length;
  }
  return `${paragraphs.join("\n\n")}\n`;
}

for (const size of [18_000, 36_000]) {
  const file = path.join(HERE, `script-${size / 1000}k.json`);
  const content = body(size);
  const script = { "pace-ms": 8, turns: [{ reasoning: REASONING, content }] };
  fs.writeFileSync(file, `${JSON.stringify(script)}\n`, "utf8");
  const chars = content.length + REASONING.length;
  console.log(
    `${path.basename(file)}: ${chars} chars (answer ${content.length}), ~${Math.round(chars / 5)} chunks`,
  );
}
