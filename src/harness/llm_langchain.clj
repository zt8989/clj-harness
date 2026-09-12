(ns harness.llm-langchain
  "langchain4clj-backed provider (:protocol :langchain4clj).

  Translates the harness provider-shaped history + tool specs into LangChain4j
  ChatMessages / ToolSpecifications, calls the (blocking) chat API, and
  translates the ChatResponse back into the provider shape harness.loop expects.

  Design, consistent with .scratch/langchain4clj-provider/spec.md:
   - blocking chat, not SSE (streaming handler is a later ticket);
   - harness.loop keeps ownership of the ReAct loop AND tool execution, so
     langchain4j is used only to GENERATE text + tool calls, never to run them;
   - tool arguments round-trip as the raw JSON string the model emits, handed to
     harness.tools/run! verbatim (so the kernel's own executor stays authoritative);
   - reasoning_content is a known gap: only providers LangChain4j recognizes
     surface reasoning (OpenAI o-series via :return-thinking); DeepSeek/OpenRouter
     reasoning_content is not surfaced here, matching the spec's conflict table."
  (:require [clojure.walk :as walk]
            [harness.event :as ev]
            [harness.llm :as llm]
            [harness.tools :as tools]
            [langchain4clj.core :as lc]
            [langchain4clj.messages :as lcmsg]
            [langchain4clj.tools :as lctools])
  (:import [dev.langchain4j.data.message AiMessage]
           [dev.langchain4j.model.chat.response ChatResponse]
           [java.util ArrayList]))

;; ----------------------------------------------------- harness history -> LC EDN

(defn- tool-name-map
  "tool_call_id -> tool name, so a later :role \"tool\" result can be labelled."
  [messages]
  (into {}
        (for [m messages
              :when (= "assistant" (:role m))
              tc (:tool_calls m)]
          [(:id tc) (get-in tc [:function :name])])))

(defn ->lc-edn
  "One harness provider-shaped message -> langchain4clj EDN message.
  Inverse of the shape langchain4clj.messages/edn->message accepts."
  ([m] (->lc-edn m {}))
  ([m id->name]
   (case (:role m)
     "system"  {:type :system :text (:content m)}
     "user"    {:type :user :text (:content m)}
     "assistant" {:type :ai
                  :text (or (:content m) "")
                  :tool-execution-requests
                  (mapv (fn [tc] {:id (:id tc)
                                  :name (get-in tc [:function :name])
                                  :arguments (get-in tc [:function :arguments])})
                        (:tool_calls m))}
     "tool"    {:type :tool-result
                :id (:tool_call_id m)
                :tool-name (get id->name (:tool_call_id m) "")
                :text (:content m)}
     ;; unknown role: surface as user text so the call still goes through
     {:type :user :text (or (:content m) "")})))

(defn lc-edn->messages
  "harness history -> a vector of LangChain4j ChatMessage objects.

  A Clojure vector (not the ArrayList edn->messages returns) is required:
  langchain4clj's build-chat-request keys off (sequential? message), and
  java.util.ArrayList reports false there -- a vector is Sequential."
  [messages]
  (vec (lcmsg/edn->messages (mapv #(->lc-edn % (tool-name-map messages)) messages))))

;; --------------------------------------------------------- harness specs -> LC

(defn- string-keys->keyword-keys [m]
  "Recursively turn map keys that are strings into keywords, so harness's
  string-keyed JSON-Schema matches the keyword keys build-json-schema expects.
  Values (e.g. \"string\", \"object\", enum literals) are left untouched."
  (walk/postwalk
    (fn [x]
      (if (map? x)
        (reduce-kv (fn [acc k v] (assoc acc (if (string? k) (keyword k) k) v))
                   {} x)
        x))
    m))

(defn specs->tool-specs
  "harness.tools/specs (OpenAI shape) -> a vector of LangChain4j ToolSpecification."
  []
  (mapv (fn [openai-spec]
          (lctools/create-tool-specification
            {:name        (get-in openai-spec [:function :name])
             :description (get-in openai-spec [:function :description])
             :parameters  (string-keys->keyword-keys
                            (get-in openai-spec [:function :parameters]))}))
        (tools/specs)))

;; --------------------------------------------------------- ChatResponse -> harness

(defn parse-chat-response
  "ChatResponse -> {:text s :tool-calls [{:id :name :arguments}]}.
  arguments is the raw JSON string the model emitted; harness.tools/run! parses it."
  [^ChatResponse resp]
  (let [ai    (.aiMessage resp)
        text  (or (.text ai) "")
        calls (mapv (fn [r] {:id (.id r) :name (.name r) :arguments (.arguments r)})
                    (.toolExecutionRequests ai))]
    {:text text :tool-calls calls}))

;; --------------------------------------------------------------------- dispatch

(defn- build-model [provider]
  (lc/openai-model
    (cond-> {:api-key (:api-key provider) :model (:model provider)}
      (:base-url provider) (assoc :base-url (:base-url provider)))))

(defmethod llm/stream! :langchain4clj
  [{:as provider} messages on-event]
  (let [lc-msgs (lc-edn->messages messages)
        specs   (specs->tool-specs)
        model   (build-model provider)
        ;; Always pass an opts map (non-empty) so chat returns a ChatResponse we can
        ;; parse uniformly; an empty :tools seq is simply not sent.
        resp    (lc/chat model lc-msgs {:tools (or specs [])})
        {:keys [text tool-calls]} (parse-chat-response resp)]
    (when (seq text) (on-event (ev/text-delta text)))
    (doseq [{:keys [id name arguments]} tool-calls]
      (on-event (ev/tool-call id name arguments)))
    (cond-> {:role "assistant" :content text}
      (seq tool-calls) (assoc :tool_calls
                              (mapv (fn [{:keys [id name arguments]}]
                                      {:id id :type "function"
                                       :function {:name name :arguments arguments}})
                                    tool-calls)))))
