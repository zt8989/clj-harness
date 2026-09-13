(ns harness.opaque
  "The non-introspectable counterpart of harness.memory: everything that carries
  a secret or could carry one. The api-key is resolved from .env/environment
  here and never surfaces through a memory-surface return value; the raw
  provider override may carry a key (an offline scripted provider need not, but
  nothing forbids one), so it lives here too. Vars are private wherever the
  language allows -- eval is not invited to this namespace."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [harness.home :as home]
            [harness.memory :as mem]))

(defonce ^:private provider-override (atom nil))

(defn use-provider!
  "Serve from PROVIDER instead of config.edn; pass nil to go back to config.
  This is how the whole edge can be exercised offline, against a scripted provider,
  without an API key or a network."
  [provider]
  (reset! provider-override provider))

(defn- parse-dotenv
  "A .env file's contents -> a {name value} map. Handles the shapes the format
  actually uses: `export` prefixes, surrounding single or double quotes,
  `#` comments, blank lines, and values that themselves contain `=` (only the
  first `=` splits).

  We parse it ourselves rather than lean on the dotenv library because that
  library resolves `.env` from the CURRENT DIRECTORY at namespace-load time and
  caches it in a def -- so it cannot be pointed at harness.home, and it would
  miss an edit made while the process runs. Both of those matter here."
  [raw]
  (into {}
        (->> (str/split-lines raw)
             (map str/trim)
             (remove #(or (empty? %) (str/starts-with? % "#")))
             (map #(str/split % #"=" 2))
             (filter #(= 2 (count %)))
             (map (fn [[k v]]
                    [(str/replace (str/trim k) #"^export\s+" "")
                     (let [v (str/trim v)]
                       (if (and (>= (count v) 2)
                                (or (and (str/starts-with? v "\"") (str/ends-with? v "\""))
                                    (and (str/starts-with? v "'") (str/ends-with? v "'"))))
                         (subs v 1 (dec (count v)))
                         v))])))))

(defn- api-key
  "The API key, following the dotenv library's documented precedence: a value in
  .env wins over a real environment variable. So .env is the single place that
  decides, and setting a shell variable will NOT override it.

  The file is harness.home's .env -- and is re-read every time, like config.edn,
  so editing it takes effect without a restart."
  []
  (let [f (home/dotenv-file)
        from-file (when (.exists f) (get (parse-dotenv (slurp f :encoding "UTF-8"))
                                         "HARNESS_API_KEY"))]
    (or from-file (System/getenv "HARNESS_API_KEY"))))

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
