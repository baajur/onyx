(ns onyx.log.curator-test
  (:require [clojure.core.async :refer [chan >!! <!! close!]]
            [com.stuartsierra.component :as component]
            [onyx.system :as system]
            [onyx.extensions :as extensions]
            [onyx.messaging.dummy-messenger]
            [onyx.test-helper :refer [load-config]]
            [onyx.log.curator :as cu]
            [midje.sweet :refer :all]
            [onyx.api]))

(def onyx-id (java.util.UUID/randomUUID))

(def config (load-config))

(def env-config 
  (assoc (:env-config config) :onyx/id onyx-id))

(let [env (onyx.api/start-env env-config)] 
  (try 
    (let [client (cu/connect (:zookeeper/address env-config) "onyx")
          value [1 3 48]]

      (cu/create client "/ab" :data (into-array Byte/TYPE value))

      (facts "Value is written and can be read" 
             (fact value => (into [] (:data (cu/data client "/ab")))))
      (cu/close client)

      (let [client2 (cu/connect (:zookeeper/address env-config) "onyx")
            watcher-sentinel (atom 0)] 
        (facts "Test default ephemerality from previous test"
               (fact (into [] (:data (cu/data client2 "/ab"))) => (throws Exception)))

        ;; write out some sequential values with parent
        (cu/create-all client2 "/ab/zd/hi/entry-" :sequential? true :persistent? true)
        (cu/create-all client2 "/ab/zd/hi/entry-" :sequential? true)
        (cu/create client2 "/ab/zd/hi/entry-" :sequential? true :persistent? true)
        (cu/create client2 "/ab/zd/hi/entry-" :sequential? true)

        (facts "Check sequential children can be found"
               (fact 
                 (cu/children client2 "/ab/zd/hi" :watcher (fn [_] (swap! watcher-sentinel inc))) 
                 => 
                 ["entry-0000000000"  "entry-0000000001"  "entry-0000000002"  "entry-0000000003"]))
        
        ;; add another child so watcher will be triggered
        (cu/create client2 "/ab/zd/hi/entry-" :sequential? true :persistent? true)

        (facts "Check watcher triggered"
               (fact @watcher-sentinel => 1))

      (cu/close client2)

      (let [client3 (cu/connect (:zookeeper/address env-config) "onyx")]

        (facts "Check only sequential persistent children remain"
               (fact 
                 (cu/children client3 "/ab/zd/hi") => 
                 ["entry-0000000000" "entry-0000000002" "entry-0000000004"]))

        (cu/create client3 "/ab2" :data (into-array Byte/TYPE value) :persistent? true)

        (facts "Check exists after add"
               (fact (:aversion (cu/exists client3 "/ab2")) => 0))

        (cu/delete client3 "/ab2")

        (facts "Deleted value"
               (fact 
                 (cu/data client "/ab") => (throws Exception)))
        
        (facts "Check exists after delete"
               (fact (cu/exists client3 "/ab2") => nil)))))

    (finally
      (onyx.api/shutdown-env env))))


