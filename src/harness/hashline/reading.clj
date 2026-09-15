(ns harness.hashline.reading
  "Reading a file as anchored rows: is it even a text file, and what does the
  model get shown.

  WHY THE TEXT CHECK IS HERE AND NOT IN THE TOOL. `read` is one tool with two
  behaviours, and only one of them needs to know whether a file is text. Putting
  the question next to the rendering keeps the tool a dispatch and keeps this
  answer testable against a file and nothing else -- no session, no store, no
  tool call.

  REFUSAL, NOT DELEGATION. Upstream hands images to its host's built-in read,
  which attaches them visually. This harness has no such host capability, so an
  image is refused with a message that says so. Pretending to support it would be
  worse than the refusal: the model would send an image, get garbage, and have to
  work out why.

  WHAT COUNTS AS TEXT, and why the test is conservative. A NUL byte in the first
  8 KB means binary in every file format that matters, and a BOM for UTF-16/32
  means 'text, but not the text this reader decodes' -- slurping one as UTF-8
  produces a plausible-looking string full of NULs, which is the worst possible
  outcome: no error, wrong content, and anchors minted against it. So both are
  refusals. A UTF-8 BOM is NOT a refusal: the file still decodes correctly, and
  the BOM is line 1's first character like any other."
  (:require [clojure.string :as str])
  (:import [java.io File RandomAccessFile]))

;; ------------------------------------------------------------- the limits

(def max-file-bytes
  "A file bigger than this is refused rather than read. 100 MiB, upstream's
  number: past it the whole-file read this design rests on stops being something
  a session can do, and `write` is the honest answer."
  (* 100 1024 1024))

(def line-budget
  "How many rows one call may return. 2000, matching the host read upstream
  replaces: enough for a real source file, small enough that a 50,000-line log
  comes back as a first page with an offset rather than as a wall."
  2000)

(def byte-budget
  "How many bytes of ROW TEXT one call may return, before the per-line cap below
  is even considered. 50 KB, again upstream's."
  51200)

(def ^:private sniff-bytes 8192)

(def ^:private row-cap
  "A single row longer than this is not shown. The ANCHOR is still emitted, so
  the line stays addressable -- the model is told how long it is and how to look
  at it another way, and can still replace it."
  51200)

;; ---------------------------------------------------------- is this text

(defn- sample ^bytes [^File f]
  (with-open [raf (RandomAccessFile. f "r")]
    (let [n (int (min sniff-bytes (.length raf)))
          bs (byte-array n)]
      (.readFully raf bs)
      bs)))

(defn- starts-with? [^bytes bs ^bytes magic]
  (and (>= (alength bs) (alength magic))
       (loop [i 0]
         (if (= i (alength magic))
           true
           (if (= (aget bs i) (aget magic i))
             (recur (inc i))
             false)))))

(defn- magic [& xs]
  (byte-array (map unchecked-byte xs)))

(def ^:private image-magics
  {"JPEG" (magic 0xFF 0xD8 0xFF)
   "PNG"  (magic 0x89 0x50 0x4E 0x47)
   "GIF"  (magic 0x47 0x49 0x46 0x38)
   "BMP"  (magic 0x42 0x4D)})

(defn- image-kind
  "The image format BS starts with, or nil. WebP is checked by hand because its
  magic is two tags apart."
  [^bytes bs]
  (or (some (fn [[k m]] (when (starts-with? bs m) k)) image-magics)
      (when (and (starts-with? bs (magic 0x52 0x49 0x46 0x46))   ; RIFF
                 (>= (alength bs) 12)
                 (= (aget bs 8) (unchecked-byte 0x57))           ; W
                 (= (aget bs 9) (unchecked-byte 0x45))           ; E
                 (= (aget bs 10) (unchecked-byte 0x42))          ; B
                 (= (aget bs 11) (unchecked-byte 0x50)))         ; P
        "WebP")))

