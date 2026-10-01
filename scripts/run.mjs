#!/usr/bin/env node
//
// 把「真的服务」起起来，几步，一步失败就停在那里、不往下走：
//
//   1. npm run build        —— 页面建出来（ui/dist；它自带 `tsc --noEmit`）
//   2. 编译检查             —— 一个 JVM 里把服务端那棵树 require 一遍
//   3. clojure -M:run       —— 起后端，并且**确认它真的起来了**（默认脱离这个脚本）
//
//   node scripts/run.mjs                        端口由 OS 挑（0），起来后打印地址
//   node scripts/run.mjs --port 8080            指定端口
//   node scripts/run.mjs --port 8080 --detach   新后端**生下来就是孤儿**：在 clj-harness 会话里
//                                               跑，也不会随那个会话被收走（见下）
//   node scripts/run.mjs --foreground          输出进**这个终端**（同一份也落进日志），脚本一直
//                                               活到它退出，这个终端里 Ctrl-C 停它
//   node scripts/run.mjs --stop --port 8080     停掉 :8080 上的后端（不建页面、不起新的）
//   node scripts/run.mjs --restart --port 8080  先停后起（要显式端口，理由见下）
//   node scripts/run.mjs --restart --port 8080 --detach
//                                               把「停旧 + 起新」交给一个脱离的进程，脚本
//                                               立刻返回 —— 要重启**自己所在的那个会话的
//                                               宿主**时用这一发
//   node scripts/run.mjs --help
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
// 所以脚本退出它还在，`--port 0` 保证不跟别的东西撞号。但脱离 ≠ 不被收：跑这个脚本的那个
// clj-harness 会话退出时，那个 JVM 会把自己起过的整棵**后代树**收掉（`harness.infra.shell/reap!`
// 走的是 `.descendants` —— 每一层的 PPID 树），而「脚本的后代」正好在那棵树里。`--detach` 就是
// 为这件事：新后端由 `setsid -f` 生下来，中间那层立刻退出，它**一出生就是孤儿**（PPID 是 1），
// 那棵树里没有它。自己终端里跑 `clojure -M:run` 当然也行，两条路都对。
//
// `--foreground` 是第三条路，也是唯一一条**不属于**上面这段话的：那一发不脱离、不 unref，输出的
// 两根管子回到脚本手里、由脚本转给终端，脚本一直活到它退出为止 —— 它就是这个终端的孩子，
// Ctrl-C 由操作系统送给这一组，谁也不用转发（见 `scripts/proc.mjs` 的 `runInForeground`）。
//
// 后端那个 JVM 的命令行上还写着一个堆上限（`JVM_ARGS`），理由在第 3 步那一处。
//
// `--stop` / `--restart` 认的是**地址**而不是 pid：端口是浏览器和 README 指向过的东西，pid 是
// 昨天写下、今天可能已经被回收的号码。所以「停掉后端」=「停掉 :PORT 上正在听的那个」，现问现算
// （`scripts/proc.mjs` 的 `listenerOn`），没有要照看的状态文件。`--restart` 因此要**显式端口**：
// 0 是「让 OS 挑」，挑出来的号码下次不是它，没有「同一个地址回来」这回事。
//
// `--restart --detach` 为什么要把重启**交出去**：要停的那个后端往往就是调用者的祖先。从会话里
// 重启这个会话的宿主，`kill` 下去连同发起它的那条命令一起没了（收尾收的就是那棵后代树），
// 「起新的」根本没机会跑。所以脚本先把一个 `--restart-later` 进程**孤儿式**地生下来（它一出生
// 就在那棵树外），由它去等 `--grace` 秒、停旧、起新、自检；调用者把那行 pid 与日志路径打出来就
// 返回。等这几秒也是故意的：发起它的那次回答要先说完。
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

import { LISTENING } from "./backend.mjs";
import {
  ON_WINDOWS,
  askListenerToStop,
  exitOf,
  insistListenerStop,
  leaveBehind,
  leaveBehindOrphan,
  listenerOn,
  run,
  runInForeground,
  stopTree,
} from "./proc.mjs";

