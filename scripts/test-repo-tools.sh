#!/usr/bin/env bash
# Tests for the git hooks in .githooks/ and for scripts\mirror-upstream.cmd, run in throwaway repositories
# under a temporary folder. Nothing here touches this repository or the app folder.
#
#   scripts\test-repo-tools.cmd  (from cmd; it runs this file with Git for Windows' bash)
set -u

HERE=$(cd "$(dirname "$0")/.." && (pwd -W 2>/dev/null || pwd))
WORK=$(cd "$(mktemp -d)" && (pwd -W 2>/dev/null || pwd))
trap 'rm -rf "$WORK"' EXIT

export GIT_AUTHOR_NAME=test GIT_AUTHOR_EMAIL=test@example.com
export GIT_COMMITTER_NAME=test GIT_COMMITTER_EMAIL=test@example.com
# Submodules and fetches below use local paths, which newer git refuses by default.
export GIT_CONFIG_COUNT=1 GIT_CONFIG_KEY_0=protocol.file.allow GIT_CONFIG_VALUE_0=always

PASSED=0
FAILED=0
pass() { PASSED=$((PASSED + 1)); echo "ok   $1"; }
fail() {
    FAILED=$((FAILED + 1)); echo "FAIL $1"
    if [ -n "${2:-}" ]; then printf '%s\n' "$2" | sed 's/^/     /'; fi
}

# --- Secrets the hooks must recognise, in a stand-in for the app folder --------------------------------

DATA="$WORK/data"
mkdir -p "$DATA/offline-mfi" "$DATA/pairings"
head -c 700 /dev/urandom > "$DATA/offline-mfi/identity.pk8"
head -c 900 /dev/urandom > "$DATA/offline-mfi/certificate.p7b"
head -c 300 /dev/urandom > "$DATA/pairings/0000-test"
SECRET_VALUE=$(head -c 48 /dev/urandom | base64 | tr -d '\n')
printf 'deviceId=AA:BB:CC:DD:EE:FF\nprivateKey=%s\n' "$SECRET_VALUE" > "$DATA/identity.properties"
export GUARD_DATA_DIRS="$DATA"
CERT_BASE64=$(base64 < "$DATA/offline-mfi/certificate.p7b" | tr -d '\n')

# --- pre-commit -----------------------------------------------------------------------------------------

REPO="$WORK/repo"
git init -q -b main "$REPO"
git -C "$REPO" config core.hooksPath "$HERE/.githooks"
git -C "$REPO" config core.autocrlf false
echo "readme" > "$REPO/README.md"
git -C "$REPO" add README.md
if git -C "$REPO" commit -qm "Start" > "$WORK/out" 2>&1; then pass "a plain first commit is accepted"
else fail "a plain first commit is accepted" "$(cat "$WORK/out")"; fi

# commit_rejected <name> <expected text in the output>: the staged change must be refused.
commit_rejected() {
    if git -C "$REPO" commit -qm "Try" > "$WORK/out" 2>&1; then
        fail "$1" "the commit went through"
        git -C "$REPO" reset -q --hard HEAD~1
    elif ! grep -qF -- "$2" "$WORK/out"; then
        fail "$1" "refused, but the output does not name '$2':"$'\n'"$(cat "$WORK/out")"
    else
        pass "$1"
    fi
    git -C "$REPO" reset -q --hard HEAD
    git -C "$REPO" clean -qfdx
}

commit_accepted() {
    if git -C "$REPO" commit -qm "Try" > "$WORK/out" 2>&1; then pass "$1"
    else fail "$1" "$(cat "$WORK/out")"; fi
    git -C "$REPO" clean -qfdx
}

mkdir -p "$REPO/offline-mfi"
echo "not really a key" > "$REPO/offline-mfi/identity.pk8"
git -C "$REPO" add -A
commit_rejected "a file in offline-mfi/ is refused" "offline-mfi/identity.pk8"

mkdir -p "$REPO/config"
echo "deviceId=1" > "$REPO/config/identity.properties"
git -C "$REPO" add -A
commit_rejected "identity.properties in any folder is refused" "config/identity.properties"

mkdir -p "$REPO/pairings"
echo "record" > "$REPO/pairings/phone"
git -C "$REPO" add -A
commit_rejected "a pairing record is refused" "pairings/phone"

mkdir -p "$REPO/keys"
echo "anything" > "$REPO/keys/dev.pem"
git -C "$REPO" add -A
commit_rejected "a .pem file is refused" "keys/dev.pem"

printf 'notes\n-----BEGIN PRIVATE KEY-----\nMIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQC7\n-----END PRIVATE KEY-----\n' \
    > "$REPO/notes.txt"
git -C "$REPO" add -A
commit_rejected "a PEM private key block is refused" "notes.txt"

printf 'val begin = "-----BEGIN PRIVATE KEY-----"\nval end = "-----END PRIVATE KEY-----"\n' > "$REPO/Pem.kt"
git -C "$REPO" add -A
commit_accepted "code that only names the PEM markers is accepted"

