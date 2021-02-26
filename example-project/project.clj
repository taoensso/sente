(defproject com.taoensso.examples/sente "1.16.2"
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
  [[org.clojure/clojure       "1.10.2"]
   [org.clojure/clojurescript "1.10.773"]
   [org.clojure/core.async    "1.3.610"]
   [org.clojure/tools.nrepl   "0.2.13"] ; Optional, for Cider

   [com.taoensso/sente        "1.16.2"] ; <--- Sente
   [com.taoensso/timbre       "5.1.2"]

   ;;; TODO Choose (uncomment) a supported web server -----------------------
   [http-kit                             "2.5.3"] ; Default
   ;; [org.immutant/web                  "2.1.10"
   ;;  :exclusions [ring/ring-core]]
   ;; [nginx-clojure/nginx-clojure-embed "0.5.2"] ; Needs v0.4.2+
   ;; [aleph                             "0.4.1"]
   ;; [info.sunng/ring-jetty9-adapter    "0.14.2"]
   ;; -----------------------------------------------------------------------

   [ring                      "1.9.1"]
   [ring/ring-defaults        "0.3.2"] ; Includes `ring-anti-forgery`, etc.
   ;; [ring-anti-forgery      "1.3.0"]

   [compojure                 "1.6.2"] ; Or routing lib of your choice
   [hiccup                    "1.0.5"] ; Optional, just for HTML

   ;;; Transit deps optional; may be used to aid perf. of larger data payloads
   ;;; (see reference example for details):
   [com.cognitect/transit-clj  "1.0.324"]
   [com.cognitect/transit-cljs "0.8.264"]]

  :plugins
  [[lein-pprint         "1.3.2"]
   [lein-ancient        "0.7.0"]
   ;[com.cemerick/austin "0.1.6"]
   [lein-cljsbuild      "1.1.8"]
   [cider/cider-nrepl   "0.25.9"]] ; Optional, for use with Emacs

  :cljsbuild
  {:builds
   [{:id :cljs-client
     :source-paths ["src"]
     :compiler {:output-to "resources/public/main.js"
                :optimizations :whitespace #_:advanced
                :pretty-print true}}]}

  :main example.server

  :clean-targets ^{:protect false} ["resources/public/main.js"]

  ;; Call `lein start-repl` to get a (headless) development repl that you can
  ;; connect to with Cider+emacs or your IDE of choice:
  :aliases
  {"start-repl" ["do" "clean," "cljsbuild" "once," "repl" ":headless"]
   "start"      ["do" "clean," "cljsbuild" "once," "run"]}

  :repositories
  {"sonatype-oss-public" "https://oss.sonatype.org/content/groups/public/"})