const HERE = fileURLToPath(import.meta.url);
const ROOT = path.resolve(path.dirname(HERE), "..");
const UI_DIR = path.join(ROOT, "ui");

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

const USAGE = `把真正的服务起起来（建页面 -> 验编译 -> 起后端，并且确认它真的起来了）。

  node scripts/run.mjs                        端口由 OS 挑（0），起来后打印地址
  node scripts/run.mjs --port 8080            指定端口
  node scripts/run.mjs --port 8080 --detach   新后端生下来就是孤儿（PPID 1）：在 clj-harness
                                              会话里跑也不会随那个会话被收走
  node scripts/run.mjs --foreground           输出进这个终端（同一份也落进日志）：脚本一直活到
                                             它退出才返回，这个终端里 Ctrl-C 停它
  node scripts/run.mjs --stop --port 8080     停掉 :8080 上的后端
  node scripts/run.mjs --restart --port 8080  先停后起（要显式端口）
  node scripts/run.mjs --restart --port 8080 --detach
                                              把重启交给一个脱离的进程去做，脚本立刻返回；
                                              重启自己所在的那个会话的宿主时用这一发
  node scripts/run.mjs --restart --port 8080 --detach --grace 60
                                              --grace 秒（默认 30）后才动手停旧
  node scripts/run.mjs --help                 这段

跑完会打印地址、日志路径、以及怎么停它。`;

/// How long the caller waits after asking, before it insists.
const STOP_WAIT_MS = 10_000;
const STOP_INSIST_MS = 5_000;

// ----------------------------------------------------------------- arguments

const options = { port: 0, grace: 30, detach: false, foreground: false, restart: false, restartLater: false, stop: false };
const argv = process.argv.slice(2);

function usageThenExit(code, line) {
  if (line !== undefined) console.error(`run.mjs: ${line}`);
  console.error(`\n${USAGE}`);
  process.exit(code);
}

for (let index = 0; index < argv.length; index += 1) {
  const arg = argv[index];
  if (arg === "--help" || arg === "-h") {
    console.log(USAGE);
    process.exit(0);
  } else if (arg === "--detach") {
    options.detach = true;
  } else if (arg === "--foreground") {
    options.foreground = true;
  } else if (arg === "--restart") {
    options.restart = true;
  } else if (arg === "--restart-later") {
    options.restartLater = true;
  } else if (arg === "--stop") {
    options.stop = true;
  } else if (arg === "--port" || arg === "--grace") {
    const value = argv[index + 1];
    if (value === undefined || value.startsWith("--")) usageThenExit(2, `${arg} 要一个值`);
    const number = Number(value);
    if (arg === "--port") {
      if (!Number.isInteger(number) || number < 0 || number > 65535) {
        usageThenExit(2, `--port 要一个 0..65535 的整数，拿到的是 ${value}`);
      }
      options.port = number;
    } else {
      if (!Number.isInteger(number) || number < 0) usageThenExit(2, `--grace 要一个 ≥0 的整数（秒），拿到的是 ${value}`);
      options.grace = number;
    }
    index += 1;
  } else {
    usageThenExit(2, `不认识的开关 ${arg}`);
  }
}

if ((options.stop || options.restart || options.restartLater) && options.port === 0) {
  usageThenExit(2, "要停/要重启就得说清端口（--port N）：0 是「让 OS 挑」，挑出来的号码下次不是它");
}
if (options.stop && options.restart) usageThenExit(2, "--stop 只停、--restart 停完再起，两件事选一件");
if (options.foreground && options.detach) {
  // 一个是「生下来就脱离调用者」，一个是「跟着这个终端活」，没有一句能同时成立。
  usageThenExit(2, "--detach 与 --foreground 说的是两件相反的事，选一件");
}

// ------------------------------------------------------- who is on that port

/// `ss`/`lsof` 都可能不在，或者答不上来：那种时候要说清楚，而不是当成「没人在听」——「没人在听」
/// 会让 `--stop` 假装成功、让 `--restart` 起出第二个。
function whoIsOn(port) {
  try {
    return listenerOn(port);
  } catch (failure) {
    console.error(`run.mjs: ${failure.message}`);
    process.exit(2);
  }
}

