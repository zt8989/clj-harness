(ns scratch-read-routes
  "Ticket 03 of `.scratch/reasoning-out-of-the-record`: MEASURED EVIDENCE for the read side.

  WHAT THIS ASKS. The write side stops putting the per-token `REASONING_*` frames on the
  record; the reasoning is folded back off the run's own `message` row (the provider's
  `reasoning_content`). Two real logs are read here, UNTOUCHED:

    - `fa35f356-…` (2026-09-23/24) the OLD shape: reasoning frames on the record -- and
      `model/start` still copying the whole tool table;
    - `ed334c9c-…` (2026-09-26) written BEFORE this change, so its reasoning frames are
      STILL THERE too (the write side has not been restarted since). Both logs therefore
      carry the frames; what follows reports what is actually on disk.

  A. the size and the whole-file parse cost, on each log as it is and after a copy with
     every reasoning-family frame dropped (`REASONING_START` / `REASONING_MESSAGE_START` /
     `REASONING_MESSAGE_CONTENT` / `REASONING_MESSAGE_END` / `REASONING_END`). The dropped
     copy goes to the OS temp directory; the real logs are only ever read.
     Whether this row is a reasoning frame is decided by PARSING the JSON and asking the
     row's `:payload :type` -- never by searching for the word REASONING in the line,
     because a `message` row of this very repository's session QUOTES those names and
     dropping that line would be dropping a person's sentence.

  B./C. the FOUR READ ROUTES, on the same two real files:

     1. `harness.edge.replay/rebuild`                (POST /api/threads/<stem>/rebuild)
     2. `harness.edge.replay/sofar`                  (GET  /api/threads/<stem>/sofar)
     3. the page route: `harness.edge.replay/entries` (the whole fold) cut by
        `harness.kernel.session/tail-of` and `before-of` -- the exact pair
        `harness.edge.http/page-get` calls (the page cut has no function of its own in
        `replay.clj`; it lives in `harness.kernel.session`, re-exported by
        `harness.edge.sessions`).
     4. `harness.edge.trajectory/log-trajectory`      (GET  /api/threads/<stem>/trajectory)

     Each must (a) still answer with the reasoning and (b) say the SAME WORDS the trajectory
     reads off the model rows' `reasoning_content`. The two sides are compared text for
     text, in order: the reasoning-role messages the fold produces vs the `:reasoning` of
     the trajectory's assistant items.

     ONE ASYMMETRY IS MEASURED RATHER THAN ASSUMED. A folded reasoning message's id is
     `<runId>-r<n>` (the frames' own spelling, which the row path reproduces), so each one
     can be attributed to the run that produced it. A run that emitted reasoning frames but
     never wrote a `message` row -- a run that died before `:run/done` -- has reasoning the
     trajectory CANNOT see (it reads model rows). So both the whole fold and the fold
     restricted to runs that DID write a model row are compared, and the runs that did not
     are named with the number of reasoning-frame blocks they carry.

  D. THE ONE FOLD THE REAL LOGS COULD NOT SHOW. Both logs on disk were written BEFORE the write
     side stopped recording reasoning frames, so on the files as they are the fold still takes the
     reasoning off the FRAMES and never asks the row path (`attach-reasoning`) that has to carry it
     now. Each real log is folded TWICE -- as it is, and as the reasoning-free copy `A` already
     wrote -- and the two MESSAGE lists are compared message for message, with `:seq` left out
     (a record's line offsets cannot survive a rewrite). ONE ACCEPTED DIFFERENCE IS TAKEN OUT
     FIRST (ADR 0009): a reasoning message whose whole text is blank is built by the FRAMES and
     deliberately not by the row path, so the frames fold is filtered of them and the number
     dropped is reported, never counted as a pass. What is then left over is named by KIND -- a
     position whose only difference is the `:id` is the row path's NUMBERING, not a different word
     -- and the reasoning the frames carry and the rows cannot rebuild is listed BY RUN, so the OLD
     log's one run that streamed reasoning and wrote no model row (`3f547396-…`) is a number of its
     own, never added to the blank's.

  HOW TO RUN IT. `.scratch/` is on no classpath, so the file is loaded by PATH, from the
  repo root:

    clojure -J-Dstdout.encoding=UTF-8 -J-Xmx6g -M:dev -i .scratch/reasoning-out-of-the-record/evidence/read_routes.clj

  Every line printed is appended to `read_routes.txt` beside this file (deleted first, so a
  re-run replaces it rather than stacking). `-J-Xmx6g` is comfort: the 68 MB log parses in
  ~1.2 s and holds a few hundred MB of rows.

  `~/.clj-harness` IS READ AND NEVER WRITTEN. `(tr/isolate!)` -- the FIRST thing this file
  does -- points the config root and the OS home at fresh temp directories for this process,
  so the harness's own paths cannot reach the developer's install. The two logs are read by
  absolute path, and the stripped copies are written under `java.io.tmpdir`.

  Two knobs make a re-run possible without editing the file (system properties):
    -Dread-routes.home    the config home (default C:/Users/zhouteng/.clj-harness)
    -Dread-routes.project the project log directory's name under projects/"
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [harness.infra.db :as db]
            [harness.edge.replay :as replay]
            [harness.edge.trajectory :as trajectory]
            [harness.kernel.session :as session]
            [harness.test-runner :as tr]))

