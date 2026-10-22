# Working on ChairPlay

Rules for coding agents, learned from mistakes made on this port. DiPlay itself lives in the `upstream/`
submodule; its `AGENTS.md` and CI command are for the Android app and do not apply here.

## Scope

- Never edit files inside `upstream/`: it is DiPlay, pinned to a commit, and moving it to a newer DiPlay must
  stay a pointer change. Replace or wrap upstream code from this repository instead.
- Notes and plans that are not for the repository go to `desktop-docs/` (ignored by git).
- To take a newer DiPlay: `git -C upstream fetch`, `git -C upstream checkout <commit>`, build and test, then
  commit the `upstream` pointer. Upstream can change the core's API, so a new DiPlay can break this build.
  When the submodule points at a mirror rather than DiPlay's GitHub, run `scripts\mirror-upstream.cmd <url>`
  first (add `--local` if only this clone uses the mirror): the script fills it and pins every commit used here.
- Never fetch DiPlay's refs into this repository (only into `upstream/`); its tags here would be pushed with
  `--tags`. The pre-push hook refuses history that does not start at this project's first commit.
- Git hooks live in `.githooks/` (`git config core.hooksPath .githooks`). Never bypass them with
  `--no-verify`; a refused commit means a key or app-folder file is staged. After changing a hook or
  `scripts\mirror-upstream.cmd`, run `scripts\test-repo-tools.cmd`.
- The owner works in cmd: scripts meant to be run by hand are `.cmd` (CRLF, Windows' own `sort`/`find` by full
  path). Hooks stay bash, because git runs them with its bash. Every process start is slow on Windows; batch
  work into few processes (one `git cat-file --batch`, not one call per file).
- Another agent session may share this working tree: never stage ahead of the commit (a `git rm` sat in the
  index and went into another session's commit). Commit only your own paths: `git commit -- <paths>`.
- Git here is 2.37: `merge-tree --write-tree` does not exist, and its error exit is not a conflict.

## Building and testing

- JDK 17 only. From Git Bash: `export JAVA_HOME="/c/Program Files/Java/jdk-17.0.1"`, then `./gradlew …`.
  Never run DiPlay's own build in `upstream/`: it needs the Android SDK.
- Run tests with a bound and a log, not through a pipe that can hide a hang:
  `timeout 900 ./gradlew test --console=plain > <scratchpad>/test.log 2>&1`, then read the log.
- Count results from `build/test-results/test/*.xml`, and check their time: a run that did not reach
  the tests leaves the previous run's reports behind.
- Upstream's C code (SpeexDSP behind `SpeexEchoCanceller`) is built by `speexEchoDll` with a pinned zig that
  `installZig` downloads into the Gradle user home. The first build needs about 6 minutes and the network. To
  move zig, change `zigVersion` and `zigSha256` together (the hash is in https://ziglang.org/download/index.json).
  The DLL ships in the image's root, next to the exe: jpackage's `app/` folder is not on `java.library.path`.
- `gradlew.bat` is patched: it passes no `-classpath` (the empty one broke the Oracle `javapath` java.exe stub
  when `JAVA_HOME` is unset). Regenerating the wrapper (`gradle wrapper`) drops the patch; put it back.
- Every commit must build on its own. When splitting a change into several commits, compile the earlier one
  alone (a temporary `git worktree` at that commit) before calling it done.
- Write the failing test first and watch it fail for the expected reason (superpowers:test-driven-development).

## Editing files

- Never put a script or file content longer than one line into a Bash command: no heredoc (`<<'EOF'`), no
  multi-line `python -c`, no `echo … >`. Quotes, backticks, `${…}` and backslashes in it break the shell or are
  rewritten by the tool layer (`\n` turns into a real line break), and this has wasted time about 20 times. Write
  the script with the Write tool into the scratchpad and run it by path (`python <scratchpad>/edit.py <args>`), or
  change the file with Edit or Write. This holds for every language and for "simple" content too.
- Avoid `sed` on lines with backslashes or Windows paths; use the Edit tool.
- Kotlin initialises properties top to bottom. A property (or companion `val`) that calls code using a
  property declared further down gets null: declare in order, or make it `by lazy`.

## Interface texts

Every text the app shows (web UI, controls window, window titles, messages) follows the Microsoft Writing Style
Guide:

- Sentence case everywhere: tabs, labels, buttons, choices, checkboxes, statuses, tooltips and group headers
  (written "Safe area"; CSS may show them in capitals). Names keep their spelling: ChairPlay, CarPlay, AltScreen,
  iPhone, Siri, Wi-Fi, Bluetooth, DiPlay. So do acronyms (HUD, ETA) and key names as Windows writes them (Enter,
  Esc, Shift+Tab, Num Enter). Units are lower case: px, fps, mm, ppi, s.
- One word, one spelling: choices side by side do not show the same word in two cases ("Turn card", not "Card"
  beside "Map + card").
- No full stop after a label, button, choice or one-sentence tooltip; a text of several sentences has them.
- Tooltips on actions are imperative ("End the session and connect again (Ctrl+R)"); a disabled control's
  tooltip says what enables it ("Connect the iPhone first").
- A message names the problem, then what to do: "MFi files not found. Put identity.pk8 and certificate.p7b in
  this folder:".
- US English (License, color), and a comma before the last "and" of a list.
- No caption explains what happens when a switch is off or a field is blank: hover explains a field.

## Running the app and background work

- Never hard-kill a running CarPlay session (`taskkill /F`, stopping the background task that started the
  exe): the iPhone then ignores new sessions until it is restarted. Stop it with `taskkill //PID <pid>`
  without `/F`, which closes the window and runs the shutdown hook, or ask the owner to close the window.
- On this Windows machine, stopping a background task or a Monitor kills only the Git Bash wrapper; its
  `tail -F`, `grep --line-buffered` and `sleep` children keep running inside the project folder and block
  renaming it. Prefer bounded waits (`for i in $(seq 1 60); do grep -q … && break; sleep 1; done`) over
  `tail -F`; after stopping any pipeline, kill the leftovers by command line (PowerShell
  `Get-CimInstance Win32_Process`, matching the scratchpad log path), never other sessions' processes.
- Stop the Gradle daemon (`./gradlew --stop`) when the session's builds are done.
- `tasklist | grep …` lists its own `grep.exe`; that one is not a leftover.
- Install the app with `./gradlew deploy` into `Documents\ChairPlayApp`; it keeps the data
  files there. Never print or copy `identity.properties`, `offline-mfi/` or the Wi-Fi passphrase.
