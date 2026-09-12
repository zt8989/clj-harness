(ns harness.opaque
  "The non-introspectable counterpart of harness.memory: everything that carries
  a secret or could carry one. The api-key is resolved from .env/environment
  here and never surfaces through a memory-surface return value; the raw
  provider override may carry a key (an offline scripted provider need not, but
  nothing forbids one), so it lives here too. Vars are private wherever the
  language allows -- eval is not invited to this namespace."
  (:require [dotenv :as dotenv]
            [harness.memory :as mem]))

(defonce ^:private provider-override (atom nil))

(defn use-provider!
  "Serve from PROVIDER instead of config.edn; pass nil to go back to config.
  This is how the whole edge can be exercised offline, against a scripted provider,
  without an API key or a network."
  [provider]
  (reset! provider-override provider))

(defn- api-key
  "The API key from .env through the dotenv library, following ITS precedence:
  a value in .env wins over a real environment variable. So .env is the single
  place that decides, and setting a shell variable will NOT override it."
  []
  (dotenv/env "HARNESS_API_KEY"))

(defn effective-provider
  "config.edn plus the ENV-sourced api-key. The config half is re-read every
  time so it can be edited while the process runs; the key half is resolved
  here and rides only inside a provider handed to the LLM layer -- it is never
  exposed through harness.memory."
  []
  (assoc (mem/config) :api-key (api-key)))

(defn current-provider
  "What the http edge serves from: the override when one is set (offline,
  scripted providers), else the config.edn provider."
  []
  (or @provider-override (effective-provider)))
