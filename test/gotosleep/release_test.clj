(ns gotosleep.release-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [gotosleep.daemon.jobs :as jobs]
            [gotosleep.daemon.main :as daemon]
            [gotosleep.version :as version]))

(defn- files-under [root suffixes]
  (->> (file-seq (io/file root))
       (filter #(.isFile %))
       (map #(.getPath %))
       (filter (fn [path] (some #(str/ends-with? path %) suffixes)))
       sort))

(def canonical-mit-license
  (str "MIT License\n"
       "\n"
       "Copyright (c) 2026 didibus\n"
       "\n"
       "Permission is hereby granted, free of charge, to any person obtaining a copy\n"
       "of this software and associated documentation files (the \"Software\"), to deal\n"
       "in the Software without restriction, including without limitation the rights\n"
       "to use, copy, modify, merge, publish, distribute, sublicense, and/or sell\n"
       "copies of the Software, and to permit persons to whom the Software is\n"
       "furnished to do so, subject to the following conditions:\n"
       "\n"
       "The above copyright notice and this permission notice shall be included in all\n"
       "copies or substantial portions of the Software.\n"
       "\n"
       "THE SOFTWARE IS PROVIDED \"AS IS\", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR\n"
       "IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,\n"
       "FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE\n"
       "AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER\n"
       "LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,\n"
       "OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE\n"
       "SOFTWARE.\n"))

(def changelog-category-order
  {"Added" 0
   "Changed" 1
   "Deprecated" 2
   "Removed" 3
   "Fixed" 4
   "Security" 5})

(defn- line-index [lines expected]
  (first (keep-indexed (fn [i line] (when (= expected line) i)) lines)))

(defn- heading-index [lines pattern]
  (first (keep-indexed (fn [i line] (when (re-matches pattern line) i)) lines)))

(defn- section-end [lines start]
  (or (first (keep-indexed (fn [i line]
                             (when (and (> i start)
                                        (str/starts-with? line "## "))
                               i))
                           lines))
      (count lines)))

(defn- section-categories [lines start end]
  (->> (subvec lines (inc start) end)
       (keep #(second (re-matches #"### (Added|Changed|Deprecated|Removed|Fixed|Security)" %)))
       vec))

(defn- nonempty-categories? [lines start end]
  (let [section (subvec lines (inc start) end)
        headings (keep-indexed
                  (fn [i line]
                    (when (re-matches #"### (Added|Changed|Deprecated|Removed|Fixed|Security)" line)
                      i))
                  section)]
    (and (seq headings)
         (every?
          (fn [heading]
            (let [next-heading (or (first (filter #(> % heading) headings))
                                   (count section))]
              (some #(str/starts-with? % "- ")
                    (subvec section (inc heading) next-heading))))
          headings))))

(defn- categories-ordered? [categories]
  (= (map changelog-category-order categories)
     (sort (map changelog-category-order categories))))

(defn- contains-all? [text fragments]
  (every? #(str/includes? text %) fragments))

(defn- squish [text]
  (str/replace text #"\s+" " "))

(deftest authoritative-runtime-version
  (let [deps (edn/read-string (slurp "deps.edn"))]
    (is (= "0.1.0" version/value))
    (is (re-matches #"\d+\.\d+\.\d+" version/value))
    (is (= "0.8.16" (:jolt/min-version deps)))))

(deftest runtime-identities
  (is (= "com.rubberducking.gotosleep.agent" jobs/agent-label))
  (is (= jobs/agent-label (:agent-label jobs/default-config)))
  (is (= "/Library/LaunchAgents/com.rubberducking.gotosleep.agent.plist"
         (:agent-plist jobs/default-config)))
  (is (= "com.rubberducking.gotosleep.daemon" daemon/daemon-label))
  (is (= "com.rubberducking.gotosleep.pkg" daemon/package-receipt))
  (is (= {:agent-label "com.rubberducking.gotosleep.agent"
          :agent-plist "/Library/LaunchAgents/com.rubberducking.gotosleep.agent.plist"
          :daemon-label "com.rubberducking.gotosleep.daemon"
          :daemon-plist "/Library/LaunchDaemons/com.rubberducking.gotosleep.daemon.plist"
          :package-receipt "com.rubberducking.gotosleep.pkg"}
         daemon/default-uninstall-config)))

(deftest authoritative-version-source
  (let [version-literals
        (for [path (files-under "src" [".clj"])
              :let [source (slurp path)]
              :when (str/includes? source "\"0.1.0\"")]
          path)]
    (is (= ["src/gotosleep/version.clj"] version-literals))))

(deftest public-license-is-canonical-mit
  (is (= canonical-mit-license (slurp "LICENSE")))
  (is (re-find #"(?i)\bMIT License\b" (slurp "README.md"))))

(deftest readme-release-contract
  (let [readme (slurp "README.md")
        compact (squish readme)
        enforcement-index (line-index (vec (str/split-lines readme))
                                      "## Understand enforcement before installing")
        install-index (line-index (vec (str/split-lines readme)) "## Install")]
    (testing "support and maturity"
      (is (contains-all? compact
                         ["Version 0.1.0"
                          "Apple silicon (`arm64`)"
                          "macOS 26 or later"
                          "no x86_64 build"
                          "complete installed reboot/login enforcement acceptance sequence has not yet been run"])))
    (testing "enforcement is disclosed before installation"
      (is (number? enforcement-index))
      (is (number? install-index))
      (is (< enforcement-index install-index))
      (is (contains-all? compact
                         ["root LaunchDaemon"
                          "Quitting the menu-bar app does not stop enforcement"
                          "freezes eight hours before it begins"
                          "complete weekly schedule is growth-only"
                          "may not shorten, move, disable, or delete"
                          "24 hours of continuous active state"
                          "private, unsupported macOS symbol `SACLockScreenImmediate`"])))
    (testing "release artifacts, integrity, and trust boundaries"
      (is (contains-all? compact
                         ["`GoToSleep-0.1.0.pkg`"
                          "`SHA256SUMS`"
                          "shasum -a 256 --strict -c SHA256SUMS"
                          "byte-for-byte identical"
                          "does not authenticate the publisher"
                          "Trust the release location"
                          "trust the published source"])))
    (testing "unsigned Gatekeeper installation paths"
      (is (contains-all? compact
                         ["intentionally unsigned and not notarized"
                          "Gatekeeper is therefore expected to refuse"
                          "System Settings → Privacy & Security"
                          "Open Anyway"
                          "xattr -d com.apple.quarantine GoToSleep-0.1.0.pkg"
                          "sudo installer -pkg GoToSleep-0.1.0.pkg -target /"
                          "`sudo` authorizes changes to the Mac"
                          "does not authenticate the package"
                          "does not"
                          "replace code signing"])))
    (testing "uninstall availability"
      (is (contains-all? compact
                         ["authoritative state is open"
                          "choose **Uninstall…**"
                          "unavailable while any block is frozen or active"])))
    (testing "notched menu-bar fallback"
      (is (contains-all? compact
                         ["crowded menu bar"
                          "MacBook display notch"
                          "open **Go To Sleep** from Applications or Spotlight"
                          "bring Settings forward"])))
    (testing "release build prerequisites and commands"
      (is (contains-all? compact
                         ["Jolt 0.8.16"
                          "Chez Scheme 10.4.1"
                          "`tarm64osx` development directory"
                          "Apple Clang"
                          "brew install chezscheme"
                          "JOLT_CHEZ_CSV"
                          "export PATH=\"$JOLT_CHEZ_CSV:$PATH\""
                          "jolt -M:test"
                          "jolt -M:smoke"
                          "bash dev/e2e/run.sh"
                          "jolt -A:test -m gotosleep.agent.cancel-smoke"
                          "jolt stage"
                          "jolt pkg"
                          "bash dev/check-pkg.sh"
                          "target/release/GoToSleep-0.1.0.pkg"
                          "target/release/SHA256SUMS"])))
    (testing "source layout and license"
      (is (contains-all? compact
                         ["## Source layout"
                          "`src/gotosleep/daemon/`"
                          "`src/gotosleep/agent/`"
                          "`src/gotosleep/lock/`"
                          "`test/`"
                          "`smoke/`"
                          "`dev/e2e/`"
                          "`packaging/`"
                          "## License"
                          "[MIT License](LICENSE)"])))
    (testing "documentation makes no unsupported completion claim"
      (is (not (re-find #"(?i)\b(fully verified|fully tested|complete end-to-end acceptance)\b"
                        readme))))))

(defn- assert-valid-changelog [changelog]
  (let [lines (vec (str/split-lines changelog))
        title-index (line-index lines "# Changelog")
        unreleased-index (line-index lines "## [Unreleased]")
        version-pattern (re-pattern
                         (str "^## \\[" (str/replace version/value "." "\\.")
                              "\\] - \\d{4}-\\d{2}-\\d{2}$"))
        version-index (heading-index lines version-pattern)]
    (is (= 0 title-index))
    (is (str/includes? changelog
                       "[Keep a Changelog](https://keepachangelog.com/en/1.1.0/)"))
    (is (number? unreleased-index))
    (is (str/includes? changelog version/value))
    (if version-index
      (testing "final export form"
        (let [unreleased-end (section-end lines unreleased-index)
              version-end (section-end lines version-index)
              categories (section-categories lines version-index version-end)]
          (is (< unreleased-index version-index))
          (is (empty? (filter #(or (str/starts-with? % "### ")
                                   (str/starts-with? % "- "))
                             (subvec lines (inc unreleased-index) unreleased-end))))
          (is (nonempty-categories? lines version-index version-end))
          (is (categories-ordered? categories))
          (is (re-find
               (re-pattern
                (str "(?m)^\\[Unreleased\\]: https://github\\.com/[^/\\s]+/[^/\\s]+/compare/v"
                     (str/replace version/value "." "\\.") "\\.\\.\\.HEAD$"))
               changelog))
          (is (re-find
               (re-pattern
                (str "(?m)^\\[" (str/replace version/value "." "\\.")
                     "\\]: https://github\\.com/[^/\\s]+/[^/\\s]+/releases/tag/v"
                     (str/replace version/value "." "\\.") "$"))
               changelog))
          :final))
      (testing "canonical preview form"
        (let [unreleased-end (section-end lines unreleased-index)
              categories (section-categories lines unreleased-index unreleased-end)]
          (is (nonempty-categories? lines unreleased-index unreleased-end))
          (is (categories-ordered? categories))
          (is (nil? (heading-index lines #"^## \[\d+\.\d+\.\d+\](?: - .+)?$")))
          (is (not (re-find #"(?m)^\[[^\]]+\]: https://github\.com/" changelog)))
          :preview)))))

(deftest changelog-is-valid-preview-or-final-release
  (assert-valid-changelog (slurp "CHANGELOG.md")))

(deftest final-export-changelog-shape-is-supported
  (is (= :final
         (assert-valid-changelog
          (str "# Changelog\n"
               "\n"
               "All notable changes to Go To Sleep will be documented in this file.\n"
               "\n"
               "The format is based on "
               "[Keep a Changelog](https://keepachangelog.com/en/1.1.0/).\n"
               "\n"
               "## [Unreleased]\n"
               "\n"
               "## [" version/value "] - 2026-10-04\n"
               "\n"
               "### Added\n"
               "\n"
               "- Initial public release.\n"
               "\n"
               "[Unreleased]: https://github.com/example/go-to-sleep/compare/v"
               version/value "...HEAD\n"
               "[" version/value "]: https://github.com/example/go-to-sleep/releases/tag/v"
               version/value "\n")))))
