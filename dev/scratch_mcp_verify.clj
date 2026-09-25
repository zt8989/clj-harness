;; Scratch verification of the MCP stdio quoting fix (delete after use).
;; Goes through the SAME protocol as -main: isolate! + run-suite! (which checks
;; that the developer's real home was not touched).
(require '[harness.test-runner])
(harness.test-runner/run-suite!
 '[harness.infra.shell-test
   harness.kernel.tools-test
   harness.cap.mcp-test
   harness.cap.mcp_wired-test])