mkdir -p "$REPO/res"
cp "$DATA/offline-mfi/identity.pk8" "$REPO/res/blob.bin"
git -C "$REPO" add -A
commit_rejected "a copy of the MFi key under another name is refused" "res/blob.bin"

printf 'val cert = "%s"\n' "$CERT_BASE64" > "$REPO/Cert.kt"
git -C "$REPO" add -A
commit_rejected "the MFi certificate pasted as base64 is refused" "Cert.kt"
if grep -qF "${CERT_BASE64:100:24}" "$WORK/out"; then fail "the refusal does not print the secret"
else pass "the refusal does not print the secret"; fi

printf 'val key = "%s"\n' "$SECRET_VALUE" > "$REPO/Identity.kt"
git -C "$REPO" add -A
commit_rejected "a value from identity.properties pasted into code is refused" "Identity.kt"

echo "fun main() {}" > "$REPO/Main.kt"
git -C "$REPO" add -A
commit_accepted "an ordinary source file is accepted"

# --- pre-push -------------------------------------------------------------------------------------------

git init -q --bare "$WORK/origin.git"
git -C "$REPO" remote add origin "$WORK/origin.git"
if git -C "$REPO" push -q origin HEAD:refs/heads/main > "$WORK/out" 2>&1; then pass "pushing our own branch is accepted"
else fail "pushing our own branch is accepted" "$(cat "$WORK/out")"; fi

FOREIGN="$WORK/foreign"
git init -q "$FOREIGN"
echo "theirs" > "$FOREIGN/file"
git -C "$FOREIGN" add file
git -C "$FOREIGN" -c core.hooksPath=/dev/null commit -qm "Their history"
git -C "$REPO" fetch -q "$FOREIGN" HEAD:refs/heads/theirs
git -C "$REPO" update-ref refs/tags/v9 theirs

if git -C "$REPO" push -q origin theirs > "$WORK/out" 2>&1; then fail "pushing a branch of another project's history is refused"
elif git -C "$WORK/origin.git" rev-parse -q --verify refs/heads/theirs > /dev/null; then
    fail "pushing a branch of another project's history is refused" "the branch reached the remote"
else pass "pushing a branch of another project's history is refused"; fi

if git -C "$REPO" push -q origin --tags > "$WORK/out" 2>&1; then fail "pushing another project's tags is refused"
elif git -C "$WORK/origin.git" rev-parse -q --verify refs/tags/v9 > /dev/null; then
    fail "pushing another project's tags is refused" "the tag reached the remote"
elif ! grep -qF "refs/tags/v9" "$WORK/out"; then
    fail "pushing another project's tags is refused" "the output does not name the tag:"$'\n'"$(cat "$WORK/out")"
else pass "pushing another project's tags is refused"; fi
git -C "$REPO" update-ref -d refs/tags/v9

git -C "$REPO" push -q origin HEAD:refs/heads/temp > /dev/null 2>&1
if git -C "$REPO" push -q origin :refs/heads/temp > "$WORK/out" 2>&1; then pass "deleting a remote branch is accepted"
else fail "deleting a remote branch is accepted" "$(cat "$WORK/out")"; fi

# --- mirror-upstream.cmd --------------------------------------------------------------------------------

UP="$WORK/up"
git init -q "$UP"
git -C "$UP" config core.hooksPath /dev/null
echo 1 > "$UP/f"; git -C "$UP" add f; git -C "$UP" commit -qm "A"
PIN_A=$(git -C "$UP" rev-parse HEAD)
echo 2 > "$UP/f"; git -C "$UP" commit -qam "B"
PIN_B=$(git -C "$UP" rev-parse HEAD)
git -C "$UP" update-ref refs/tags/v1 HEAD
git -C "$UP" branch -M main
git clone -q --bare "$UP" "$WORK/source.git"

SUPER="$WORK/super"
git init -q "$SUPER"
git -C "$SUPER" config core.hooksPath /dev/null
git -C "$SUPER" submodule add -q "$WORK/source.git" upstream
git -C "$SUPER/upstream" checkout -q "$PIN_A"
git -C "$SUPER" add upstream .gitmodules; git -C "$SUPER" commit -qm "Pin A"
git -C "$SUPER/upstream" checkout -q "$PIN_B"
git -C "$SUPER" add upstream; git -C "$SUPER" commit -qm "Pin B"

# The script runs in cmd.exe, as a user runs it; no path conversion, so cmd sees /c and Windows paths.
MIRROR_CMD=$(cygpath -w "$HERE/scripts/mirror-upstream.cmd")
mirror_cmd() { (cd "$SUPER" && MSYS_NO_PATHCONV=1 cmd.exe /c "$MIRROR_CMD" "$@"); }
# run_mirror <cache> <mirror> [--local]: the script run from the superproject, with the test source.
run_mirror() {
    UPSTREAM_SOURCE="$WORK/source.git" MIRROR_CACHE="$1" mirror_cmd "${@:2}" > "$WORK/out" 2>&1
}
has_ref() { git -C "$1" rev-parse -q --verify "$2" > /dev/null 2>&1; }

