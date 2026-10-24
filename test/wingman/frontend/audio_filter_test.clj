(ns wingman.frontend.audio-filter-test
  (:require [clojure.test :refer [deftest is]]
            [wingman.frontend.audio-filter :as audio-filter]))

(defn- pcm16
  "Encode a seq of signed 16-bit samples as little-endian PCM bytes."
  [samples]
  (let [samples (vec samples)
        ba (byte-array (* 2 (count samples)))]
    (doseq [[i s] (map-indexed vector samples)]
      (let [u (if (neg? s)
                (bit-and (+ s 65536) 0xFFFF)
                s)]
        (aset ba (* 2 i) (unchecked-byte (bit-and u 0xFF)))
        (aset ba (inc (* 2 i)) (unchecked-byte (bit-shift-right u 8)))))
    ba))

(deftest dbfs-empty-is-negative-infinity-test
  (is (= Double/NEGATIVE_INFINITY (audio-filter/dbfs (byte-array 0))))
  (is (= Double/NEGATIVE_INFINITY (audio-filter/dbfs (byte-array 64)))))

(deftest dbfs-full-scale-near-zero-dbfs-test
  (let [level (audio-filter/dbfs (pcm16 [32767 -32768 32767 -32768]))]
    (is (< (Math/abs level) 0.01))))

(deftest dbfs-ignores-constant-dc-offset-test
  ;; A silent chunk from a capture device with a +300 DC offset has no AC energy.
  (let [block (pcm16 (repeat 200 300))
        level (audio-filter/dbfs block)]
    (is (or (= Double/NEGATIVE_INFINITY level) (< level -50.0)))
    (is (false? (audio-filter/loud-enough? block -50.0)))))

(deftest dbfs-full-scale-with-dc-offset-near-zero-dbfs-test
  ;; Full-scale alternation still reads about 0 dBFS when riding on a +300 offset.
  (let [no-offset (pcm16 (take 200 (cycle [32467 -32467])))
        offset (pcm16 (take 200 (cycle [32767 -32167])))
        no-offset-level (audio-filter/dbfs no-offset)
        offset-level (audio-filter/dbfs offset)]
    (is (< (Math/abs offset-level) 1.0))
    (is (< (Math/abs (- offset-level no-offset-level)) 1.0))
    (is (true? (audio-filter/loud-enough? offset -50.0)))))

(deftest loud-enough-test
  ;; Constant 33 is a flat DC block: no AC energy, so it never passes the gate.
  (is (false? (audio-filter/loud-enough? (pcm16 (repeat 200 33)) -50.0)))
  (is (false? (audio-filter/loud-enough? (pcm16 (take 200 (cycle [33 -33]))) -50.0)))
  (is (true? (audio-filter/loud-enough? (pcm16 (take 200 (cycle [1036 -1036]))) -50.0))))

(deftest hallucinated-text-drops-repetitions-test
  ;; Periodic repetition (k=2): hello microphone hello microphone
  (is (true? (audio-filter/hallucinated-text? "Hello, microphone. Hello, microphone.")))

  ;; Low unique-word ratio after the repeated clause
  (is (true? (audio-filter/hallucinated-text?
              "I don't know what to do, but I don't know what to do. I don't know what to do. I don't know what to do.")))

  ;; Periodic repetition plus an annotation
  (is (true? (audio-filter/hallucinated-text? "Hello microphone. Hello microphone. [silence]")))

  ;; Periodic repetition of "no" behind a trailing tag
  (is (true? (audio-filter/hallucinated-text? "No, no, no, no. [BLANK_AUDIO]")))

  ;; Repeating clause without punctuation between the repeats
  (is (true? (audio-filter/hallucinated-text?
              "I'll dive in guys now, I'll dive in guys now, I'll dive in.")))

  ;; Known denylist exact matches, with and without a trailing tag
  (is (true? (audio-filter/hallucinated-text? "Thank you for watching.")))
  (is (true? (audio-filter/hallucinated-text? "Thank you for watching. [BLANK_AUDIO]")))
  (is (true? (audio-filter/hallucinated-text? "Please subscribe"))))

(deftest strip-annotations-removes-tags-test
  (is (= "Start translating to the circle like this."
         (audio-filter/strip-annotations "Start translating to the circle like this. [BLANK_AUDIO]")))
  (is (= "Hello there, did you hear me?"
         (audio-filter/strip-annotations
          "(keyboard clicking) (keyboard clicking) Hello there, did you hear me? (keyboard clicking) [BLANK_AUDIO]")))
  (is (= "" (audio-filter/strip-annotations "[BLANK_AUDIO]"))))

(deftest hallucinated-text-drops-tag-only-chunks-test
  ;; An annotated chunk that leaves less than 4 words of speech is non-speech sound.
  (is (true? (audio-filter/hallucinated-text? "[BLANK_AUDIO]")))
  (is (true? (audio-filter/hallucinated-text? "(keyboard clicking) [BLANK_AUDIO]")))
  (is (true? (audio-filter/hallucinated-text? "Hello there. [BLANK_AUDIO]")))
  (is (true? (audio-filter/hallucinated-text? "Yes. (keyboard clicking)"))))

(deftest hallucinated-text-keeps-speech-with-trailing-tag-test
  ;; Whisper appends [BLANK_AUDIO] to real speech; the speech is what counts.
  (is (false? (audio-filter/hallucinated-text?
               "Start translating to the circle like this. [BLANK_AUDIO]")))
  (is (false? (audio-filter/hallucinated-text? "I'm open to peace right now. [BLANK_AUDIO]")))
  (is (false? (audio-filter/hallucinated-text? "I got my phone. [BLANK_AUDIO]")))
  (is (false? (audio-filter/hallucinated-text?
               "(keyboard clicking) (keyboard clicking) Hello there, did you hear me? (keyboard clicking) [BLANK_AUDIO]"))))

(deftest hallucinated-text-keeps-real-speech-test
  (is (false? (audio-filter/hallucinated-text? "We need to scale the cache for more traffic.")))
  (is (false? (audio-filter/hallucinated-text? "I repeat, I repeat the question.")))
  (is (false? (audio-filter/hallucinated-text? "Thank you.")))
  (is (false? (audio-filter/hallucinated-text? "Hello there, did you hear me?")))
  (is (false? (audio-filter/hallucinated-text? "Walk me through the design of a rate limiter for a public API."))))
