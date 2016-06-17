(ns com.walmartlabs.datascope
  (:require [rhizome.viz :as viz]
            [clojure.string :as str])
  (:import [clojure.lang ISeq IPersistentVector IPersistentMap IDeref IPersistentSet]))

(defprotocol Scalar
  "Scalars appear as keys and values and this assists with rendering of them as such."

  (as-label [v]
    "Return the text representation of a value such that it can be included as a label for a key or value."))

(extend-protocol Scalar

  Object
  (as-label [v] (pr-str v))

  nil
  (as-label [_] "nil"))


(defprotocol Composite
  "Other types wrap one or more values (a map, sequence, vector, etc.)"

  (composite-type [v]
    "Returns :map, :seq, :vec. This key is used when identifying empty values and so forth.")

  (render-composite [v state]
    "Renders the value as a new node (this is often quite recursive).

    Returns a tuple of the new state, and the node-id just rendered.

    State has a number of keys:

    :values
    : map from a non-scalar value to its unique id; this is used to create edges, and track
      which values have already been rendered.

    :nodes
    : map from value id to rendered text for the node; rendering adds a value to this map.

    :edges
    : map from node id to node id; the source node is may be extended with a port (e.g., \"map_1:v3\")\""))

(def ^:private label-start "[label=<<table border=\"0\" cellborder=\"1\">")

(def ^:private label-end "</table>>]")

(defn ^:private type->node-id
  "Returns tuple of updated state and node id."
  [state type]
  (let [ix (get state ::ix 0)]
    [(assoc state ::ix (inc ix))
     (str (name type) "_" ix)]))

(defn ^:private value->node-id
  "Computes a unique Graphviz node id for the value (based on the value's composite type).

  Returns tuple of updated state and node id."
  [state value]
  (type->node-id state (composite-type value)))

(defn ^:private composite-key
  [value]
  (System/identityHashCode value))

(defn ^:private maybe-render
  "Maybe render the value (recursively).

  For composite values, returns a tuple of the state (updated state,
  if the value was a recursive render) and the (preiously) rendered node id.

  For scalar values, returns a tuple of state and nil."
  [state v]
  ;; Anything not a Composite is a Scalar
  (if-not (satisfies? Composite v)
    [state nil]
    (let [type (composite-type v)
          k (composite-key v)]
      (if-let [node-id (get-in state [:values type k])]
        [state node-id]
        (render-composite v state)))))

(defn ^:private html-safe
  [s]
  (-> s
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))


(defn ^:private type-name
  [obj]
  (str "<font point-size=\"8\">"
       (-> obj
           class
           .getName
           (str/replace #"^clojure\.lang\." "")
           html-safe)
       "</font>"))

(defn ^:private type-name-row
  [obj]
  (str "<tr><td border=\"0\">"
       (type-name obj)
       "</td></tr>"))

(defn ^:private cell
  [state id prefix v i]
  (let [[state' value-id] (maybe-render state v)
        scalar? (nil? value-id)
        port (when-not scalar? (str (name prefix) i))
        new-state (if port
                    (assoc-in state' [:edges (str id ":" port)] value-id)
                    state')]
    [new-state (str (if port
                      (str "<td port=" \" port \" "> ")
                      "<td>")
                    (when scalar?
                      (-> v as-label html-safe))
                    "</td>")]))

(defn ^:private add-composite-mapping
  "Store a composite value for later access (this prevents identical or same nodes from being rendered
  multiple times).  Keyed on the value's composite type, then the composite value itself, the ultimate value is the
  node-id.

  Sequences may be infinite and can't be used as keys, so in some cases,
  no mapping is added: equivalent sequences will render as distinct nodes."
  [state value node-id]
  (let [k (composite-key value)]
    (assoc-in state [:values (composite-type value) k] node-id)))

(defn ^:private add-node
  [state value node-id node]
  "Add the rendered text for a node for later inclusion into the DOT output."
  (assoc-in state [:nodes (composite-type value) node-id] node))

(defn ^:private add-empty
  [state v]
  (let [[state' node-id] (type->node-id state :empty)
        empty-label (pr-str v)]
    [(-> state'
         (add-composite-mapping v node-id)
         (assoc-in [:nodes :empty node-id] (str "[label=" \" empty-label \" \])))
     node-id]))

(defn ^:private render-map
  [state m]
  (if (empty? m)
    (add-empty state m)
    (let [[state' node-id] (value->node-id state m)
          reduction-step (fn [state k v i]
                           (let [[k-state k-chunk] (cell state node-id :k k i)
                                 [v-state v-chunk] (cell k-state node-id :v v i)]
                             [v-state (str "<tr>"
                                           k-chunk
                                           v-chunk
                                           "</tr>")]))
          ikvs (map-indexed (fn [i [k v]]
                              [i k v])
                            m)
          reducer (fn [[state rows-chunk] [i k v]]
                    (let [[state' row-chunk] (reduction-step state k v i)]
                      [state' (str rows-chunk row-chunk)]))
          [state'' key-value-rows] (reduce reducer
                                       [(add-composite-mapping state' m node-id) ""]
                                       ikvs)]
      [(add-node state'' m node-id (str label-start
                                        "<tr><td colspan=\"2\" border=\"0\">"
                                        (type-name m)
                                        "</td></tr>"
                                        key-value-rows
                                        label-end))
       node-id])))

(defn ^:private render-ref
  [state ref]
  (let [[state-1 node-id] (value->node-id state ref)
        ref-val (deref ref)
        [state-2 target-node-id] (maybe-render state-1 ref-val)
        label (str "<<b>"
                   (type-name ref)
                   "</b>"
                   (when-not target-node-id
                     (str "<br/>" (-> ref-val as-label html-safe)))
                   ">")
        state-3 (cond-> (-> state-2
                            (add-composite-mapping ref node-id)
                            (add-node ref node-id (str "[label=" label "]")))
                  target-node-id (assoc-in [:edges node-id] target-node-id))]
    [state-3 node-id]))

(defn ^:private render-elements
  "Renders the elements as a series of TR/TD rows.  Returns tuple of updated state and
  the rendered elements (as a string)."
  [state node-id coll]
  (let [reducer (fn [[state label-chunk] i v]
                  (let [[state' cell-chunk] (cell state node-id :i v i)]
                    [state' (str label-chunk "<tr>" cell-chunk "</tr>")]))]
    (reduce-kv reducer
               [(add-composite-mapping state coll node-id) ""]
               coll)))

(defn ^:private render-vector
  [state v]
  (if (empty? v)
    (add-empty state v)
    (let [[state' node-id] (value->node-id state v)
          [state'' element-rows] (render-elements state' node-id v)]
      [(add-node state'' v node-id (str label-start
                                        (type-name-row v)
                                        element-rows
                                        label-end))
       node-id])))

(defn ^:private render-seq
  [state coll]
  (if (empty? coll)
    (add-empty state coll)
    (let [[state' node-id] (value->node-id state coll)
          max-seq (:max-seq state 10)
          reducer (fn [[state label-chunk] [i v]]
                    (if (= i max-seq)
                      (reduced [state (str label-chunk "<tr><td border=\"0\">...</td></tr>")])
                      (let [[state' cell-chunk] (cell state node-id :i v i)]
                        [state' (str label-chunk "<tr>" cell-chunk "</tr>")])))
          ivs (->> (map vector (iterate inc 0) coll)
                   (take (inc max-seq)))
          [state'' element-rows] (reduce reducer
                                         [(add-composite-mapping state' coll node-id) ""]
                                         ivs)]
      [(add-node state'' coll node-id (str label-start
                                           (type-name-row coll)
                                           element-rows
                                           label-end))
       node-id])))

(defn ^:private render-set
  [state coll]
  (if (empty? coll)
    (add-empty state coll)
    (let [[state' node-id] (value->node-id state coll)
          [state'' label-chunk] (render-elements state' node-id (vec coll))]
      [(add-node state'' coll node-id (str label-start
                                           (type-name-row coll) label-chunk
                                           label-end))
       node-id])))

(extend-protocol Composite

  IPersistentMap

  (render-composite [m state]
    (render-map state m))

  (composite-type [_] :map)

  IPersistentVector

  (render-composite [v state]
    (render-vector state v))

  (composite-type [_] :vec)

  IPersistentSet
  (render-composite [set state]
    (render-set state set))

  (composite-type [_] :set)

  ISeq

  (render-composite [coll state]
    (render-seq state coll))

  (composite-type [_] :seq)

  IDeref

  (render-composite [ref state]
    (render-ref state ref))

  (composite-type [_] :ref))

(defn ^:private render-nodes
  [nodes key defaults]
  (when-not (str/blank? defaults)
    (println (str " \n node [" defaults "];")))
  (doseq [[id text] (get nodes key)]
    (println (str "  " id " " text ";"))))

(defn dot
  "Given a root composite value, returns the DOT (Graphviz
  description) of the "
  [root-value]
  {:pre [(satisfies? Composite root-value)]}
  (let [[{:keys [nodes edges]}] (render-composite root-value {})]
    (with-out-str
      (println "digraph G {\n  rankdir=LR;")
      (println "  node [shape=plaintext, style=\"rounded,filled\", fillcolor=\"#FAF0E6\"];")
      (render-nodes nodes :map "")
      (render-nodes nodes :seq "")
      (render-nodes nodes :vec "style=filled")
      (render-nodes nodes :set "style=filled")
      (render-nodes nodes :ref "shape=ellipse")
      (render-nodes nodes :empty "shape=none, style=\"\", fontsize=32")
      (println)
      (doseq [[from to] edges]
        (println (str "  " from " -> " to ";")))
      (println "}"))))

(defn view
  "Renders the root value as a Graphviz document, then
  uses Rhizome to open a frame to view the document."
  [root-value]
  (-> root-value
      dot
      viz/dot->image
      viz/view-image))
