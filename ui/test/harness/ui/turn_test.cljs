(ns harness.ui.turn-test
  "Two turns on ONE thread id, which is the shape that used to break. From
  ui/verify-real.mjs.

  The bug this pins: the second request of a conversation used to answer 400 --
  the kernel appended the reasoning of turn one to the history in a shape the
  provider rejected. It is covered offline by harness.loop-test, but the path
  that actually failed ran through the AG-UI edge AND the client, which only this
  level exercises.

  With a scripted provider the assertion can be exact: turn two's text is
  reachable only if the server accepted the history the client rebuilt from turn
  one -- reasoning message included -- and the script advanced."
  (:require ["@ag-ui/client" :refer [HttpAgent]]
            [cljs.test :refer [deftest is]]
            [harness.ui.e2e :as e2e])
  (:require-macros [harness.ui.test-runner :refer [deftest-index]]))

(defn- new-agent []
  (let [agent (HttpAgent. #js {:url (e2e/url)})
        failed (atom nil)]
    ;; A failed run arrives as a RUN_ERROR FRAME -- so it is `onRunErrorEvent`
    ;; that reports it. The hook that sounds like it should, `onRunFailedEvent`,
    ;; does not exist on this client: a typo in a hook name is silently dropped
    ;; by its subscriber registry, and the check then never fires. (verify-real.mjs
    ;; and verify-approval.mjs both had that typo, which means their "did the run
    ;; fail?" guards were dead code.)
    (.subscribe agent #js {:onRunErrorEvent
                           (fn [^js b] (reset! failed (.-message (.-event b))))})
    [agent failed]))

(defn- send! [^js agent tid text]
  (.addMessage agent #js {:id (str "u-" (js/Date.now) "-" (.-random js/Math))
                          :role "user" :content text})
  (.runAgent agent #js {:threadId tid
                        :runId (str "run-" (js/Date.now) "-" (.-random js/Math))
                        :messages (.-messages agent)
                        :tools #js [] :context #js []}))

(defn- messages [^js agent] (vec (array-seq (.-messages agent))))

(defn- has-text? [^js agent text]
  (some #(= text (e2e/content %)) (messages agent)))

(deftest a-second-turn-continues-the-same-thread
  (let [[^js agent failed] (new-agent)
        tid (e2e/thread-id "turns")
        first-text "\u7b2c\u4e00\u8f6e\u3002"
        second-text "\u7b2c\u4e8c\u8f6e\u3002"]
    (e2e/async!
     (fn [_]
       (e2e/script! [#js {:reasoning "\u5148\u8bfb\u4e00\u4e0b\u3002" :content first-text}
                     #js {:content second-text}])
       (send! agent tid "\u770b\u770b\u8fd9\u4e2a\u9879\u76ee\u3002"))
     (fn [_]
       (is (has-text? agent first-text) "turn one's text is in the conversation")
       ;; Hand the conversation to turn two the way the UI does: the client owns
       ;; the history, and the next run carries all of it.
       (send! agent tid "\u7ee7\u7eed\u3002"))
     (fn [_]
       (is (nil? @failed) (str "the second turn did not fail: " (pr-str @failed)))
       (is (has-text? agent second-text) "and the script's SECOND turn was served")
       ;; The history must have grown across both turns, or "it did not 400"
       ;; could just mean the second run started from scratch.
       (is (>= (count (messages agent)) 4)
           "the second run carried the first turn's history")))))

(deftest a-reasoning-message-does-not-poison-the-next-turn
  ;; The exact regression: turn one produces ONLY reasoning and a tool call, so
  ;; the message the history gains is one a provider must accept back. A
  ;; zero-content assistant message carrying reasoning is the shape that 400'd.
  (let [[^js agent failed] (new-agent)
        tid (e2e/thread-id "reasoning-poison")]
    (e2e/async!
     (fn [_]
       (e2e/script! [#js {:reasoning "\u53ea\u6709\u63a8\u7406\u3002"
                          :content ""
                          :tool-calls #js [#js {:id "c1" :name "read"
                                                :arguments #js {:path "deps.edn"}}]}
                     #js {:reasoning "\u518d\u60f3\u4e00\u6b21\u3002"
                          :content "\u597d\u4e86\u3002"}])
       (send! agent tid "read deps.edn"))
     (fn [_]
       (is (some #(= "reasoning" (.-role %)) (messages agent))
           "turn one left a reasoning message in the history")
       (send! agent tid "\u518d\u6765\u3002"))
     (fn [_]
       (is (nil? @failed)
           (str "resending a reasoning message is accepted: " (pr-str @failed)))
       (is (has-text? agent "\u597d\u4e86\u3002")
           "and the run continued into turn two")))))

(deftest-index)
