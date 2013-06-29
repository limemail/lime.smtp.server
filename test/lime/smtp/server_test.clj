(ns lime.smtp.server-test
  (require [clojure.java.io :as io]
           [clojure.test :refer :all]
           [lime.smtp.server :refer :all])
  (import (clojure.lang ExceptionInfo)
          (java.io StringReader StringWriter)
          (java.net ServerSocket Socket)))

(deftest test-handle-ehlo
  (is (= (handle-ehlo {:server-host "server.example.com" :mode :command} "")
         {:server-host "server.example.com"
          :reply {:code 250 :text "server.example.com"}
          :client-host nil
          :mode :command}))
  (is (= (handle-ehlo {:server-host "server.example.com" :mode :command} "client.example.com")
         {:server-host "server.example.com"
          :client-host "client.example.com"
          :reply {:code 250 :text "server.example.com"}
          :mode :command}))
  (is (not (contains? (handle-ehlo {:foo :bar} "") :foo)))
  ;; TODO Test for advertized extensions
  ;; TODO Test for domain or address literal syntax error (501)
  )

(deftest test-handle-helo
  (is (= (handle-helo {:server-host "server.example.com" :mode :command} "")
         {:server-host "server.example.com"
          :reply {:code 250 :text "server.example.com" }
          :client-host nil
          :mode :command}))
  (is (= (handle-helo {:server-host "server.example.com" :mode :command} "client.example.com")
         {:server-host "server.example.com"
          :client-host "client.example.com"
          :reply {:code 250 :text "server.example.com"}
          :mode :command}))
  (is (not (contains? (handle-ehlo {:foo :bar} "") :foo)))
  ;; TODO Test for domain syntax error (501)
  )

(deftest test-handle-mail
  (is (= (handle-mail {:mode :command} "FROM:foo@example.com")
         {:sender "foo@example.com"
          :reply {:code 250}
          :mode :command}))
  ;; TODO Test that invalid parameters return 455
  ;; TODO Test if mail session is already in progress (503)
  ;; TODO Test that mailbox syntax is correct (otherwise 553)
  ;; TODO Test that unrecognized or not implemented params return 555
  )

(deftest test-handle-rcpt
  (is (= (handle-rcpt {:mode :command} "TO:foo@example.com")
         {:recipients ["foo@example.com"]
          :reply {:code 250}
          :mode :command}))
  (is (= (handle-rcpt {:recipients ["bar@example.com"] :mode :command} "TO:foo@example.com")
         {:recipients ["bar@example.com" "foo@example.com"]
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
  (is (= (build-reply {:code 250 :lines ["one" "two" "three"]})
         "250-one\r\n250-two\r\n250 three"))
  (is (= (build-reply {:code 250 :lines ["one" "two"]})
         "250-one\r\n250 two"))
  (is (= (build-reply {:code 250 :lines ["one"]})
         "250 one"))
  (is (= (build-reply {:code 250 :lines []})
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
           {:mode :command :message "foo\r\nbar\r\n"}))))

(deftest test-quit-mode
  (let [port (+ (rand-int (- 65535 1000)) 1000)]
    (with-open [server (ServerSocket. port)
                client (Socket. "localhost" port)
                socket (.accept server)]
      (let [writer (StringWriter.)
            session (quit-mode {} nil socket nil writer)]
        (is (= session {}))
        (is (= (str writer) "221 Closing connection\r\n"))
        (is (.isClosed socket))))))

(deftest test-unrecognized-mode
  (let [writer (StringWriter.)
        session (unrecognized-mode {:mode :foo} nil nil nil writer)]
    (is (= session {:mode :command}))
    (is (= (str writer) "451 Server error\r\n"))))
