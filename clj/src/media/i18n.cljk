(ns media.i18n
  "Language support for the medium. media.gftd.ai renders a B-framed brief in the
  subject's (or source's) language, so the A→B medium spans language gaps too.
  Registry = ISO 639-1 (180+ codes, well over the 100-language target). Pure: the
  TS shell uses lang-directive to steer the litellm generation and stores the
  resolved code on the link."
  (:require [kotoba.lang.text :as str]))

(defn- ->js [x] #?(:cljs (clj->js x) :clj x))

(def languages
  "ISO 639-1 code → English name. 180+ entries (> the 100-language target)."
  {"ab" "Abkhaz" "aa" "Afar" "af" "Afrikaans" "ak" "Akan" "sq" "Albanian"
   "am" "Amharic" "ar" "Arabic" "an" "Aragonese" "hy" "Armenian" "as" "Assamese"
   "av" "Avaric" "ae" "Avestan" "ay" "Aymara" "az" "Azerbaijani" "bm" "Bambara"
   "ba" "Bashkir" "eu" "Basque" "be" "Belarusian" "bn" "Bengali" "bh" "Bihari"
   "bi" "Bislama" "bs" "Bosnian" "br" "Breton" "bg" "Bulgarian" "my" "Burmese"
   "ca" "Catalan" "ch" "Chamorro" "ce" "Chechen" "ny" "Chichewa" "zh" "Chinese"
   "cv" "Chuvash" "kw" "Cornish" "co" "Corsican" "cr" "Cree" "hr" "Croatian"
   "cs" "Czech" "da" "Danish" "dv" "Divehi" "nl" "Dutch" "dz" "Dzongkha"
   "en" "English" "eo" "Esperanto" "et" "Estonian" "ee" "Ewe" "fo" "Faroese"
   "fj" "Fijian" "fi" "Finnish" "fr" "French" "ff" "Fula" "gl" "Galician"
   "ka" "Georgian" "de" "German" "el" "Greek" "gn" "Guarani" "gu" "Gujarati"
   "ht" "Haitian Creole" "ha" "Hausa" "he" "Hebrew" "hz" "Herero" "hi" "Hindi"
   "ho" "Hiri Motu" "hu" "Hungarian" "ia" "Interlingua" "id" "Indonesian"
   "ie" "Interlingue" "ga" "Irish" "ig" "Igbo" "ik" "Inupiaq" "io" "Ido"
   "is" "Icelandic" "it" "Italian" "iu" "Inuktitut" "ja" "Japanese" "jv" "Javanese"
   "kl" "Kalaallisut" "kn" "Kannada" "kr" "Kanuri" "ks" "Kashmiri" "kk" "Kazakh"
   "km" "Khmer" "ki" "Kikuyu" "rw" "Kinyarwanda" "ky" "Kyrgyz" "kv" "Komi"
   "kg" "Kongo" "ko" "Korean" "ku" "Kurdish" "kj" "Kwanyama" "la" "Latin"
   "lb" "Luxembourgish" "lg" "Ganda" "li" "Limburgish" "ln" "Lingala" "lo" "Lao"
   "lt" "Lithuanian" "lu" "Luba-Katanga" "lv" "Latvian" "gv" "Manx" "mk" "Macedonian"
   "mg" "Malagasy" "ms" "Malay" "ml" "Malayalam" "mt" "Maltese" "mi" "Maori"
   "mr" "Marathi" "mh" "Marshallese" "mn" "Mongolian" "na" "Nauru" "nv" "Navajo"
   "nb" "Norwegian Bokmal" "nd" "North Ndebele" "ne" "Nepali" "ng" "Ndonga"
   "nn" "Norwegian Nynorsk" "no" "Norwegian" "ii" "Nuosu" "nr" "South Ndebele"
   "oc" "Occitan" "oj" "Ojibwe" "cu" "Old Church Slavonic" "om" "Oromo" "or" "Oriya"
   "os" "Ossetian" "pa" "Punjabi" "pi" "Pali" "fa" "Persian" "pl" "Polish"
   "ps" "Pashto" "pt" "Portuguese" "qu" "Quechua" "rm" "Romansh" "rn" "Kirundi"
   "ro" "Romanian" "ru" "Russian" "sa" "Sanskrit" "sc" "Sardinian" "sd" "Sindhi"
   "se" "Northern Sami" "sm" "Samoan" "sg" "Sango" "sr" "Serbian" "gd" "Scottish Gaelic"
   "sn" "Shona" "si" "Sinhala" "sk" "Slovak" "sl" "Slovene" "so" "Somali"
   "st" "Southern Sotho" "es" "Spanish" "su" "Sundanese" "sw" "Swahili" "ss" "Swati"
   "sv" "Swedish" "ta" "Tamil" "te" "Telugu" "tg" "Tajik" "th" "Thai"
   "ti" "Tigrinya" "bo" "Tibetan" "tk" "Turkmen" "tl" "Tagalog" "tn" "Tswana"
   "to" "Tonga" "tr" "Turkish" "ts" "Tsonga" "tt" "Tatar" "tw" "Twi"
   "ty" "Tahitian" "ug" "Uyghur" "uk" "Ukrainian" "ur" "Urdu" "uz" "Uzbek"
   "ve" "Venda" "vi" "Vietnamese" "vo" "Volapuk" "wa" "Walloon" "cy" "Welsh"
   "wo" "Wolof" "fy" "Western Frisian" "xh" "Xhosa" "yi" "Yiddish" "yo" "Yoruba"
   "za" "Zhuang" "zu" "Zulu"})

(def ^:private name->code
  (into {} (map (fn [[c n]] [(str/lower n) c])) languages))

(defn supported-lang-count [] (count languages))

(defn normalize-lang
  "Accepts a code (\"ja\", \"pt-BR\") or an English name (\"Japanese\") and
  returns the canonical ISO 639-1 code, or nil if unsupported. nil/blank input
  defaults to English (\"en\")."
  [input]
  (if (or (nil? input) (and (string? input) (str/blank? input)))
    "en"
    (let [s (str/lower (str/trim (str input)))
          base (first (str/split s #"[-_]"))]   ; pt-BR → pt
      (cond
        (contains? languages base) base
        (contains? name->code s)   (name->code s)
        :else nil))))

(defn lang-name [code] (get languages (normalize-lang code)))

(defn lang-directive
  "System-prompt directive steering generation into the target language."
  [code]
  (let [c (normalize-lang code) n (lang-name c)]
    (if (and c n (not= c "en"))
      (str "Write the headline and brief in " n " (" c "). Use natural, native phrasing for that language.")
      "Write the headline and brief in clear English.")))

;; ── JS exports ───────────────────────────────────────────────────────────────
(defn supported-langs [] (->js (vec (sort (keys languages)))))
(defn js-supported-lang-count [] (supported-lang-count))
(defn js-normalize-lang [input] (normalize-lang input))
(defn js-lang-name [code] (lang-name code))
(defn js-lang-directive [code] (lang-directive code))
