(ns lime.smtp.server-test
  (require [clojure.java.io :as io]
           [clojure.test :refer :all]
           [lime.smtp.server :refer :all])
  (import (clojure.lang ExceptionInfo)
          (java.io StringReader StringWriter)
          (java.net ServerSocket Socket)))

(defn test-fn
  [f data]
  (doseq [{:keys [params expect throws]} data]
    (is (= expect (apply f params)))))

(def helo-data
  [{:params [nil nil]
    :expect {:reply {:code 250 :text "Service ready"}
             :client-host nil}}
   {:params [{} nil]
    :expect {:reply {:code 250 :text "Service ready"}
             :client-host nil}}
   {:params [nil ""]
    :expect {:reply {:code 250 :text "Service ready"}
             :client-host nil}}
   {:params [{} ""]
    :expect {:reply {:code 250 :text "Service ready"}
             :client-host nil}}
   {:params [nil "client.example.com"]
    :expect {:reply {:code 250 :text "Service ready"}
             :client-host "client.example.com"}}
   {:params [{} "client.example.com"]
    :expect {:reply {:code 250 :text "Service ready"}
             :client-host "client.example.com"}}
   {:params [{:server-host "server.example.com"} nil]
    :expect {:server-host "server.example.com"
             :reply {:code 250 :text "server.example.com Service ready"}
             :client-host nil}}
   {:params [{:server-host "server.example.com"} ""]
    :expect {:server-host "server.example.com"
             :reply {:code 250 :text "server.example.com Service ready"}
             :client-host nil}}
   {:params [{:server-host "server.example.com"} "client.example.com"]
    :expect {:server-host "server.example.com"
             :reply {:code 250 :text "server.example.com Service ready"}
             :client-host "client.example.com"}}
   {:params [{:server-host "server.example.com"
              :transaction nil} nil]
    :expect {:server-host "server.example.com"
             :reply {:code 250 :text "server.example.com Service ready"}
             :client-host nil}}
   {:params [{:server-host "server.example.com"
              :transaction nil} ""]
    :expect {:server-host "server.example.com"
             :reply {:code 250 :text "server.example.com Service ready"}
             :client-host nil}}
   {:params [{:server-host "server.example.com"
              :transaction nil} "client.example.com"]
    :expect {:server-host "server.example.com"
             :reply {:code 250 :text "server.example.com Service ready"}
             :client-host "client.example.com"}}
   {:params [{:server-host "server.example.com"
              :transaction {}} nil]
    :expect {:server-host "server.example.com"
             :reply {:code 250 :text "server.example.com Service ready"}
             :client-host nil}}
   {:params [{:server-host "server.example.com"
              :transaction {}} ""]
    :expect {:server-host "server.example.com"
             :reply {:code 250 :text "server.example.com Service ready"}
             :client-host nil}}
   {:params [{:server-host "server.example.com"
              :transaction {}} "client.example.com"]
    :expect {:server-host "server.example.com"
             :reply {:code 250 :text "server.example.com Service ready"}
             :client-host "client.example.com"}}])
(require 'clojure.pprint)
(def ehlo-data
  (letfn [(f [entry]
            (clojure.pprint/pprint entry)
            (-> entry
                (assoc-in [:params 0 :extensions] ["FOO" "BAR"])
                (update-in [:expect :reply :text] #(if %1
                                                     (cons %1 %2)
                                                     %2) ["FOO" "BAR"])))]
    (map f helo-data)))

(deftest test-helo
  (test-fn handle-helo helo-data))

(deftest test-ehlo
  (test-fn handle-ehlo (concat helo-data ehlo-data)))

(def mail-data
  [{:params [nil nil]
    :expect {:reply {:code 553}}}])

(deftest test-mail
  (test-fn handle-mail mail-data))


;E: 552, 451, 452, 550, 553, 503, 455, 555











(deftest test-handle-mail
  (testing "Command mode not preserved"
    (let [session (handle-mail {:mode :command} "FROM:foo@example.com")]
      (is (= (:mode session) :command))))
  (testing "Sender was not set properly"
    (let [session (handle-mail {} "FROM:foo@example.com")]
      (is (= (get-in session [:transaction :sender]) "foo@example.com"))))
  (testing "Reply should be 250"
    (let [session (handle-mail {} "FROM:foo@example.com")]
      (is (= (:reply session) {:code 250}))))
  
  ;; TODO Test that invalid parameters return 455
  ;; TODO Test if mail session is already in progress (503)
  ;; TODO Test that mailbox syntax is correct (otherwise 553)
  ;; TODO Test that unrecognized or not implemented params return 555
  ;; TODO 501
  )

(deftest test-handle-rcpt
  (is (= (handle-rcpt {:mode :command} "TO:foo@example.com")
         {:transaction {:recipients ["foo@example.com"]}
          :reply {:code 250}
          :mode :command}))
  (is (= (handle-rcpt {:transaction {:recipients ["bar@example.com"]}
                       :mode :command} "TO:foo@example.com")
         {:transaction {:recipients ["bar@example.com" "foo@example.com"]}
          :reply {:code 250}
          :mode :command}))
  ;; TODO Test that invalid parameters return 455
  ;; TODO Test if mail session is already in progress (503)
  ;; TODO Test that mailbox syntax is correct (otherwise 553)
  ;; TODO Test that unrecognized or not implemented params return 555
  )

