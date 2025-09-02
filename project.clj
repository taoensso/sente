(defproject com.taoensso/sente "1.21.0-beta2"
  :author "Peter Taoussanis <https://www.taoensso.com>"
  :description "Realtime web comms library for Clojure/Script"
  :url "https://github.com/taoensso/sente"

  :license
  {:name "Eclipse Public License - v 1.0"
   :url  "https://www.eclipse.org/legal/epl-v10.html"}

  :dependencies
  [[org.clojure/core.async "1.8.741"]
   [com.taoensso/encore    "3.154.0"]
   [com.taoensso/trove     "1.0.0"]]

  :test-paths ["test" #_"src"]

  :profiles
  {;; :default [:base :system :user :provided :dev]
   :provided {:dependencies [[org.clojure/clojurescript "1.12.42"]
                             [org.clojure/clojure       "1.12.2"]]}
   :c1.12    {:dependencies [[org.clojure/clojure       "1.12.2"]]}
   :c1.11    {:dependencies [[org.clojure/clojure       "1.11.1"]]}
   :c1.10    {:dependencies [[org.clojure/clojure       "1.10.2"]]}

   :graal-tests
   {:source-paths ["test"]
    :main taoensso.graal-tests
    :aot [taoensso.graal-tests]
    :uberjar-name "graal-tests.jar"
    :dependencies
    [[org.clojure/clojure                  "1.11.1"]
     [com.github.clj-easy/graal-build-time "1.0.5"]]}

   :community
   {:dependencies
    [[org.immutant/web               "2.1.10"]
     [nginx-clojure                  "0.6.0"]
     [aleph                          "0.9.1"]
     [macchiato/core                 "0.2.23"] ; 0.2.24 seems to fail?
     [luminus/ring-undertow-adapter  "1.4.1"]
     [info.sunng/ring-jetty9-adapter "0.37.6"]
     [ring/ring-core                            "1.14.2"]
     [ring/ring-jetty-adapter                   "1.14.2"]
     [org.ring-clojure/ring-websocket-protocols "1.14.2"]
     [org.java-websocket/Java-WebSocket         "1.6.0"]]

    ;; For nginx-clojure on Java 17+,
    ;; Ref. https://github.com/nginx-clojure/nginx-clojure/issues/273
    :jvm-opts
    ["--add-opens=java.base/java.lang=ALL-UNNAMED"
     "--add-opens=java.base/sun.nio.cs=ALL-UNNAMED"
     "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED"]}

   :dev [:dev+ :community]
   :dev+
   {:jvm-opts ["-server" "-Dtaoensso.elide-deprecated=true"]
    :global-vars
    {*warn-on-reflection* true
     *assert*             true
     *unchecked-math*     false #_:warn-on-boxed}

    :dependencies
    [[com.cognitect/transit-clj  "1.0.333"]
     [com.cognitect/transit-cljs "0.8.280"]
     [org.clojure/test.check     "1.1.1"]
     [http-kit                   "2.8.1"]]

    :plugins
    [[lein-pprint    "1.3.2"]
     [lein-ancient   "0.7.0"]
     [lein-cljsbuild "1.1.8"]]}}

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

   "test-clj"   ["with-profile" "+c1.12:+c1.11:+c1.10" "test"]
   "test-cljs"  ["with-profile" "+c1.12" "cljsbuild"   "test"]
   "test-all"   ["do" ["clean"] ["test-clj"] ["test-cljs"]]})
