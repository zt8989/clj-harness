(ns harness.kernel.event-test
  (:require [clojure.test :refer [deftest is testing]]
            [harness.kernel.event :as ev]))

(deftest tool-lifecycle-shapes
  (is (= {:type :tool/pre-execute :id "c" :name "read" :outcome :pass :missing []}
         (ev/tool-pre-execute "c" "read" :pass [])))
  (is (= {:type :tool/pre-execute :id "c" :name "read" :outcome :missing-args :missing [:path]}
         (ev/tool-pre-execute "c" "read" :missing-args [:path])))
  (is (= {:type :tool/execute :id "c" :name "read" :error nil}
         (ev/tool-executed "c" "read" nil)))
  (is (= {:type :tool/execute :id "c" :name "read" :error "boom"}
         (ev/tool-executed "c" "read" "boom")))
  (is (= {:type :tool/post-execute :id "c" :name "read"}
         (ev/tool-post-execute "c" "read"))))

(deftest model-call-shapes
  ;; The pair that brackets every model call. The start carries the call's IDENTITY
  ;; and the tool table that went out; the end carries what the vendor reported; both
  ;; are audit-only (no wire frame), and the read side pairs them BY ORDER.
  (testing "the start keeps the three knobs that say who this call went to"
    (is (= {:type :model/start :model "deepseek-chat" :base-url "https://api.example/v1"}
           (ev/model-start {:model "deepseek-chat" :base-url "https://api.example/v1"} nil)))
    (testing "and :reasoning-effort only when the session has one -- 'no such key' is
              how 'this request is not in thinking mode' is said"
      (is (= {:type :model/start :model "m" :base-url "u" :reasoning-effort "high"}
             (ev/model-start {:model "m" :base-url "u" :reasoning-effort "high"} nil))))
    (testing ":context-window when the catalog declared one -- the denominator the
              composer's context ring divides this call's prompt by"
      ;; Recorded on the CALL rather than left to be re-resolved at read time: the
      ;; window in force for THIS call is the only one that divides THIS call's
      ;; prompt, and a session can be switched to another model between calls.
      (is (= {:type :model/start :model "m" :context-window 262144}
             (ev/model-start {:model "m" :context-window 262144} nil)))
      (testing "and a model nobody gave a window writes no such key -- absent, not nil"
        (is (= {:type :model/start :model "m"} (ev/model-start {:model "m"} nil)))))
    (testing "a provider that names nothing leaves the line naming nothing"
      ;; The scripted pin is exactly that provider: it has no :model and no
      ;; :base-url. Writing nulls would say 'the model is null', which is a
      ;; different (and false) statement from 'this call did not say who it went to'.
      (is (= {:type :model/start} (ev/model-start {:protocol :fake} nil))))
    (testing "the provider's other keys are not the call's identity and are not copied"
      (let [ev (ev/model-start {:model "m" :base-url "u" :api-key "SECRET" :input [:text]} nil)]
        (is (not (contains? ev :api-key)))
        (is (not (contains? ev :input))))))

  (testing "the request's tool table is on the line, VERBATIM, and only when there is one"
    ;; The table that went out is the table recorded: the caller hands in the very
    ;; value it put in the request body, so 'described exactly like this' is a fact
    ;; about the request rather than a second resolution that happens to agree.
    (let [specs [{:type "function"
                  :function {:name "read" :description "Read a file" :parameters {}}}]]
      (is (= specs (:tools (ev/model-start {:model "m"} specs)))))
    (is (= {:type :model/start :model "m"} (ev/model-start {:model "m"} []))
        "a call with no tools says nothing about tools -- an empty array is not a fact")
    (is (= {:type :model/start :model "m"} (ev/model-start {:model "m"} nil))))

  (testing "the end carries the vendor's report verbatim, keys and all"
    (is (= {:type :model/end :usage {:total_tokens 1093} :finish-reason "tool_calls"}
           (ev/model-end {:usage {:total_tokens 1093} :finish-reason "tool_calls"})))
    (testing "and an empty report is still an event -- a call that died mid-stream
              says 'nothing was reported', which is not the same as zeroes"
      (is (= {:type :model/end} (ev/model-end nil)))
      (is (= {:type :model/end} (ev/model-end {}))))))

(deftest shapes
  (is (= {:type :run/start} (ev/run-start)))
  (is (= {:type :text/delta :text "a"} (ev/text-delta "a")))
  (is (= {:type :reasoning/delta :text "r"} (ev/reasoning-delta "r")))
  (is (= {:type :tool/call :id "c" :name "read" :args "{}"}
         (ev/tool-call "c" "read" "{}")))
  (is (= {:type :tool/result :id "c" :content "x" :error false}
         (ev/tool-result "c" "x" false)))
  (is (= {:type :run/end} (ev/run-end)))
  (is (= {:type :run/error :message "boom"} (ev/run-error "boom"))))