;; ---------------------------------------------------------------- isolation, first thing
(def iso-root (tr/isolate!))

;; ---------------------------------------------------------------- the transcript
(def evidence-dir (.getParentFile (io/file *file*)))
(def out (io/file evidence-dir "read_routes.txt"))
(io/delete-file out true)

(defn- say
  "Print a line, and append the same line to the transcript."
  [& xs]
  (let [s (apply str (interpose " " (map str xs)))]
    (println s)
    (spit out (str s "\n") :append true :encoding "UTF-8")))

;; ---------------------------------------------------------------- the two real logs
(def real-home
  (or (System/getProperty "read-routes.home") "C:/Users/zhouteng/.clj-harness"))

(def real-project
  (or (System/getProperty "read-routes.project")
      "C__Users_zhouteng_Documents_workspace_lisp-harness"))

(defn- log-file [stem]
  (io/file real-home "projects" real-project (str stem ".jsonl")))

(def logs
  [{:tag  "OLD  fa35f356 (written 2026-09-23/24)"
    :stem "fa35f356-1315-4a9d-8154-dfb1c7ee8dee"}
   {:tag  "NEW  ed334c9c (written 2026-09-26, BEFORE this change -- its frames are still there)"
    :stem "ed334c9c-9b6c-4a8e-bd0e-1f695fbd37fe"}])

;; ---------------------------------------------------------------- A. reasoning frames
(defn- reasoning-frame-line?
  "Is LINE a recorded frame whose type begins with REASONING? Parsed, not grepped."
  [line]
  (let [row (try (json/read-str line :key-fn keyword) (catch Throwable _ nil))]
    (and (= "event" (:type row))
         (str/starts-with? (str (get-in row [:payload :type])) "REASONING"))))

(defn- strip-reasoning!
  "Stream SRC into DST, dropping every reasoning frame row. Returns
  {:lines n :kept k :reasoning r :reasoning-bytes b} -- `:reasoning-bytes` counts each
  dropped line's UTF-8 bytes plus its newline, so it can be read against the file size."
  [^java.io.File src ^java.io.File dst]
  (with-open [r (io/reader src :encoding "UTF-8")
              w (io/writer dst :encoding "UTF-8")]
    (let [rdr ^java.io.BufferedReader r
          wtr ^java.io.BufferedWriter w]
      (loop [ls (line-seq rdr) lines 0 kept 0 reasoning 0 rbytes 0]
        (if-some [line (first ls)]
          (if (reasoning-frame-line? line)
            (recur (rest ls) (inc lines) kept (inc reasoning)
                   (+ rbytes (alength (.getBytes ^String line "UTF-8")) 1))
            (do (.write wtr ^String line)
                (.write wtr "\n")
                (recur (rest ls) (inc lines) (inc kept) reasoning rbytes)))
          {:lines lines :kept kept :reasoning reasoning :reasoning-bytes rbytes})))))

