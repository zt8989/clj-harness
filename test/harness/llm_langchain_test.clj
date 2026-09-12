(ns harness.llm-langchain-test
  "Offline test for the :langchain4clj skeleton. The blocking chat call is
  stubbed, so no network or API key is needed. Locks the text path's provider
  shape; reasoning/tools parity is asserted by the feature tickets, not here."
  (:require [clojure.test :refer [deftest is testing]]
            [harness.llm :as llm]
            [harness.llm-langchain]
            [langchain4clj.core :as lc]))

(deftest blocking-chat-translates-to-provider-shape
  (let [seen (atom [])]
    (with-redefs [lc/chat (fn [_ message _] (str "echo:" message))]
      (let [msg (llm/stream! {:protocol :langchain4clj
                              :base-url "https://openrouter.ai/api/v1"
                              :model "x"
                              :api-key "test"}
                             [{:role "user" :content "hi"}]
                             #(swap! seen conj %))]
        (testing "one text delta, then a plain assistant message"
          (is (= [{:type :text/delta :text "echo:hi"}] @seen))
          (is (= {:role "assistant" :content "echo:hi"} msg)))))))
