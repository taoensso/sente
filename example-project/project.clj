(defproject com.taoensso.examples/sente "0.15.1"
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
  [[org.clojure/clojure       "1.6.0"]
   ;;
   [org.clojure/clojurescript "0.0-2268"]
   [org.clojure/core.async    "0.1.278.0-76b25b-alpha"]
   ;;
   [com.taoensso/sente        "0.15.1"] ; <--- Sente
   [com.taoensso/timbre       "3.2.1"]
   ;;
   [http-kit                  "2.1.18"] ; <--- http-kit (currently required)
   ;;
   [compojure                 "1.1.8"]  ; Or routing lib of your choice
   [ring                      "1.3.0"]
   ;; [ring-anti-forgery      "1.0.0"]
   [ring/ring-defaults        "0.1.1"]  ; Incl. `ring-anti-forgery`, etc.
   [hiccup                    "1.0.5"]  ; Optional, just for HTML
   [org.clojure/core.match    "0.2.1"]  ; Optional but quite handly
   ]

  :plugins
  [[lein-pprint         "1.1.1"]
   [lein-ancient        "0.5.5"]
   [com.cemerick/austin "0.1.4"]
   [com.keminglabs/cljx "0.4.0"]
   [lein-cljsbuild      "1.0.3"]]

  :hooks [cljx.hooks leiningen.cljsbuild]
  :cljx
  {:builds
   [{:source-paths ["src"] :rules :clj  :output-path "target/classes"}
    {:source-paths ["src"] :rules :cljs :output-path "target/classes"}]}

  :cljsbuild
  {:builds ; Compiled in parallel
   [{:id :main
     :source-paths ["src" "target/classes"]
     :compiler     {:output-to "resources/public/main.js"
                    :optimizations :whitespace #_:advanced
                    :pretty-print true}}]}

  ;; Call `lein start-dev` to get a (headless) development repl that you can
  ;; connect to with Cider+emacs or your IDE of choice:
  :aliases
  {"build-once" ["do" "cljx" "once," "cljsbuild" "once"]
   "start-dev"  ["repl" ":headless"]})
