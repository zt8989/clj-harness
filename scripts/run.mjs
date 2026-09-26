#!/usr/bin/env node
//
// 把「真的服务」起起来，三步，一步失败就停在那里、不往下走：
//
//   1. npm run build        —— 页面建出来（ui/dist；它自带 `tsc --noEmit`）
//   2. 编译检查             —— 一个 JVM 里把服务端那棵树 require 一遍
//   3. clojure -M:run       —— 后台跑起来，并且**确认它真的起来了**
//
//   node scripts/run.mjs                 端口由 OS 挑（0），起来后打印地址
//   node scripts/run.mjs --port 8080     指定端口
//
// 为什么第三步不是「发一条命令就完」。后台起东西的失败是**无声的**：端口被占、依赖变了、
// 编译不过，都会让那个进程立刻退出，而调用者手里只剩一个号码，看起来一切正常。所以这里读
// **它自己打印的那行 listening**（`harness.edge.http/-main` 的 docstring 就是为这件事写的：
// 端口由 OS 挑，「a script can start this on 0, read the line, and point a browser at it」；
// 那行 banner 与 dev.mjs 共用一份，见 `scripts/backend.mjs`），读到之后再拿 HTTP 敲一下那个
// 地址 —— 两头都对上才算起来了。起不来就把它的日志尾巴打出来，而不是只说一句「超时」。
//
// 第 2 步**不是**跑测试：`-M -e "(require …)"` 只起一个 JVM、把服务端那棵树载进去，秒级，而且
// 报出来的就是编译器的话。测试要几分钟，而且红的那条可能是断言，不是编译。
//
// 进程归属，说清楚：后台那个 clojure 是**脱离**这个脚本起的（detached + unref + 输出进文件），
// 所以脚本退出它还在，`--port 0` 保证不跟别的东西撞号。但它是**跑这个脚本的那个 clj-harness
// 会话的后代**，而那个 JVM 退出时会把自己起过的整棵后代树收掉（`harness.infra.shell/reap!`）——
// 也就是说：从 clj-harness 里跑，这个服务活不过那个会话。要长住的，用自己的终端跑
// `clojure -M:run`。脚本最后把 PID 与停它的命令都打出来。
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

import { LISTENING } from "./backend.mjs";
import { exitOf, leaveBehind, run, stopTree, ON_WINDOWS } from "./proc.mjs";

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const UI_DIR = path.join(ROOT, "ui");

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

// ----------------------------------------------------------------- arguments

const argv = process.argv.slice(2);
const at = argv.indexOf("--port");
const port = at === -1 ? 0 : Number(argv[at + 1]);
if (!Number.isInteger(port) || port < 0 || port > 65535) {
  console.error(`run.mjs: --port 要一个 0..65535 的整数，拿到的是 ${argv[at + 1]}`);
  process.exit(2);
}

// ------------------------------------------------------------------ 1. 页面

console.log("run.mjs: 建页面（ui: npm run build）");
if ((await exitOf(run("npm", ["run", "build"], { cwd: UI_DIR, stdio: "inherit" }))) !== 0) {
  console.error("run.mjs: 页面没建出来，停在这里 —— 后端起来了也发不出页面");
  process.exit(1);
}

// ------------------------------------------------------------ 2. 编得过吗

console.log("run.mjs: 编译检查（一个 JVM 里 require 服务端那棵树）");
const compiled = run("clojure", ["-M", "-e", "(require 'harness.edge.http)"], {
  cwd: ROOT,
  stdio: "inherit",
});
if ((await exitOf(compiled)) !== 0) {
  console.error("run.mjs: 后端编不过，停在这里 —— 上面就是编译器的话");
  process.exit(1);
}

// --------------------------------------------------- 3. 后端，来真的那一发

const logPath = path.join(os.tmpdir(), `clj-harness-run-${Date.now()}.log`);
console.log(`run.mjs: 起后端（clojure -M:run --port ${port}），它的输出进 ${logPath}`);

// 用 `leaveBehind` 而不是 `run`，两件在 Windows 上量过的事写在 `scripts/proc.mjs` 里：
// 走一层 shell 的自定义 stdio 一个字节都不进文件；`detached` 会把 java 的话整个吞掉。
// 这个脚本的全部验证都建立在「读它自己打印的那行」上，所以那句在哪里被吃掉都是致命的。
const backend = leaveBehind("clojure", ["-M:run", "--port", String(port)], {
  cwd: ROOT,
  logPath,
});

let died = false;
backend.once("exit", () => {
  died = true;
});

/// 起不来就说清楚为什么：它的日志尾巴比「超时」有用得多，而且这条路径上不会有别的线索。
function fail(reason) {
  console.error(`run.mjs: ${reason}`);
  try {
    const tail = fs.readFileSync(logPath, "utf8").trimEnd().split("\n").slice(-25).join("\n");
    if (tail !== "") console.error(`run.mjs: ${logPath} 的最后几行 ——\n${tail}`);
  } catch {
    // 日志都读不到，那上面那句就是全部。
  }
  stopTree(backend);
  process.exit(1);
}

// 等它把那行打出来。300s 不是随手写的：这台机器上同时跑着两三个 JVM（测试、别的会话）时，
// `-M:run` 从起 JVM 到 bind 端口见过 25s，也见过更久；等不到就说清楚，而不是猜。
const WAIT_MS = 300_000;
const deadline = Date.now() + WAIT_MS;
let bound = "";
while (Date.now() < deadline) {
  const said = fs.existsSync(logPath) ? fs.readFileSync(logPath, "utf8") : "";
  const announced = said.match(LISTENING);
  if (announced) {
    bound = announced[1];
    break;
  }
  if (died) break;
  await sleep(300);
}

if (bound === "") {
  fail(died ? "后端起完就退了（下面几行是它说的）" : `等了 ${WAIT_MS / 1000}s 也没等到它打印 listening`);
}

const url = `http://127.0.0.1:${bound}`;

// 它自己说在听着，还得**真的答话**才算起来了：banner 是进程打的，「起成功」是外面敲得到。
let answered;
try {
  answered = await fetch(`${url}/`);
} catch (failure) {
  fail(`它说在 ${url} 听着，但敲不通（${failure.message}）`);
}
if (!answered.ok) {
  fail(`它说在 ${url} 听着，但 / 回的是 ${answered.status}`);
}

const home = process.env.CLJ_HARNESS_HOME ?? path.join(os.homedir(), ".clj-harness");
console.log(`run.mjs: 起来了 —— ${url}`);
console.log("run.mjs: 页面（ui/dist）与 API 是同一个地址，敲一下就是刚建的那一页");
console.log(`run.mjs: 它的输出：${logPath}`);
console.log(`run.mjs: harness 自己的日志：${path.join(home, "logs", "harness.infra.log")}`);
console.log(
  `run.mjs: 停它：${ON_WINDOWS ? `taskkill /PID ${backend.pid} /T /F` : `kill ${backend.pid}`}`,
);
console.log("run.mjs: 它是这个脚本的后代：跑脚本的那个 clj-harness 会话退出时，它会被一起收掉");
console.log("run.mjs: 要长住的，用自己的终端起 clojure -M:run");
process.exit(0);
