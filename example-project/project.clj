(defproject com.taoensso.examples/sente "1.12.0"
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
  [[org.clojure/clojure       "1.9.0"]
   [org.clojure/clojurescript "1.9.946"]
   [org.clojure/core.async    "0.3.465"]
   [org.clojure/tools.nrepl   "0.2.13"] ; Optional, for Cider

   [com.taoensso/sente        "1.12.0"] ; <--- Sente
   [com.taoensso/timbre       "4.10.0"]

   ;;; TODO Choose (uncomment) a supported web server -----------------------
   [http-kit                             "2.2.0"] ; Default
   ;; [org.immutant/web                  "2.1.4"]
   ;; [nginx-clojure/nginx-clojure-embed "0.4.4"] ; Needs v0.4.2+
   ;; [aleph                             "0.4.1"]
   ;; -----------------------------------------------------------------------

   [ring                      "1.6.3"]
   [ring/ring-defaults        "0.3.1"] ; Includes `ring-anti-forgery`, etc.
   ;; [ring-anti-forgery      "1.0.0"]

   [compojure                 "1.6.0"] ; Or routing lib of your choice
   [hiccup                    "1.0.5"] ; Optional, just for HTML

   ;;; Transit deps optional; may be used to aid perf. of larger data payloads
   ;;; (see reference example for details):
   [com.cognitect/transit-clj  "0.8.300"]
   [com.cognitect/transit-cljs "0.8.243"]]

  :plugins
  [[lein-pprint         "1.2.0"]
   [lein-ancient        "0.6.14"]
   [com.cemerick/austin "0.1.6"]
   [lein-cljsbuild      "1.1.7"]
   [cider/cider-nrepl   "0.15.1"] ; Optional, for use with Emacs
   ]

  :cljsbuild
  {:builds
   [{:id :cljs-client
     :source-paths ["src"]
     :compiler {:output-to "target/main.js"
                :optimizations :whitespace #_:advanced
                :pretty-print true}}]}

  :main example.server

  ;; Call `lein start-repl` to get a (headless) development repl that you can
  ;; connect to with Cider+emacs or your IDE of choice:
  :aliases
  {"start-repl" ["do" "clean," "cljsbuild" "once," "repl" ":headless"]
   "start"      ["do" "clean," "cljsbuild" "once," "run"]}

  :repositories
  {"sonatype-oss-public" "https://oss.sonatype.org/content/groups/public/"})
