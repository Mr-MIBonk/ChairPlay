# Working on DiPlay Desktop (`desktop/`)

Rules for coding agents, learned from mistakes made on this port. They add to the repository's `AGENTS.md`;
where they differ for `desktop/`, these win (the CI command there builds Android and does not apply here).

## Scope

- Change only `desktop/` and `.gitignore`. Upstream files (`shared/`, `common/`, the root `CLAUDE.md` and
  `AGENTS.md`, `.github/`) stay untouched so upstream merges stay clean. Replace or wrap upstream code from
  `desktop/` instead.
- Notes and plans that are not for the repository go to `desktop-docs/` (ignored by git).
- To check that upstream still merges: `git fetch upstream`, then
  `git merge-tree $(git merge-base HEAD upstream/main) HEAD upstream/main` and look for conflict markers.
  Git here is 2.37, which has no `merge-tree --write-tree`; its error exit is not a conflict. A clean merge can
  still break the desktop build when upstream changes the core's API: build and test after merging.

## Building and testing

- JDK 17 only. From Git Bash: `export JAVA_HOME="/c/Program Files/Java/jdk-17.0.1"`, then `./gradlew -p desktop …`.
  Never build the repository root: it needs the Android SDK.
- Run tests with a bound and a log, not through a pipe that can hide a hang:
  `timeout 600 ./gradlew -p desktop test --console=plain > <scratchpad>/test.log 2>&1`, then read the log.
- Count results from `desktop/build/test-results/test/*.xml`, and check their time: a run that did not reach
  the tests leaves the previous run's reports behind.
- Every commit must build on its own. When splitting a change into several commits, compile the earlier one
  alone (a temporary `git worktree` at that commit) before calling it done.
- Write the failing test first and watch it fail for the expected reason (superpowers:test-driven-development).

## Editing files

- Do not put Kotlin or Markdown that contains backslash escapes (`"\n"`, `\\`, Windows paths) through a shell
  heredoc into a Python string: the tool layer turns `\n` into real line breaks and breaks the code. Use the
  Edit tool for such lines, or build the backslash with `chr(92)` in Python.
- Avoid `sed` on lines with backslashes or Windows paths; use the Edit tool.
- Kotlin initialises properties top to bottom. A property (or companion `val`) that calls code using a
  property declared further down gets null: declare in order, or make it `by lazy`.

## Running the app and background work

- Never hard-kill a running CarPlay session (`taskkill /F`, stopping the background task that started the
  exe): the iPhone then ignores new sessions until it is restarted. Stop it with `taskkill //PID <pid>`
  without `/F`, which closes the window and runs the shutdown hook, or ask the owner to close the window.
- On this Windows machine, stopping a background task or a Monitor kills only the Git Bash wrapper; its
  `tail -F`, `grep --line-buffered` and `sleep` children keep running inside the project folder and block
  renaming it. Prefer bounded waits (`for i in $(seq 1 60); do grep -q … && break; sleep 1; done`) over
  `tail -F`; after stopping any pipeline, kill the leftovers by command line (PowerShell
  `Get-CimInstance Win32_Process`, matching the scratchpad log path), never other sessions' processes.
- Stop the Gradle daemon (`./gradlew -p desktop --stop`) when the session's builds are done.
- `tasklist | grep …` lists its own `grep.exe`; that one is not a leftover.
- Install the app with `./gradlew -p desktop deploy` into `Documents\DiPlayDesktopApp`; it keeps the data
  files there. Never print or copy `identity.properties`, `offline-mfi/` or the Wi-Fi passphrase.
