(defproject com.taoensso/sente "0.15.1"
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
   [org.clojure/clojurescript "0.0-2268"]
   [org.clojure/core.async    "0.1.278.0-76b25b-alpha"]
   [org.clojure/tools.reader  "0.8.5"]
   [com.taoensso/encore       "1.7.0"]
   [com.taoensso/timbre       "3.2.1"]
   [http-kit                  "2.1.18"]]

  :plugins
  [[com.keminglabs/cljx "0.4.0"]
   [lein-cljsbuild      "1.0.3"]]

  :profiles
  {;; :default [:base :system :user :provided :dev]
   :server-jvm {:jvm-opts ^:replace ["-server"]}
   :1.6  {:dependencies [[org.clojure/clojure     "1.6.0"]]}
   :test {:dependencies [[expectations            "2.0.7"]
                         [org.clojure/test.check  "0.5.8"]
                         ;; [com.cemerick/double-check "0.5.7"]
                         ]
          :plugins [[lein-expectations "0.0.8"]
                    [lein-autoexpect   "1.2.2"]]}

   :dev
   [:1.6 :test
    {:plugins
     [[lein-pprint                     "1.1.1"]
      [lein-ancient                    "0.5.5"]
      [com.cemerick/austin             "0.1.4"]
      [lein-expectations               "0.0.8"]
      [lein-autoexpect                 "1.2.2"]
      [com.cemerick/clojurescript.test "0.3.1"]
      [codox                           "0.8.10"]]}]}

  :cljx
  {:builds
   [{:source-paths ["src" "test"] :rules :clj  :output-path "target/classes"}
    {:source-paths ["src" "test"] :rules :cljs :output-path "target/classes"}]}

  :cljsbuild
  {:test-commands {"node"    ["node" :node-runner "target/main.js"]
                   "phantom" ["phantomjs" :runner "target/main.js"]}
   :builds
   [{:id :main
     :source-paths ["src" "test" "target/classes"]
     :compiler     {:output-to "target/main.js"
                    :optimizations :advanced
                    :pretty-print false}}]}

  :test-paths ["test" "src"]
  ;;:hooks    [cljx.hooks leiningen.cljsbuild]
  :prep-tasks [["cljx" "once"] "javac" "compile"]
  :codox {:language :clojure ; [:clojure :clojurescript] ; No support?
          :sources  ["target/classes"]
          :src-linenum-anchor-prefix "L"
          :src-dir-uri "http://github.com/ptaoussanis/encore/blob/master/src/"
          :src-uri-mapping {#"target/classes"
                            #(.replaceFirst (str %) "(.cljs$|.clj$)" ".cljx")}}

  :aliases
  {"test-all"   ["with-profile" "default:+1.6" "expectations"]
   "test-auto"  ["with-profile" "+test" "autoexpect"]
   "build-once" ["do" "cljx" "once," "cljsbuild" "once"]
   "deploy-lib" ["do" "build-once," "deploy" "clojars," "install"]
   "start-dev"  ["with-profile" "+server-jvm" "repl" ":headless"]}

  :repositories
  {"sonatype"
   {:url "http://oss.sonatype.org/content/repositories/releases"
    :snapshots false
    :releases {:checksum :fail}}
   "sonatype-snapshots"
   {:url "http://oss.sonatype.org/content/repositories/snapshots"
    :snapshots true
    :releases {:checksum :fail :update :always}}})
