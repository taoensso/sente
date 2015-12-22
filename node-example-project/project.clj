(defproject com.taoensso.examples/sente "1.7.0-beta2"
  :description "Sente, reference web-app example project"
  :url "https://github.com/ptaoussanis/sente"
  :license {:name "Eclipse Public License"
            :url  "http://www.eclipse.org/legal/epl-v10.html"
            :distribution :repo
            :comments "Same as Clojure"}
  :min-lein-version "2.3.3"
  :global-vars {*warn-on-reflection* true
                *assert* true}

  :dependencies
  [[org.clojure/clojure       "1.7.0"] ; May use any v1.5.1+
   [org.clojure/clojurescript "1.7.122"]
   [org.clojure/core.async    "0.1.346.0-17112a-alpha"]

   [com.taoensso/sente        "1.7.0-RC1"] ; <--- Sente
   [com.taoensso/timbre       "4.1.2"]

   [hiccups "0.3.0"]

   ;;; ---> Choose (uncomment) a supported web server from here, or :npm below <---
   [org.clojars.whamtet/dogfort "0.2.0-SNAPSHOT"]

   [ring                      "1.4.0"]
   [ring/ring-defaults        "0.1.5"] ; Includes `ring-anti-forgery`, etc.
   ;; [ring-anti-forgery      "1.0.0"]

   ;;; Transit deps optional; may be used to aid perf. of larger data payloads
   ;;; (see reference example for details):
   [com.cognitect/transit-cljs "0.8.225"]]

  :plugins
  [[lein-pprint         "1.1.2"]
   [lein-ancient        "0.6.7"]
   [com.cemerick/austin "0.1.6"]
   [lein-cljsbuild      "1.1.0"]
   [cider/cider-nrepl   "0.8.2"] ; Optional, for use with Emacs
   [lein-npm "0.6.1"]
   ]

  :npm {:dependencies [[source-map-support "*"]

                       ;; If you uncommone one of these servers, comment out the others above

                       ;; express needs express and express-ws
                       [express "4.13.3"]
                       [express-ws "1.0.0-rc.2"]
                       [body-parser "1.14.1"]
                       [cookie-parser "1.4.0"]
                       [express-session "1.11.3"]
                       [csurf "1.8.3"]


                       ;; ws is needed for dogfort and express
                       [ws "0.8.0"]]}

  :cljsbuild
  {:builds ; Compiled in parallel
   [{:id :main
     :source-paths ["src"]
     :compiler     {:output-to "resources/public/main.js"
                    :output-dir "resources/public/out"
                    :optimizations :whitespace #_:advanced
                    :source-map "resources/public/main.js.map"
                    :source-map-path "out"
                    :pretty-print true}}
    {:id :node
     :source-paths ["src" "src_node"]
     :compiler {:output-to "main.js"
                :output-dir "out"
                :main example.my-app-node
                :target :nodejs
                :source-map "main.js.map"
                :source-map-path "out"
                }}
    ]}

  ;; Call `lein start-dev` to get a (headless) development repl that you can
  ;; connect to with Cider+emacs or your IDE of choice:
  :aliases
  {"build-once" ["cljsbuild" "once"]
   "start-dev"  ["repl" ":headless"]}

  :repositories
  {"sonatype-oss-public" "https://oss.sonatype.org/content/groups/public/"})
