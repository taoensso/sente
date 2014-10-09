(defproject sente-test-server "0.1.0-SNAPSHOT"
  :description "FIXME: write description"

  :dependencies [;;[org.clojure/clojure "1.6.0"]
                 [org.clojure/clojure       "1.7.0-alpha2"]
                 [org.clojure/core.async    "0.1.346.0-17112a-alpha"]

                 [http-kit                  "2.1.19"]

                 [compojure                 "1.2.0"]

                 [ring                      "1.3.1"]
                 [ring/ring-defaults        "0.1.2"]

                 ;;[com.cognitect/transit-clj "0.8.259"]
                 [com.cognitect/transit-clj "0.8.247"]

                 [com.taoensso/sente        "1.2.0"] ; <--- Sente
                 [com.taoensso/timbre       "3.3.1"]]

  :plugins [[lein-ancient "0.5.5"]]

  :uberjar-name "sente-test-server.jar"

  :main ^:skip-aot server.core

  :target-path "target/%s"

  :profiles {:uberjar {:aot :all}})
