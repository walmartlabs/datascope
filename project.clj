(defproject walmartlabs/datascope "0.1.1"
  :description "Visualization of Clojure data structures using Graphviz"
  :url "https://github.com/walmartlabs/datascope"
  :license {:name "Apache Sofware License 2.0"
            :url  "http://www.apache.org/licenses/LICENSE-2.0.html"}
  :plugins [[lein-codox "0.9.3"]]
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [io.aviso/pretty "0.1.30"]
                 [rhizome "0.2.5"]]
  :aliases {"release" ["do"
                       "clean,"
                       "deploy" "clojars"]}
  :codox {:source-uri "https://github.com/walmartlabs/datascope/blob/master/{filepath}#L{line}"
          :metadata   {:doc/format :markdown}})
