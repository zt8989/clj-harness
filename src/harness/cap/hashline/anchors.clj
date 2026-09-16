(ns harness.cap.hashline.anchors
  "Anchors, and the checksum that makes one safe to trust.

  AN ANCHOR IS A FOUR-LETTER NAME FOR A LINE. `read` puts one at the front of
  every row it emits, and `replace`/`insert` address a line by that name instead
  of by its content or its position. Three properties do the work:

    - it is OPAQUE. The model copies the four letters; it never constructs one,
      never infers one from the text, and never counts line numbers. A name it
      makes up is rejected because it is not in the table, and a name it copied
      from a stale read is rejected because the line's checksum moved.
    - it is UNIQUE WITHIN A SESSION. Two lines never share one, including two
      lines with byte-identical content -- which is the whole reason this exists,
      since `old_string` cannot tell those apart and the model has to guess.
    - it is STABLE ACROSS AN EDIT when the line did not move. An edit frees the
      anchors it removed, keeps the ones whose content and position survived, and
      mints fresh ones for what is new, so the diff handed back after an edit is
      full of anchors that still work.

  ALLOCATED, NEVER DERIVED. An anchor is not a hash of its line. It is taken from
  a fixed pool in an order that depends only on (a) where the session's probe
  currently stands and (b) which anchors that session still owns. A content-
  derived name would have to collide gracefully -- two identical lines wanting
  the same name -- and 'gracefully' there means a disambiguation rule the model
  has to learn. A pool walks around the problem: it is big enough that two live
  lines never collide, and if it ever ran out that is said out loud rather than
  papered over.

  ------------------------------------------------------------------ the pool

  THE POOL IS A BIT SET OVER THE ANCHOR UNIVERSE, which is every 4-letter string
  over A-Za-z: 52^4 = 7,311,616 of them, of which 1,353,139 are in the pool. The
  artifact is `resources/hashline/anchor-table.bin.gz` -- 913,952 bytes of bit
  set, 85 KB gzipped -- and bit v is set when the anchor whose base-52 value is v
  is a pool member.

  WHY NOT JUST STORE THE TABLE. Upstream ships the anchors as a 5.4 MB JSON
  string, which gzips to 2.8 MB. The pool is 18.5% dense, so a bit set is 85 KB
  gzipped -- three percent of that, carrying exactly the same information. It is
  also the shape the operations want: 'the i-th anchor' is a select, 'which index
  is this anchor' is a rank, and 'is this anchor real' is a bit test, none of
  which need the strings to exist anywhere.

  WHICH ANCHORS ARE IN IT, AND WHY THEY ARE THOSE. Curated upstream by measuring
  tokenizers: `anchor│` (four letters plus the separator) has to come out as
  exactly three tokens across the models a coding agent is likely to run on. That
  is not a property this repo can re-derive, so the table is vendored rather than
  generated -- and the vendoring is CHECKED rather than trusted (below).

  HOW TO REGENERATE IT, and how to check it is intact. From upstream's
  src/hashline/anchor-table.json:

    - read the `anchors` field (the 1,353,139 four-letter groups concatenated
      with no separator; sha256 of that string is
      67175ac4d50e17f422529982591649d6988a8681e78e6a5ca1b7eebf174ecb4d,
      5,412,556 bytes);
    - for each group take its base-52 value, with `alphabet` below in order
      (A=0 ... z=51), and set that bit -- LSB first within each byte;
    - write the 913,952 bytes and gzip them.

  The result's sha256 is `bitmap-digest`, and the loader refuses to run on
  anything else -- so the artifact is exact or nothing, never a quietly smaller
  or differently-ordered pool that would hand two lines the same name. Anyone can
  reproduce it from the original and check this repo's word for it.

  A NOTE ON THE ONE THING THAT IS NOT UPSTREAM'S. Upstream seeds a session's
  probe from the session key AND the process id, so two processes mint disjoint
  sequences. This port seeds it from the session key ALONE: the premise here is
  that anchors survive a restart, and a per-process seed would make the same
  session answer differently in two processes -- which is precisely the property
  that would then have to be argued rather than tested."
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io InputStream]
           [java.net URL]
           [java.nio ByteBuffer]
           [java.security MessageDigest]
           [java.util.function Supplier]
           [java.util.zip GZIPInputStream]))

;; --------------------------------------------------------------- the pool

