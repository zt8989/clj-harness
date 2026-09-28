(ns harness.edge.normalized
  "已重整化（normalized）——这份记录能不能**继续写下去**（`.scratch/record-normalization`）。

  未重整化的会话是**只读**的：能看，不能续；要续必须先经 fork 重整化。

  这个命名空间**刻意不依赖 `harness.edge.replay`**：记录读侧要用它，反过来就成了环。
  它只需要两件最小的事——一行的种类（`type`）与它的信封（`payload`）。

  ## 判据是**对着真记录**定的（2026-09-28 的走查，`dev/scratch_normalized_census.clj`）

  主人那一句「以后所有的消息都要被那个 start/end 这种信封包裹」有两种读法，读数把第一种排除了：

  - **位置读法**（每条 message 行夹在自己的 START/END **之间**）——**真记录一条都不满足**。
    写手是「帧先落、它描述的那条 message 行紧跟其后」：一条工具答复是
    `TOOL_CALL_START/ARGS/END`（例：行 17–19）之后才有的 `message` 行（行 25），
    而它的正文**按结构不可能**出现在 `TOOL_CALL_END` 之前——结果得先算出来。
    按位置判，每个会话一打开就是只读，连刚写下的那一轮也不算数。
  - **成对读法**（信封成对、答复成对）——真记录全过，撕裂的那条不过。**落地的就是这一条。**

  三条判据，每一条都对着这台机器上真存在的记录：

    (1) 每一行都是当前格式：有 `type` 与 `payload`（不是旧契约裸露的 `kind`）；
        —— `~/.clj-harness/logs/*.jsonl` 就是反例（旧格式）；
    (2) 信封**成对**：每一个 `START`（`TEXT_MESSAGE_START` / `TOOL_CALL_START`）都有同一个身份的
        `END`，每一个 `END` 也都有它的 `START`。一组的 START 没有 END，就是一次没说完的话
        （run 被切断在一句话中间），这份记录不能续；
    (3) 工具调用与答复**成对**：每一个 `TOOL_CALL_START` 的 toolCallId 都要有一行 `message`
        以同一个身份答复它，反过来每一条工具答复也要有它自己的调用。
        —— 票 05 在 resume 之后丢掉的就是前半条：**答复只剩一行帧（TOOL_CALL_RESULT），没有行**。
        **例外**：**被停车等待人裁**的调用不算缺行——记录自己在 run 的终局帧的
        `outcome.interrupts` 里点了它的名，缺的不是行而是一个人的决定，答案由 resume 那一次补
        （`parked-calls`，与 `harness.edge.replay/open-runs` 同一个例外）。

  判据是**流式**的：一遍扫过，只攒几个集合（信封的身份、调用的身份、答复的身份），
  不建整份向量，也不看位置——所以它既能当 `fold` 挂进 `harness.edge.replay` 的那一趟读，
  也能对一份已经在手里的 seq 直接答。")

(def ^:private start-frames #{"TEXT_MESSAGE_START" "TOOL_CALL_START"})
(def ^:private end-frames   #{"TEXT_MESSAGE_END"   "TOOL_CALL_END"})

(defn- row-kind [row] (:type row))
(defn- row-payload [row] (:payload row))

(defn- frame-type
  "一行**帧**的类型。它住在 payload 里：记录的一行是
  `{:type \"event\" :payload {:type \"TEXT_MESSAGE_START\" ..}}`——行的种类是 `event`，
  帧的类型是 payload 里的那个。"
  [row]
  (get-in row [:payload :type]))

(defn- frame-id [row]
  (let [p (row-payload row)]
    (or (:messageId p) (:toolCallId p))))

(defn- answered-call
  "一条**工具答复**认领的那次调用：message 行的 payload 里的 `tool_call_id`（与帧的
  `toolCallId` 是同一个命名空间，`.scratch/jsonl-two-kinds` 票 02）。

  只认工具答复：客户自己那条、开篇块、系统提示、注入的上下文都不是「被谁包裹着」的消息——
  它们的身份（`:id`）是会话条目的身份，线上帧从来不为它们开信封。"
  [row]
  (get-in row [:payload :tool_call_id]))

(defn- parked-calls
  "The calls ROW's terminal names as WAITING FOR A HUMAN, or nil.
  
  THE INTERRUPT OBJECTS ARE THE AG-UI ONES (`harness.edge.ag-ui/interrupt-frame` wrote them), and
  the call they are about is their `toolCallId`. Reading them here is what keeps a parked run from
  being judged as damage: the record is not missing an answer, it is waiting for a decision, and
  the row for the answer is written by the resume run."
  [row]
  (keep :toolCallId (get-in row [:payload :outcome :interrupts])))
(defn- step
  "一份记录的一行 -> 账本的下一步。`ctx` 用不上：这份账只看行自己的信封，不看位置。

  形状与 `harness.edge.replay` 的 folds 一致：`(fn [acc ctx [line-index row]] acc)`。"
  [acc _ctx [_i row]]
  (let [acc (if (and (contains? row :type) (contains? row :payload))
              acc
              (update acc :old inc))]
    (if (= "message" (row-kind row))
      (if-some [call (answered-call row)]
        (update acc :answers conj call)
        acc)
      (let [t     (frame-type row)
            id    (frame-id row)
            ;; A RUN THAT ENDED ON AN INTERRUPT HAS SAID WHAT IS MISSING, AND IT IS NOT A ROW:
            ;; the calls named in `outcome.interrupts` are waiting for a PERSON to decide them
            ;; (the ordinary approval park, and the question park). The record is complete about
            ;; them -- the answer arrives on the resume run -- so they must not be read as calls
            ;; nobody answered. `harness.edge.replay/open-runs` exempts exactly these.
            acc   (if-some [parked (seq (parked-calls row))]
                    (update acc :parked into parked)
                    acc)]
        (cond
          (nil? id)                  acc
          (contains? start-frames t) (cond-> (update acc :opened conj id)
                                       (= "TOOL_CALL_START" t) (update :calls conj id))
          (contains? end-frames t)   (update acc :closed conj id)
          :else                      acc)))))
(def fold
  "这条判据挂进 `harness.edge.replay` 那**一趟读**的形状：`{:init (fn [] <空账>) :step step}`。
  它的最终值交给 `finish`。

  注意 `:init` 是**一个 thunk**：`harness.edge.replay/folds-init` 调的是 `((:init fold))`——
  少一层就是 `ClassCastException: fn cannot be cast to Associative`。"
  {:init (fn [] {:old 0 :opened #{} :closed #{} :calls #{} :answers #{} :parked #{}})
   :step step})

(defn finish
  "账本（`step` 攒下的、或 `fold` 交来的）-> 判定 `{:normalized? bool :reasons [句…]}`。
  理由是给人看的句子，前后端都用它。"
  [state]
  (let [{:keys [old opened closed calls answers parked]} state
        ;; AN ANSWER IS A ROW OR AN INTERRUPT: see `parked-calls` above.
        answered   (into (or answers #{}) (or parked #{}))
        unclosed   (remove closed opened)
        dangled    (remove opened closed)
        unanswered (remove answered calls)
        unclaimed  (remove calls answered)
        reasons    (cond-> []
                     (pos? (long (or old 0))) (conj (str old " 行还是旧格式（没有 type/payload 信封）"))
                     (seq unclosed)   (conj (str (count unclosed) " 组 START 没有它的 END（信封没收尾）"))
                     (seq dangled)    (conj (str (count dangled) " 组 END 没有它的 START"))
                     (seq unanswered) (conj (str (count unanswered) " 次工具调用没有 message 行答复"))
                     (seq unclaimed)  (conj (str (count unclaimed) " 条工具答复没有它的 TOOL_CALL_START")))]
    {:normalized? (empty? reasons) :reasons (vec reasons)}))

(defn normalized?
  "RECORDS（一行一个 map，任何 seq）是否已重整化，答 `{:normalized? bool :reasons [句…]}`——理由见命名空间
  的说明。一遍扫过，不建整份向量：与 `fold` 共用同一个 `step`/`finish`，所以两条路不会各说各话。"
  [records]
  (finish (reduce (fn [acc [i row]] (step acc nil [i row]))
                  ((:init fold))
                  (map-indexed vector records))))
