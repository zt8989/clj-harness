(ns harness.edge.llm-timeout
  "The idle guard's TWO KNOBS, as configuration: how long a model call may go without a
  single line of the vendor's stream before it is treated as dead, and how many times a
  call that died that way may be tried again.

  IT IS ITS OWN NAMESPACE BECAUSE OF WHO READS IT. The guard itself is the kernel's
  (`harness.kernel.llm/idle-guarded-lines` disconnects, `harness.kernel.loop/model-call-watched`
  decides), and the kernel reads no configuration -- so harness.edn is read HERE, at the
  edge, and handed down the same way `harness.edge.compaction/overflow-retries` is. Two
  readers (the run edge and the compaction path, which makes a model call of its own) and
  one answer, which is the whole reason this is a namespace rather than four lines in
  `harness.edge.http`.

  THE VALUES ARE harness.edn's `:llm` BLOCK, composed KEY BY KEY from the two levels --
  the project level over the user level, the shape `:editing` and `:compaction` already
  use -- and read FRESH on every call (harness.edn's own discipline: edit the file and the
  next run obeys it, no restart). A block that says nothing takes the defaults:

      {:llm {:idle-timeout-ms     500     ; 0 turns the guard off
             :idle-timeout-retries 3}}     ; retries AFTER the first attempt

  A VALUE THAT IS NOT A WHOLE NUMBER OF MILLISECONDS -- or of retries -- IS REFUSED BY
  NAME, never rounded: the number bounds how long a run may sit silent, and a fraction or
  a negative there is a typo somebody needs to see, not a value to interpret. 0 is a
  value and not a typo: `:idle-timeout-ms 0` means 'never give up on a silent vendor'
  (the guard is off, exactly as it is for a run handed no number at all), and
  `:idle-timeout-retries 0` means 'the first timeout ends the run'."
  (:require [harness.cap.project :as project]
            [harness.kernel.llm :as llm]))

(def default-retries
  "How many times a model call that went quiet may be tried again, when harness.edn says
  nothing: THREE -- four attempts in all, counting the one that timed out.

  IT IS A BUDGET FOR ONE MODEL CALL, not for a run and not for a session. A call that
  succeeds spends nothing, and a later call in the same run gets the same three: what is
  bounded is a vendor that cannot answer THIS request, which is the thing a retry can
  plausibly fix. `0` turns the recovery off -- the first timeout ends the run."
  3)

(defn- block
  "harness.edn's `:llm` block for THREAD-ID, the PROJECT level over the USER level, as
  written -- nothing merged with the defaults and nothing validated. The one reader, so
  the two knobs below cannot fold the same file two different ways."
  [thread-id]
  (let [{:keys [user project]} (project/harness-edn-levels thread-id)]
    (reduce (fn [m level]
              (let [b (:llm (get {:user user :project project} level))]
                (if (map? b) (merge m b) m)))
            {}
            [:user :project])))

(defn- whole-number!
  "N as a count of KEY, or a refusal naming the value, the key and where it came from.
  See the namespace docstring for why nothing here rounds."
  [n key]
  (when-not (and (integer? n) (not (neg? n)))
    (throw (ex-info (str "harness.edn :llm " (name key) " must be a whole number"
                         " (0 turns it off), but it is " (pr-str n))
                    {:key key :value n :reason :bad-llm-timeout-value})))
  n)

(defn idle-timeout-ms
  "How long THREAD-ID's model calls may go without a line before the guard cuts them off,
  in milliseconds: harness.edn's `:llm :idle-timeout-ms`, defaulting to
  `harness.kernel.llm/default-idle-timeout-ms` (500).

  THE DEFAULT LIVES IN THE KERNEL, not here: this is the knob's reader, and the one
  sentence about what the number MEANS belongs beside the code that enforces it."
  [thread-id]
  (whole-number! (get (block thread-id) :idle-timeout-ms llm/default-idle-timeout-ms)
                 :idle-timeout-ms))

(defn retries
  "How many times a call that timed out may be tried again for THREAD-ID: harness.edn's
  `:llm :idle-timeout-retries`, defaulting to `default-retries` (3)."
  [thread-id]
  (whole-number! (get (block thread-id) :idle-timeout-retries default-retries)
                 :idle-timeout-retries))