(def alphabet
  "The anchor alphabet, IN ORDER -- and the order is load-bearing: an anchor's
  position in the universe is its base-52 value, so reordering these letters
  renumbers every anchor and invalidates the digest check."
  "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz")

(def anchor-length 4)

(def universe
  "How many four-letter strings there are."
  (int (reduce (fn [n _] (* n 52)) 1 (range anchor-length))))

(def ^:private bitmap-bytes (quot universe 8))

(def ^:private bitmap-digest
  "sha256 of the artifact's UNCOMPRESSED bytes. The recipe that produces it from
  upstream's JSON is in the namespace docstring."
  "d3a1643fa3a309220acc8235fcc0f4a865ce0b0abfa25c64cdf375e989c3a206")

(def ^:private superblock-bits 8192)

(def ^:private superblock-bytes (quot superblock-bits 8))

(def ^:private alphabet-value
  "char -> its base-52 digit, or -1. An int-array rather than a map because
  `anchor-index` runs once per anchor a request mentions, and because 'this char
  is not in the alphabet' has to be answerable without boxing."
  (let [a (int-array 128 -1)]
    (doseq [[i c] (map-indexed vector alphabet)]
      (aset a (int c) (int i)))
    a))

(defn- digest-of ^bytes [^bytes bs]
  (.digest (MessageDigest/getInstance "SHA-256") bs))

(defn- hex
  "BS as lowercase hex, all of it."
  [^bytes bs]
  (let [digits "0123456789abcdef"
        sb     (StringBuilder. (* 2 (alength bs)))]
    (dotimes [i (alength bs)]
      (let [b (bit-and (aget bs i) 0xff)]
        (.append sb (.charAt digits (bit-shift-right b 4)))
        (.append sb (.charAt digits (bit-and b 0x0f)))))
    (.toString sb)))

(defn- hex8
  "The first 8 bytes of BS as 16 hex characters -- the form every CHECKSUM here
  takes. Eight bytes of SHA-256 is 64 bits, far below any collision worth
  planning for across one file's lines, and it keeps a checksum no wider than the
  anchor it travels beside.

  The artifact's own digest is NOT shortened (see `read-bitmap`): it is written
  to be checked against `sha256sum` by somebody regenerating the file, and that
  answer is 64 characters long."
  [^bytes bs]
  (subs (hex bs) 0 16))

(defn- read-bitmap
  "The artifact, uncompressed and checked. Every failure here is NAMED and says
  what it found: a pool that is missing, short, or not the one this build was
  written against must stop the run, never become a smaller pool that hands two
  lines the same name."
  ^bytes []
  (let [^URL res (io/resource "hashline/anchor-table.bin.gz")]
    (when-not res
      (throw (ex-info (str "the anchor table is not on the classpath"
                           " (resources/hashline/anchor-table.bin.gz);"
                           " \"resources\" must be in deps.edn :paths")
                      {:resource "hashline/anchor-table.bin.gz" :reason :missing})))
    (let [bs (with-open [in (GZIPInputStream. (.openStream res))]
               (.readAllBytes ^InputStream in))]
      (when-not (= bitmap-bytes (alength bs))
        (throw (ex-info (str "the anchor table is corrupt: " (alength bs)
                             " bytes, expected " bitmap-bytes)
                        {:bytes (alength bs) :expected bitmap-bytes
                         :reason :wrong-length})))
      (let [found (hex (digest-of bs))]
        (when-not (= bitmap-digest found)
          (throw (ex-info (str "the anchor table is not the one this build knows:"
                               " sha256 " found " of the uncompressed bytes,"
                               " expected " bitmap-digest
                               " -- see NOTICE and this namespace's docstring for"
                               " how it is derived from upstream")
                          {:digest found :expected bitmap-digest :reason :digest}))))
      bs)))

(defn- bit-counts
  "Set bits in BITS at byte indices [FROM, TO)."
  ^long [^bytes bits ^long from ^long to]
  (loop [i from, acc 0]
    (if (>= i to)
      (long acc)
      (recur (inc i) (+ acc (Integer/bitCount (bit-and (aget bits i) 0xff)))))))

