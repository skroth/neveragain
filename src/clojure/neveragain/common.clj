(ns neveragain.common
  (:require 
    (clojure [string :as string])
    (clojure.java [jdbc :refer :all])
    [clojure.data.codec.base64 :as b64]
    (neveragain [settings :as settings]))
  (:import 
    (neveragain CustomPublicKey)
    (java.net ServerSocket InetAddress Socket)
    (org.bouncycastle.jce.provider BouncyCastleProvider)
    (org.bouncycastle.jce.spec ElGamalParameterSpec ElGamalPublicKeySpec)
    (org.bouncycastle.crypto.modes CCMBlockCipher)
    (org.bouncycastle.crypto.engines AESFastEngine)
    (org.bouncycastle.crypto.params KeyParameter CCMParameters)
    (java.lang.String)
    (java.security KeyPairGenerator SecureRandom Security)
    (java.io PrintWriter InputStreamReader BufferedReader IOException)
    (java.util Hashtable Scanner NoSuchElementException)
    (javax.crypto Cipher KeyGenerator)
    (javax.crypto.spec SecretKeySpec IvParameterSpec)
    (javax.naming.directory InitialDirContext)
    (javax.net.ssl SSLSocketFactory)
    (org.mindrot.jbcrypt BCrypt)))

;; Mmhmm, I love me some good ole state
(Security/addProvider (new BouncyCastleProvider))

(defn write-out [out-writer message]
  (.println out-writer (str message "\r"))
  (.flush out-writer))

(defn get-hostname []
  (.getHostName (InetAddress/getLocalHost)))

