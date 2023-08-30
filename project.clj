(defproject com.taoensso/sente "1.19.2"
  :author "Peter Taoussanis <https://www.taoensso.com>"
  :description "Realtime web comms for Clojure/Script applications"
  :url "https://github.com/taoensso/sente"

  :license
  {:name "Eclipse Public License - v 1.0"
   :url  "https://www.eclipse.org/legal/epl-v10.html"}

  :dependencies
  [[org.clojure/core.async   "1.6.673"]
   [com.taoensso/encore      "3.62.1"]
   [org.java-websocket/Java-WebSocket "1.5.3"]
   [org.clojure/tools.reader "1.3.6"]
   [com.taoensso/timbre      "6.2.2"]]

  :profiles
  {;; :default [:base :system :user :provided :dev]
   :provided {:dependencies [[org.clojure/clojurescript "1.11.60"]
                             [org.clojure/clojure       "1.11.1"]]}
   :c1.11    {:dependencies [[org.clojure/clojure       "1.11.1"]]}
   :c1.10    {:dependencies [[org.clojure/clojure       "1.10.2"]]}

   :community
   {:dependencies
    [[org.immutant/web               "2.1.10"]
     [nginx-clojure                  "0.6.0"]
     [aleph                          "0.6.3"]
     [macchiato/core                 "0.2.23"] ; Note 0.2.24 seems to fail?
     [luminus/ring-undertow-adapter  "1.3.1"]
     [info.sunng/ring-jetty9-adapter "0.22.0"]]

    ;; For nginx-clojure on Java 17+,
    ;; Ref. https://github.com/nginx-clojure/nginx-clojure/issues/273
    :jvm-opts
    ["--add-opens=java.base/java.lang=ALL-UNNAMED"
     "--add-opens=java.base/sun.nio.cs=ALL-UNNAMED"
     "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED"]}

   :test
   {:jvm-opts ["-Dtaoensso.elide-deprecated=true"]
    :global-vars
    {*warn-on-reflection* true
     *assert*             true
     *unchecked-math*     false #_:warn-on-boxed}

    :dependencies
    [[com.cognitect/transit-clj  "1.0.333"]
     [com.cognitect/transit-cljs "0.8.280"]
     [org.clojure/test.check     "1.1.1"]
     [http-kit                   "2.7.0"]]}

   :graal-tests
   {:dependencies [[org.clojure/clojure "1.11.1"]
                   [com.github.clj-easy/graal-build-time "0.1.4"]]
    :main taoensso.graal-tests
    :aot [taoensso.graal-tests]
    :uberjar-name "graal-tests.jar"}

   :dev
   [:c1.11 :community :test
    {:jvm-opts ["-server"]
     :plugins
     [[lein-pprint    "1.3.2"]
      [lein-ancient   "0.7.0"]
      [lein-cljsbuild "1.1.8"]
      [com.taoensso.forks/lein-codox "0.10.10"]]

     :codox
     {:language #{:clojure :clojurescript}
      :base-language :clojure}}]}

  :test-paths ["test" #_"src"]

  :cljsbuild
  {:test-commands {"node" ["node" "target/test.js"]}
   :builds
   [{:id :main
     :source-paths ["src" "test"]
     :compiler     {:output-to "target/main.js"
                    :optimizations :advanced
                    :pretty-print false}}

    {:id :test
     :source-paths [#_"src" "test"]
     :compiler
     {:output-to "target/test.js"
      :target :nodejs
      :optimizations :simple}}]}

  :aliases
  {"start-dev"  ["with-profile" "+dev" "repl" ":headless"]
   "build-once" ["do" ["clean"] ["cljsbuild" "once"]]
   "deploy-lib" ["do" ["build-once"] ["deploy" "clojars"] ["install"]]

   "test-clj"   ["with-profile" "+c1.11:+c1.10:+c1.9" "test"]
   "test-cljs"  ["with-profile" "+test" "cljsbuild"   "test"]
   "test-all"   ["do" ["clean"] ["test-clj"] ["test-cljs"]]})
