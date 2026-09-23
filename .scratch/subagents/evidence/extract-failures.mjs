// Pull the set of failing (test-name, file:line) pairs out of a
// harness.test-runner log. Both runs are compared BY NAME, not by count:
// a count alone can't tell "my change broke something" from "this box was
// already red". Usage: node extract-failures.mjs <log> > <out.names>
import { readFileSync } from "node:fs";

const log = readFileSync(process.argv[2], "utf8");
const lines = log.split(/\r?\n/);

// "FAIL in (some-test-name) (file_test.clj:122)"  -- ERROR looks the same.
// The line number can be negative -- a Java-stack-frame location like
// "FileInputStream.java:-2" is how the JDK reports a frame with no line info.
const re = /^(FAIL|ERROR) in \(([^)]+)\) \(([^):]+):(-?\d+)\)/;
const names = new Set();
for (const l of lines) {
  const m = re.exec(l.trim());
  if (m) names.add(`${m[1]} ${m[2]} @ ${m[3]}:${m[4]}`);
}
for (const n of [...names].sort()) console.log(n);
