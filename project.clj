(defproject com.taoensso/sente "1.8.2-SNAPSHOT"
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
  [[org.clojure/clojure      "1.5.1"]
   [org.clojure/core.async   "0.2.374"]
   [com.taoensso/encore      "2.44.0"]
   [org.clojure/tools.reader "0.10.0"]
   [com.taoensso/timbre      "4.3.1"]]

  :profiles
  {;; :default [:base :system :user :provided :dev]
   :server-jvm {:jvm-opts ^:replace ["-server"]}
   :1.5  {:dependencies [[org.clojure/clojure "1.5.1"]]}
   :1.6  {:dependencies [[org.clojure/clojure "1.6.0"]]}
   :1.7  {:dependencies [[org.clojure/clojure "1.7.0"]]}
   :1.8  {:dependencies [[org.clojure/clojure "1.8.0"]]}
   :test {:dependencies [[com.cognitect/transit-clj  "0.8.285"]
                         [com.cognitect/transit-cljs "0.8.237"]
                         [compojure                  "1.5.0"]
                         [ring                       "1.4.0"]
                         [ring/ring-defaults         "0.2.0"]
                         [doo                        "0.1.6"]
                         [devcards                   "0.2.1-6"]]
          :plugins [[lein-figwheel                   "0.5.2"
                     :exclusions [org.clojure/clojure]]
                    [lein-doo                        "0.1.6"]]}

   :provided {:dependencies [[org.clojure/clojurescript "1.7.170"]]}

   :dev
   [:1.8 :test
    {:dependencies
     [[http-kit         "2.2.0-alpha1"]
      [org.immutant/web "2.1.3"]
      [nginx-clojure    "0.4.4"]]
     :plugins
     [;;; These must be in :dev, Ref. https://github.com/lynaghk/cljx/issues/47:
      [com.keminglabs/cljx             "0.6.0"]
      [lein-cljsbuild                  "1.1.3"]
      ;;
      [lein-pprint                     "1.1.2"]
      [lein-ancient                    "0.6.8"]
      ;; [com.cemerick/austin          "0.1.4"]
      [lein-codox                      "0.9.4"]]
     :source-paths ["src" "target/classes" "test"]
     :test-paths   ["src" "test"]
     :figwheel {:ring-handler taoensso.sente.test-server/relay-app}
     :clean-targets ^{:protect false} [:target-path "dev-resources"]
     :cljsbuild {:test-commands  {}
                 :builds
                 [{:id           "devcards"
                   :source-paths ["src" "test" "target/classes"]
                   :figwheel     {:devcards true}
                   :compiler     {:main                  taoensso.sente.devcards
                                  :closure-defines       {"taoensso.sente.client_tests.host_override" "localhost:3449"}
                                  :asset-path            "js/compiled/devcards_out"
                                  :output-to             "dev-resources/public/js/compiled/sente_devcards.js"
                                  :output-dir            "dev-resources/public/js/compiled/devcards_out"
                                  :optimizations         :none
                                  :anon-fn-naming-policy :unmapped
                                  :source-map            true
                                  :source-map-timestamp  true}}]}}]}

  :cljx
  {:builds
   [{:source-paths ["src" "test"] :rules :clj  :output-path "target/classes"}
    {:source-paths ["src" "test"] :rules :cljs :output-path "target/classes"}]}

  :source-paths ["src" "target/classes" "test"]
  :test-paths ["test" "target/classes" "src"]
  :prep-tasks [["cljx" "once"] "javac" "compile"]
  :codox
  {:language :clojure ; [:clojure :clojurescript] ; No support?
   :source-paths ["target/classes"]
   :source-uri
   {#"target/classes"
    "https://github.com/ptaoussanis/sente/blob/master/src/{classpath}x#L{line}"
    #".*"
    "https://github.com/ptaoussanis/sente/blob/master/{filepath}#L{line}"}}

  :aliases
  {"build-once" ["do" "cljx" "once," "cljsbuild" "once"]
   "test-all"   ["do" "clean," "build-once," "test,"]
   "devcards"   ["do" "clean," "cljx" "once," "figwheel,"]
   "deploy-lib" ["do" "build-once," "deploy" "clojars," "install"]
   "start-dev"  ["with-profile" "+server-jvm" "repl" ":headless"]}

  :repositories
  {"sonatype-oss-public" "https://oss.sonatype.org/content/groups/public/"})
