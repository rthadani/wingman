(ns wingman.frontend.config)

(defn env [var-name]
  (System/getenv var-name))

(defn- parse-long-safe [s]
  (try
    (Long/parseLong s)
    (catch NumberFormatException _
      nil)))

(defn load-config [opts]
  (merge {:port (or (parse-long-safe (env "PORT")) 7777)
          :pi-bin (or (env "PI_BIN") "pi")
          :provider (env "PI_PROVIDER")
          :model (env "PI_MODEL")
          :whisper-model (or (env "WHISPER_MODEL") "models/ggml-base.en.bin")
          :sessions-dir "data/sessions"
          :tools-dir "tools"
          :archive-dir "data/archive"
          :capture-url (or (env "CAPTURE_URL") "http://localhost:7778")}
         opts))
