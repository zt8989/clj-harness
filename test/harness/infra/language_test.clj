(ns harness.infra.language-test
  "harness.infra.language: which language this harness speaks, and the chain of sources
  that decides it.

  EACH LINK IS DRIVEN THROUGH ITS OWN SEAM rather than read back off this machine: the
  config link by writing a config.edn into a home of the test's own, the system link by
  watching the shell the OS is asked through, and precedence by standing all three links
  in and asserting the order. A case that asserted the machine's real answer could only
  ever assert the one machine the suite happens to run on."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [harness.infra.env :as env]
            [harness.infra.home :as home]
            [harness.infra.language :as language]
            [harness.infra.shell :as shell]
            [harness.test-support :as support]))

(defn- write-config!
  [text]
  (let [f (home/config-file)]
    (.mkdirs (.getParentFile f))
    (spit f text :encoding "UTF-8")))

(deftest a-tag-is-reduced-to-a-language-this-harness-speaks
  (testing "the base subtag decides, and the old spellings are the same tag"
    (doseq [[tag expected] {"zh"          :zh
                            "zh-CN"       :zh
                            "zh_TW"       :zh
                            "zh-Hans-CN"  :zh
                            "en"          :en
                            "en-US"       :en
                            "en_US.UTF-8" :en}]
      (is (= expected (language/base-language tag)) (pr-str tag))))
  (testing "a language nobody here speaks is 'no answer', not an error"
    (doseq [tag ["fr" "de-DE" "C" "POSIX" "" nil]]
      (is (nil? (language/base-language tag)) (pr-str tag)))))

(deftest the-config-file-names-the-language-or-says-nothing
  (support/with-temp-env [root home]
    (testing "no :ui section is no answer from this source"
      (write-config! "{:default {:provider :fake}}\n")
      (is (nil? (language/config-language))))
    (testing "a :language this harness speaks is the answer"
      (write-config! "{:ui {:language :zh}}\n")
      (is (= :zh (language/config-language))))
    (testing "a value nobody speaks is no answer HERE -- the file's shape names it"
      (write-config! "{:ui {:language :fr}}\n")
      (is (nil? (language/config-language))))
    (testing "and a file that will not parse is no answer either, not a throw"
      (write-config! "{:ui {:language\n")
      (is (nil? (language/config-language))))))

(deftest the-oss-own-language-is-asked-of-the-os
  (try
    (let [asked (atom [])]
      (with-redefs [env/platform (constantly "macos")
                    shell/run    (fn [req]
                                   (swap! asked conj req)
                                   {:exit 0
                                    :out  "(\n    \"zh-Hans\",\n    \"en-CN\"\n)\n"
                                    :err  ""})]
        (language/reset-system!)
        (is (= :zh (language/system-language)) "the first tag it can reduce is the answer")
        (is (= :zh (language/system-language)) "and it is remembered")
        (is (= 1 (count @asked)) "one spawn per process, then cached")
        (is (str/includes? (:command (first @asked)) "AppleLanguages")
            "it asks the OS, not this process's locale")))
    (finally (language/reset-system!))))

(deftest a-machine-that-will-not-answer-says-so-rather-than-guessing
  (testing "the OS command timed out"
    (try
      (with-redefs [env/platform (constantly "macos")
                    shell/run    (fn [_] {:exit nil :out "" :err "" :timeout true})]
        (language/reset-system!)
        (is (nil? (language/system-language))))
      (finally (language/reset-system!))))
  (testing "the shell would not start at all"
    (try
      (with-redefs [env/platform (constantly "macos")
                    shell/run    (fn [_] (throw (ex-info "no shell here" {})))]
        (language/reset-system!)
        (is (nil? (language/system-language))))
      (finally (language/reset-system!))))
  (testing "a platform with no shell-free answer for its own language"
    (try
      (with-redefs [env/platform (constantly "linux")]
        (language/reset-system!)
        (is (nil? (language/system-language))))
      (finally (language/reset-system!)))))

(deftest the-terminal-link-only-answers-with-a-language-this-harness-speaks
  (let [answer (language/terminal-language)]
    (is (or (nil? answer) (contains? language/supported answer))
        "whatever this machine's shell says, it is either a spoken language or no answer")))

(deftest the-chain-tries-each-source-in-order-and-ends-in-english
  (testing "config.edn wins over both"
    (with-redefs [language/config-language   (constantly :zh)
                  language/system-language   (constantly :en)
                  language/terminal-language (constantly :en)]
      (is (= :zh (language/resolved)))))
  (testing "then the OS's own language"
    (with-redefs [language/config-language   (constantly nil)
                  language/system-language   (constantly :zh)
                  language/terminal-language (constantly :en)]
      (is (= :zh (language/resolved)))))
  (testing "then the terminal's"
    (with-redefs [language/config-language   (constantly nil)
                  language/system-language   (constantly nil)
                  language/terminal-language (constantly :zh)]
      (is (= :zh (language/resolved)))))
  (testing "and English is a real answer, not an absence"
    (with-redefs [language/config-language   (constantly nil)
                  language/system-language   (constantly nil)
                  language/terminal-language (constantly nil)]
      (is (= :en (language/resolved))))))

(deftest the-line-names-the-value-and-what-to-call-it
  (with-redefs [language/config-language (constantly :zh)]
    (is (= "language: Chinese (zh)" (language/line))))
  (with-redefs [language/config-language   (constantly nil)
                language/system-language   (constantly nil)
                language/terminal-language (constantly nil)]
    (is (= "language: English (en)" (language/line))
        "the line never vanishes -- the chain's end is what gets stated")))
