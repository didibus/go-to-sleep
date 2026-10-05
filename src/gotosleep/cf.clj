(ns gotosleep.cf
  "CoreFoundation / IOKit reads through jolt.ffi, converted to plain Clojure
  data. Every CF value is type-checked before it is read; unknown types become
  [:cf-type id] rather than being dereferenced."
  (:require [jolt.ffi :as ffi]))

(ffi/load-library "/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation")
(ffi/load-library "/System/Library/Frameworks/IOKit.framework/IOKit")

(ffi/defcfn io-registry-root "IORegistryGetRootEntry" [:uint32] :uint32)
(ffi/defcfn io-service-matching "IOServiceNameMatching" [:string] :pointer)
(ffi/defcfn io-get-matching-service "IOServiceGetMatchingService" [:uint32 :pointer] :uint32)
(ffi/defcfn io-create-property "IORegistryEntryCreateCFProperty" [:uint32 :pointer :pointer :uint32] :pointer)
(ffi/defcfn io-object-release "IOObjectRelease" [:uint32] :int)

(ffi/defcfn cf-string-create "CFStringCreateWithCString" [:pointer :string :uint32] :pointer)
(ffi/defcfn cf-release "CFRelease" [:pointer] :void)
(ffi/defcfn cf-type-id "CFGetTypeID" [:pointer] :uint64)
(ffi/defcfn cf-array-type-id "CFArrayGetTypeID" [] :uint64)
(ffi/defcfn cf-dict-type-id "CFDictionaryGetTypeID" [] :uint64)
(ffi/defcfn cf-bool-type-id "CFBooleanGetTypeID" [] :uint64)
(ffi/defcfn cf-number-type-id "CFNumberGetTypeID" [] :uint64)
(ffi/defcfn cf-string-type-id "CFStringGetTypeID" [] :uint64)
(ffi/defcfn cf-array-count "CFArrayGetCount" [:pointer] :int64)
(ffi/defcfn cf-array-at "CFArrayGetValueAtIndex" [:pointer :int64] :pointer)
(ffi/defcfn cf-dict-count "CFDictionaryGetCount" [:pointer] :int64)
(ffi/defcfn cf-dict-keys-values "CFDictionaryGetKeysAndValues" [:pointer :pointer :pointer] :void)
(ffi/defcfn cf-bool-value "CFBooleanGetValue" [:pointer] :uint8)
(ffi/defcfn cf-number-value "CFNumberGetValue" [:pointer :int64 :pointer] :uint8)
(ffi/defcfn cf-string-cstring "CFStringGetCString" [:pointer :pointer :int64 :uint32] :uint8)

(def ^:private utf8 0x08000100)
(def ^:private k-cf-number-sint64 4)

(defn- cfstring->str [p]
  (ffi/with-alloc [buf 1024]
    (if (pos? (cf-string-cstring p buf 1024 utf8))
      (ffi/ptr->string buf)
      [:cf-type :unconvertible-string])))

(defn cf->clj
  "Converts a CF object graph (arrays, dictionaries, booleans, numbers,
  strings) to Clojure data. Does not release `p`."
  [p]
  (if (ffi/null? p)
    nil
    (let [t (cf-type-id p)]
      (cond
        (= t (cf-array-type-id))
        (mapv #(cf->clj (cf-array-at p %)) (range (cf-array-count p)))

        (= t (cf-dict-type-id))
        (let [n (cf-dict-count p)]
          (if (zero? n)
            {}
            (ffi/with-alloc [ks (* 8 n)]
              (ffi/with-alloc [vs (* 8 n)]
                (cf-dict-keys-values p ks vs)
                (into {} (for [i (range n)]
                           [(cf->clj (ffi/read ks :pointer (* 8 i)))
                            (cf->clj (ffi/read vs :pointer (* 8 i)))]))))))

        (= t (cf-bool-type-id)) (pos? (cf-bool-value p))

        (= t (cf-number-type-id))
        (ffi/with-alloc [out 8]
          (cf-number-value p k-cf-number-sint64 out)
          (ffi/read out :int64 0))

        (= t (cf-string-type-id)) (cfstring->str p)

        :else [:cf-type t]))))

(defn- read-property [entry key]
  (let [k (cf-string-create ffi/null key utf8)]
    (try
      (let [v (io-create-property entry k ffi/null 0)]
        (try (cf->clj v)
             (finally (when-not (ffi/null? v) (cf-release v)))))
      (finally (cf-release k)))))

(defn root-property
  "A property of the IORegistry root entry, as Clojure data (nil if absent)."
  [key]
  (let [root (io-registry-root 0)]
    (try (read-property root key)
         (finally (io-object-release root)))))

(defn service-property
  "A property of the first IOService matching `service-name` (nil if the
  service or the property is absent)."
  [service-name key]
  ;; IOServiceGetMatchingService consumes the matching dictionary.
  (let [svc (io-get-matching-service 0 (io-service-matching service-name))]
    (if (zero? svc)
      nil
      (try (read-property svc key)
           (finally (io-object-release svc))))))
