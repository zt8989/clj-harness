// Picking a Java for the child processes.
//
// The ClojureScript build needs Java 21 (shadow-cljs 3.x), while this project's
// backend runs happily on 17 -- and on a machine with several JDKs installed,
// `java` on PATH is usually the wrong one. So the test runner resolves it
// explicitly rather than hoping.
//
// $JAVA_HOME wins when it is already 21 or newer: an explicit choice beats any
// guess this file could make. Otherwise the platform's usual install locations
// are tried in order, and null means "use the ambient environment" -- the caller
// inherits PATH and the failure, if any, shows up in the child's own output
// rather than as a confusing error from here.
import { spawnSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";

function majorOf(home) {
  // `java -version` prints to STDERR and exits 0, so a successful call gives an
  // empty stdout -- the version is in `stderr`, whether the call succeeds or
  // fails. Reading only stdout reported "no Java 21" on a machine that has it,
  // and the build then ran under Java 17 and died with an
  // UnsupportedClassVersionError from Closure's own classes.
  const r = spawnSync(path.join(home, "bin", "java"), ["-version"], { encoding: "utf8" });
  const text = `${r.stderr || ""}${r.stdout || ""}`;
  const v = (text.match(/version "(\d+)/) || [])[1];
  return v ? Number(v) : null;
}

const CANDIDATES = [
  "/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home",
  "/usr/local/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home",
  "/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home",
];

function fromJavaVirtualMachines() {
  const dir = "/Library/Java/JavaVirtualMachines";
  if (!fs.existsSync(dir)) return [];
  return fs
    .readdirSync(dir)
    .filter((n) => /openjdk-2[1-9]|jdk-2[1-9]/.test(n))
    .map((n) => path.join(dir, n, "Contents", "Home"));
}

export function javaHome21() {
  const env = process.env.JAVA_HOME;
  if (env && majorOf(env) >= 21) return env;
  for (const home of [...CANDIDATES, ...fromJavaVirtualMachines()]) {
    if (fs.existsSync(home) && majorOf(home) >= 21) return home;
  }
  return null;
}
