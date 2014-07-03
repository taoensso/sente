(defproject sample.sente.mouse "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}

  :jvm-opts ^:replace ["-Xmx1g" "-server"]

  :dependencies [[org.clojure/clojure "1.6.0"]
                 [org.clojure/clojurescript "0.0-2197"]
                 [org.clojure/core.async    "0.1.278.0-76b25b-alpha"]
                 [org.clojure/core.match    "0.2.1"]
                 [ring/ring "1.2.1"]
                 [compojure "1.1.8"]
                 [fogus/ring-edn "0.2.0"]
                 ;;
                 [http-kit                  "2.1.18"]
                 ;;
                 [com.taoensso/sente        "0.14.1"] ; <--- Sente
                 [com.taoensso/timbre       "3.2.1"]
                 [com.taoensso.forks/ring-anti-forgery "0.3.1"]
                ]

  :plugins [[lein-cljsbuild "1.0.3"]]
  :hooks [leiningen.cljsbuild]

  :source-paths ["src/clj"]
  :resource-paths ["resources"]

  :main example.mouse.core;

  :cljsbuild {
    :builds [{:id "dev"
              :source-paths ["src/cljs"]
              :compiler {
                :output-to "resources/public/js/main.js"
                :output-dir "resources/public/js/out"
                :optimizations :none
                :source-map true}}]})
