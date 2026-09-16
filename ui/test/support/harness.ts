// Starting a harness for a test run, and stopping it again.
//
// The suites drive a REAL harness over real HTTP -- that is the point of them --
// but nothing about that needs the developer's own server, the developer's
// ~/.clj-harness, an api-key, or a model. So this spawns one whose provider is
// `harness.fake`'s scripted double, on a port the OS picks, writing its logs into
// a temp directory. Two consequences worth stating because they are what makes
// the suite trustworthy: every run replays identically, and `npm test` cannot be
// satisfied by a stale server that happened to be listening on 8080.
//
// The control channel is a FILE, not an endpoint. The server re-reads it whenever
// a new thread id arrives (see dev/harness/e2e_server.clj), so a test says what
// the model will reply by writing bytes both processes can see -- and the
// production HTTP edge grows no test-only route.
//
// No Java is looked up here. The retired ClojureScript compiler needed a JDK 21,
// which is why this used to hunt for one; that toolchain is gone, and the backend
// runs on whatever `java` is on PATH (the repo targets 17). The child simply
// inherits the ambient environment.
import { spawn, type ChildProcess } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = path.resolve(HERE, "..", "..", "..");

// `-M:dev` is the alias that puts dev/ on the classpath, which is where
// harness.e2e-server lives. It is also where harness.fake lives, which the
// server requires -- a deliberate dev-only dependency.
const E2E_ARGS = ["-M:dev", "-m", "harness.e2e-server"];

// A config.edn the server can resolve, in the INLINE form so it needs no
// providers.edn. It has to be the COMPLETE inline form: the provider catalog
// validates an inline description and refuses one that names no :base-url or
// :model, and that validation runs even when a script is pinned -- harness.http
// resolves the provider for the audit timeline on every run.
//
// It declares NO modalities on purpose: an inline provider that says nothing
// about what it accepts is not guarded (harness.ag-ui/undeclared-input?), and a
// seed must not make every text-only test fail for a reason the test never stated.
const SEED_CONFIG = '{:protocol :fake :base-url "http://offline.invalid/v1" :model "seeded"}\n';

export interface Harness {
  url: string;
  scriptPath: string;
  /** The config root (CLJ_HARNESS_HOME). The session logs live under it. */
  home: string;
  /**
   * The OS home -- the host's convention directory, where `~/AGENTS.md` and
   * `~/.agents/skills` are read from. Handed back because a check that wants a
   * SYSTEM-LEVEL skill in the catalogue has to write one before a run reads it,
   * and the server pins this to a temp directory of its own unless the spawner
   * names one (see `--user-home`). An empty user home is the normal case: the
   * suites plant nothing unless they mean to.
   */
  userHome: string;
  stop: () => Promise<void>;
  startup: string;
  stderr: () => string;
}

function seedHome(dir: string): void {
  // Only the config. The store (`harness.db`) and the projects tree are both
  // created on demand by whichever side writes first, so seeding either here
  // would be this file guessing at a layout that already has one owner.
  fs.writeFileSync(path.join(dir, "config.edn"), SEED_CONFIG, "utf8");
}

interface Ready {
  port: number;
  output: string;
}

function waitForReady(proc: ChildProcess, timeoutMs: number): Promise<Ready> {
  return new Promise<Ready>((resolve, reject) => {
    const stdout = proc.stdout;
    if (stdout === null) {
      reject(new Error("harness.e2e-server was spawned without a stdout pipe to read PRINT-READY from"));
      return;
    }

    let buf = "";
    const timer = setTimeout(() => {
      reject(new Error(`harness.e2e-server did not print PRINT-READY within ${timeoutMs}ms; output so far:\n${buf}`));
    }, timeoutMs);

    const onData = (chunk: Buffer) => {
      buf += chunk.toString();
      const m = buf.match(/PRINT-READY \{:port (\d+)\}/);
      if (m) {
        clearTimeout(timer);
        stdout.off("data", onData);
        resolve({ port: Number(m[1]), output: buf });
      }
    };
    stdout.on("data", onData);
    proc.once("exit", (code) => {
      clearTimeout(timer);
      reject(new Error(`harness.e2e-server exited ${code} before becoming ready; output:\n${buf}`));
    });
    proc.once("error", (e) => {
      clearTimeout(timer);
      reject(new Error(`could not spawn clojure: ${e.message}\nIs the Clojure CLI installed and on PATH?`));
    });
  });
}

