(ns harness.tools
  "The five tools. A tool is
     {:description string :parameters JSON-Schema :required [kw..] :run (fn [args] string)}

  RUN gets a keyword-keyed argument map and returns a string.

  run! takes a tool call in the PROVIDER's shape -- {:id .. :type \"function\"
  :function {:name .. :arguments json-string}} -- because the kernel keeps messages
  in the provider shape and never converts. It never throws and never returns nil:
  a tool failure is information for the model, not a failure of the run."
  (:refer-clojure :exclude [run!])
  (:require [clojure.data.json :as json]
            [clojure.string :as str]))

(defonce registry (atom {}))

(defn register! [name tool] (swap! registry assoc name tool))

(defn- validate! [{:keys [required]} args]
  (let [missing (remove #(contains? args %) required)]
    (when (seq missing)
      (throw (ex-info (str "missing required argument(s): "
                           (str/join ", " (map name missing)))
                      {})))))

(defn run!
  [{:keys [function]}]
  (try
    (let [{:keys [name arguments]} function
          tool   (or (get @registry name)
                     (throw (ex-info (str "unknown tool: " name) {})))
          parsed (json/read-str (if (str/blank? arguments) "{}" arguments) :key-fn keyword)]
      (validate! tool parsed)
      {:content (str ((:run tool) parsed)) :error false})
    (catch Throwable t
      {:content (ex-message t) :error true})))