(defn- build-pool
  "BITS plus the select index over it: `:select` holds one entry per block plus a
  final total, and `(select b)` is how many pool members lie before block b. With
  that, the i-th member costs one binary search and a scan of at most one block."
  [^bytes bits]
  (let [blocks (inc (quot (dec universe) superblock-bytes))
        select (long-array (inc blocks))
        total  (loop [b 0, seen 0]
                 (if (>= b blocks)
                   seen
                   (let [from (* b superblock-bytes)
                         to   (min bitmap-bytes (+ from superblock-bytes))]
                     (aset select b (long seen))
                     (recur (inc b) (+ seen (bit-counts bits from to))))))]
    (aset select blocks total)
    {:bits bits :select select :total total}))

(defonce ^:private pool (delay (build-pool (read-bitmap))))

(defn pool-size
  "How many anchors the pool holds. Counted from the artifact rather than
  declared, so it cannot disagree with the table it describes."
  ^long []
  (long (:total ^:private @pool)))

(defn- select
  "The value of the I-TH pool member, 0-based. Callers have already range-checked."
  ^long [^long i]
  (let [{:keys [bits ^longs select]} @pool
        block (loop [lo 0, hi (dec (alength select))]
                (if (>= lo hi)
                  lo
                  (let [mid (quot (+ lo hi 1) 2)]
                    (if (<= (aget select mid) i)
                      (recur mid hi)
                      (recur lo (dec mid))))))
        want  (- i (aget select block))
        from  (* block superblock-bytes)
        to    (min bitmap-bytes (+ from superblock-bytes))]
    (loop [b from, seen 0]
      (let [byte (bit-and (aget bits b) 0xff)
            cnt  (Integer/bitCount byte)]
        (if (< (+ seen cnt) (inc want))
          (recur (inc b) (+ seen cnt))
          ;; WANT falls inside this byte: walk its set bits until the (want-seen)-th.
          (let [w (long (- want seen))]
            (loop [v 0, n 0]
              (if (bit-test byte v)
                (if (= n w) (+ (* b 8) v) (recur (inc v) (inc n)))
                (recur (inc v) n)))))))))

(defn- base52
  "V as four letters of `alphabet`, most significant first."
  [^long v]
  (let [cs (char-array anchor-length)]
    (loop [v v, i (dec anchor-length)]
      (if (neg? i)
        (String. cs)
        (do (aset cs (int i) (.charAt alphabet (int (rem v 52))))
            (recur (quot v 52) (dec i)))))))

(defn anchor-at
  "The I-TH member of the pool, 0-based, as a four-letter string."
  [i]
  (let [n (pool-size)]
    (when-not (and (integer? i) (<= 0 i) (< i n))
      (throw (ex-info (str "anchor index " (pr-str i) " is outside the pool (0.."
                           (dec n) ")")
                      {:index i :pool-size n :reason :out-of-range})))
    (base52 (select (long i)))))

(defn- value-of
  "S as its base-52 value, or nil when it is not four letters of the alphabet."
  [^String s]
  (when (and (string? s) (= anchor-length (.length s)))
    (loop [i 0, v 0]
      (if (= i anchor-length)
        v
        (let [c (int (.charAt s i))]
          (if (or (>= c 128) (neg? (aget alphabet-value c)))
            nil
            (recur (inc i) (+ (* v 52) (aget alphabet-value c)))))))))

(defn anchor-index
  "Where ANCHOR sits in the pool, 0-based, or nil when it is not a member.

  Nil covers both ways of not being a member -- not four letters of the alphabet,
  and four letters that are simply not in the curated table -- because every
  caller wants the same answer to both: this is not a name we hand out."
  [^String anchor]
  (when-let [v (value-of anchor)]
    (let [{:keys [bits ^longs select]} @pool]
      (when (bit-test (bit-and (aget bits (quot v 8)) 0xff) (bit-and v 7))
        (let [block (quot v superblock-bits)
              from  (* block superblock-bytes)]
          (+ (aget select block)
             (bit-counts bits from (quot v 8))
             ;; The byte V sits in, counted only BELOW V -- a whole-byte count
             ;; would include V's own bit and every one above it.
             (Integer/bitCount (bit-and (aget bits (quot v 8))
                                        (dec (bit-shift-left 1 (bit-and v 7)))))))))))

(defn anchor?
  "Is ANCHOR a name this process hands out?"
  [anchor]
  (some? (anchor-index anchor)))

