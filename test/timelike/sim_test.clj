(ns timelike.sim-test
  (:refer-clojure :exclude [time future])
  (:use clojure.test
        [clojure.pprint :only [pprint]]
        timelike.scheduler
        timelike.node
        [incanter.stats :only [quantile]]))

(defn linesep
  [f]
  (f)
  (println))

(defn reset-test!
  [f]
  (reset-scheduler!)
  (f)
  (when-not (zero? @all-threads)
    (await-completion))
  (reset-scheduler!))

(use-fixtures :each reset-test!)

(defn pstats
  "Print statistics. We examing only the middle half of the request set, to
  avoid measuring ramp-up and draining dynamics."
  [reqs]
  (println)
  (let [n          (count reqs)
        reqs       (->> reqs
                     (drop (/ n 4))
                     (take (/ n 2)))
        latencies  (map latency reqs)
        response-rate (response-rate reqs)
        request-rate  (request-rate reqs)
        [q0 q5 q95 q99 q1] (quantile latencies :probs [0 0.5 0.95 0.99 1])]
    (println "Total reqs:      " n)
    (println "Selected reqs:   " (count reqs)) 
    (println "Successful frac: " (float (/ (count (remove error? reqs))
                                         (count reqs))))
    (println "Request rate:    " (float (* 1000 request-rate))  "reqs/s")
    (println "Response rate:   " (float (* 1000 response-rate)) "reqs/s")

    (println "Latency distribution:")
    (println "Min:    " q0)
    (println "Median: " q5)
    (println "95th %: " q95)
    (println "99th %: " q99)
    (println "Max:    " q1)))
 
(def n 100000)
(def interval 0.5)
(def pool-size 250)

(defn test-node
  [name node]
  (println name)
  (let [results (future*
                  (load-poisson n interval req node))]
    (pstats @results) 
    (println)))

(defn dyno
  "A singlethreaded, request-queuing server, with a fixed per-request
  overhead plus an exponentially distributed time to process the request,
  connected by a short network cable."
  []
  (cable 2 
    (queue-exclusive
      (delay-fixed 20
        (delay-exponential 100 
          (server :rails))))))

(defn faulty-dyno
  "Like a dyno, but only 90% available."
  []
  (cable 2
    (faulty 20000 1000
      (queue-exclusive
        (delay-fixed 20
          (delay-exponential 100
            (server :rails)))))))

(defn dynos
  "A pool of n dynos"
  [n]
  (pool n (dyno)))

(deftest single-dyno-test
         (let [responses (future*
                           (load-poisson 10000 150 req (dyno)))]
           (println "A single dyno")
           (println)
           (prn (first @responses))
           (pstats @responses)
           (println)))

(deftest ^:simple random-test
         (test-node "Random LB"
           (lb-random 
             (dynos pool-size))))

(deftest ^:simple rr-test
         (test-node "Round-robin LB"
           (lb-rr 
             (dynos pool-size))))

(deftest ^:simple min-conn-test
         (test-node "Min-conn LB"
           (lb-min-conn
             (dynos pool-size))))

(defn bamboo-test
  [n]
  (test-node (str "Bamboo with " n " routers")
     (let [dynos (dynos pool-size)]
       (lb-random
         (pool n
           (cable 5
             (lb-min-conn
               dynos)))))))

(deftest ^:bamboo bamboo-2
         (bamboo-test 2))
(deftest ^:bamboo bamboo-4
         (bamboo-test 4))
(deftest ^:bamboo bamboo-8
         (bamboo-test 8))
(deftest ^:bamboo bamboo-16
         (bamboo-test 16))

(deftest ^:faulty min-conn-faulty-test
         (test-node "Min-conn -> pool of faulty dynos."
           (lb-min-conn
             (pool pool-size
               (faulty-dyno)))))

(deftest ^:faulty min-conn-faulty-test-hold
         (test-node "Min-conn with 1s error hold time -> pool of faulty dynos."
           (lb-min-conn :lb {:error-hold-time 1000}
             (pool pool-size
               (faulty-dyno)))))

(deftest ^:faulty retry-min-conn-faulty-test
         (test-node "Retry -> min-conn -> faulty pool"
           (retry 3
             (lb-min-conn :lb {:error-hold-time 1000}
               (pool pool-size
                 (faulty-dyno))))))

(defn faulty-lb
  [pool]
  (faulty 20000 1000
    (retry 3
      (lb-min-conn :lb {:error-hold-time 1000}
        pool))))

(deftest ^:distributed random-faulty-lb-test
  (test-node "Random -> 10 faulty lbs -> One pool"
    (let [dynos (pool pool-size (faulty-dyno))]
      (lb-random
        (pool 10
          (cable 5
            (faulty-lb
              dynos)))))))

(deftest ^:distributed retry-random-faulty-lb-test
  (test-node "Retry -> Random -> 10 faulty lbs -> One pool"
    (let [dynos (pool pool-size (faulty-dyno))]
      (retry 3
        (lb-random
          (pool 10
            (cable 5
              (faulty-lb
                dynos))))))))

(deftest ^:distributed retry-random-faulty-lb-block-test
  (assert (zero? (mod pool-size 10)))
  (test-node "Retry -> Random -> 10 faulty lbs -> 10 pools"
    (retry 3
      (lb-random
        (pool 10
          (cable 5
            (faulty-lb
              (pool (/ pool-size 10)
                (faulty-dyno)))))))))

(defn limit-conn
  [limit downstream]
  (let [c (atom 0)]
    (fn [req]
      (if (>= @c limit)
        (conj req (error))
        (do (swap! c inc)
            (let [res (downstream req)]
            (swap! c dec)
            res))))))

(defn uwsgi
  [n]
  (lb-rr :uwsgi
    (pool n
      (queue-exclusive
        (delay-fixed 20
          (delay-exponential 100
            (server :django)))))))

(defn app-node
  [n p]
  (cable 2
    (lb-min-conn :nginx
      (pool n
        (uwsgi p)))))

(def nodes 10)
(def instances 4)
(def procs 8)
(def limit-ratio 1.5)

(deftest ^:theory normal-test
  (test-node "Varnish -> nginx (least_conn) -> uWSGI"
    (lb-random :varnish
      (pool nodes
        (app-node instances procs)))))

(deftest ^:theory theory-test
  (test-node "Varnish (retry backends) -> nginx (limited) -> uWSGI"
    (retry nodes
      (lb-random :varnish
        (pool nodes
          (limit-conn (* instances procs limit-ratio)
            (app-node instances procs)))))))

(deftest ^:theory theory-no-retry-test
  (test-node "Varnish -> nginx (limited) -> uWSGI"
    (lb-random :varnish
      (pool nodes
        (limit-conn (* instances procs limit-ratio)
          (app-node instances procs))))))
