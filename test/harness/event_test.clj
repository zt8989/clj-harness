(ns harness.event-test
  (:require [clojure.test :refer [deftest is]]
            [harness.event :as ev]))

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
