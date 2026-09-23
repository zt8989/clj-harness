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
  (reset! (var-get #'mux/connections) {}))

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