if mirror_cmd > "$WORK/out" 2>&1; then
    fail "without a mirror URL the script stops"
elif ! grep -q "usage" "$WORK/out"; then
    fail "without a mirror URL the script stops" "$(cat "$WORK/out")"
else pass "without a mirror URL the script stops"; fi

git init -q --bare "$WORK/mirror.git"
if run_mirror "$WORK/cache.git" "$WORK/mirror.git"; then pass "the first mirror run succeeds"
else fail "the first mirror run succeeds" "$(cat "$WORK/out")"; fi

if [ "$(git -C "$WORK/mirror.git" rev-parse -q --verify refs/heads/main)" = "$PIN_B" ] && has_ref "$WORK/mirror.git" refs/tags/v1
then pass "the mirror has upstream's branches and tags"
else fail "the mirror has upstream's branches and tags" "$(git -C "$WORK/mirror.git" show-ref)"; fi

if has_ref "$WORK/mirror.git" "refs/tags/pinned/$PIN_A" && has_ref "$WORK/mirror.git" "refs/tags/pinned/$PIN_B"
then pass "every commit the superproject ever pinned is kept as pinned/<sha>"
else fail "every commit the superproject ever pinned is kept as pinned/<sha>" "$(git -C "$WORK/mirror.git" show-ref)"; fi

if [ "$(git -C "$SUPER" config -f .gitmodules submodule.upstream.url)" = "$WORK/mirror.git" ] &&
   [ "$(git -C "$SUPER" config submodule.upstream.url)" = "$WORK/mirror.git" ] &&
   [ "$(git -C "$SUPER/upstream" remote get-url origin)" = "$WORK/mirror.git" ]
then pass "the submodule now points at the mirror"
else fail "the submodule now points at the mirror" "$(git -C "$SUPER" config -f .gitmodules -l)"; fi

if [ -f "$WORK/cache.git.bundle" ] && git bundle list-heads "$WORK/cache.git.bundle" | grep -q "refs/tags/pinned/$PIN_B"
then pass "a bundle with the pinned commits is written next to the cache"
else fail "a bundle with the pinned commits is written next to the cache" "$(cat "$WORK/out")"; fi

if run_mirror "$WORK/cache.git" "$WORK/mirror.git"; then pass "running it again succeeds"
else fail "running it again succeeds" "$(cat "$WORK/out")"; fi

# --local: only this clone uses the mirror; .gitmodules keeps the shared URL.
git init -q --bare "$WORK/mirrorL.git"
if run_mirror "$WORK/cache.git" "$WORK/mirrorL.git" --local &&
   [ "$(git -C "$SUPER" config -f .gitmodules submodule.upstream.url)" = "$WORK/mirror.git" ] &&
   [ "$(git -C "$SUPER" config submodule.upstream.url)" = "$WORK/mirrorL.git" ] &&
   [ "$(git -C "$SUPER/upstream" remote get-url origin)" = "$WORK/mirrorL.git" ]
then pass "--local points only this clone at the mirror"
else fail "--local points only this clone at the mirror" "$(cat "$WORK/out"; git -C "$SUPER" config -l | grep upstream)"; fi

# Upstream rewrites its history, then disappears: the cache still holds the pinned commits.
git -C "$UP" checkout -q --orphan rewritten
echo 3 > "$UP/f"; git -C "$UP" add f; git -C "$UP" commit -qm "Rewritten"
git -C "$UP" push -q --force "$WORK/source.git" rewritten:main
rm -rf "$WORK/source.git"
git init -q --bare "$WORK/mirror2.git"
if run_mirror "$WORK/cache.git" "$WORK/mirror2.git" && has_ref "$WORK/mirror2.git" "refs/tags/pinned/$PIN_A"
then pass "with upstream gone, a new mirror is filled from the cache"
else fail "with upstream gone, a new mirror is filled from the cache" "$(cat "$WORK/out")"; fi
if grep -qi "unreachable\|could not fetch" "$WORK/out"; then pass "an unreachable upstream is reported"
else fail "an unreachable upstream is reported" "$(cat "$WORK/out")"; fi

# A commit that only the local submodule has, with no cache and no upstream: taken from the submodule.
echo 4 > "$SUPER/upstream/f"
git -C "$SUPER/upstream" -c core.hooksPath=/dev/null commit -qam "C"
PIN_C=$(git -C "$SUPER/upstream" rev-parse HEAD)
git -C "$SUPER" add upstream; git -C "$SUPER" commit -qm "Pin C"
git init -q --bare "$WORK/mirror3.git"
if run_mirror "$WORK/cache3.git" "$WORK/mirror3.git" && has_ref "$WORK/mirror3.git" "refs/tags/pinned/$PIN_C"
then pass "a pinned commit missing upstream is taken from the local submodule"
else fail "a pinned commit missing upstream is taken from the local submodule" "$(cat "$WORK/out")"; fi

echo
echo "$PASSED passed, $FAILED failed"
[ "$FAILED" -eq 0 ]
