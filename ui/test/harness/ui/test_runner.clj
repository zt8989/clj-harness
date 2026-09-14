(ns harness.ui.test-runner
  "The macro half of the vitest bridge. Kept beside the runtime half
  (test_runner.cljs), which is what a shadow-cljs ns with macros looks like.

  `deftest-index` exists because cljs.test enumerates tests at MACRO time:
  `test-all-vars-block` walks `cljs.analyzer/ns-interns` and emits a literal list
  of `(var ..)` forms, and the runtime has no equivalent registry to walk. The
  vitest driver needs that list at runtime, to hand one test at a time back to
  the CLJS side, so the same walk happens here and its result is emitted as a
  def.

  Because `ns-interns` is a compile-time question, the macro has to be called
  FROM the namespace whose tests it indexes -- which is why each suite ends with
  `(deftest-index)` rather than the runner reaching in.

  `cljs.analyzer.api`, not `cljs.analyzer`: the compiler-state accessors live on
  the api facade, which is also the one cljs.test's own test-enumeration macro
  uses."
  (:require [cljs.analyzer.api :as ana-api]
            [cljs.analyzer :as ana]))

(defmacro deftest-index
  "Def `test-index` in the current namespace: one entry per `deftest`, ordered by
  line so the vitest output reads in source order, each carrying the var itself
  -- the driver answers vitest with an id string, and the var is what the id
  resolves back to.

  A namespace with no tests gets an empty index, not an error: the shape stays
  the same and the runner reports zero tests for it.

  The def's name is `~'test-index`, not `test-index`: syntax-quote resolves
  symbols against the namespace the macro was DEFINED in, so a plain `test-index`
  here would def `harness.ui.test-runner/test-index` in the caller's namespace
  and fail with \"Can't def ns-qualified name\"."
  []
  (let [ns-sym ana/*cljs-ns*
        entries (->> (ana-api/ns-interns ns-sym)
                     (filter (fn [[_ v]] (:test v)))
                     (sort-by (fn [[_ v]] (:line v)))
                     (mapv (fn [[k _]]
                             {:id   (str ns-sym "/" (name k))
                              :ns   (str ns-sym)
                              :name (str (name k))
                              :v    (symbol (name k))})))]
    `(def ~'test-index
       [~@(map (fn [{:keys [id ns name v]}]
                 `{:id ~id :ns ~ns :name ~name :v (var ~v)})
               entries)])))
