 (ns user
   (:require [com.walmartlabs.datascope :refer [render-composite dot view]]
             [clojure.pprint :refer [pprint]]))

(alter-var-root #'*print-length* (constantly 15))
