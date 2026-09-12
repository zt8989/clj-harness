(ns harness.memory
  "The introspectable surface: the in-memory state and accessors eval (and the
  agent through it) may freely read -- the frozen system prompt, the tool
  registry, and the config.edn contents. Nothing here ever carries a secret:
  the ENV-sourced api-key and any raw provider that could hold one live in
  harness.opaque, the non-introspectable counterpart of this namespace."
  (:require [clojure.edn :as edn]))

;; ------------------------------------------------------------------- prompt

(defonce frozen-prompt (atom nil))

(defn prompt
  "The system prompt, FROZEN: prompt.md is read once -- on the first call -- and
  every run after that reuses the same text. The provider's prefill (prompt
  cache) keys on a stable prefix; a system prompt that changes per run would
  miss it on every call. Editing prompt.md takes effect only after
  (reset-prompt!) or a process restart."
  []
  (or @frozen-prompt
      (reset! frozen-prompt (slurp "prompt.md" :encoding "UTF-8"))))

(defn reset-prompt!
  "Re-read prompt.md into the frozen slot. The deliberate counterpart of
  freezing: the agent -- or you, in the REPL -- opts into a new prefix, trading
  one cold prefill for the change."
  []
  (reset! frozen-prompt nil))

;; -------------------------------------------------------------------- tools

(defonce registry (atom {}))

(defn register! [name tool] (swap! registry assoc name tool))

;; ------------------------------------------------------------------- config

(defn config
  "config.edn, re-read every time so it can be edited while the process runs.
  This is the EDN half only (:protocol/:base-url/:model...) -- there is no
  api-key here and never will be: the key is resolved from .env/environment in
  harness.opaque, which is where the effective provider is assembled."
  []
  (edn/read-string (slurp "config.edn")))
