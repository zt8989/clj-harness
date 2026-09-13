(ns harness.wire
  "This project's own minimal model of an AG-UI client, plus the structural rules that
  client enforces. Lives under dev/ rather than test/ because two callers need it and
  neither should own it: the tests (which assert the same contract) and any dev-side
  tooling that inspects an SSE stream.

  Since ticket 05 the frame applier and the terminal check live in src/ as
  harness.frames -- rebuilding a conversation from recorded frames is a product
  capability now. What is left here is purely TEST TOOLING:

    - frames-from-sse, which parses a raw SSE body into frames so tests can assert
      on what the wire actually carried;
    - violations, the structural rule check, which every SSE-reading test runs to
      prove the stream was well-formed end to end.

  It is deliberately NOT in src/. The kernel emits events; it does not consume them."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [harness.frames :as frames]))

(defn frames-from-sse
  "The AG-UI events carried by a raw SSE body."
  [body]
  (->> (str/split-lines body)
       (keep (fn [line]
               (when (str/starts-with? line "data:")
                 (json/read-str (str/trim (subs line 5)) :key-fn keyword))))
       vec))

(defn violations
  "Every structural rule an AG-UI client enforces, checked across a whole stream.
  Returns a vector of human-readable problems; empty means the stream is valid."
  [frames]
  (let [open (atom {})
        bad  (atom [])]
    (doseq [f frames]
      (let [t  (:type f)
            id (or (:messageId f) (:toolCallId f))]
        (cond
          (str/ends-with? (str t) "_CHUNK")
          (swap! bad conj (str "chunk event emitted: " t))

          (contains? #{"TEXT_MESSAGE_START" "REASONING_MESSAGE_START"} t)
          (if (contains? @open id)
            (swap! bad conj (str "double START for " id))
            (swap! open assoc id :message))

          (contains? #{"TEXT_MESSAGE_CONTENT" "REASONING_MESSAGE_CONTENT"} t)
          (when-not (contains? @open (:messageId f))
            (swap! bad conj (str "CONTENT for unopened " (:messageId f))))

          (contains? #{"TEXT_MESSAGE_END" "REASONING_MESSAGE_END"} t)
          (if (contains? @open (:messageId f))
            (swap! open dissoc (:messageId f))
            (swap! bad conj (str "END for unopened " (:messageId f))))

          (= t "TOOL_CALL_START") (swap! open assoc id :tool)

          (= t "TOOL_CALL_ARGS")
          (when-not (contains? @open id) (swap! bad conj (str "ARGS for unopened " id)))

          (= t "TOOL_CALL_END") (swap! open dissoc id)

          (frames/terminal? f)
          (do (when (seq @open) (swap! bad conj (str "left open at " t ": " (keys @open))))
              (when-not (= f (last frames))
                (swap! bad conj (str "frames after " t)))))))
    @bad))