async function waitForFree(port, timeoutMs) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (whoIsOn(port) === null) return true;
    await sleep(200);
  }
  return whoIsOn(port) === null;
}

/// 停掉 :PORT 上正在听的那个，并且**等它真的让开**：一个还在 TIME_WAIT/BIND 的地址上起新东西，
/// 只会让新后端自己退出。两次机会，第一次是 SIGTERM（harness 自己的收尾会跑），第二次是坚持。
async function stopOnPort(port) {
  const found = whoIsOn(port);
  if (found === null) {
    console.log(`run.mjs: :${port} 上没有人在听，没什么可停的`);
    return false;
  }
  const named = found.command === null ? "" : `（${found.command}）`;
  console.log(`run.mjs: :${port} 上是 pid ${found.pids.join(", ")}${named}，请它停`);
  askListenerToStop(port);
  if (await waitForFree(port, STOP_WAIT_MS)) {
    console.log(`run.mjs: :${port} 空出来了`);
    return true;
  }
  console.log(`run.mjs: 它 ${STOP_WAIT_MS / 1000}s 没让开，不再等`);
  insistListenerStop(port);
  if (await waitForFree(port, STOP_INSIST_MS)) {
    console.log(`run.mjs: :${port} 空出来了（第二下才停）`);
    return true;
  }
  const left = whoIsOn(port);
  console.error(`run.mjs: :${port} 还被占着（pid ${left === null ? "?" : left.pids.join(", ")}），不往下走了`);
  process.exit(2);
}

/// 等那个脱离的进程把它自己的 pid 写进日志（`--restart-later` 的第一行就是）。`setsid -f` 把
/// 中间那层换掉了，spawn 给的号码没有意义；读不到也不影响重启，只是「撤掉它」那句话得让主人
/// 自己去日志里找。
async function pidFromLog(file, timeoutMs) {
  const until = Date.now() + timeoutMs;
  while (Date.now() < until) {
    try {
      const found = fs.readFileSync(file, "utf8").match(/pid=(\d+)/);
      if (found) return Number(found[1]);
    } catch {
      // 还没建出来，下一轮再看。
    }
    await sleep(100);
  }
  return null;
}

// ------------------------------------------------------------- 0. 停 / 重启

if (options.stop) {
  await stopOnPort(options.port);
  process.exit(0);
}

if (options.restart && options.detach && !options.restartLater) {
  // 交给一个脱离的进程：它一出生就在要停的那棵后代树**外面**，所以停下去之后它还活着。
  const logPath = path.join(os.tmpdir(), `clj-harness-restart-${Date.now()}.log`);
  const workerPidPath = path.join(os.tmpdir(), `clj-harness-restart-${Date.now()}.pid`);
  const orphan = !ON_WINDOWS;
  const workerArgs = [
    HERE,
    "--port",
    String(options.port),
    "--restart-later",
    "--grace",
    String(options.grace),
    "--detach",
  ];
  const worker = leaveBehindOrphan(process.execPath, workerArgs, {
    cwd: ROOT,
    logPath,
    pidPath: orphan ? workerPidPath : null,
  });
  const workerPid = orphan ? await pidFromLog(logPath, 3000) : worker.pid;
  const named = workerPid === undefined || workerPid === null ? "" : `（pid ${workerPid}）`;
  console.log(`run.mjs: 重启已交给一个脱离本会话的进程${named}：它等 ${options.grace} 秒后动手 —— 先建页面、验编译，再停掉 :${options.port} 上的旧后端、把新的起起来并自检`);
  console.log(`run.mjs: 它的输出：${logPath}（新后端那行 listening 也在里面）`);
  console.log(
    workerPid === undefined || workerPid === null
      ? `run.mjs: 撤掉它：看 ${logPath} 第一行的 pid，kill 掉`
      : `run.mjs: 撤掉它：${ON_WINDOWS ? `taskkill /PID ${workerPid} /T /F` : `kill ${workerPid}`}（那 ${options.grace} 秒里有效）`,
  );
  process.exit(0);
}

