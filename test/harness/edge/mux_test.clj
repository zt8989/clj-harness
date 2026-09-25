(ns harness.edge.mux-test
  "The downlink's subscription rules, driven through the real route functions with a
  recording channel in place of a socket.

  WHY A FAKE CHANNEL RATHER THAN A SOCKET. What this ticket has to get right is WHICH
  frames a connection is sent -- filtering by what it subscribed to, one frame per change,
  a named ending for a window that is over -- and none of that is about the WebSocket
  framing underneath it. The wire itself (a handshake, a text frame, a reconnect) is the
  browser walkthrough's business; here the channel is a vector of JSON strings.

  AND IT IS A `reify` OF http-kit's OWN `Channel` PROTOCOL, not a stub of ours: the route
  calls `hk/send!`, so the test would fail to compile the day that call changed."
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [harness.edge.http :as http]
            [harness.edge.mux :as mux]
            [harness.edge.sessions :as sessions]
            [org.httpkit.server :as hk]))

(defn- forget-everything!
  "A clean table and a clean downlink registry per case. The registry is a `defonce`, so
  a case that left a connection behind would be the next case's phantom."
  []
  (doseq [tid (keys (sessions/live))] (sessions/drop! tid))
  (reset! (var-get #'mux/connections) {})
  ;; AND THE RUN BUFFERS, which outlive a connection on purpose (that is the point of them) --
  ;; a case must not inherit another case's remembered frames under the same thread id.
  (reset! (var-get #'mux/runs) {})
  ;; AND THE FACT RING IS THE SAME KIND OF THING (ticket 05), for the same reason: it outlives a
  ;; connection on purpose, so a case must not inherit another case's remembered facts.
  (reset! (var-get #'mux/facts) {}))

(use-fixtures :each (fn [f]
                      (forget-everything!)
                      (try (f) (finally (forget-everything!)))))

(defn- fake-channel
  "A channel that remembers the bytes written to it. `send!` is http-kit's protocol
  method, so this is the same door the route uses."
  [sent]
  (reify hk/Channel
    (open? [_] true)
    (websocket? [_] true)
    (close [_] nil)
    (send! [_ data] (swap! sent conj data) true)
    (send! [_ data _close-after] (swap! sent conj data) true)
    (on-receive [_ _] nil)
    (on-close [_ _] nil)
    (on-ping [_ _] nil)))

(defn- frames [sent]
  (mapv #(json/read-str % :key-fn keyword) @sent))

(defn- user [id text] {:id id :role "user" :content text})

(deftest a-connection-hears-the-conversations-it-subscribed-to-and-no-others
  (sessions/touch! "mux-a")
  (sessions/append! "mux-a" "g1" [(user "a1" "first")])
  (sessions/land! "mux-a" "g1" 10)
  (sessions/touch! "mux-b")
  (sessions/append! "mux-b" "g2" [(user "b1" "other")])

  (let [sent (atom [])
        ch   (fake-channel sent)]
    (testing "the opening frame is the window it asked for, tagged with its conversation"
      (#'http/mux-attend! "tok-1" ch [{:threadId "mux-a"}])
      (is (= ["mux-a"] (mapv :threadId (frames sent))))
      (is (= "window" (:type (first (frames sent)))))
      (is (= ["a1"] (mapv #(get-in % [:entries 0 :message :id]) (frames sent)))))

    (testing "a change to a subscribed conversation is pushed, with its thread id"
      (sessions/append! "mux-a" "g3" [(user "a2" "second")])
      (sessions/land! "mux-a" "g3" 20)
      (let [pushed (last (frames sent))]
        (is (= "mux-a" (:threadId pushed)))
        (is (= ["a2"] (mapv #(get-in % [:entries 0 :message :id]) [pushed])))))

    (testing "a change to a conversation this connection did NOT subscribe to reaches it not at all"
      (let [before (count @sent)]
        (sessions/append! "mux-b" "g4" [(user "b2" "third")])
        (is (= before (count @sent))
            "mux-b was never subscribed on this connection")))))

(deftest a-second-connection-on-another-conversation-is-not-cc-d
  (sessions/touch! "mux-c")
  (sessions/touch! "mux-d")
  (let [sent-c (atom []) sent-d (atom [])
        ch-c   (fake-channel sent-c) ch-d (fake-channel sent-d)]
    (#'http/mux-attend! "tok-c" ch-c [{:threadId "mux-c"}])
    (#'http/mux-attend! "tok-d" ch-d [{:threadId "mux-d"}])
    (let [c-before (count @sent-c) d-before (count @sent-d)]
      (sessions/append! "mux-c" "gc" [(user "c1" "for c")])
      (is (> (count @sent-c) c-before) "c's own connection hears c")
      (is (= d-before (count @sent-d)) "d's connection does not"))))

(deftest detaching-releases-every-subscription
  (sessions/touch! "mux-e")
  (let [sent (atom []) ch (fake-channel sent)]
    (#'http/mux-attend! "tok-e" ch [{:threadId "mux-e"}])
    (let [before (count @sent)]
      (mux/detach! "tok-e")
      (is (nil? (mux/channel "tok-e")))
      (sessions/append! "mux-e" "ge" [(user "e1" "after detach")])
      (is (= before (count @sent)) "a released connection is rung no more"))))

(deftest a-stale-generation-is-told-the-window-is-over
  (sessions/touch! "mux-f")
  (let [sent (atom []) ch (fake-channel sent)]
    (#'http/mux-attend! "tok-f" ch [{:threadId "mux-f" :generation "not-this-window"}])
    (let [frame (first (frames sent))]
      (is (= "end" (:type frame)))
      (is (= "mux-f" (:threadId frame)))
      (is (not (mux/subscribed? "tok-f" "mux-f")) "a refused window opens no watch"))))

(deftest the-subscribe-route-updates-a-live-connection
  (sessions/touch! "mux-g")
  (let [sent (atom []) ch (fake-channel sent)]
    (#'http/mux-attend! "tok-g" ch [])
    (testing "subscribe adds a conversation, from the cursor the body names"
      (let [resp (#'http/mux-subscribe-post
                  {:body (java.io.ByteArrayInputStream.
                          (.getBytes (json/write-str {:subscriber "tok-g"
                                                      :subscribe [{:threadId "mux-g"}]})
                                     "UTF-8"))})
            body (json/read-str (String. ^bytes (:body resp) "UTF-8") :key-fn keyword)]
        (is (= 200 (:status resp)))
        (is (= ["mux-g"] (:threads body)))
        (is (mux/subscribed? "tok-g" "mux-g"))
        (is (some #(= "mux-g" (:threadId %)) (frames sent)) "the opening frame went out")))
    (testing "unsubscribe releases it"
      (let [resp (#'http/mux-subscribe-post
                  {:body (java.io.ByteArrayInputStream.
                          (.getBytes (json/write-str {:subscriber "tok-g"
                                                      :unsubscribe ["mux-g"]})
                                     "UTF-8"))})]
        (is (= 200 (:status resp)))
        (is (not (mux/subscribed? "tok-g" "mux-g")))))))

(deftest the-subscribe-route-refuses-a-connection-it-cannot-address
  (testing "a connection that has closed is named, not silently accepted"
    (let [resp (#'http/mux-subscribe-post
                {:body (java.io.ByteArrayInputStream.
                        (.getBytes (json/write-str {:subscriber "gone" :subscribe []}) "UTF-8"))})]
      (is (= 404 (:status resp)))))
  (testing "no subscriber at all is a 400"
    (let [resp (#'http/mux-subscribe-post
                {:body (java.io.ByteArrayInputStream.
                        (.getBytes (json/write-str {:subscribe []}) "UTF-8"))})]
      (is (= 400 (:status resp))))))

(deftest a-downlink-without-a-token-is-refused-before-any-handshake
  (let [resp (#'http/mux-get {:query-string ""})]
    (is (= 400 (:status resp)))))

(deftest a-runs-frames-go-to-the-connections-watching-that-conversation
  ;; A RUN'S FRAMES ARE NOT A WINDOW CHANGE, so they do not ride the pump: the emitter
  ;; broadcasts them (http's `mux-broadcast!`), and only to the connections that subscribed
  ;; to THAT conversation -- the same filtering, one door over.
  (sessions/touch! "mux-run-a")
  (sessions/touch! "mux-run-b")
  (let [sent-a (atom []) sent-b (atom [])
        ch-a   (fake-channel sent-a) ch-b (fake-channel sent-b)]
    (#'http/mux-attend! "tok-run-a" ch-a [{:threadId "mux-run-a"}])
    (#'http/mux-attend! "tok-run-b" ch-b [{:threadId "mux-run-b"}])
    (let [a-before (count @sent-a)
          b-before (count @sent-b)]
      (#'http/mux-broadcast! "mux-run-a" {:type "TEXT_MESSAGE_CONTENT" :delta "hi" :runId "r1"})
      (is (= (inc a-before) (count @sent-a)) "exactly one frame arrived")
      (let [frame (last (frames sent-a))]
        (is (= "TEXT_MESSAGE_CONTENT" (:type frame)))
        (is (= "mux-run-a" (:threadId frame)))
        (is (= "r1" (:runId frame))))
      (is (= b-before (count @sent-b)) "the other conversation's connection hears nothing"))))

(deftest a-reconnecting-reader-is-handed-the-run-frames-it-missed
  ;; TICKET 03's remaining criterion: a run is a PUSH, so a socket that drops mid-run loses
  ;; the gap unless the sender REMEMBERED it. `mux-broadcast!` numbers and remembers; a second
  ;; connection that declares `runSince` gets the frames after that number.
  (sessions/touch! "mux-gap")
  (let [first-sent (atom []) first-ch (fake-channel first-sent)]
    (#'http/mux-attend! "tok-gap-1" first-ch [{:threadId "mux-gap"}])
    (#'http/mux-broadcast! "mux-gap" {:type "RUN_STARTED"})
    (#'http/mux-broadcast! "mux-gap" {:type "TEXT_MESSAGE_CONTENT" :delta "a"})
    (#'http/mux-broadcast! "mux-gap" {:type "TEXT_MESSAGE_CONTENT" :delta "b"})
    (let [run-frames (fn [sent]
                       (->> (frames sent)
                            (filterv #(contains? #{"RUN_STARTED" "TEXT_MESSAGE_CONTENT"} (:type %)))))]
      (is (= [1 2 3] (mapv :seq (run-frames first-sent)))
          "the broadcast numbers its frames from the run's start, and tags each with the thread")
      (is (= "mux-gap" (:threadId (second (run-frames first-sent)))))

      (testing "a connection that comes back saying how far it got is handed only the rest"
        (let [second-sent (atom []) second-ch (fake-channel second-sent)]
          (#'http/mux-attend! "tok-gap-2" second-ch [{:threadId "mux-gap" :runSince 2}])
          (let [replayed (run-frames second-sent)]
            (is (= [3] (mapv :seq replayed)))
            (is (= "b" (:delta (first replayed)))))))

      (testing "and a NIL cursor replays nothing -- the buffer may hold the PREVIOUS run's terminal"
        (let [third-sent (atom []) third-ch (fake-channel third-sent)]
          (#'http/mux-attend! "tok-gap-3" third-ch [{:threadId "mux-gap"}])
          (is (= [] (mapv :seq (run-frames third-sent)))
              "a connection about to START a run is handed none of the last one")
          (#'http/mux-broadcast! "mux-gap" {:type "TEXT_MESSAGE_CONTENT" :delta "c"})
          (is (= [4] (mapv :seq (run-frames third-sent)))
              "...and hears what happens after it subscribed"))))))

(deftest a-reconnecting-page-is-handed-the-facts-it-missed
  ;; TICKET 05 OF `.scratch/turn-and-model-events`. A fact is a PUSH like a run frame, and a push
  ;; nobody heard is gone -- so the sender remembers the last few (`harness.edge.mux/record-fact!`,
  ;; written by `family-send!`) and a reader declares how far it got.
  ;;
  ;; THE NUMBER IS THE RECORD'S LINE, so it is asked with its OWN cursor (`factSince`) rather than
  ;; the run's (`runSince`): the two count different things, and a reader can be current on one and
  ;; behind on the other.
  ;;
  ;; THE CHANNEL WRITES JSON STRINGS (`fake-channel` above is http-kit's own protocol), so the
  ;; facts are read back the way a socket reads them -- `json/read-str`, then filter by type.
  (let [facts (fn [sent]
                (->> @sent
                     (mapv #(json/read-str % :key-fn keyword))
                     (filterv #(contains? #{"turn/start" "turn/end" "model/start" "model/end"}
                                          (:type %)))
                     (mapv :seq)))
        sent  (atom [])
        ch    (fake-channel sent)]
    (#'http/mux-attend! "tok-fact-1" ch [{:threadId "mux-fact" :factSince 7}])
    (is (= [] (facts sent)) "a cursor that is current is handed nothing")
    (#'http/family-send! "mux-fact" {:type "model/end" :seq 8 :numbers {}})
    (is (= [8] (facts sent)) "a fact that lands after the cursor is pushed")
    (testing "and the page that was AWAY is handed the gap it missed"
      (let [back    (atom [])
            back-ch (fake-channel back)]
        (#'http/mux-attend! "tok-fact-2" back-ch [{:threadId "mux-fact" :factSince 7}])
        (is (= [8] (facts back)))))))

