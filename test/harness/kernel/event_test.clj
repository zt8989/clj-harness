(ns harness.kernel.event-test
  (:require [clojure.test :refer [deftest is]]
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