if (options.restartLater) {
  // 这一发是 `--restart --detach` 起的那个进程自己：**先等**，因为发起它的那次回答要先说完。
  // 停旧不在这里 —— 它排在「建页面 + 验编译」之后，起不来的话旧的那个还在服务。
  console.log(`run.mjs: restart-later pid=${process.pid} port=${options.port} grace=${options.grace}`);
  console.log(`run.mjs: --restart-later：${options.grace} 秒后动手（撤掉我：kill ${process.pid}）`);
  await sleep(options.grace * 1000);
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

// 停旧排在**建页面与验编译之后**：这两步失败的概率不低（依赖变了、编译不过、npm 抽风），
// 而那时候旧的还在服务，比「先停掉、再发现起不来」在 :PORT 上留一个空洞好得多。
if (options.restart || options.restartLater) {
  console.log(`run.mjs: 停掉 :${options.port} 上的旧后端（新的已经验过编译了）`);
  await stopOnPort(options.port);
}
// --------------------------------------------------- 3. 后端，来真的那一发

const logPath = path.join(os.tmpdir(), `clj-harness-run-${Date.now()}.log`);
const pidPath = path.join(os.tmpdir(), `clj-harness-run-${Date.now()}.pid`);

/// 后端这个 JVM 的堆上限，写在**命令行**上而不是 `deps.edn` 的 `:jvm-opts`：这台机器的
/// deps.clj 启动器不读 `:jvm-opts`（`deps.edn` 里那段注释也记着这件事），只有 `-J` 顶用 ——
/// 实测 `-J-Xmx321m` 进来就是 322 MB 的上限。不写的话默认是**物理内存的 1/4**（这台 4 GB），
/// 而 G1 不会自己把 region 交回 OS（`G1PeriodicGCInterval` 默认是 0），于是一阵高峰把堆顶到
/// 1.7 GB 之后，那个水面就一直挂在任务管理器上：2026-10-01 实测，进程 1.6 GB 工作集，活着的
/// 对象只有 51 MB，一次强制 Full GC 吐回 1.2 GB（读数与那三笔账见
/// `.scratch/memory-hygiene/spec.md`）。第 2 步那个编译检查**不封顶**：它是一次性的，不是这个
/// 长住的进程。
const JVM_ARGS = ["-J-Xmx1g"];
const backendArgs = [...JVM_ARGS, "-M:run", "--port", String(options.port)];
console.log(`run.mjs: 起后端（clojure ${JVM_ARGS.join(" ")} -M:run --port ${options.port}），它的输出进 ${logPath}`);

// 用 `leaveBehind` 而不是 `run`，两件在 Windows 上量过的事写在 `scripts/proc.mjs` 里：
// 走一层 shell 的自定义 stdio 一个字节都不进文件；`detached` 会把 java 的话整个吞掉。
// 这个脚本的全部验证都建立在「读它自己打印的那行」上，所以那句在哪里被吃掉都是致命的。
//
// `--detach` 走 `leaveBehindOrphan`：多一层 `setsid -f`（换成孤儿），并且让它**自己**把 pid
// 写下来——`setsid` 把中间那层换掉了，spawn 给的那个号码在返回前就已经没了，而「起没起来」和
// 「怎么停它」都要那个真号码。写 pid 的动作就是 `exec` 之前的那一次重定向，没有别的机关。
//
// `--foreground` 走 `runInForeground`：不脱离、不进自己的进程组，就挂在这个终端上，而两根
// 管子回到这里。**验证仍然只有一条路**（下面那个循环读的还是这份日志），因为这里的 tee 把每个
// 字节同时送给终端与日志 —— 两半都要，所以别把 tee 挪到下面去。
const backend = options.foreground
  ? runInForeground("clojure", backendArgs, { cwd: ROOT })
  : options.detach
    ? leaveBehindOrphan("clojure", backendArgs, {
        cwd: ROOT,
        logPath,
        pidPath: ON_WINDOWS ? null : pidPath,
      })
    : leaveBehind("clojure", backendArgs, { cwd: ROOT, logPath });

if (options.foreground) {
  // 同步追加是故意的：读这份日志的就是下面那个循环，写完再读没有先后之争。异步写会留下一段
  // 「它已经说了、日志里还没有」的窗口，而这里的判据正是「日志里有没有那句话」。
  backend.stdout.on("data", (chunk) => {
    fs.appendFileSync(logPath, chunk);
    process.stdout.write(chunk);
  });
  backend.stderr.on("data", (chunk) => {
    fs.appendFileSync(logPath, chunk);
    process.stderr.write(chunk);
  });
}

/// `--detach` 之后我们手里没有 pid（POSIX），只有它自己写下的那一行；其余情况手里就是 child。
const ownedPid = options.detach && !ON_WINDOWS ? null : backend.pid;

function readPidFile() {
  if (ownedPid !== null) return ownedPid;
  try {
    const pid = Number(fs.readFileSync(pidPath, "utf8").trim());
    return Number.isInteger(pid) && pid > 0 ? pid : null;
  } catch {
    return null; // 还没写出来
  }
}

/// 还活着吗。直接起的那个看它的退出事件，孤儿那个问它的 pid（`kill 0` 只问不杀）。
function stillAlive() {
  if (ownedPid !== null) return backend.exitCode === null && backend.signalCode === null;
  const pid = readPidFile();
  if (pid === null) return true; // 还没写出来，不当成死
  try {
    process.kill(pid, 0);
    return true;
  } catch {
    return false;
  }
}

let died = false;
// 孤儿那一路的 child 是 `setsid`，它**起完就退**，退出事件在这里不是「后端死了」的意思。
if (ownedPid !== null) backend.once("exit", () => {
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
  if (died || !stillAlive()) break;
  await sleep(300);
}

if (bound === "") {
  fail(died || !stillAlive() ? "后端起完就退了（下面几行是它说的）" : `等了 ${WAIT_MS / 1000}s 也没等到它打印 listening`);
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

const backendPid = readPidFile();
const home = process.env.CLJ_HARNESS_HOME ?? path.join(os.homedir(), ".clj-harness");
console.log(`run.mjs: 起来了 —— ${url}`);
console.log("run.mjs: 页面（ui/dist）与 API 是同一个地址，敲一下就是刚建的那一页");
console.log(options.foreground
  ? `run.mjs: 它的输出：这个终端（同一份也落在 ${logPath}）`
  : `run.mjs: 它的输出：${logPath}`);
console.log(`run.mjs: harness 自己的日志：${path.join(home, "logs", "harness.infra.log")}`);
if (options.foreground) {
  console.log("run.mjs: 它是**前台**跑的：这个脚本坐在这儿，直到它退出才返回");
  console.log("run.mjs: 停它：这个终端里 Ctrl-C —— 脚本不抢先 taskkill，让后端走完自己的收尾");
} else if (backendPid !== null && backendPid !== undefined) {
  console.log(`run.mjs: 停它：${ON_WINDOWS ? `taskkill /PID ${backendPid} /T /F` : `kill ${backendPid}`}，或 node scripts/run.mjs --stop --port ${bound}`);
}
if (options.detach) {
  console.log("run.mjs: 它是**孤儿**式起的（setsid -f）：跑脚本的那个 clj-harness 会话退出，收不到它");
} else {
  console.log("run.mjs: 它是这个脚本的后代：跑脚本的那个 clj-harness 会话退出时，它会被一起收掉");
  console.log("run.mjs: 要它活过那个会话，加 --detach（生下来就脱离），或在自己的终端里跑 clojure -M:run");
}
if (options.foreground) {
  // 前台的整个意思就是「这个脚本是那个终端到后端的一个窗口」：它活到后端退出为止，后端怎么退的，
  // 它怎么退。信号不在这里接 —— 为什么不接写在 `runInForeground` 上。
  const code = await exitOf(backend);
  console.log(`run.mjs: 后端退了（退出码 ${code}），脚本跟着退`);
  process.exit(code);
}
process.exit(0);