(defn get-user-record
  ([address]
    (get-user-record address settings/db))
  ([address db]
    (first
      (query db (into [] (concat 
        ["SELECT * FROM users WHERE address=? AND hostname=?"]
        (string/split address #"@")))))))

(defn has-account-here 
  ([address]
    (has-account-here address settings/db))
  ([address db]
    (boolean
      (query db (into [] (concat 
        ["SELECT 1 FROM users WHERE address=? AND hostname=?"]
        (string/split address #"@")))))))

(defn hash-pass [password]
  (BCrypt/hashpw password (BCrypt/gensalt settings/salt-factor)))

(defn match-pass 
  ([address password]
    (match-pass address password settings/db))
  ([address password db]
    "Returns true if the supplied password matches the stored hashed password
    for the user with address `address`."
    (let [user-data (first (query db (into [] (concat
        ["SELECT hashword FROM users WHERE address=? AND hostname=?"]
        (string/split address #"@")))))]
      (BCrypt/checkpw password (:hashword user-data)))))

(defn gen-aes-key
  ([]
    (gen-aes-key settings/default-aes-key-size))
  ([key-size]
    "Generate a random AES key with a given key length."
    (let [kg (KeyGenerator/getInstance "AES" "BC")]
      (.init kg key-size)
      (KeyParameter. (.getEncoded (.generateKey kg))))))

(defn gen-key-pair 
  ([]
    (gen-key-pair settings/default-ecc-key-size))
  ([key-length] 
    "Generate a random ElGamal key pair with a given key length."
    (let [kpg (KeyPairGenerator/getInstance  "ElGamal" "BC")]
      (.initialize kpg key-length (SecureRandom.))
      (.generateKeyPair kpg))))

(defn serialize-pub-key [key]
  "Encodes a ElGamal public key as base 10 integers in a '/' deliniated string."
  (let [spec (.getParams key)]
    (string/join "/" [(.getY key) (.getP spec) (.getG spec)])))

(defn deserialize-pub-key [key]
  "Takes an ascii/utf-8 string in the format 'y/p/g' (where each letter
  encodes a big integer) and creates an ElGamalPublicKeySpec."
  (let [[y p g] (map (fn [x] (BigInteger. x)) (string/split key #"/"))]
    (CustomPublicKey. (ElGamalPublicKeySpec. y (ElGamalParameterSpec. p g)))))

(defn block-proc [cipher message]
  "Sequentially processes blocks of an arbitrary sized sequence using an 
  initialized cipher. Seq must be castable to a byte array."
  (let [block-size (.getBlockSize cipher)]
    (loop [next-block (take block-size message) 
        cipher-text [] 
        remaining (drop block-size message)]
      (if (= (count next-block) block-size)
        (recur 
          (take block-size remaining)
          (concat cipher-text (seq (.update cipher (byte-array next-block))))
          (drop block-size remaining))
        (byte-array (concat cipher-text 
          (.doFinal cipher (byte-array next-block))))))))

(defn bc-block-proc [cipher message]
  "Same as `block-proc` but for the Bouncy Castle engine API"
  (let [in-len (alength message)
      out-len (.getOutputSize cipher in-len)
      out (byte-array out-len)
      written (.processBytes cipher message 0 in-len out 0)]
    (.doFinal cipher out written)
    out))

(defn random-bytes [num-bytes]
  "Generate a secure array of random bytes, used for making IVs."
  (let [rbytes (byte-array num-bytes)]
    (.nextBytes (SecureRandom.) rbytes)
    rbytes))

(defn encrypt-message [message pub-key]
  "Encrypt a message and return the cipher text and an encrypted AES key (the 
  message is encrypted with the AES key, the AES key is encrypted with the 
  public key) and the initialization vector used with the AES key."
  (let [aes-key (gen-aes-key)
      aes-cipher (CCMBlockCipher. (AESFastEngine.))
      elgamal-cipher (Cipher/getInstance "ElGamal/None/NoPadding" "BC")
      iv (random-bytes 13) ; Random 13 byte IV/nonce
      ccm-params (CCMParameters. 
        aes-key 
        64 ; 64 bit tag size, we'll stick with it across the system
        iv
        (.getBytes ""))] ; 0 bytes of associated data

    (.init aes-cipher true ccm-params)
    (.init elgamal-cipher Cipher/ENCRYPT_MODE pub-key)
    [
      (bc-block-proc aes-cipher (.getBytes message "UTF-8")) ; Cipher text
      (block-proc elgamal-cipher (.getKey aes-key)) ; Cipher key
      iv ; Initialization vector
    ]))

(defn decrypt-message [cipher-text enc-aes-key priv-key]
  "Decrypt a message and return it as an array of bytes."
  (let [elgamal-cipher (Cipher/getInstance "ElGamal/None/NoPadding" "BC")]
    (.init elgamal-cipher Cipher/DECRYPT_MODE priv-key)
    (let [aes-key-material (block-proc elgamal-cipher enc-aes-key)
        aes-key (KeyParameter. aes-key-material)
        aes-cipher (CCMBlockCipher. (AESFastEngine.))
        cipher-seq (seq cipher-text)
        iv (byte-array (take 15 cipher-seq))
        tag (byte-array (take-last 8 cipher-seq))
        actual-cipher-text (byte-array (drop 15 cipher-seq))
        ccm-params (CCMParameters. 
          aes-key 
          64 ; 64 bit tag size, we'll stick with it across the system
          iv ; Random 15 byte IV
          (.getBytes ""))]
      (.init aes-cipher false ccm-params)
      (bc-block-proc aes-cipher actual-cipher-text))))

(defn save-raw-message 
  ([message recipient]
    "Call save-raw-message with the default database"
    (save-raw-message message recipient settings/db))
  ([message recipient db]
    "Encrypt a message and save it to the database, placing it in the inbox of
    the local user with the specified full email address. Returns the id of the
    newly saved message."
    (let [user (first (query db (into [] (concat
          ["SELECT * FROM users WHERE address=? AND hostname=? LIMIT 1"] 
          (string/split recipient #"@")))))
        pub-key (deserialize-pub-key (:elgamal_pub_key user))
        [cipher-text enc-key iv] (encrypt-message message pub-key)]
      ; sqlite's last row id key name doesn't play nice with clojure's syntax
      ; but this'll work
      (let [message-id ((keyword "last_insert_rowid()") (first
            (insert! db :messages {
              :recipient_id (:id user)
              :data (apply str (map char (b64/encode cipher-text)))
              :aes_key (apply str (map char (b64/encode enc-key)))
              :iv (apply str (map char (b64/encode iv)))
              :recv_date (quot (System/currentTimeMillis) 1000) })))]

        ; Now insert the inbox tag record
        (insert! db :tags {
          :owner_id (:id user)
          :name "inbox"
          :message_id message-id })

        ; And return the message id, like we said
        message-id))))

(defn get-mx-hosts [hostname]
  "Returns a vector containing [priority host] pairs of MX records for a 
  given FQDN"
  (let [env (Hashtable.)]
    (.put env "java.naming.factory.initial" "com.sun.jndi.dns.DnsContextFactory")
    (let [idcx (InitialDirContext. env)
        attrs (.getAttributes idcx hostname (into-array ["MX"]))
        attr (.getAll (.get attrs "MX"))]

      ; Now we have a bastardized enumerable, let's make it something less ungodly
      (loop [hosts []]
        (if (.hasMore attr)
          (recur (conj hosts ; append [priority host] to the hosts vector
            (string/split 
              ; Pop off the trailing period
              (string/join "" (butlast (.next attr))) #"\s")))
          ; Sort the list by ascending "distance"
          (sort-by (fn [x] (Integer. (first x))) hosts))))))

(defn make-conn [s]
  "Takes a socket and returns a map of wrapped input and output streams along
  with the original socket. This configuration is generally used to represent
  a foreign connection within this project."
  (let [conn {
      :in (Scanner. (.getInputStream s))
      :out (PrintWriter. (.getOutputStream s))
      :socket s}]
    (.useDelimiter (:in conn) #"\r\n")
    conn))

(defn negotiate-socket [hostname mandatory-tls]
  ; Try a few socket configurations and return a socket-like object if any
  ; of them are viable. If mandatory-tls is true won't return a non-ssl socket.
  (try
    (let [ip-address (InetAddress/getByName hostname)
        socket (Socket. ip-address 25)]
      (make-conn socket))
    (catch IOException e nil)))

(defn read-smtp-block [conn]
  "Reads from the connection into a buffer until a terminal line is hit."
  (loop [line (.next (:in conn)) data ""]
    (if (re-matches #"^\d{3}-.*" line)
      (recur (.next (:in conn)) (str data "\r\n" line))
      (str data "\r\n" line))))

(defn relay-message [envl recipient]
  (let [hostname (get (string/split recipient #"@") 1)
      mx-hosts (get-mx-hosts hostname)]
    (loop [[[priority host] & remaining] mx-hosts]
      (println (str "Trying host " host))
      (let [conn (negotiate-socket host false)]
        (println (str "R: " (.next (:in conn))))
        (if-not conn
          (if (seq remaining) (recur remaining) nil)
          (do
            (write-out (:out conn) (str "EHLO " (get-hostname)))
            ; Read the EHLO response, don't do anything with it for the moment.
            (println (read-smtp-block conn))
            (write-out (:out conn) (str "MAIL FROM:<" (:from envl) ">"))
            (println (read-smtp-block conn))
            (write-out (:out conn) (str "RCPT TO:<" recipient ">"))
            (println (read-smtp-block conn))
            (write-out (:out conn) "DATA")
            (println (read-smtp-block conn))
            (write-out (:out conn) (:data envl))
            (write-out (:out conn) ".")
            (println (read-smtp-block conn))
            ))))))

(defn proc-envelope [envl]
  (dorun (for [recipient (:recipients envl)]
    (if (has-account-here recipient)
      (save-raw-message (:data envl) recipient)
      (relay-message envl recipient)))))