/**
 * Start one harness. Resolves to {url, scriptPath, home, userHome, stop, startup, stderr}.
 *
 * `url` and `scriptPath` are what the suites consume: the e2e helpers post to the
 * former and write the latter as a case decides what the model should say.
 * `home` is handed back too, because the server's jsonl lands under
 * `<home>/projects/<workspace>` -- one workspace per project, plus `.unbound` for
 * a session that has no project -- should a test ever want to read it; `startup`
 * and `stderr` are the child's captured output, kept for a failure message rather
 * than a test's assertion.
 *
 * `userHome` is the SECOND home, and it is a SIBLING of the first rather than a
 * directory inside it. That is not tidiness: the config root is in the fence's
 * allowed set, so a user home nested under it would answer a question the fence
 * checks ask. It is passed to the child as `--user-home`, so the directory this
 * spawner made is the one the server actually reads -- a check can plant a
 * system-level skill in it and see that skill arrive.
 */
export async function startHarness({ timeoutMs = 120_000 }: { timeoutMs?: number } = {}): Promise<Harness> {
  const base = fs.mkdtempSync(path.join(os.tmpdir(), "clj-harness-ui-test-"));
  const home = path.join(base, "home");
  const userHome = path.join(base, "user-home");
  const scriptPath = path.join(base, "script.json");
  fs.mkdirSync(home, { recursive: true });
  fs.mkdirSync(userHome, { recursive: true });
  seedHome(home);
  fs.writeFileSync(scriptPath, JSON.stringify({ turns: [] }), "utf8");

  const env = { ...process.env, CLJ_HARNESS_HOME: home };

  // stdio: the child's startup output is captured for the failure message rather
  // than streamed, so `npm test` output stays the tests'. Its stderr is kept
  // separate for the same reason -- a Clojure warning must not look like a test
  // problem.
  const proc = spawn(
    "clojure",
    [...E2E_ARGS, "--port", "0", "--script-file", scriptPath, "--user-home", userHome],
    {
      cwd: REPO_ROOT,
      env,
      stdio: ["ignore", "pipe", "pipe"],
    },
  );
  let stderr = "";
  if (proc.stderr !== null) {
    proc.stderr.on("data", (chunk: Buffer) => {
      stderr += chunk.toString();
    });
  }

  const { port, output } = await waitForReady(proc, timeoutMs);

  // Stops the server AND removes its temp directory. The removal waits for the
  // process to actually exit: the JVM's shutdown hook closes the socket on
  // SIGTERM, and deleting the log directory out from under a still-running
  // server would leave the directory behind anyway (or resurrect it). The
  // timeout is a floor, not a promise -- a JVM that refuses to die must not hang
  // the whole suite, so the directory is attempted regardless and a leftover is
  // reported.
  const stop = async (): Promise<void> => {
    if (proc.exitCode === null && proc.signalCode === null) {
      await new Promise<void>((resolve) => {
        const done = () => resolve();
        proc.once("exit", done);
        proc.kill("SIGTERM");
        setTimeout(done, 5000);
      });
    }
    try {
      // Retry once: on Windows the JVM's file handles may take a moment to drop.
      try {
        fs.rmSync(base, { recursive: true, force: true });
      } catch {
        await new Promise((r) => setTimeout(r, 250));
        fs.rmSync(base, { recursive: true, force: true });
      }
    } catch (e) {
      console.warn(`warning: could not remove the test home ${base}: ${(e as Error).message}`);
    }
  };

  return {
    url: `http://localhost:${port}/`,
    scriptPath,
    home,
    userHome,
    stop,
    startup: output,
    stderr: () => stderr,
  };
}
