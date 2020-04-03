(ns com.walmartlabs.datascope
  (:require [rhizome.viz :as viz]
            [clojure.string :as str]
            [io.aviso.exception :refer [demangle]])
  (:import [clojure.lang ISeq IPersistentVector IPersistentMap IDeref IPersistentSet AFn Symbol]))

(defn ^:private html-safe
  [s]
  (-> s
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn ^:private demangle-fn
  [f]
  (let [class-name (-> f ^Class type .getName)
        [namespace-name & raw-function-ids] (str/split class-name #"\$")
        ;; Clojure adds __1234 unique ids to the ends of things, remove those.
        function-ids (map #(str/replace % #"__\d+" "") raw-function-ids)]
    ;; The assumption is that no real namespace or function name will contain underscores (the underscores
    ;; are name-mangled dashes).
    (->>
      (cons namespace-name function-ids)
      (map demangle)
      (str/join "/"))))

(defprotocol Scalar
  "Scalars appear as keys and values and this protocol assists with rendering of them as such."

  (as-label [v]
    "Return the text representation of a value such that it can be included as a label for a key or value.

    The returned value should be HTML safe."))

(extend-protocol Scalar

  Object
  (as-label [v] (-> v pr-str html-safe))

  Symbol
  (as-label [v]
    (str "<i>" (-> v pr-str html-safe) "</i>"))

  AFn
  (as-label [f]
    (str "<i>" (demangle-fn f) "</i>"))

  nil
  (as-label [_] "<i>nil</i>"))

(defprotocol Composite
  "A non-scalar type, which wraps one or more values (e.g., map, sequence, vector, reference, etc.)"
  (render-as-scalar? [v]
    "Returns true if should be rendered as a scalar. Typically, this means an empty collection.")

  (composite-type [v]
    "Returns :map, :seq, :vec, etc. This key is used when generating Graphviz node ids.")

  (render-composite [v state]
    "Renders the value as a new node (this is often quite recursive).

    Returns a tuple of the new state, and the node-id just rendered.

    State has a number of keys:

    :values
    : map from a non-scalar value to its unique node id; this is used to create edges, and track
      which values have already been rendered. The key is the default hash code for the
      value (e.g., keyed on identity, not on value).

    :nodes
    : map from node id to the text to render for that node (outer brackets are supplied).

    :edges
    : map from node id to node id; the source node is may be extended with a port (e.g., \"map_1:v3\")\""))

(def ^:private label-start "label=<<table border=\"0\" cellborder=\"1\">")

(def ^:private label-end "</table>>")

(defn ^:private composite-key
  [value]
  (System/identityHashCode value))

(defn ^:private value->node-id
  "Computes a unique Graphviz node id for the value (based on the value's composite type and
  composite key)."
  [value]
  (str (name (composite-type value)) "_" (composite-key value)))

(defn ^:private maybe-render
  "Maybe render the value (recursively).

  For composite values, returns a tuple of the state (updated state,
  if the value was a recursive render) and the (preiously) rendered node id.

  For scalar values, returns a tuple of state and nil."
  [state v]
  ;; Anything not a Composite is a Scalar
  (cond
    (not (satisfies? Composite v))
    [state nil]

    (render-as-scalar? v)
    [state nil]

    :else
    (let [type (composite-type v)
          k (composite-key v)]
      (if-let [node-id (get-in state [:values type k])]
        [state node-id]
        (render-composite v state)))))

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
                      (as-label v))
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
  [state node-id node]
  "Add the rendered text for a node for later inclusion into the DOT output."
  (assoc-in state [:nodes node-id] node))

(defn ^:private render-map
  [state m]
  (let [node-id (value->node-id m)
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
        [state' key-value-rows] (reduce reducer
                                        [(add-composite-mapping state m node-id) ""]
                                        ikvs)]
    [(add-node state' node-id
               (str label-start
                    "<tr><td colspan=\"2\" border=\"0\">"
                    (type-name m)
                    "</td></tr>"
                    key-value-rows
                    label-end))
     node-id]))

(defn ^:private render-ref
  [state ref]
  (let [node-id (value->node-id ref)
        ref-val (deref ref)
        [state-1 target-node-id] (maybe-render state ref-val)
        label (str "<<b>"
                   (type-name ref)
                   "</b>"
                   (when-not target-node-id
                     (str "<br/>" (-> ref-val as-label html-safe)))
                   ">")
        state-2 (cond-> (-> state-1
                            (add-composite-mapping ref node-id)
                            (add-node node-id
                                      (str "shape=ellipse, label=" label)))
                  target-node-id (assoc-in [:edges node-id] target-node-id))]
    [state-2 node-id]))

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
  (let [node-id (value->node-id v)
        [state' element-rows] (render-elements state node-id v)]
    [(add-node state' node-id
               (str "style=filled, "
                    label-start
                    (type-name-row v)
                    element-rows
                    label-end))
     node-id]))

(defn ^:private render-seq
  [state coll]
  (let [node-id (value->node-id coll)
        max-seq (:max-seq state 10)
        reducer (fn [[state label-chunk] [i v]]
                  (if (= i max-seq)
                    (reduced [state (str label-chunk "<tr><td border=\"0\">...</td></tr>")])
                    (let [[state' cell-chunk] (cell state node-id :i v i)]
                      [state' (str label-chunk "<tr>" cell-chunk "</tr>")])))
        ivs (->> (map vector (iterate inc 0) coll)
                 (take (inc max-seq)))
        [state' element-rows] (reduce reducer
                                      [(add-composite-mapping state coll node-id) ""]
                                      ivs)]
    [(add-node state' node-id
               (str label-start
                    (type-name-row coll)
                    element-rows
                    label-end))
     node-id]))

(defn ^:private render-set
  [state coll]
  (let [node-id (value->node-id coll)
        [state' label-chunk] (render-elements state node-id (vec coll))]
    [(add-node state' node-id
               (str label-start
                    (type-name-row coll) label-chunk
                    label-end))
     node-id]))

(extend-protocol Composite

  IPersistentMap

  (render-as-scalar? [m] (empty? m))

  (render-composite [m state]
    (render-map state m))

  (composite-type [_] :map)

  IPersistentVector

  (render-as-scalar? [v] (empty? v))

  (render-composite [v state]
    (render-vector state v))

  (composite-type [_] :vec)

  IPersistentSet

  (render-as-scalar? [s] (empty? s))

  (render-composite [set state]
    (render-set state set))

  (composite-type [_] :set)

  ISeq

  (render-as-scalar? [s] (empty? s))

  (render-composite [coll state]
    (render-seq state coll))

  (composite-type [_] :seq)

  IDeref

  (render-as-scalar? [_] false)

  (render-composite [ref state]
    (render-ref state ref))

  (composite-type [_] :ref))

(defn dot
  "Given a root composite value, returns the DOT (Graphviz
  description) of the composite value."
  [root-value]
  {:pre [(satisfies? Composite root-value)]}
  (let [[{:keys [nodes edges]}] (render-composite root-value {})]
    (with-out-str
      (println "digraph G {\n  rankdir=LR;")
      (println "\n  node [shape=plaintext, style=\"rounded,filled\", fillcolor=\"#FAF0E6\"];")

      (doseq [[id text] nodes]
        (println (str "  " id " [" text "];")))

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
