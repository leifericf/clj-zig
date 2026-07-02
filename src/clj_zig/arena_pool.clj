(ns clj-zig.arena-pool
  "Thread-local arena pooling for the FFM call path. When enabled
  (-Dclj-zig.arena-pool=true), a thread-local confined arena is reused
  across calls instead of creating and closing one per call. The arena
  is refreshed (closed and replaced) every `refresh-interval` calls to
  bound its growth. Disabled by default; enable for latency-sensitive
  workloads where the per-call arena overhead dominates."
  (:import [java.lang.foreign Arena]))

(def ^:private pool-enabled
  (Boolean/getBoolean "clj-zig.arena-pool"))

(def ^:private refresh-interval 1024)

(def ^:private ^ThreadLocal tl-arena
  (ThreadLocal/withInitial
   (reify java.util.function.Supplier
     (get [_] {:arena (Arena/ofConfined) :count 0}))))

(defn- refresh-if-needed []
  (let [entry (.get tl-arena)]
    (when (>= (:count entry) refresh-interval)
      (.close ^Arena (:arena entry))
      (.set tl-arena {:arena (Arena/ofConfined) :count 0}))
    entry))

(defn with-pooled-arena
  "Run `f` with an Arena. When pooling is enabled, reuses a thread-local
  confined arena, incrementing its call counter (the arena is refreshed
  every `refresh-interval` calls to bound memory growth). When disabled,
  allocates a fresh confined arena per call (the default, matching the
  existing `with-open` pattern)."
  [f]
  (if pool-enabled
    (let [entry (refresh-if-needed)
          arena (:arena entry)]
      (.set tl-arena (assoc entry :count (inc (:count entry))))
      (f arena))
    (with-open [arena (Arena/ofConfined)]
      (f arena))))
