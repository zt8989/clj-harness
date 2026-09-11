(ns harness.llm
  "Provider layer. One multimethod, dispatched on :protocol.

  Contract for every method:
    (stream! provider messages on-event) -> assistant message

  ON-EVENT is called with each harness.event value as it is produced. The returned
  assistant message is provider-shaped and is appended to the history VERBATIM by
  harness.loop -- never rebuilt. That is what keeps reasoning_content alive across
  tool rounds, which DeepSeek requires whenever the request carries tools
  (omitting it there is a hard HTTP 400).")

(defmulti stream!
  (fn [provider _messages _on-event] (:protocol provider)))
