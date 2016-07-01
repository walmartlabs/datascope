(ns user
  (:require [com.walmartlabs.datascope :refer [render-composite dot view]]
            [clojure.pprint :refer [pprint]]
            [rhizome.viz :as viz]))

(alter-var-root #'*print-length* (constantly 15))

(def example-1                                              ; basics.png
  {:n 10
   :string "A scalar."
   :vector [1 2 3 4]
   :empty {}
   :enabled? true
   :sequence (iterate inc 1)
   :missing nil
    :set #{:science-fiction :military :romance}
   :atom (atom {:foo :bar :empty {}})})

(def common-objects
  (let [a {:map :a}
        b {:map :b
           :a a}]
    {:map :root
     :a a
     :b b
     :vector [:vector :c a b]
     :set #{:set a b}
     :atom (atom a)}))

(def instaparse-document [:document
                          [:definition
                           [:operation-definition
                            [:selection-set
                             [:selection
                              [:field
                               [:name "customer"]
                               [:arguments
                                [:argument
                                 [:name "id"]
                                 [:value
                                  [:string-value
                                   [:string-character [:source-character "9"]]
                                   [:string-character [:source-character "0"]]
                                   [:string-character [:source-character "1"]]
                                   [:string-character [:source-character "2"]]
                                   [:string-character [:source-character "5"]]]]]]
                               [:selection-set
                                [:selection [:field [:name "name"]]]
                                [:selection [:field [:name "email"]]]
                                [:selection
                                 [:field
                                  [:name "transactions"]
                                  [:arguments [:argument [:name "max"] [:value [:int-value [:integer-part [:non-zero-digit "1"] [:digit "0"]]]]]]
                                  [:selection-set [:selection [:field [:name "total"]]] [:selection [:field [:name "item_count"]]]]]]]]]
                             [:selection [:field [:name "state"]]]]]]])


(defn save
  [root-object file-name]
  (-> (dot root-object)
      viz/dot->image
      (viz/save-image file-name)))
