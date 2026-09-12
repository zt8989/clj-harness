(ns harness.llm
  "Provider layer. One multimethod, dispatched on :protocol.

  Contract for every method:
    (stream! provider messages on-event) -> assistant message

  ON-EVENT is called with each harness.event value as it is produced. The returned
  assistant message is provider-shaped and is appended to the history VERBATIM by
  harness.loop -- never rebuilt.

  A provider is just a config map, so (config) can be handed straight to loop/run!:
    {:protocol :langchain4clj, :base-url .., :model .., :api-key ..}

  The active provider is langchain4j (see harness.llm-langchain): a blocking chat
  wrapper that translates the harness history + tool specs in and out. It does NOT
  surface reasoning_content -- a documented gap versus the removed SSE provider
  (see .scratch/langchain4clj-provider/spec.md, conflict table)."
  (:require [clojure.edn :as edn]
            [dotenv :as dotenv]))

(defmulti stream!
  (fn [provider _messages _on-event] (:protocol provider)))

;; --------------------------------------------------------------------- config

(defn config
  "config.edn, re-read every time so it can be edited while the process runs.
  The API key comes from .env through the dotenv library, following ITS precedence:
  a value in .env wins over a real environment variable. So .env is the single place
  that decides, and setting a shell variable will NOT override it."
  []
  (assoc (edn/read-string (slurp "config.edn"))
         :api-key (dotenv/env "HARNESS_API_KEY")))

(defn prompt
  "prompt.md, re-read before every run so the agent can rewrite its own instructions
  -- or its own kernel -- and see the change take effect on the very next turn."
  []
  (slurp "prompt.md" :encoding "UTF-8"))
