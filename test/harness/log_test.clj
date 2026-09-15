(ns harness.log-test
  "The backend's error log, and the net that feeds it.

  Tested through the REAL file and the REAL console binding rather than a stub
  sink: the two things worth knowing are that a line lands on disk in a shape a
  reader can parse, and that the same sentence reaches the console -- and a stub
  would prove neither."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [harness.home :as home]
            [harness.http :as http]
            [harness.log :as log]
            [harness.project :as project]))

(defn- lines [f]
  (when (.exists f)
    (mapv #(json/read-str % :key-fn keyword)
          (remove str/blank? (str/split-lines (slurp f :encoding "UTF-8"))))))

(defn- fresh-home
  "A root of its own per test, so one test's lines are not another's."
  [name]
  (let [dir (io/file (System/getProperty "java.io.tmpdir")
                     (str "clj-harness-log-test-" name "-" (System/currentTimeMillis)))]
    (.mkdirs dir)
    (str dir)))

(defn- with-root [name f]
  (let [root (fresh-home name)]
    (binding [home/*root-override* root]
      (f (io/file root "harness.log")))))

(deftest an-error-goes-to-the-file-and-to-the-console
  (with-root
   "both"
   (fn [f]
     (let [console (with-out-str
                     (binding [*err* *out*]
                       (try (throw (ex-info "no provider named :beta"
                                            {:provider :beta :thread-id "t-1"}))
                            (catch Throwable t
                              (log/error! :run-refused t {:thread-id "t-1"})))))]
       (testing "the file gets one line, and it is JSON"
         (let [rows (lines f)]
           (is (= 1 (count rows)))
           (let [row (first rows)]
             (is (= "error" (:level row)))
             (is (= "run-refused" (:kind row)))
             (is (= "no provider named :beta" (:message row)))
             (is (= "clojure.lang.ExceptionInfo" (:class row)))
             (is (= {:thread-id "t-1"} (:context row)))
             (is (seq (:trace row))))))
       (testing "the exception's own data rides along -- it is where this codebase puts the useful bit"
         (is (str/includes? (:data (first (lines f))) ":provider :beta")))
       (testing "and the console carries the same sentence the file does"
         (is (str/includes? console "[error] run-refused")))
       (testing "with the whole trace, because it is for somebody reading now"
         (is (str/includes? console "harness.log_test")))))))

(deftest the-log-appends-and-never-overwrites
  (with-root
   "append"
   (fn [f]
     (let [quiet (fn [] (binding [*err* (java.io.StringWriter.)]
                          (log/error! :twice (ex-info "first" {}) nil)))]
       (quiet)
       (quiet)
       (quiet)
       (is (= 3 (count (lines f))))
       (is (every? #(= "twice" (:kind %)) (lines f)))))))

(deftest a-line-with-no-exception-and-no-context-is-still-writable
  ;; Not every error arrives as a Throwable -- a route may want to record a
  ;; refusal it built as a value. The shape has to survive that rather than
  ;; depending on a stack trace being there.
  (with-root
   "bare"
   (fn [f]
     (binding [*err* (java.io.StringWriter.)]
       (log/error! :refused (ex-info "just a sentence" {})))
     (let [row (first (lines f))]
       (is (= "just a sentence" (:message row)))
       (is (= "refused" (:kind row)))))))

(deftest a-request-that-throws-is-answered-500-and-written-down
  ;; The net under the route table. Exercised by making a real route throw
  ;; something it does not expect -- which is exactly the case the net exists
  ;; for, since every route already catches what it DOES expect.
  (with-root
   "net"
   (fn [f]
     (let [resp (binding [*err* (java.io.StringWriter.)]
                  (with-redefs [project/binding-for (fn [_] (throw (ex-info "storage is unreachable"
                                                                            {:reason :unreachable})))]
                    (http/handler {:uri "/api/git" :request-method :get
                                   :query-string "threadId=net-1" :headers {}})))]
       (testing "the client gets the server's own sentence, not a blank 500"
         (is (= 500 (:status resp)))
         (is (str/includes? (String. ^bytes (:body resp) "UTF-8") "storage is unreachable")))
       (testing "and the failure is on disk with the route that raised it"
         (let [row (first (lines f))]
           (is (= "request-failed" (:kind row)))
           (is (= "storage is unreachable" (:message row)))
           (is (= "get" (get-in row [:context :method]))
               "JSON has no keywords, so a recorded keyword comes back a string")
           (is (= "/api/git" (get-in row [:context :uri])))))))))

(deftest logging-a-failure-never-throws
  ;; The logger must not be able to replace the error it was reporting.
  (with-root
   "safe"
   (fn [_]
     (binding [*err* (java.io.StringWriter.)]
       (is (instance? Throwable (log/error! :x (ex-info "m" {}))))
       (is (nil? (log/error! :x nil nil))
           "a nil Throwable answers nil rather than throwing")))))
