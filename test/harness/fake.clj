(ns harness.fake
  "A scripted provider, so the whole loop can be driven offline. It lives under
  test/ to stay out of the core line budget."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [harness.event :as ev]
            [harness.llm :as llm]))

(def ^:private chunk-size 5)

(defn- emit! [make-event s on-event]
  (doseq [c (partition-all chunk-size s)]
    (on-event (make-event (str/join c)))))

;; A SHARED script for tests that need a script living in an EDN file (a
;; registry entry cannot carry an atom -- edn/read-string has no reader for
;; one). The test sets this to a fresh atom before the run; the :fake protocol
;; reads from it. The pin seam (fake/scripted) keeps its own script and is
;; unaffected.
(defonce test-script (atom []))

(defn- script-provider [script on-event]
  (let [{:keys [reasoning content tool-calls]} (first @script)]
    (swap! script #(vec (rest %)))
    (emit! ev/reasoning-delta reasoning on-event)
    (emit! ev/text-delta content on-event)
    (let [calls (mapv (fn [{:keys [id name arguments]}]
                        (let [args (json/write-str arguments)]
                          (on-event (ev/tool-call id name args))
                          {:id id :type "function"
                           :function {:name name :arguments args}}))
                      tool-calls)]
      (cond-> {:role "assistant" :content (or content "")}
        (seq reasoning) (assoc :reasoning_content reasoning)
        (seq calls)     (assoc :tool_calls calls)))))

(defn scripted
  "Provider over a vector of turns. A turn is
     {:reasoning s, :content s, :tool-calls [{:id s :name s :arguments map}]}
  The assistant message it returns is deliberately OpenAI-shaped, because that is
  what the history holds."
  [turns]
  {:protocol :fake :script (atom (vec turns))})

(defmethod llm/stream! :fake
  [{:keys [script]} _messages on-event _thread-id]
  (script-provider (or script test-script) on-event))
