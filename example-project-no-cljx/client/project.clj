(defproject sente-test-client "0.1.0-SNAPSHOT"
  :description "FIXME: write this!"

  :dependencies [;;[org.clojure/clojure "1.6.0"]
                 [org.clojure/clojure       "1.7.0-alpha2"]
                 [org.clojure/clojurescript "0.0-2322"]
                 [org.clojure/core.async    "0.1.346.0-17112a-alpha"]

                 [com.cognitect/transit-cljs "0.8.188"]

                 [com.taoensso/sente        "1.2.0"] ; <--- Sente
                 [com.taoensso/timbre       "3.3.1"]]

  :plugins [[lein-ancient "0.5.5"]
            [lein-cljsbuild "1.0.4-SNAPSHOT"]]

  :source-paths ["src"]

  :cljsbuild {
    :builds [{
        :source-paths ["src"]
        :compiler {
          :output-to "../server/resources/public/js/main.js"
          :optimizations :whitespace
          :pretty-print true}}]})