(defn anchor-shape?
  "Could ANCHOR be MEANT as one? Four alphanumerics -- looser than `anchor?`,
  because the slips a model makes are as often digits as letters, and a rejection
  that says 'not a 4-character anchor' is more useful than 'not a pool member'
  when the real problem is a pasted line number."
  [anchor]
  (boolean (and (string? anchor)
                (re-matches #"[A-Za-z0-9]{4}" anchor))))

;; ------------------------------------------------------------ the checksum

(def max-hash-source-bytes
  "How much of a line is hashed. A line longer than this shares its checksum with
  any other line matching in its first 500 bytes -- upstream's trade, kept: the
  alternative is hashing a megabyte-long minified line on every read, and what it
  risks is a missed staleness signal on such a line, not a wrong edit."
  500)

(defn canonical
  "LINE as the checksum sees it: every carriage return removed, trailing
  whitespace removed, LEADING whitespace kept.

  This is what lets an anchor survive somebody saving the file in an editor. CRLF
  and LF become the same bytes, and a line that gained or lost trailing spaces is
  the same line. Indentation is NOT normalized, because it is content.

  Both ends of this store run this function, so what matters is not matching
  JavaScript's whitespace set exactly but agreeing with ourselves; the one place
  it could show is exotic trailing whitespace (a non-breaking space), which
  Java's stripTrailing does not consider whitespace and JS's trimEnd does."
  [^String line]
  (-> line (.replace "\r" "") (.stripTrailing)))

(defn- truncate-bytes
  "S cut to at most MAX UTF-8 bytes, never through a code point. Cutting may lose
  a whole character; it may not produce bytes that do not decode."
  ^String [^String s ^long max]
  (let [bs (.getBytes s "UTF-8")]
    (if (<= (alength bs) max)
      s
      (let [end (loop [k max]
                  ;; Byte k begins a new code point (it is not a continuation
                  ;; byte) exactly when everything before it is a whole sequence.
                  (if (or (zero? k) (not= 0x80 (bit-and (aget bs k) 0xc0)))
                    k
                    (recur (dec k))))]
        (String. bs 0 end "UTF-8")))))

(def ^:private thread-digest
  "A digest per thread. `line-checksum` runs once per line of every file a request
  touches, and MessageDigest is neither thread-safe nor cheap to create, so
  neither a shared instance nor a fresh one per line is right -- this is the shape
  that is both."
  (ThreadLocal/withInitial
   (reify Supplier (get [_] (MessageDigest/getInstance "SHA-256")))))

(defn line-checksum
  "The checksum of one line: SHA-256/16 of its canonical form, truncated to
  `max-hash-source-bytes` first."
  ^String [^String line]
  (let [^MessageDigest md (.get thread-digest)]
    (.reset md)
    (hex8 (.digest md (.getBytes (truncate-bytes (canonical line) max-hash-source-bytes)
                                 "UTF-8")))))

(defn split-lines
  "CONTENT as a vector of lines, in the one reading an anchor can be attached to:
  `\\n` separates, a trailing newline does not open an empty last line, and an
  EMPTY FILE IS ONE EMPTY LINE rather than none.

  That last rule is upstream's and it is the useful one, because it gives an empty
  file something to address: `read` can show one row the model may replace, and
  `insert` can put the first content after it. Zero lines would leave an empty
  file with nothing to name and the model with no way in."
  [^String content]
  (let [normalized (.replace content "\r\n" "\n")
        parts      (vec (.split normalized "\n" -1))]
    (cond
      (= 1 (count parts))            parts
      (.endsWith normalized "\n")    (subvec parts 0 (dec (count parts)))
      :else                          parts)))

(defn line-checksums
  "The per-line checksums of CONTENT, in order."
  [^String content]
  (mapv line-checksum (split-lines content)))

(defn file-checksum
  "One checksum for a whole file, from its LINE checksums -- what `line-checksums`
  returns, joined. Equal line checksums in the same order give an equal file
  checksum, and a file whose lines moved gives a different one.

  Derived from the line checksums rather than hashed from raw bytes, so the two
  questions 'did this line change' and 'did this file change' can never disagree:
  a file checksum over raw bytes would move on a CRLF conversion that left every
  line checksum identical, and the snapshot would then look stale for no reason."
  [lines]
  (let [^MessageDigest md (.get thread-digest)]
    (.reset md)
    (hex8 (.digest md (.getBytes (str/join " " lines) "UTF-8")))))

;; ---------------------------------------------------------- the allocation

(def anchor-stride
  "How far the probe jumps per allocation: 836,286, coprime with the pool size
  1,353,139 (their only common factor is 1) and about 1.618 of it -- the golden
  ratio, which is why successive anchors land in unrelated regions of the table
  instead of marching through it. The coprimality is what makes the walk visit
  every member exactly once before repeating."
  836286)

(def probe-limit
  "How many occupied positions the probe walks past before giving up. Sized so an
  exhausted pool fails in milliseconds rather than scanning 1.35 million entries
  first."
  8192)

(defn seed
  "Where SESSION-KEY's probe starts. Folded from SHA-256 of the key, so two
  sessions begin in unrelated regions of the pool and the same session begins in
  the same place every time -- including in another process, which is what makes
  a session's new anchors reproducible across a restart. See the namespace
  docstring's note on upstream's process id."
  ^long [session-key]
  (let [bs (digest-of (.getBytes (str session-key) "UTF-8"))]
    (Long/remainderUnsigned (.getLong (ByteBuffer/wrap bs 0 8)) (pool-size))))

(defn mint
  "The next anchor for a session's `{:probe :owned}`, and the probe it left
  behind.

  Walks with `anchor-stride`, skipping anything this session already owns. It
  deliberately does NOT skip anchors the session used and then freed: re-issuing
  one of those is wanted (a deleted line's name becomes available again), and the
  only anchor that must never repeat is one a live line still answers to -- which
  is exactly what `:owned` is.

  A pool with nothing free is a NAMED failure. It cannot happen by accident at
  1,353,139 anchors, and the honest answer to a session that has genuinely run out
  is to say so rather than start recycling names behind the model's back."
  [{:keys [probe owned]}]
  (let [n (pool-size)]
    (loop [p (long (or probe 0)), tries 0]
      (if (>= tries probe-limit)
        (throw (ex-info (str "the anchor pool is exhausted: this session owns "
                             (count owned) " of " n " anchors and the next "
                             probe-limit " positions were all taken."
                             " Use write for very large files.")
                        {:owned (count owned) :pool-size n :probe p
                         :reason :pool-exhausted}))
        (let [a (anchor-at p)]
          (if (contains? owned a)
            (recur (mod (+ p anchor-stride) n) (inc tries))
            {:anchor a :probe (mod (+ p anchor-stride) n)}))))))

;; ----------------------------------------------------------- the alignment

(defn- survivor?
  "May the anchor at a positional slot survive? The checksums have to agree, and
  the anchor must not be owned for some OTHER path -- an anchor names a line in
  one file for one session, and that is the one invariant an alignment must never
  break. The ownership half is cheap and is kept even though a snapshot and its
  ownership are written together: the invariant should not rest on that."
  [owned path anchor]
  (let [holder (get owned anchor)]
    (or (nil? holder) (= holder path))))

(defn- mint-into
  "Mint one anchor for SLOT into STATE."
  [{:keys [path]} slot state]
  (let [{:keys [anchor probe]} (mint state)]
    (-> state
        (assoc-in [:anchors slot] anchor)
        (assoc :probe probe)
        (update :owned assoc anchor path)
        (update :added conj anchor))))

(defn- keep-into
  "Carry an old anchor forward into the new slot it still names."
  [state slot anchor]
  (-> state (assoc-in [:anchors slot] anchor) (update :kept conj anchor)))

(defn- align-spans
  "The alignment when the caller knows what its edit did. Returns STATE."
  [{:keys [old-anchors old-checksums new-checksums path] :as ctx} spans state]
  (let [old-n (count old-anchors)]
    (loop [spans spans
           cursor {:old 0 :new 0}
           state state]
      (if-not (seq spans)
        ;; Everything after the last span held still too.
        (reduce (fn [state i]
                  (let [shift (- (:new cursor) (:old cursor))]
                    (keep-into state (+ i shift) (nth old-anchors i))))
                state
                (range (:old cursor) old-n))
        (let [{:keys [old-start old-end new-start new-end]} (first spans)
              shift (- (:new cursor) (:old cursor))
              ;; ...and so did everything between the previous span and this one.
              state (reduce (fn [state i]
                              (keep-into state (+ i shift) (nth old-anchors i)))
                            state
                            (range (:old cursor) old-start))
              state (reduce (fn [state s]
                              (let [o    (+ old-start s)
                                    slot (+ new-start s)
                                    was  (when (and (< o old-end) (< o old-n))
                                           (nth old-anchors o nil))]
                                (if (and was
                                         (= (nth old-checksums o) (nth new-checksums slot))
                                         (survivor? (:owned state) path was))
                                  (keep-into state slot was)
                                  (mint-into ctx slot state))))
                            state
                            (range 0 (- new-end new-start)))]
          (recur (rest spans) {:old old-end :new new-end} state))))))

(defn- align-shared
  "The alignment when nobody is saying how the file changed: keep the longest
  common prefix and suffix over the checksums and mint everything between."
  [{:keys [old-anchors old-checksums new-checksums] :as ctx} state]
  (let [n      (count new-checksums)
        old-n  (count old-anchors)
        limit  (min (count old-checksums) n)
        prefix (loop [i 0]
                 (if (and (< i limit) (= (nth old-checksums i) (nth new-checksums i)))
                   (recur (inc i))
                   i))
        suffix (loop [k 0]
                 (if (and (< (+ k prefix) (count old-checksums))
                          (< (+ k prefix) n)
                          (= (nth old-checksums (- (count old-checksums) 1 k))
                             (nth new-checksums (- n 1 k))))
                   (recur (inc k))
                   k))
        state  (reduce (fn [state slot] (mint-into ctx slot state))
                       state
                       (range prefix (- n suffix)))
        state  (reduce (fn [state i] (keep-into state i (nth old-anchors i)))
                       state
                       (range 0 prefix))]
    (reduce (fn [state k]
              (keep-into state (- n 1 k) (nth old-anchors (- old-n 1 k))))
            state
            (range 0 suffix))))

(defn align
  "The new anchors for a file after an edit, and what changed about ownership.

    :old-anchors    the anchors before the edit, one per old line
    :old-checksums  the checksums before the edit, one per old line
    :new-checksums  the checksums after the edit, one per new line
    :spans          what the edit did, as half-open ranges in BOTH coordinate
                    systems: {:old-start i :old-end j :new-start k :new-end l}
                    means old lines [i,j) became new lines [k,l). Ascending and
                    disjoint. Omit when the edit is not the caller's to describe
                    -- a file that changed on disk, say.
    :path           the file these anchors are about
    :owned          the session's ownership so far, anchor -> {:path :checksum}
    :probe          where the session's probe stands

  Returns {:anchors [...] :freed #{..} :added #{..} :probe n}, where `:added` is
  the set of anchors the caller must newly record as owned -- all of them for the
  file it is editing -- and `:freed` is what it must let go of. Anchors that
  survived are in NEITHER: they are already owned, and rewriting them would be
  churn.

  WITH SPANS the alignment is exact, because the caller knows what it did. Lines
  outside every span keep their anchors, because they did not move; inside a span
  a slot keeps its anchor only when that POSITION's content checksum is unchanged,
  which is what makes 'replace this line with itself' preserve the name.

  WITHOUT SPANS the file changed and nobody is saying how, so the fallback is
  prefix/suffix with the middle minted. That can only ever mint more than it had
  to, never keep an anchor that no longer describes its line -- and it is
  deliberately not a general diff. A real one would preserve more anchors across
  an edit made in somebody's editor; it would also be the most intricate thing in
  this namespace, so it waits until something needs it."
  [{:keys [old-anchors old-checksums new-checksums spans path owned probe]}]
  (let [old-anchors (vec (or old-anchors []))
        old-chs     (vec (or old-checksums []))
        n           (count new-checksums)
        base        {:anchors (vec (repeat n nil))
                     :kept    #{}
                     :added   #{}
                     :owned   (or owned {})
                     :probe   (long (or probe 0))}
        ctx         {:old-anchors   old-anchors
                     :old-checksums old-chs
                     :new-checksums new-checksums
                     :path          path}
        state       (if (seq spans)
                      (align-spans ctx spans base)
                      (align-shared ctx base))]
    (-> state
        ;; An old anchor is FREED unless it survived into the new file. The
        ;; survivors are already owned and already recorded; naming them here
        ;; would have the caller write, for every edit, a row it did not change.
        (assoc :freed (into #{}
                            (comp (map #(nth old-anchors % nil))
                                  (remove nil?)
                                  (remove (:kept state)))
                            (range 0 (count old-anchors))))
        (dissoc :kept)
        (update :probe long))))
