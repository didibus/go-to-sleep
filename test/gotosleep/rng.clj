(ns gotosleep.rng
  "A tiny seeded PRNG (xorshift64*) for reproducible property tests.")

(defn make [seed] (atom (if (zero? seed) 88172645463325252 seed)))

(defn next-long! [st]
  (let [x @st
        x (bit-xor x (unsigned-bit-shift-right x 12))
        x (bit-xor x (bit-shift-left x 25))
        x (bit-xor x (unsigned-bit-shift-right x 27))]
    (reset! st x)
    (unsigned-bit-shift-right (unchecked-multiply x 2685821657736338717) 1)))

(defn int! [st n] (mod (next-long! st) n))
(defn pick! [st coll] (nth coll (int! st (count coll))))
(defn chance! [st p] (< (int! st 1000000) (* p 1000000)))
