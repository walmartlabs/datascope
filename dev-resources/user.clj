(ns user
  (:require [com.walmartlabs.datascope :refer [render-composite dot view]]
            [clojure.pprint :refer [pprint]]
            [rhizome.viz :as viz]))

(alter-var-root #'*print-length* (constantly 15))

(defn save
  [root-object file-name]
  (-> (dot root-object)
      viz/dot->image
      (viz/save-image file-name)))
