(ns harness.cap.hooks
  "Where a user's hook declarations live on disk, and how the two files become one:
  the configuration home's `hooks.edn` (the USER level) with the bound project's
  `.harness/hooks.edn` (the PROJECT level) over it.

  A SHALLOW merge of top-level keys, project wins -- naming :pre-tool-use in a
  project REPLACES the user's :pre-tool-use declarations rather than appending to
  them. Same rule as harness.cap.project/harness-config, and for the same reason:
  'what will actually run' should be readable in one file, not inferred from how two
  files nest. An unbound session sees the user level alone.

  THIS IS A CAPABILITY, NOT A MECHANISM, and the line between the two is worth
  naming because the split looks arbitrary until it is: the kernel owns the point
  table, what a declaration may say, and the ORDER the sources run in
  (harness.kernel.hooks); this namespace owns WHICH TWO FILES and how they merge.
  The kernel used to know both, and the tell was that it required
  harness.cap.project for one call -- `binding-for`. A namespace that has to ask
  which directory a session is bound to is not a mechanism.

  The reader is INSTALLED, not required: `install!` hands the kernel a function that
  answers a thread's declarations, through the same door the tool table uses. With
  nothing installed the kernel sees no files at all, which is why a test that asks
  about the kernel's own rows does not have to write one."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [harness.cap.project :as project]
            [harness.infra.home :as home]
            [harness.kernel.hooks :as hooks]))

(defn- read-hooks-edn
  "One hooks.edn FILE, or {} when it does not exist -- a missing file is the empty
  configuration, never an error (a fresh install has no hooks and that is the
  normal state). A file that EXISTS but is broken -- not valid EDN, not a map, an
  unknown point key, a bad declaration -- is a hard, NAMED failure with the absolute
  path: an ignored hooks.edn is indistinguishable from one that says nothing, and
  the difference is the whole meaning of the file."
  [file]
  (let [f (io/file file)]
    (if-not (.exists f)
      {}
      (let [abs (.getAbsolutePath f)
            raw (try (edn/read-string (slurp f :encoding "UTF-8"))
                     (catch Exception e
                       (hooks/fail (str abs " is not valid EDN (" (ex-message e) ")")
                                   {:path abs :reason :invalid-edn})))]
        (when-not (map? raw)
          (hooks/fail (str abs " must be an EDN map of hook point -> [declarations]")
                      {:path abs :reason :not-a-map}))
        (into {}
              (map (fn [[k decls]]
                     (let [point (hooks/point-for k)]
                       (when-not point
                         (hooks/fail (str abs " names an unknown hook point " (pr-str k)
                                          "; the points are " (pr-str hooks/point-keys))
                                     {:path abs :point k :reason :unknown-point}))
                       (when-not (sequential? decls)
                         (hooks/fail (str abs " point " (pr-str k)
                                          " must be a vector of declarations, not "
                                          (pr-str (type decls)))
                                     {:path abs :point k :reason :not-a-vector}))
                       [k (vec (map-indexed
                                #(hooks/check-declaration k point %1 %2 :config)
                                decls))])))
              raw)))))

(defn config
  "The declarations THREAD-ID's two files hold. Every call re-reads both, so an
  edit takes effect at the next trigger rather than at the next restart."
  ([] (config nil))
  ([thread-id]
   (merge (read-hooks-edn (home/hooks-file))
          (when-let [dir (project/binding-for thread-id)]
            (read-hooks-edn (io/file dir ".harness" "hooks.edn"))))))

(defn install!
  "Let the kernel read this session's hook files, and answer the teardown that takes
  the reader away again.

  WHAT IS HANDED OVER IS A FUNCTION, not a file list: the kernel asks
  `(config thread-id)` and knows nothing else -- not that there are two files, not
  that either of them is in a project, not that a project is a thing. `teardown`
  returns the kernel to the no-files state, which is what every test that asks about
  the kernel's own rows starts from."
  []
  (hooks/install! {:name "hooks.edn" :declarations config}))
