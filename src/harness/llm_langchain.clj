(ns harness.llm-langchain
  "langchain4clj-backed provider (:protocol :langchain4clj).

  First cut, deliberately thin: blocking chat translated back into the
  provider shape. The old :openai-completions path is untouched and stays green.

  Known gaps vs the minimal-kernel contract (see the feature spec):
  no reasoning deltas (streaming handler is text-only), no tool_calls round
  trip (history + tools translation is a later ticket), blocking call instead
  of SSE, and DeepSeek-style reasoning_content replay is not preserved."
  (:require [harness.event :as ev]
            [harness.llm :as llm]
            [langchain4clj.core :as lc]))

(defn- last-user-text [messages]
  (let [users (filter #(= "user" (:role %)) messages)
        content (:content (last users))]
    (if (string? content) content "")))

(defmethod llm/stream! :langchain4clj
  [{:keys [base-url model api-key]} messages on-event]
  (let [m (lc/openai-model (cond-> {:api-key api-key :model model}
                             base-url (assoc :base-url base-url)))
        text (str (lc/chat m (last-user-text messages) {}))]
    (on-event (ev/text-delta text))
    {:role "assistant" :content text}))