(defn- encoding-name
  "The UTF-16/32 flavour BS opens with, or nil. Both are text, and both are text
  this reader cannot decode -- which is why they are refused by NAME rather than
  left to come back as a string full of NULs."
  [^bytes bs]
  (let [b (fn [i] (when (< i (alength bs)) (bit-and (aget bs i) 0xff)))]
    (cond
      (and (= 0xFF (b 0)) (= 0xFE (b 1)) (= 0x00 (b 2)) (= 0x00 (b 3))) "UTF-32LE"
      (and (= 0x00 (b 0)) (= 0x00 (b 1)) (= 0xFE (b 2)) (= 0xFF (b 3))) "UTF-32BE"
      (and (= 0xFF (b 0)) (= 0xFE (b 1)))                               "UTF-16LE"
      (and (= 0xFE (b 0)) (= 0xFF (b 1)))                               "UTF-16BE"
      :else nil)))

(defn- has-nul?
  "Does BS contain a zero byte? The one cheap test every binary format fails and
  no text file does."
  [^bytes bs]
  (loop [i 0]
    (cond
      (= i (alength bs)) false
      (zero? (aget bs i)) true
      :else (recur (inc i)))))

(defn classify
  "What KIND of file F is, for a reader that can only do text: :text, or a
  refusal thrown by name.

  Every refusal says what the file is, why that cannot be read as anchored rows,
  and -- where there is one -- what to do instead. A refusal whose reader cannot
  act on it is a dead end, so each of these carries its own way out."
  [^File f]
  (let [abs (.getAbsolutePath f)]
    (cond
      (not (.exists f))
      (throw (ex-info (str "no such file: " abs) {:path abs :reason :missing}))

      (.isDirectory f)
      (throw (ex-info (str abs " is a directory, not a file. List it with"
                           " `bash` (ls), then read the file you want.")
                      {:path abs :reason :directory}))

      (> (.length f) max-file-bytes)
      (throw (ex-info (str abs " is " (.length f) " bytes, over the "
                           max-file-bytes "-byte limit for an anchored read."
                           " Use `write` to replace it wholesale, or `bash`"
                           " (head/tail/grep) to work with part of it.")
                      {:path abs :reason :too-large :bytes (.length f)}))

      (zero? (.length f))
      :text  ; an empty file is text: it is one empty line, anchored like any other

      :else
      (let [bs (sample f)]
        (cond
          (encoding-name bs)
          (throw (ex-info (str abs " is " (encoding-name bs) " encoded text, which"
                               " anchored editing does not decode. Convert it"
                               " first (iconv -f " (str/lower-case (encoding-name bs))
                               " -t utf-8), then read it again.")
                          {:path abs :reason :encoding :encoding (encoding-name bs)}))

          (image-kind bs)
          (throw (ex-info (str abs " is a " (image-kind bs) " image. This harness"
                               " has no way to attach an image to a read; use"
                               " `bash` if you need something about the file itself"
                               " (file, ls -l).")
                          {:path abs :reason :image :format (image-kind bs)}))

          (has-nul? bs)
          (throw (ex-info (str abs " looks like a binary file (it contains a NUL"
                               " byte). Anchored editing only reads text; use"
                               " `bash` for the parts you need.")
                          {:path abs :reason :binary}))

          :else :text)))))

(defn read-text
  "F's contents, decoded UTF-8. `classify` is the gate: everything this decodes
  has already been established to BE text in a form this reader understands."
  [^File f]
  (slurp f :encoding "UTF-8"))

;; -------------------------------------------------------------- the rows

(def hash-sep
  "The separator between an anchor and its line: U+2502, BOX DRAWINGS LIGHT
  VERTICAL. Not a pipe (`|`) -- the anchor table is curated for TOKENIZERS, and
  `anchor│` landing as three tokens is the property the whole table was chosen
  for. A plain pipe would look almost identical and tokenize differently."
  "│")

(defn display-line
  "The line as a row shows it: carriage returns removed, since the checksum
  ignores them and a trailing CR would be invisible-but-present in the output."
  [^String line]
  (str/replace line "\r" ""))

(defn row
  "One row, as the model reads it."
  [anchor ^String line]
  (str anchor hash-sep (display-line line)))

(defn- utf8-length ^long [^String s]
  (alength (.getBytes s "UTF-8")))

