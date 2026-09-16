(ns harness.repl
  "The daily shape of this project: start the server, then land in a REPL that is
  already inside the running process -- so the kernel can be rewritten with
  (require 'harness.kernel.loop :reload) while the server keeps serving.

      clojure -M:repl

  The console encoding here is a separate matter from the wire: this machine's JVM
  defaults to GBK, so if Chinese output prints as ? you need
      clojure '-J-Dstdout.encoding=UTF-8' -M:repl
  (deps.clj ignores :jvm-opts, so it has to be passed by hand.)"
  (:require [clojure.main]
            [harness.edge.http :as http]))

(defn -main [& _]
  (def server (http/start!))
  (println "in the REPL: (server) stops the server; (require 'harness.kernel.loop :reload) swaps the kernel")
  (clojure.main/repl))
