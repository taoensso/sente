(defproject com.taoensso/sente "0.11.0-SNAPSHOT"
  :author "Peter Taoussanis <https://www.taoensso.com>"
  :description "Clojure channel sockets library"
  :url "https://github.com/ptaoussanis/sente"
  :license {:name "Eclipse Public License"
            :url  "http://www.eclipse.org/legal/epl-v10.html"
            :distribution :repo
            :comments "Same as Clojure"}
  :min-lein-version "2.3.3"
  :global-vars {*warn-on-reflection* true
                *assert* true}
  :dependencies
  [[org.clojure/clojure       "1.5.1"]
   [org.clojure/clojurescript "0.0-2173"]
   [org.clojure/core.async    "0.1.278.0-76b25b-alpha"]
   [org.clojure/tools.reader  "0.8.3"]
   [com.taoensso/encore       "1.4.0"]
   [com.taoensso/timbre       "3.1.6"]
   [http-kit                  "2.1.18"]]

  :test-paths ["test" "src"]
  :profiles
  {;; :default [:base :system :user :provided :dev]
   :1.6  {:dependencies [[org.clojure/clojure     "1.6.0"]]}
   :test {:dependencies [[expectations            "1.4.56"]
                         [reiddraper/simple-check "0.5.6"]]
          :plugins [[lein-expectations "0.0.8"]
                    [lein-autoexpect   "1.2.2"]]}
   :dev* [:dev {:jvm-opts ^:replace ["-server"]
                :hooks [cljx.hooks leiningen.cljsbuild]}]
   :dev
   [:1.6 :test
    {:plugins
     [[lein-ancient                    "0.5.4"]
      [com.keminglabs/cljx             "0.3.2"] ; Must precede Austin!
      [com.cemerick/austin             "0.1.4"]
      [lein-cljsbuild                  "1.0.2"]
      [com.cemerick/clojurescript.test "0.2.2"]
      [codox                           "0.6.7"]]

     :cljx
     {:builds
      [{:source-paths ["src" "test"] :rules :clj  :output-path "target/classes"}
       {:source-paths ["src" "test"] :rules :cljs :output-path "target/classes"}]}

     :cljsbuild
     {:test-commands {"node"    ["node" :node-runner "target/main.js"]
                      "phantom" ["phantomjs" :runner "target/main.js"]}
      :builds ; Compiled in parallel
      [{:id :main
        :source-paths ["src" "test" "target/classes"]
        :compiler     {:output-to "target/main.js"
                       :optimizations :advanced
                       :pretty-print false}}]}}]}

  :codox {:sources ["target/classes"]} ; For use with cljx
  :aliases
  {"test-all"   ["with-profile" "default:+1.6" "expectations"]
   "test-auto"  ["with-profile" "+test" "autoexpect"]
   "build-once" ["do" "cljx" "once," "cljsbuild" "once"]
   "deploy-lib" ["do" "build-once," "deploy" "clojars," "install"]
   "start-dev"  ["with-profile" "+dev*" "repl" ":headless"]}

  :repositories
  {"sonatype"
   {:url "http://oss.sonatype.org/content/repositories/releases"
    :snapshots false
    :releases {:checksum :fail}}
   "sonatype-snapshots"
   {:url "http://oss.sonatype.org/content/repositories/snapshots"
    :snapshots true
    :releases {:checksum :fail :update :always}}})