(defn row-for
  "The row for line N in PATH, whatever its size. A line over the per-line cap is
  replaced by a SHORT row that keeps its anchor: the line is still perfectly
  editable, the model just cannot see all of it in this view, and it is told how
  long the line is and how to look at it another way.

  Computing the row BEFORE measuring it is what keeps the byte budget honest. The
  budget is about what the model RECEIVES, and an oversized line's row is a couple
  of hundred bytes -- so measuring the LINE instead would stop the page short for a
  reason the model never sees, and hide the anchors of the lines after it.

  Public because `anchor_grep` needs exactly this: its rows carry a line number
  column in front, and an oversized line has to become the same short row there as
  it does here, with the same anchor and the same advice."
  [^String path ^String line anchor ^long n]
  (if (> (utf8-length line) row-cap)
    (row anchor (str "[Line " n " is " (utf8-length line) " bytes, over the "
                     row-cap "-byte row limit; its content is not shown."
                     " Inspect it with bash (sed -n '" n "p' " path " | head -c "
                     row-cap "), or replace the whole line by this anchor]"))
    (row anchor line)))

(defn- footer
  "The trailing line when a read stopped short: what was shown, and the offset
  that continues. The offset is the point -- a model that has to derive it from a
  line count will get it wrong, and the right value is known right here."
  [^long shown-to ^long total ^String why]
  (str "[Showing lines 1-" shown-to " of " total " — " why
       ". Use offset=" (inc shown-to) " to continue.]"))

(defn preview
  "CONTENT as anchored rows for ANCHORS, honouring OFFSET (1-based, default 1) and
  LIMIT (optional), and stopping at the byte budget whatever the limit says.

  OPTS also carries :path, which appears in two messages a reader may need to act
  on: an oversized row says how to inspect the line with bash, and that advice is
  useless without the file it names.

  Returns:

    :text         what to hand the model, footer included
    :shown        the anchors whose rows were EMITTED -- including an oversized
                  row's, since the model was told its name. This is what a later
                  edit may address; see harness.hashline.store for why it is kept.
    :shown-to     how many lines from the top were covered
    :next-offset  the offset that continues, or nil at the end

  The byte budget is checked per ROW, so a page never ends mid-line: a model that
  received half a line would try to edit half a line."
  [^String content anchors {:keys [offset limit path]}]
  (let [lines (mapv display-line (harness.hashline.anchors/split-lines content))
        total (count lines)
        start (dec (long (or offset 1)))
        end   (min total (long (or (when limit (+ start (long limit))) total)))]
    (when (>= start total)
      (throw (ex-info
              (str "offset " (inc start) " is past the end of the file ("
                   total (if (= 1 total) " line" " lines")
                   "). Use offset=1 to read from the start"
                   (when (pos? total)
                     (str ", or offset=" total " to read the last line"))
                   ".")
              {:offset (inc start) :lines total :reason :offset-past-end})))
    (cond
      ;; An empty file is ONE empty line (see anchors/split-lines), and it is the
      ;; anchor on that line that gives the model a way to fill it.
      (and (= 1 total) (= "" (first lines)))
      {:text  (str (row (first anchors) "")
                   "\n[The file is empty. Use replace to insert content.]")
       :shown #{(first anchors)}
       :shown-to 1
       :next-offset nil}

      :else
      (let [page (loop [i start, bytes 0, out [], shown #{}]
                   (if (>= i end)
                     {:rows out :shown shown :to i}
                     (let [a     (nth anchors i)
                           r     (row-for path (nth lines i) a (inc i))
                           rsize (utf8-length r)]
                       (if (and (pos? bytes) (> (+ bytes rsize) byte-budget))
                         {:rows out :shown shown :to i}
                         (recur (inc i) (+ bytes rsize) (conj out r) (conj shown a))))))
            shown-to (:to page)
            why      (cond
                       (>= shown-to total) nil
                       (>= shown-to end)   (str "stopped at limit=" (or limit "—"))
                       :else               (str "stopped at the " byte-budget
                                                "-byte budget"))
            body     (str/join "\n" (:rows page))]
        (if (zero? (count (:rows page)))
          ;; Only reachable if a SINGLE row text exceeds the whole byte budget,
          ;; which the per-line cap makes impossible in practice -- but a page of
          ;; zero rows is the one outcome nothing can be done with, so the row is
          ;; emitted anyway rather than the answer being nothing.
          (let [a (nth anchors start)]
            {:text  (row-for path (nth lines start) a (inc start))
             :shown #{a}
             :shown-to (inc start)
             :next-offset nil})
          {:text  (if why (str body "\n" (footer shown-to total why)) body)
           :shown (:shown page)
           :shown-to shown-to
           :next-offset (when why (inc shown-to))})))))

