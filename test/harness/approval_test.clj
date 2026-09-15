(ns harness.approval-test
  "Pre-tool human approval, kernel half: a marked call parks in the seam and the
  run ends on an interrupt instead of running the tool. Also the project fence
  (ticket 02): a BOUND session's file tools park on a path resolving outside
  the project directory and the configuration home -- same park, same
  interrupt, same resume, only the reason differs."
  (:require [clojure.core.async :as async]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [harness.ag-ui :as ag]
            [harness.fake :as fake]
            [harness.home :as home]
            [harness.loop :as loop]
            [harness.project :as project]
            [harness.tools :as tools]
            [harness.wire :as wire]))

(def ^:private dir (str (System/getProperty "java.io.tmpdir") "/harness-approval-test"))

(defn- drain [ch]
  (loop [acc []]
    (if-let [ev (async/<!! ch)]
      (if (= :run/done (:type ev))
        {:history (:history ev) :seen acc}
        (recur (conj acc ev)))
      {:history nil :seen acc})))

(def ^:private lifecycle #{:tool/pre-execute :tool/execute :tool/post-execute})

(defn- phases [seen] (filterv #(contains? lifecycle (:type %)) seen))
(defn- results [seen] (filterv #(= :tool/result (:type %)) seen))
(defn- tool-msgs [history] (filterv #(= "tool" (:role %)) history))
(defn- run
  ([provider messages thread-id] (run provider messages thread-id nil))
  ([provider messages thread-id opts]
   (drain (loop/run-chan provider messages (merge {:thread-id thread-id} opts)))))

(defn- interrupt-id
  "The interrupt id of a park run's terminal event."
  [park-run]
  (:id (first (:interrupts (last (:seen park-run))))))

(defn- call [id name arguments]
  {:id id :name name :arguments arguments})

(deftest a-marked-call-parks-instead-of-running
  (let [thr  "thr-park-1"
        path (str dir "/parked.txt")
        _    (io/delete-file path true)
        _    (tools/session-require-approval! thr "write")
        {:keys [seen history]}
        (run (fake/scripted [{:content ""
                              :tool-calls [(call "c1" "write" {:path path :content "hello"})]}
                             {:content "done"}])
             [] thr)
        term (last seen)]

    (testing "the run ends on an interrupt, never on a run end"
      (is (= :run/interrupt (:type term)))
      (is (not-any? #(= :run/end (:type %)) seen)))

    (testing "the tool never ran and nothing was reported as its result"
      (is (false? (.exists (io/file path))))
      (is (empty? (results seen))))

    (testing "the seam parked at pre and closed its transit at post, no execute phase"
      (is (= [:tool/pre-execute :tool/post-execute] (mapv :type (phases seen))))
      (is (= :needs-approval (:outcome (first (phases seen))))))

    (testing "the interrupt names the call, and memory holds the parked record"
      (let [[int] (:interrupts term)]
        (is (= "c1" (:tool-call-id int)))
        (is (= "write" (:name int)))
        (is (string? (:id int)))
        (is (= thr (:thread-id (tools/parked (:id int)))))
        (is (= "c1" (:tool-call-id (tools/parked (:id int)))))
        (is (= 1 (count (tools/parked-calls thr))))))

    (testing "the parked call is left unanswered in the history"
      (is (some #(seq (:tool_calls %)) history))
      (is (empty? (tool-msgs history))))))

(deftest a-tool-can-declare-its-own-approval-requirement
  (let [thr "thr-park-flag"
        ran (atom false)]
    (tools/session-register! thr "probe"
      {:description "A probe." :requires-approval true
       :parameters {:type "object" :properties {} :required []} :required []
       :run (fn [_] (reset! ran true) "ran")})

    (testing "the flag is a harness concern, not part of the provider's tool schema"
      (let [spec (first (filter #(= "probe" (get-in % [:function :name]))
                                (tools/specs thr)))]
        (is (= #{:name :description :parameters} (set (keys (:function spec)))))))

    (testing "a declared requirement parks the call without a session opt-in"
      (let [{:keys [seen]} (run (fake/scripted [{:content ""
                                                 :tool-calls [(call "c1" "probe" {})]}
                                                {:content "done"}])
                                [] thr)]
        (is (= :run/interrupt (:type (last seen))))
        (is (false? @ran))))))

(deftest unmarked-tools-are-untouched
  (let [{:keys [seen history]}
        (run (fake/scripted [{:content ""
                              :tool-calls [(call "c1" "read" {:path "deps.edn"})]}
                             {:content "done"}])
             [] "thr-plain")]
    (is (= ["c1"] (mapv :id (results seen))))
    (is (= :run/end (:type (last seen))))
    (is (= 1 (count (tool-msgs history))))))

(deftest a-mixed-turn-parks-only-the-marked-call
  (let [thr  "thr-park-mixed"
        path (str dir "/mixed.txt")
        _    (io/delete-file path true)
        _    (tools/session-require-approval! thr "write")
        {:keys [seen history]}
        (run (fake/scripted [{:content ""
                              :tool-calls [(call "c1" "read" {:path "deps.edn"})
                                           (call "c2" "write" {:path path :content "x"})]}
                             {:content "done"}])
             [] thr)]
    (testing "the unmarked call of the same turn still runs"
      (is (= ["c1"] (mapv :id (results seen))))
      (is (= "c1" (:tool_call_id (first (tool-msgs history))))))

    (testing "the marked one parks: unanswered, and the file was never written"
      (is (= :run/interrupt (:type (last seen))))
      (is (= ["c2"] (mapv :tool-call-id (:interrupts (last seen)))))
      (is (false? (.exists (io/file path))))
      (is (= 1 (count (tool-msgs history)))))))

(deftest approval-is-session-scoped
  (tools/session-require-approval! "thr-a" "read")
  (is (true? (tools/session-approval-required? "thr-a" "read")))
  (is (false? (tools/session-approval-required? "thr-b" "read")))
  (let [{:keys [seen]}
        (run (fake/scripted [{:content ""
                              :tool-calls [(call "c1" "read" {:path "deps.edn"})]}
                             {:content "done"}])
             [] "thr-b")]
    (is (= :run/end (:type (last seen))))))

(deftest a-parked-run-reaches-the-wire-as-an-interrupt
  (let [thr    "thr-park-wire"
        emit   (ag/outbound thr "run-w")
        frames (atom [])
        _      (tools/session-require-approval! thr "read")]
    (doseq [event (:seen (run (fake/scripted [{:content ""
                                               :tool-calls [(call "c1" "read" {:path "deps.edn"})]}])
                              [] thr))]
      (swap! frames into (emit event)))
    (let [last-f (last @frames)]
      (is (empty? (wire/violations @frames)))
      (is (= "RUN_FINISHED" (:type last-f)))
      (is (= "interrupt" (get-in last-f [:outcome :type])))
      (is (= ["c1"] (mapv :toolCallId (get-in last-f [:outcome :interrupts]))))
      (is (not-any? #(= "TOOL_CALL_RESULT" (:type %)) @frames)))))

;; ------------------------------------------------------------- resume replay

(deftest an-approved-call-runs-on-resume
  (let [thr  "thr-resume-yes"
        path (str dir "/resumed.txt")
        _    (io/delete-file path true)
        _    (tools/session-require-approval! thr "write")
        park (run (fake/scripted [{:content ""
                                   :tool-calls [(call "c1" "write" {:path path :content "approved!"})]}
                                  {:content "never reached"}])
                  [] thr)
        iid  (interrupt-id park)
        ;; captured while parked: the let bindings below all evaluate before any
        ;; assertion runs, so the file's state has to be sampled here.
        written-while-parked? (.exists (io/file path))
        {:keys [seen history]}
        (run (fake/scripted [{:content "finished"}]) (:history park) thr
             {:resume [{:interrupt-id iid :verdict :approved}]})]

    (testing "the park run itself did not write"
      (is (false? written-while-parked?)))

    (testing "the approved call really runs this time"
      (is (true? (.exists (io/file path))))
      (is (= "approved!" (slurp path :encoding "UTF-8"))))

    (testing "its result is reported and answered in the history"
      (is (= ["c1"] (mapv :id (results seen))))
      (is (= "c1" (:tool_call_id (first (tool-msgs history))))))

    (testing "the lifecycle shows the second transit as approved"
      (is (= [:tool/pre-execute :tool/execute :tool/post-execute]
             (mapv :type (phases seen))))
      (is (= :approved (:outcome (first (phases seen))))))

    (testing "the run finishes naturally"
      (is (= :run/end (:type (last seen)))))))

(deftest a-vetoed-call-is-answered-without-running
  (let [thr  "thr-resume-no"
        path (str dir "/vetoed.txt")
        _    (io/delete-file path true)
        _    (tools/session-require-approval! thr "write")
        park (run (fake/scripted [{:content ""
                                   :tool-calls [(call "c1" "write" {:path path :content "nope"})]}])
                  [] thr)
        iid  (interrupt-id park)
        {:keys [seen history]}
        (run (fake/scripted [{:content "understood"}]) (:history park) thr
             {:resume [{:interrupt-id iid :verdict :vetoed :payload {:reason "too risky"}}]})]

    (testing "the tool never ran"
      (is (false? (.exists (io/file path)))))

    (testing "the veto is the answer the model gets, reason included"
      (let [result (first (results seen))]
        (is (= "c1" (:id result)))
        (is (true? (:error result)))
        (is (str/includes? (:content result) "vetoed by human"))
        (is (str/includes? (:content result) "too risky")))
      (is (= (:content (first (results seen))) (:content (first (tool-msgs history))))))

    (testing "a veto has no execute phase, and the lifecycle still closes"
      (is (= [:tool/pre-execute :tool/post-execute] (mapv :type (phases seen))))
      (is (= :vetoed (:outcome (first (phases seen))))))

    (testing "the run carries on rather than failing"
      (is (= :run/end (:type (last seen)))))))

(deftest a-decision-cannot-be-spent-twice
  (let [thr "thr-resume-once"
        n   (atom 0)]
    (tools/session-register! thr "tick"
      {:description "Counts." :parameters {:type "object" :properties {} :required []}
       :required [] :run (fn [_] (swap! n inc) "tick")})
    (tools/session-require-approval! thr "tick")
    (let [park   (run (fake/scripted [{:content "" :tool-calls [(call "c1" "tick" {})]}]) [] thr)
          iid    (interrupt-id park)
          decide (fn [] (run (fake/scripted [{:content "ok"}]) (:history park) thr
                             {:resume [{:interrupt-id iid :verdict :approved}]}))
          first-run  (decide)
          second-run (decide)]
      (is (= :run/end (:type (last (:seen first-run)))))
      (is (= 1 @n))
      (testing "the spent verdict approves nothing: the call parks for a fresh decision"
        (is (= :run/interrupt (:type (last (:seen second-run))))))
      (is (= 1 @n)))))

(deftest an-unknown-interrupt-is-an-error-not-an-approval
  (let [{:keys [seen]} (run (fake/scripted [{:content "ok"}]) [] "thr-unknown"
                            {:resume [{:interrupt-id "never-parked" :verdict :approved}]})]
    (is (= [:run/start :run/error] (mapv :type seen)))
    (is (str/includes? (:message (last seen)) "unknown interrupt"))))

(deftest a-mixed-decision-list-is-answered-in-call-order
  (let [thr  "thr-resume-mixed"
        path (str dir "/mixed-resume.txt")
        _    (io/delete-file path true)
        _    (tools/session-require-approval! thr "read")
        _    (tools/session-require-approval! thr "write")
        park (run (fake/scripted [{:content ""
                                   :tool-calls [(call "c1" "read" {:path "deps.edn"})
                                                (call "c2" "write" {:path path :content "ok"})]}])
                  [] thr)
        [i1 i2] (mapv :id (:interrupts (last (:seen park))))
        {:keys [seen history]}
        (run (fake/scripted [{:content "done"}]) (:history park) thr
             {:resume [{:interrupt-id i1 :verdict :vetoed}
                       {:interrupt-id i2 :verdict :approved}]})]

    (is (= :run/end (:type (last seen))))
    (testing "the approved call ran, the vetoed one did not"
      (is (true? (.exists (io/file path))))
      (let [by-call (into {} (map (juxt :id identity) (results seen)))]
        (is (str/includes? (:content (by-call "c1")) "vetoed by human"))
        (is (str/includes? (:content (by-call "c2")) "wrote"))))

    (testing "the history answers both calls in call order"
      (is (= ["c1" "c2"] (mapv :tool_call_id (tool-msgs history)))))))

(deftest unknown-interrupts-are-not-invented
  (is (nil? (tools/parked "no-such-interrupt")))
  (is (empty? (tools/parked-calls "thr-that-never-parked"))))

;; ----------------------------------------------------------------- the fence
;;
;; Ticket 02: when a session is BOUND to a project directory, a file tool whose
;; path resolves outside the project directory AND the configuration home parks
;; for approval. Everything about the park is the ordinary approval flow -- the
;; fence only chooses WHICH calls enter it, and stamps :reason :out-of-bounds
;; on the parked record. bash is deliberately untouched: it is constrained to
;; the project cwd but its command content is never judged, a DECLARED escape
;; surface (approval is a workflow convention, not a security boundary).

(defn- rm-r!
  "Recursive delete, deepest first (io/delete-file cannot remove a non-empty
  directory, and java.io.tmpdir outlives the JVM -- the replay-test lesson)."
  [d]
  (run! #(.delete ^java.io.File %)
        (sort-by (fn [^java.io.File f] (count (.getPath f))) >
                 (file-seq (io/file d)))))

(defn- fence-rig
  "Bind THR to a fresh project directory holding one readable file."
  [thr]
  (let [pdir (str dir "/fence-project")]
    ;; rm-r first: tmpdir survives across JVM runs, and a leftover
    ;; .harness/harness.edn from the ticket-03 tests would silently re-tighten
    ;; or re-widen the fence for these ticket-02 tests.
    (rm-r! pdir)
    (.mkdirs (io/file pdir))
    (spit (str pdir "/inside.txt") "inside" :encoding "UTF-8")
    (project/bind! thr pdir)
    pdir))

(deftest a-bound-session-parks-an-out-of-bounds-path
  (let [thr     "thr-fence-park"
        _       (fence-rig thr)
        outside (str dir "/outside-the-fence.txt")
        {:keys [seen history]}
        (run (fake/scripted [{:content ""
                              :tool-calls [(call "c1" "read" {:path outside})]}
                             {:content "never reached"}])
             [] thr)
        term (last seen)]
    (testing "the run interrupts exactly like any approval"
      (is (= :run/interrupt (:type term)))
      (is (not-any? #(= :run/end (:type %)) seen)))
    (testing "the tool never ran, the call is unanswered"
      (is (empty? (results seen)))
      (is (empty? (tool-msgs history))))
    (testing "the interrupt carries the same facts as any approval"
      (let [[int]  (:interrupts term)
            parked (tools/parked (:id int))]
        (is (= "c1" (:tool-call-id int)))
        (is (= "read" (:name int)))
        (is (string? (:id int)))
        (is (= thr (:thread-id parked)))
        (testing "and the parked record is stamped with the fence reason"
          (is (= :out-of-bounds (:reason parked))))
        (testing "the path itself is the evidence the human sees"
          (is (str/includes? (:args parked) "outside-the-fence")))))))

(deftest inside-paths-and-the-config-home-run-without-parking
  (let [thr  "thr-fence-allow"
        pdir (fence-rig thr)
        {:keys [seen history]}
        (run (fake/scripted [{:content ""
                              :tool-calls [(call "c1" "read" {:path "inside.txt"})
                                           (call "c2" "read" {:path (str pdir "/inside.txt")})
                                           (call "c3" "read" {:path (str (io/file (home/root) "config.edn"))})]}
                             {:content "done"}])
             [] thr)]
    (testing "relative, absolute-in-project, and the config home all ran"
      (is (= :run/end (:type (last seen))))
      (is (= #{"c1" "c2" "c3"} (set (mapv :id (results seen)))))
      (is (= 3 (count (tool-msgs history)))))
    (testing "the config-home read got the real seeded config, not a refusal"
      (let [by-call (into {} (map (juxt :id identity) (results seen)))]
        (is (str/includes? (:content (by-call "c3")) ":protocol"))))))

(deftest fence-verdicts-follow-the-ordinary-resume
  ;; Approval overrides the fence: the human's yes stands even though the path
  ;; is still out of bounds on the resume transit. A veto is the ordinary veto:
  ;; no execution, the payload reason fed back to the model.
  (let [thr     "thr-fence-resume"
        _       (fence-rig thr)
        outside (str dir "/fence-approved.txt")
        park    (run (fake/scripted [{:content ""
                                      :tool-calls [(call "c1" "write" {:path outside :content "human said yes"})]}])
                     [] thr)
        iid     (interrupt-id park)
        yes     (run (fake/scripted [{:content "done"}]) (:history park) thr
                     {:resume [{:interrupt-id iid :verdict :approved}]})
        outside2 (str dir "/fence-vetoed.txt")
        park2   (run (fake/scripted [{:content ""
                                      :tool-calls [(call "c2" "write" {:path outside2 :content "human said no"})]}])
                     [] thr)
        iid2    (interrupt-id park2)
        no      (run (fake/scripted [{:content "understood"}]) (:history park2) thr
                     {:resume [{:interrupt-id iid2 :verdict :vetoed
                                :payload {:reason "stays inside the project"}}]})]
    (testing "an approved fence catch executes like :pass"
      (is (true? (.exists (io/file outside))))
      (is (= "human said yes" (slurp outside :encoding "UTF-8")))
      (is (= :run/end (:type (last (:seen yes)))))
      (is (= :approved (:outcome (first (phases (:seen yes)))))))
    (testing "a vetoed fence catch feeds the payload reason back"
      (let [result (first (results (:seen no)))]
        (is (true? (:error result)))
        (is (str/includes? (:content result) "vetoed by human"))
        (is (str/includes? (:content result) "stays inside the project")))
      (is (= :run/end (:type (last (:seen no))))))))

(deftest the-fence-never-engages-on-an-unbound-session
  ;; The regression guarantee, end to end: no binding, no fence. The same
  ;; outside path runs exactly as it did before the fence existed.
  (let [outside (str dir "/unbound-outside.txt")]
    (spit outside "reachable" :encoding "UTF-8")
    (let [{:keys [seen history]}
          (run (fake/scripted [{:content ""
                                :tool-calls [(call "c1" "read" {:path outside})]}
                               {:content "done"}])
               [] "thr-never-bound")]
      (is (= :run/end (:type (last seen))))
      (is (= "reachable" (:content (first (results seen)))))
      (is (= 1 (count (tool-msgs history)))))))

;; ------------------------------------------------- the fence, configurable
;;
;; Ticket 03: the project's .harness/harness.edn (over the config home's user
;; level) moves the fence -- :allow frees declared paths, :strict tightens it
;; over the project itself. Same park, same interrupt; only the allowed set
;; changes. Slash-form paths in the EDN: io/File accepts them on Windows and
;; EDN would need backslashes escaped anyway.

(defn- write-project-harness!
  "Drop an EDN string at the project's .harness/harness.edn."
  [pdir edn]
  (let [f (io/file pdir ".harness" "harness.edn")]
    (.mkdirs (.getParentFile f))
    (spit f edn :encoding "UTF-8")
    f))

(defn- slashed [p] (str/replace p "\\" "/"))

(deftest the-project-can-free-a-path-with-allow
  (let [thr   "thr-fence-allow-cfg"
        pdir  (fence-rig thr)
        freed (str dir "/freed-neighbor")
        _     (.mkdirs (io/file freed))
        _     (spit (str freed "/note.txt") "free to read" :encoding "UTF-8")
        _     (write-project-harness! pdir
                                      (str "{:approval {:allow [\"" (slashed freed) "\"]}}"))
        {:keys [seen history]}
        (run (fake/scripted [{:content ""
                              :tool-calls [(call "c1" "read" {:path (str freed "/note.txt")})]}
                             {:content "done"}])
             [] thr)]
    (testing "a path that was out of bounds runs, because the project freed it"
      (is (= :run/end (:type (last seen))))
      (is (= "free to read" (:content (first (results seen)))))
      (is (= 1 (count (tool-msgs history)))))))

(deftest strict-tightens-the-fence-over-the-project-itself
  (let [thr  "thr-fence-strict"
        pdir (fence-rig thr)
        _    (write-project-harness! pdir "{:approval {:strict true}}")
        {:keys [seen history]}
        (run (fake/scripted [{:content ""
                              :tool-calls [(call "c1" "read" {:path "inside.txt"})]}
                             {:content "never reached"}])
             [] thr)
        term (last seen)]
    (testing "an in-project read parks under strict -- the ordinary interrupt"
      (is (= :run/interrupt (:type term)))
      (is (empty? (results seen)))
      (is (empty? (tool-msgs history)))
      (is (= :out-of-bounds (:reason (tools/parked (:id (first (:interrupts term))))))))))

(deftest the-project-level-replaces-the-user-level-whole
  ;; The user level freed the whole project (:allow on pdir); the project's
  ;; own :approval {:strict true} REPLACES that whole key -- so the in-project
  ;; read parks. A deep merge or a union would free it again and this test
  ;; would catch it.
  (let [thr  "thr-fence-priority"
        pdir (fence-rig thr)
        uf   (io/file (home/root) "harness.edn")]
    (spit uf (str "{:approval {:allow [\"" (slashed pdir) "\"]}}") :encoding "UTF-8")
    (write-project-harness! pdir "{:approval {:strict true}}")
    (try
      (let [{:keys [seen]}
            (run (fake/scripted [{:content ""
                                  :tool-calls [(call "c1" "read" {:path "inside.txt"})]}
                                 {:content "never reached"}])
                 [] thr)]
        (is (= :run/interrupt (:type (last seen)))
            "the project's :approval replaced the user's -- the allow is gone"))
      (finally
        (io/delete-file uf true)))))

(deftest reading-a-skills-reference-file-does-not-park
  ;; End to end through the seam that actually decides: a skill body says "read
  ;; references/x.md", that path resolves next to the skill and outside the
  ;; project, and a park per reference file would make loading a skill useless.
  (let [proj      (str (System/getProperty "java.io.tmpdir")
                       "/harness-approval-proj-" (System/nanoTime))
        skill-dir (str (io/file (home/user-home) ".agents" "skills" "alpha"))
        ref-file  (str (io/file skill-dir "references" "x.md"))
        elsewhere (str (System/getProperty "java.io.tmpdir")
                       "/harness-approval-elsewhere-" (System/nanoTime))]
    (.mkdirs (io/file proj))
    (.mkdirs (io/file (io/file skill-dir "references")))
    (.mkdirs (io/file elsewhere))
    (spit ref-file "reference material\n" :encoding "UTF-8")
    (spit (str (io/file elsewhere "x.md")) "not a skill file\n" :encoding "UTF-8")
    (project/bind! "thr-skill-fence" proj)
    (try
      (testing "a read of a file under a skill root runs without parking"
        (let [{:keys [history]}
              (run (fake/scripted [{:content ""
                                    :tool-calls [(call "c1" "read" {:path ref-file})]}
                                   {:content "ok"}])
                   [] "thr-skill-fence")
              results (filter #(= "tool" (:role %)) history)]
          (is (= 1 (count results)))
          (is (str/includes? (:content (first results)) "reference material"))
          (is (not (str/includes? (:content (first results)) "vetoed")))))

      (testing "and the same read one directory over still parks"
        (let [{:keys [history]}
              (run (fake/scripted [{:content ""
                                    :tool-calls [(call "c2" "read" {:path (str (io/file elsewhere "x.md"))})]}
                                   {:content "ok"}])
                   [] "thr-skill-fence")]
          ;; Parked calls are left unanswered -- no tool message at all.
          (is (empty? (filter #(= "tool" (:role %)) history)))))
      (finally
        (project/bind! "thr-skill-fence" nil)
        (doseq [d [proj skill-dir elsewhere]] (io/delete-file (io/file d) true))))))
