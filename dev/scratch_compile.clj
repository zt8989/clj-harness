(ns scratch-compile
  "A fast gate for a change that reaches across namespaces: require what the change touched, so a
  missing require or a stray paren is a failure in ten seconds rather than three minutes into a
  suite. Isolates first, per `docs/rules/testing.md` -- requiring the edge resolves a config root."
  (:require [harness.test-runner :as tr]
            [harness.kernel.session]
            [harness.edge.sessions]
            [harness.edge.record]
            [harness.edge.http]))

(tr/isolate!)
(println :compiled)