(deftest test-handle-data
  (is (= (handle-data {:mode :command} "")
         {:reply {:code 354} :mode :data})))

(deftest test-handle-rset
  (is (= (handle-rset {:mode :command} "")
         {:reply {:code 250 :text "Flushed"} :mode :command})))

(deftest test-handle-help
  (is (= (handle-help {:mode :command} "")
         {:reply {:code 214} :mode :command})))

(deftest test-handle-noop
  (is (= (handle-noop {:mode :command} "")
         {:reply {:code 250 :text "OK"} :mode :command})))

(deftest test-handle-quit
  (is (= (handle-quit {:mode :command} "")
         {:reply {:code 221 :text "Closing connection"} :mode :quit})))

(deftest test-handle-unrecognized-command
  (is (= (handle-unrecognized-command {:mode :command} "")
         {:reply {:code 500 :text "Unrecognized command"} :mode :command})))

(deftest test-build-reply
  (is (= (build-reply {:code 250})
         "250"))
  (is (= (build-reply {:code 250 :text "OK"})
         "250 OK"))
  (is (thrown-with-msg? ExceptionInfo #"a three digit integer"
                        (build-reply {:code "250"})))
  (is (thrown-with-msg? ExceptionInfo #"within 200-599 range"
                        (build-reply {:code 199})))
  (is (thrown-with-msg? ExceptionInfo #"within 200-599 range"
                        (build-reply {:code 600})))
  (is (= (build-reply {:code 250 :text ["one" "two" "three"]})
         "250-one\r\n250-two\r\n250 three"))
  (is (= (build-reply {:code 250 :text ["one" "two"]})
         "250-one\r\n250 two"))
  (is (= (build-reply {:code 250 :text ["one"]})
         "250 one"))
  (is (= (build-reply {:code 250 :text []})
         "250")))

(deftest test-write-reply
  (let [writer (StringWriter.)]
    (is (nil? (write-reply writer {:code 250})))
    (is (= (str writer) "250\r\n"))))

(deftest test-read-command
  (let [reader (io/reader (StringReader. "foo"))]
    (is (= (read-command reader) "foo"))))

(deftest test-read-data
  (let [reader (io/reader (StringReader. "foo\r\nbar\r\n.\r\n"))]
    (is (= (read-data reader) "foo\r\nbar\r\n"))))

(deftest test-connect-mode
  (let [writer (StringWriter.)
        session (connect-mode {:server-host "foo"} nil nil nil writer)]
    (is (= session {:server-host "foo" :mode :command}))
    (is (= (str writer) "220 foo Service ready\r\n"))))

(deftest test-command-mode
  (let [writer (StringWriter.)
        reader (io/reader (StringReader. "NOOP"))
        session (command-mode {} core-smtp nil reader writer)]
    (is (= session {:reply {:code 250 :text "OK"}}))
    (is (= (str writer) "250 OK\r\n")))
  (let [writer (StringWriter.)
        reader (io/reader (StringReader. "NOOP "))
        session (command-mode {} core-smtp nil reader writer)]
    (is (= session {:reply {:code 250 :text "OK"}}))
    (is (= (str writer) "250 OK\r\n")))
  (let [writer (StringWriter.)
        reader (io/reader (StringReader. "NOOP foo"))
        session (command-mode {} core-smtp nil reader writer)]
    (is (= session {:reply {:code 250 :text "OK"}}))
    (is (= (str writer) "250 OK\r\n")))
  (let [writer (StringWriter.)
        reader (io/reader (StringReader. "BLAH"))
        session (command-mode {} core-smtp nil reader writer)]
    (is (= session {:reply {:code 500 :text "Unrecognized command"}}))
    (is (= (str writer) "500 Unrecognized command\r\n"))))

(deftest test-data-mode
  (let [reader (io/reader (StringReader. "foo\r\nbar\r\n.\r\n"))]
    (is (= (data-mode {:mode :data} nil nil reader nil)
           {:mode :command :transaction {:message "foo\r\nbar\r\n"}}))))

(deftest test-quit-mode
  (let [port (+ (rand-int (- 65535 1000)) 1000)]
    (with-open [server (ServerSocket. port)
                client (Socket. "localhost" port)
                socket (.accept server)]
      (let [session (quit-mode {} nil socket nil nil)]
        (is (= session {}))
        (is (.isClosed socket))))))

(deftest test-unrecognized-mode
  (let [writer (StringWriter.)
        session (unrecognized-mode {:mode :foo} nil nil nil writer)]
    (is (= session {:mode :command}))
    (is (= (str writer) "451 Server error\r\n"))))

(deftest test-ext-keywords
  (let [config {:extensions {:foo {:keyword "FOO"} :bar {:keyword "BAR"}}}]
    (is (= (ext-keywords config) ["FOO" "BAR"]))))
