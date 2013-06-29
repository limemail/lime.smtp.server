(ns lime.smtp.server
  (:require [clojure.java.io :as io]
            [clojure.string :as string]))

(defn reset-session
  [session]
  (select-keys session [:server-host :mode]))

(defn handle-ehlo
  [session parameters]
  (-> session
      (reset-session)
      (assoc :client-host (if (.isEmpty parameters) nil parameters)
             :reply {:code 250 :text (:server-host session)})))

(defn handle-helo
  [session parameters]
  (-> session
      (reset-session)
      (assoc :client-host (if (.isEmpty parameters) nil parameters)
             :reply {:code 250 :text (:server-host session)})))

(defn handle-mail
  [session parameters]
  (assoc session :sender (subs parameters 5) :reply {:code 250}))

(defn handle-rcpt
  [session parameters]
  (-> session
      (update-in [:recipients] conj (subs parameters 3))
      (assoc :reply {:code 250})))

(defn handle-data
  [session parameters]
  (assoc session :mode :data :reply {:code 354}))

(defn handle-rset
  [session parameters]
  (-> session
      (reset-session)
      (assoc :reply {:code 250 :text "Flushed"})))

(defn handle-help
  [session parameters]
  (assoc session :reply {:code 214}))

(defn handle-noop
  [session parameters]
  (assoc session :reply {:code 250 :text "OK"}))

(defn handle-quit
  [session parameters]
  (assoc session
    :reply {:code 221 :text "Closing connection"}
    :mode :quit))

(defn handle-unrecognized-command
  [session parameters]
  (assoc session :reply {:code 500 :text "Unrecognized command"}))

(defn ^:private join-reply-lines
  [lines]
  (let [coll (map #(str %1 "-" %2) (repeat 250) lines)
        [c1 c2] (split-at (dec (count coll)) coll)
        lines (concat c1 [(clojure.string/replace-first (first c2 ) \- \space)])]
    (string/join "\r\n" lines)))

(defn build-reply
  [{:keys [code text lines] :as reply}]
  (when-not (integer? code)
    (throw (ex-info "Reply code must be a three digit integer" reply)))
  (when (or (< code 200) (>= code 600))
    (throw (ex-info "Reply code must be within 200-599 range" reply)))
  (cond (and text (not (.isEmpty text)))(str code " " text)
        (seq lines) (join-reply-lines lines)
        :else (str code)))

(defn write-reply
  [writer reply]
  (doto writer
    (.write (build-reply reply))
    (.write "\r\n")
    (.flush))
  nil) ;; Can this return anything useful?

(defn read-command
  [reader]
  (.readLine reader))

(defn read-data
  [reader]
  (loop [data []]
    (let [line (.readLine reader)]
      (if (= line ".")
        (str (string/join "\r\n" data) "\r\n")
        (recur (conj data line))))))

(defn connect-mode
  [session socket reader writer]
  (write-reply writer {:code 220 :text (str (:server-host session) " Service ready")})
  (assoc session :mode :command))

(defn command-mode
  [session socket reader writer]
  (let [[command parameters] (string/split (read-command reader) #" " 2)
        handle-command (condp = (string/upper-case command)
                         "EHLO" handle-ehlo
                         "HELO" handle-helo
                         "MAIL" handle-mail
                         "RCPT" handle-rcpt
                         "DATA" handle-data
                         "RSET" handle-rset
                         "NOOP" handle-noop
                         "QUIT" handle-quit
                         handle-unrecognized-command)
        session (handle-command session parameters)]
    (write-reply writer (:reply session))
    session))

(defn data-mode
  [session socket reader writer]
  (assoc session
    :message (read-data reader)
    :mode :command))

(defn quit-mode
  [session socket reader writer]
  (write-reply writer {:code 221 :text "Closing connection"})
  (.close socket)
  session)

(defn unrecognized-mode
  [session socket reader writer]
  (write-reply writer {:code 451 :text "Server error"})
  (assoc session :mode :command))

(defn handle-session
  [session socket]
  (let [reader (io/reader (.getInputStream socket))
        writer (io/writer (.getOutputStream socket))]
    (loop [session (assoc session :mode :connect)]
      (if (.isClosed socket)
        session
        (let [handle-mode (condp = (:mode session)
                            :connect connect-mode
                            :command command-mode
                            :data data-mode
                            :quit quit-mode
                            unrecognized-mode)]
          (recur (handle-mode session socket reader writer)))))))

(def core-smtp
  {;; These are the commands the server recognizes. Extensions must
   ;; add new commands to this map. (The value might need to be a map
   ;; in order to allow for extra configuration.)
   :commands {:ehlo handle-ehlo
              :helo handle-helo
              :mail handle-mail
              :rcpt handle-rcpt
              :data handle-data
              :rset handle-rset
              :noop handle-noop
              :quit handle-quit}
   ;; These are the modes the server can operate in. The purpose of a
   ;; mode is to change how the server interacts with a socket. For
   ;; example, command-mode delegates to a command handling function,
   ;; whereas data-mode reads the mail data.
   :modes {:connect connect-mode
           :command command-mode
           :data data-mode
           :quit quit-mode}
   ;; Every extension applied to the server will be registered here.
   ;; This purpose of this is ito allow EHLO to do its job, and to
   ;; provide any additional configuration an extenstion requires.
   :extensions {:vrfy {;; The name and description of the extension.
                       ;; These values are optional, but should be
                       ;; provided for documentation purposes.
                       :name "Verify"
                       :description "Verifies if a user exists."
                       ;; They keyword to be shown in the EHLO reply.
                       :keyword "VRFY"
                       ;; Defaults to true. This determines if the
                       ;; extension should be shown in the EHLO reply.
                       :advertise? false
                       }
                :size {:keyword "SIZE"
                       ;; Additional parameters to be sent in addition
                       ;; to the keyword in the EHLO reply. If a
                       ;; sequence is provided, the values will be
                       ;; joined with a space.
                       :parameters "35882577"
                       }}})
