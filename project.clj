(defproject com.taoensso/sente "1.12.0"
  :author "Peter Taoussanis <https://www.taoensso.com>"
  :description "Realtime web comms for Clojure/Script"
  :url "https://github.com/ptaoussanis/sente"
  :license {:name "Eclipse Public License"
            :url  "http://www.eclipse.org/legal/epl-v10.html"
            :distribution :repo
            :comments "Same as Clojure"}
  :min-lein-version "2.3.3"
  :global-vars {*warn-on-reflection* true
                *assert* true}

  :dependencies
  [[org.clojure/clojure      "1.8.0"]
   [org.clojure/core.async   "0.3.465"]
   [com.taoensso/encore      "2.93.0"]
   [org.clojure/tools.reader "1.1.1"]
   [com.taoensso/timbre      "4.10.0"]]

  :plugins
  [[lein-pprint    "1.2.0"]
   [lein-ancient   "0.6.14"]
   [lein-codox     "0.10.3"]
   [lein-cljsbuild "1.1.7"]]

  :profiles
  {;; :default [:base :system :user :provided :dev]
   :server-jvm {:jvm-opts ^:replace ["-server"]}
   :1.8  {:dependencies [[org.clojure/clojure "1.8.0"]]}
   :1.9  {:dependencies [[org.clojure/clojure "1.9.0"]]}
   :test {:dependencies [[com.cognitect/transit-clj  "0.8.300"]
                         [com.cognitect/transit-cljs "0.8.243"]
                         [org.clojure/test.check     "0.9.0"]]}

   :provided {:dependencies [[org.clojure/clojurescript "1.9.946"]]}

   :dev
   [:1.9 :test :server-jvm
    {:dependencies
     [[http-kit         "2.2.0"]
      [org.immutant/web "2.1.9"]
      [nginx-clojure    "0.4.5"]
      [aleph            "0.4.4"]]}]}

  :cljsbuild
  {:test-commands {"node"    ["node" :node-runner "target/main.js"]
                   "phantom" ["phantomjs" :runner "target/main.js"]}
   :builds
   [{:id :main
     :source-paths ["src" "test"]
     :compiler     {:output-to "target/main.js"
                    :optimizations :advanced
                    :pretty-print false}}]}

  :test-paths ["test" "src"]

  :aliases
  {"build-once" ["cljsbuild" "once"]
   "deploy-lib" ["do" "build-once," "deploy" "clojars," "install"]
   "start-dev"  ["with-profile" "+dev" "repl" ":headless"]}

  :repositories
  {"sonatype-oss-public" "https://oss.sonatype.org/content/groups/public/"})
