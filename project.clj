(defproject neveragain "0.1.0-SNAPSHOT"
  :description "A secure full package email solution that provides server software and web client front end."
  :url "https://github.com/skroth/chainmail"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [
    [org.clojure/clojure "1.5.1"]
    [org.clojure/core.async "0.1.278.0-76b25b-alpha"]
    [org.clojure/java.jdbc "0.2.2"]
    [org.clojure/data.codec "0.1.0"]
    [org.clojure/data.json "0.2.3"]
    [korma "0.3.0-RC5"]
    [org.xerial/sqlite-jdbc "3.7.2"]
    [org.mindrot/jbcrypt "0.3m"]
    [swiss-arrows "1.0.0"]
    [bouncycastle/bcprov-jdk16 "140"]
    [compojure "1.1.6"]
    [selmer "0.5.7"]
    [less-awful-ssl "0.1.1"]]
  :plugins [[lein-ring "0.7.1"] 
            [lein-cljsbuild "0.2.8"]]
  :cljsbuild
  {:builds
   [{
    :source-path "src/cljs/chainmail",
     :compiler
     {;:output-dir "resources/webmail/js/cljs",
      :output-to "resources/webmail/js/cljs/main.js"
      :optimizations :whitespace
      :pretty-print true}}]}
  :profiles {:smtp {:main ^:skip-aot neveragain.core}
             :imap {:main ^:skip-aot neveragain.imap}
             :client {:main ^:skip-aot client.core}
             :uberjar {:aot :all}}
  :aliases {"smtp" ["with-profile" "smtp" "run"]
            "imap" ["with-profile" "imap" "run"]
            "client" ["with-profile" "client" "run"]
            "webmail" ["ring" "server"]}
  :main ^:skip-aot neveragain.core
  :target-path "target/%s"
  :source-paths ["src/clojure"]
  :java-source-paths ["src/java"]
  :ring {:handler webmail.routes/app})
