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
  ;; and the end carries what the vendor reported; both are audit-only (no wire
  ;; frame), and the read side pairs them BY ORDER.
  (testing "the start keeps the three knobs that say who this call went to"
    (is (= {:type :model/start :model "deepseek-chat" :base-url "https://api.example/v1"}
           (ev/model-start {:model "deepseek-chat" :base-url "https://api.example/v1"})))
    (testing "and :reasoning-effort only when the session has one -- 'no such key' is
              how 'this request is not in thinking mode' is said"
      (is (= {:type :model/start :model "m" :base-url "u" :reasoning-effort "high"}
             (ev/model-start {:model "m" :base-url "u" :reasoning-effort "high"}))))
    (testing "a provider that names nothing leaves the line naming nothing"
      ;; The scripted pin is exactly that provider: it has no :model and no
      ;; :base-url. Writing nulls would say 'the model is null', which is a
      ;; different (and false) statement from 'this call did not say who it went to'.
      (is (= {:type :model/start} (ev/model-start {:protocol :fake}))))
    (testing "the provider's other keys are not the call's identity and are not copied"
      (let [ev (ev/model-start {:model "m" :base-url "u" :api-key "SECRET" :input [:text]})]
        (is (not (contains? ev :api-key)))
        (is (not (contains? ev :input))))))

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
