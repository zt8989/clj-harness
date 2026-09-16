(ns harness.infra.log
  "Report a backend failure: `clojure.tools.logging` → SLF4J → Logback, which
  gives the console and the rolling file in one call. See `harness.infra.logging` for
  the appenders and the rotation policy.

  WHAT THIS NAMESPACE IS FOR, GIVEN THE LIBRARY ALREADY LOGS. One sentence, built
  in one place, so that the line on the console and the line in the file are the
  same line -- a caller says WHAT failed and this decides how that is spelled.
  Without it every call site invents its own phrasing, and 'which of these is the
  same error' stops being answerable by grepping.

  THE MESSAGE CARRIES THE CONTEXT AS `key=value`. Logback's patterns render text,
  so a structured field would have to be flattened somewhere; doing it here, in
  the one place that knows the vocabulary, keeps the file greppable
  (`thread-id=t-1`) without a second format to maintain.

  THE THROWABLE GOES THROUGH AS A THROWABLE, not interpolated into the message.
  That is what makes `%ex` render a real stack trace, and it is also what keeps a
  failure loggable when it arrives as a value rather than an exception."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [harness.infra.logging :as logging])
  (:import [java.time Instant]))

(defn context-str
  "CONTEXT as `k=v k=v`, deterministic in order so two lines that differ only in
  a value are still comparable by eye. Nested collections are pr-str'd rather
  than dropped -- the useful part of a failure's context is often the map itself."
  [context]
  (->> (sort-by (comp str key) context)
       (remove (comp nil? val))
       (map (fn [[k v]]
              (str (name k) "=" (if (coll? v) (pr-str v) (str v)))))
       (str/join " ")))

(defn render
  "The one sentence both sinks carry."
  [kind context]
  (let [ctx (context-str context)]
    (if (str/blank? ctx)
      (str (name kind))
      (str (name kind) " " ctx))))

(defn error!
  "Report EX, which happened while doing KIND, to the console and to the rolling
  file. Answers the Throwable, so a caller can `(throw (log/error! :x t {}))`
  and keep a single exit path.

  `logging/ensure!` runs first: the file appender is the one thing in this
  process that cannot read the root per call, so the check is what keeps a test
  run's log lines out of the developer's real home."
  ([kind ex] (error! kind ex {}))
  ([kind ex context]
   (logging/ensure!)
   (log/error ex (render kind context))
   ex))

(defn info!
  "The same shape at INFO, for the handful of facts worth a line in the file
  without being failures -- the server starting, a root being configured."
  ([kind] (info! kind {}))
  ([kind context]
   (logging/ensure!)
   (log/info (render kind context))
   nil))

(defn started
  "One line recording that the process came up and WHERE its logs are going. Not
  an error, and deliberately still a log line rather than a `println`: it is the
  first thing worth finding in a file somebody has just been pointed at, and a
  file that starts mid-story is worse than no file."
  [root port]
  (info! :listening {:root root :port port :since (str (Instant/now))}))
