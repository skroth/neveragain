(ns neveragain.settings)

(def db {
	:classname "org.sqlite.JDBC"
	:subprotocol "sqlite"
	:subname "neveragain.db"})

(def thread-count 2)
(def smtp-port 2500)
(def smtp-modules ["neveragain.rfc821"])
(def salt "yee.salty.sea.dog.scallywag.arrrrr")