(defn- timed-parse
  "`harness.edge.replay/read-records` over the whole file, timed. Records are counted and
  dropped -- nothing here keeps them."
  [^java.io.File f]
  (let [t0 (System/nanoTime)
        n  (count (replay/read-records f))
        ms (long (Math/round (/ (double (- (System/nanoTime) t0)) 1e6)))]
    {:records n :ms ms}))

(defn- no-reasoning-copy
  "Where the reasoning-free copy of F lives: the OS temp directory, named after the log's stem.
  ONE PLACE SPELLS THIS PATH, because two sections have to agree on it -- section A writes the
  file (through `strip-reasoning!`) and section D folds it back -- and a second spelling would be
  a second filter waiting to drift."
  ^java.io.File [^java.io.File f]
  (io/file (System/getProperty "java.io.tmpdir")
           (str (str/replace (.getName f) #"\.jsonl$" "") "-no-reasoning.jsonl")))

(defn- measure-sizes [tag ^java.io.File f]
  (let [copy   (no-reasoning-copy f)
        obytes (.length f)
        {:keys [lines kept reasoning reasoning-bytes]} (strip-reasoning! f copy)
        kbytes (.length copy)
        o      (timed-parse f)
        k      (timed-parse copy)]
    (say "")
    (say "== A. record size and whole-file parse :: " tag)
    (say "  file:                  " (.getPath f))
    (say "  original:  bytes" obytes " lines" lines " records" (:records o)
         " read-records ms" (:ms o))
    (say "  reasoning family: lines" reasoning
         (format "(%.2f%% of lines)" (* 100.0 (/ (double reasoning) lines)))
         " bytes" reasoning-bytes
         (format "(%.2f%% of bytes)" (* 100.0 (/ (double reasoning-bytes) obytes))))
    (say "  stripped copy:         " (.getPath copy))
    (say "  stripped:  bytes" kbytes " lines" kept " records" (:records k)
         " read-records ms" (:ms k))
    (say "  bytes removed:" (- obytes kbytes)
         (format "(%.2f%%)" (* 100.0 (/ (double (- obytes kbytes)) obytes))))))

;; ---------------------------------------------------------------- B./C. the four routes
(defn- model-row? [row]
  (and (= "message" (:type row)) (= "model" (:source row))))

(defn- reasoning-frame-row? [row]
  (and (= "event" (:type row))
       (str/starts-with? (str (get-in row [:payload :type])) "REASONING")))

(defn- model-row-runs
  "The runs that wrote at least one `message` row with `:source \"model\"` -- the runs whose
  reasoning the trajectory can see."
  [records]
  (->> records (filter model-row?) (keep :runId) (distinct) (vec)))

(defn- runs-with-reasoning-frames-but-no-model-row
  "[[run-id reasoning-frame-blocks] ..]: runs that streamed reasoning and never wrote a
  `message` row (a run that died before `:run/done`). Their reasoning is on the record and
  NOT in the trajectory."
  [records]
  (let [frames     (filter reasoning-frame-row? records)
        with-rows  (set (model-row-runs records))
        with-frames (distinct (keep :runId frames))]
    (->> with-frames
         (remove with-rows)
         (mapv (fn [r] [r (count (filter (fn [row] (and (= r (:runId row))
                                                        (= "REASONING_MESSAGE_START"
                                                           (get-in row [:payload :type]))))
                                         frames))])))))

(defn- reasoning-msgs
  "The reasoning-role messages of an AG-UI message list, IN ORDER."
  [msgs]
  (filterv #(= "reasoning" (:role %)) msgs))

(defn- owned-by-model-runs?
  "Is message M's `<runId>-r<n>` id one of RUNS? The id is the frames' own spelling, which
  the model-row path reproduces (see `harness.edge.replay/attach-reasoning`)."
  [m runs]
  (some (fn [r] (str/starts-with? (str (:id m)) r)) runs))

(defn- texts-of [msgs] (mapv #(str (:content %)) msgs))

(defn- trajectory-reasoning-texts
  "The `:reasoning` of every assistant item of a trajectory payload, IN ORDER."
  [tra]
  (->> (:turns tra)
       (mapcat :items)
       (filter #(= "assistant" (:kind %)))
       (keep :reasoning)
       (mapv str)))

(defn- find-slice
  "Where R appears as a CONTIGUOUS run inside T, as an index, or nil. A tail page is a
  suffix of the conversation and a prepend page a middle slice, so this is how a route
  that hands out a WINDOW is still compared against the whole trajectory."
  [t r]
  (let [n (count r) m (count t)]
    (when (and (pos? n) (<= n m))
      (first (keep (fn [i] (when (= r (subvec t i (+ i n))) i))
                   (range (inc (- m n))))))))

(defn- slice-line [t r]
  (let [at (find-slice t r)]
    (if (some? at)
      (str "at offset " at " -- equal: " (= r (subvec t at (+ at (count r)))))
      "none")))

(defn- show-compare
  "LABEL, the texts the route handed out (`texts`), the same restricted to runs that wrote a
  model row (`mine`), and the trajectory's texts (`t`)."
  [label texts mine t]
  (say (str "  " label ":"))
  (say "    reasoning texts handed out:      " (count texts)
       " chars:" (count (str/join "" texts)))
  (say "    ...restricted to runs with a model row:" (count mine)
       " chars:" (count (str/join "" mine)))
  (say "    trajectory reasoning texts:      " (count t)
       " chars:" (count (str/join "" t)))
  (say "    reasoning present:               " (pos? (count texts)))
  (say "    whole fold equal to trajectory:  " (= texts t))
  (say "    model-row part equal to trajectory:" (= mine t))
  (say "    whole fold as a contiguous slice of the trajectory:" (slice-line t texts))
  (say "    model-row part as a contiguous slice of it:        " (slice-line t mine)))

(defn- routes-for-log [tag ^java.io.File f]
  (say "")
  (say "================ the four read routes :: " tag " ================")
  (say "  file:" (.getPath f))
  (say "  parameters: rebuild / sofar / log-trajectory each take the File;")
  (say "              the page route is replay/entries (whole fold) cut by")
  (say "              harness.kernel.session/tail-of, then /before-of with beforeSeq =")
  (say "              (inc baseSeq of the tail page) -- the pair http/page-get calls.")
  (let [records (replay/read-records f)
        m-runs  (model-row-runs records)
        _       (say "  runs that wrote a model row:" (count m-runs))
        _       (say "  runs with reasoning frames but NO model row:"
                     (pr-str (runs-with-reasoning-frames-but-no-model-row records)))
        t       (try (trajectory-reasoning-texts (trajectory/log-trajectory f))
                     (catch Throwable e
                       (say "  trajectory FAILED:" (.getName (class e)) "-" (ex-message e))
                       nil))]
    ;; 1. rebuild
    (try
      (let [a   (replay/rebuild f)
            rms (reasoning-msgs (:messages a))]
        (say "  rebuild ran: entries" (count (:entries a)) " messages" (count (:messages a)))
        (show-compare "rebuild (:messages)"
                      (texts-of rms)
                      (texts-of (filterv #(owned-by-model-runs? % m-runs) rms))
                      t))
      (catch Throwable e
        (say "  rebuild FAILED:" (.getName (class e)) "-" (ex-message e))))
    ;; 2. sofar
    (try
      (let [a   (replay/sofar f)
            rms (reasoning-msgs (:messages a))]
        (say "  sofar ran: state" (pr-str (:state a)) " messages" (count (:messages a)))
        (show-compare "sofar (:messages)"
                      (texts-of rms)
                      (texts-of (filterv #(owned-by-model-runs? % m-runs) rms))
                      t))
      (catch Throwable e
        (say "  sofar FAILED:" (.getName (class e)) "-" (ex-message e))))
    ;; 3. the page route
    (try
      (let [es     (replay/entries records)
            tail   (session/tail-of es)
            base   (:baseSeq tail)
            tms    (reasoning-msgs (mapv :message (:entries tail)))
            before (when (some? base) (session/before-of es (inc (long base))))]
        (say "  page route ran: tail entries" (count (:entries tail))
             " baseSeq" base " hasMore" (:hasMore tail))
        (show-compare "page tail (GET .../page)"
                      (texts-of tms)
                      (texts-of (filterv #(owned-by-model-runs? % m-runs) tms))
                      t)
        (when before
          (let [bms (reasoning-msgs (mapv :message (:entries before)))]
            (say "  page before: beforeSeq" (inc (long base))
                 " entries" (count (:entries before)) " baseSeq" (:baseSeq before))
            (show-compare "page before (?beforeSeq)"
                          (texts-of bms)
                          (texts-of (filterv #(owned-by-model-runs? % m-runs) bms))
                          t))))
      (catch Throwable e
        (say "  page FAILED:" (.getName (class e)) "-" (ex-message e))))
    ;; 4. trajectory
    (when t
      (show-compare "trajectory (:turns[].items[].reasoning)" t t t))))

;; ------------------------------------------------- D. the fold without the frames
;;
;; THE TICKET'S OWN CELL, ON REAL FILES (review of ticket 03). Every log on disk today was
;; written BEFORE the write side stopped recording reasoning frames, so on it the fold still
;; takes the reasoning off the FRAMES and the row path -- `attach-reasoning`, the thing the
;; change is FOR -- is never asked. Here each real log is folded twice: as it is, and as the
;; reasoning-free copy section A already wrote. THAT COPY IS THE NEW WRITE SIDE'S SHAPE (the
;; run's own `message` rows, no reasoning frames), so the two folds must hand back the same
;; conversation, message for message.
;;
;; MESSAGES ARE COMPARED, NEVER NUMBERS. An entry's `:seq` is the record line it arrived in and
;; the dropped frames spent lines, so the two numbers differ by construction; what is compared
;; is `:message`, the thing a client keys and reads.

(defn- message-diffs
  "[[i original-message frame-free-message] ..] -- where the two MESSAGE lists part ways, in
  order, a missing one reported as ::absent. BOTH sides of each pair are handed back so the
  first differing messages can be printed as evidence rather than a bare `false`."
  [a b]
  (->> (range (max (count a) (count b)))
       (keep (fn [i]
               (let [x (get a i ::absent) y (get b i ::absent)]
                 (when (not= x y) [i x y]))))))

(defn- blank-reasoning?
  "Is M a reasoning message whose WHOLE text is blank? The frames build one for a whitespace-only
  reasoning delta and the row path deliberately does not (`attach-reasoning`'s `str/blank?` guard
  reads a blank `reasoning_content` as 'this call reported none'); that is ADR 0009's accepted
  difference, and D filters the frames fold of them before comparing."
  [m]
  (and (= "reasoning" (:role m)) (str/blank? (str (:content m)))))

(defn- same-but-id?
  "Do X and Y carry the SAME item under a DIFFERENT id -- same role and content (and, for a tool
  call, the same call)? Asking this of a differing position is how D says the difference is the
  row path's NUMBERING rather than a different word."
  [x y]
  (and (map? x) (map? y)
       (not= (:id x) (:id y))
       (boolean (or (:id x) (:id y)))
       (= (:role x) (:role y))
       (= (:content x) (:content y))
       (= (:toolCalls x) (:toolCalls y))
       (= (:toolCallId x) (:toolCallId y))))

(defn- run-id-of
  "The run an `<run-id>-r<n>` id belongs to. Run ids are UUIDs, so the LAST `-r` is the suffix."
  [id]
  (let [s (str id) at (str/last-index-of s "-r")]
    (if at (subs s 0 at) s)))

(defn- by-run
  "[[run-id n] ..]: RUNS counted, in order. One shape of answer for the two questions D asks --
  which runs lost a thought the frames carried, and which runs spell one the frames fold does not."
  [runs]
  (->> runs frequencies (sort-by key) (mapv (fn [[r n]] [r n]))))

(defn- reasoning-dropped-by-run
  "[[run-id n] ..]: the reasoning messages the frames fold A has and the frame-free fold B does
  not -- matched by BOTH id and text -- grouped by the run the id names, in order. A run that
  wrote no model row shows up here with ALL of its thoughts; the blank thoughts do too, which is
  why D reports the two kinds apart rather than as one number."
  [a b]
  (let [spelled (set (map (juxt :id :content) (reasoning-msgs b)))]
    (->> (reasoning-msgs a)
         (remove (fn [m] (spelled ((juxt :id :content) m))))
         (map #(run-id-of (:id %)))
         by-run)))

(defn- fold-with-and-without-reasoning-frames
  "Fold F as the record stands and fold the reasoning-free copy section A wrote, then report
  whether the two conversations are the same ONCE THE ONE ACCEPTED DIFFERENCE IS TAKEN OUT.

  THE ACCEPTED DIFFERENCE IS A BLANK THOUGHT (ADR 0009). The frames build a reasoning message for
  a whitespace-only reasoning delta; the row path's `attach-reasoning` reads a blank
  `reasoning_content` as 'this call reported none' and builds nothing. So the frames fold is
  filtered of those messages FIRST and the number dropped is REPORTED, never counted as a pass.

  WHAT IS LEFT IS COMPARED MESSAGE FOR MESSAGE (`:message`; `:seq` is left out because a record's
  line offsets cannot survive a rewrite). When it still differs the first two differing pairs are
  printed AND the difference is told apart by KIND: a position whose only difference is the `:id`
  is the row path's numbering, not a different word.

  The copy is `no-reasoning-copy`'s, the same file `measure-sizes` wrote; this only READS it."
  [tag ^java.io.File f]
  (let [copy       (no-reasoning-copy f)
        orig       (replay/rebuild f)
        stripped   (replay/rebuild copy)
        om         (:messages orig)
        blanks     (filterv blank-reasoning? om)
        om-bare    (filterv (complement blank-reasoning?) om)
        sm         (:messages stripped)
        diffs      (message-diffs om-bare sm)
        r-orig     (count (reasoning-msgs om))
        r-stripped (count (reasoning-msgs sm))
        frame-only (runs-with-reasoning-frames-but-no-model-row (replay/read-records f))
        frame-runs (set (map first frame-only))
        ;; THE COST TAKEN OUT AS WELL: with the frames fold's reasoning for a run that wrote no
        ;; model row dropped too, is what remains the frame-free conversation? A YES names the
        ;; whole remaining difference as that run, and the count is never added to the blank's.
        om-cost    (filterv (fn [m] (not (and (= "reasoning" (:role m))
                                              (contains? frame-runs (run-id-of (:id m))))))
                            om-bare)
        diffs-cost (message-diffs om-cost sm)
        lost       (reasoning-dropped-by-run om sm)
        id-only    (count (filter (fn [[_ x y]] (same-but-id? x y)) diffs))
        gone-a     (count (filter (fn [[_ x _]] (= x ::absent)) diffs))
        gone-b     (count (filter (fn [[_ _ y]] (= y ::absent)) diffs))
        spell      (set (map (juxt :id :content) (reasoning-msgs om-bare)))
        unspelled  (remove #(spell ((juxt :id :content) %)) (reasoning-msgs sm))
        unknown    (count unspelled)]
    (say "")
    (say "== D. the same conversation, folded with the reasoning frames deleted :: " tag)
    (say "  file:            " (.getPath f))
    (say "  frame-free copy: " (.getPath copy))
    (say "  original:  messages" (count om) " reasoning messages" r-orig)
    (say "  blank reasoning the frames built and the row path deliberately does not"
         "(ADR 0009's accepted difference, NOT a pass):" (count blanks))
    (when (seq blanks)
      (say "    dropped from the frames fold, ids:" (pr-str (mapv :id blanks))))
    (say "  frames fold minus blank reasoning: messages" (count om-bare)
         "  frame-free: messages" (count sm) " reasoning messages" r-stripped)
    (say "  message lists equal (by :message -- :seq differs by construction):" (empty? diffs))
    (when (seq diffs)
      (say "  message positions that differ:" (count diffs)
           "  same role+content, only the :id differs:" id-only)
      (say "    original-message missing:" gone-a "  frame-free-message missing:" gone-b)
      (doseq [[i x y] (take 2 diffs)]
        (say "    at message" i)
        (say "      original:   " (if (= x ::absent) "::absent (nothing here)" (pr-str x)))
        (say "      frame-free: " (if (= y ::absent) "::absent (nothing here)" (pr-str y))))
      (when (> (count diffs) 2)
        (say "    ... and" (- (count diffs) 2) "more differing positions")))
    ;; THE ANTI-VACUITY LINE: an equality of two empty folds would prove nothing.
    (say "  reasoning recovered WITHOUT the frames:" r-stripped
         (if (pos? r-stripped)
           "(the run's own `message` rows carry it)"
           "-- VACUOUS, nothing was folded on either side"))
    ;; WHAT THE FRAMES CARRIED AND THE FRAME-FREE FOLD DOES NOT, BY RUN. The accepted blank and a
    ;; run with no model row are DIFFERENT prices and are reported as different numbers.
    (say "  reasoning the frames carried and the frame-free fold does not, by run:" (pr-str lost))
    (say "  runs that streamed reasoning and wrote no model row:" (pr-str frame-only))
    (when (seq frame-only)
      (say "  with those runs' reasoning dropped too, message lists equal:" (empty? diffs-cost)))
    (say "  frame-free reasoning whose (id, text) the frames fold does not spell:" unknown)
    (when (seq unspelled)
      (say "    (recomputed after the blank reasoning was dropped) by run:"
           (pr-str (by-run (map #(run-id-of (:id %)) unspelled)))))))
;; ---------------------------------------------------------------- run
(say "==========================================================")
(say "read routes -- ticket 03 of .scratch/reasoning-out-of-the-record")
(say "isolated config root:" (.getPath (io/file iso-root)))
(say "os temp dir:" (System/getProperty "java.io.tmpdir"))
(say "real config home (READ ONLY):" real-home)
(say "logs read:")
(doseq [{:keys [tag stem]} logs]
  (say "  " tag "->" (.getPath (log-file stem))))

(doseq [{:keys [tag stem]} logs]
  (measure-sizes tag (log-file stem)))

(doseq [{:keys [tag stem]} logs]
  (routes-for-log tag (log-file stem)))

(doseq [{:keys [tag stem]} logs]
  (fold-with-and-without-reasoning-frames tag (log-file stem)))

(say "")
;; ---------------------------------------------------------------- isolation verdict
(let [opened (db/store-paths-opened)
      real   (.getAbsolutePath (io/file real-home))]
  (say "isolation: store files this process opened:" (pr-str opened))
  (say "isolation: any of them under the real config home?"
       (boolean (some #(str/starts-with? (str %) real) opened))))
(say "done.")
(shutdown-agents)
