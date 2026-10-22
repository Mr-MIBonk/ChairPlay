@echo off
rem Mirrors DiPlay (the upstream submodule) to a repository you control and points the submodule at it, so
rem this project still builds if DiPlay's repository disappears or rewrites its history.
rem
rem   scripts\mirror-upstream.cmd <mirror-url> [--local]
rem
rem Create the mirror first, empty and private: a public copy of DiPlay would be taken down together with
rem DiPlay. Run the script again whenever DiPlay moves, before "git -C upstream fetch", since after the first
rem run the submodule fetches from the mirror.
rem
rem 1. Fetches DiPlay's branches and tags into a local cache, a bare repository kept between runs.
rem 2. Keeps every commit this project has ever pinned as refs/tags/pinned/<sha>, so that old commits of this
rem    project still build. A pin DiPlay no longer has is taken from the cache or from the local submodule.
rem 3. Pushes branches, tags and pins to the mirror and writes the cache as a git bundle, an offline copy to
rem    keep somewhere off this machine ("git clone <bundle>" restores it).
rem 4. Points the submodule at the mirror: in .gitmodules (commit it afterwards), or with --local only in
rem    this clone, so that .gitmodules keeps DiPlay's URL for everyone else.
rem
rem Environment: UPSTREAM_SOURCE (DiPlay's repository), MIRROR_CACHE (the cache), MIRROR_BUNDLE (the bundle).
setlocal EnableExtensions

set "MIRROR=%~1"
set "LOCAL="
if /i "%~2"=="--local" set "LOCAL=1"
if "%MIRROR%"=="" (
    echo usage: scripts\mirror-upstream.cmd ^<mirror-url^> [--local] 1>&2
    exit /b 1
)
if not defined UPSTREAM_SOURCE set "UPSTREAM_SOURCE=https://github.com/shihabal3amri/DiPlay.git"
if not defined MIRROR_CACHE set "MIRROR_CACHE=%USERPROFILE%\.cache\diplay-upstream.git"
if not defined MIRROR_BUNDLE set "MIRROR_BUNDLE=%MIRROR_CACHE%.bundle"
set "MIRROR_CACHE=%MIRROR_CACHE:/=\%"
set "MIRROR_BUNDLE=%MIRROR_BUNDLE:/=\%"
rem Windows' own tools: a cmd started from Git Bash would find the GNU ones first.
set "SORT=%SystemRoot%\System32\sort.exe"
set "FIND=%SystemRoot%\System32\find.exe"
if /i "%MIRROR%"=="%UPSTREAM_SOURCE%" (
    echo mirror-upstream: the mirror must be your repository, not DiPlay's 1>&2
    exit /b 1
)

set "TOP="
for /f "delims=" %%t in ('git rev-parse --show-toplevel 2^>nul') do set "TOP=%%t"
if not defined TOP (
    echo mirror-upstream: run it inside this repository 1>&2
    exit /b 1
)
cd /d "%TOP%"
git config -f .gitmodules submodule.upstream.path >nul || (
    echo mirror-upstream: no upstream submodule in .gitmodules 1>&2
    exit /b 1
)

if not exist "%MIRROR_CACHE%" git init -q --bare "%MIRROR_CACHE%" || exit /b 1

echo Fetching %UPSTREAM_SOURCE%
git -C "%MIRROR_CACHE%" fetch -q --no-tags "%UPSTREAM_SOURCE%" "+refs/heads/*:refs/heads/*" "+refs/tags/*:refs/tags/*" ^
    || echo mirror-upstream: could not fetch %UPSTREAM_SOURCE%, it is unreachable or gone; using the cache and the local submodule 1>&2

rem Every commit the submodule has pointed at, in any branch of this project, and the one staged now.
set "PINS=%TEMP%\mirror-upstream-%RANDOM%%RANDOM%.txt"
set "CURRENT="
for /f "tokens=1,2" %%m in ('git ls-files -s -- upstream') do if "%%m"=="160000" set "CURRENT=%%n"
(
    for /f %%c in ('git log --all --format^=%%H -- upstream') do git rev-parse -q --verify "%%c:upstream" 2>nul
    if defined CURRENT echo %CURRENT%
) > "%PINS%.all"
"%SORT%" /unique "%PINS%.all" /o "%PINS%"
del "%PINS%.all"

set "SUBMODULE_GIT="
for /f "delims=" %%g in ('git -C upstream rev-parse --absolute-git-dir 2^>nul') do set "SUBMODULE_GIT=%%g"
set "FATAL="
for /f "usebackq" %%p in ("%PINS%") do call :keep_pin %%p
for /f %%n in ('type "%PINS%" ^| "%FIND%" /c /v ""') do set "COUNT=%%n"
del "%PINS%"
if defined FATAL exit /b 1

echo Pushing to %MIRROR%
git -C "%MIRROR_CACHE%" push -q "%MIRROR%" "+refs/heads/*:refs/heads/*" "+refs/tags/*:refs/tags/*" || exit /b 1
set "RECEIVED="
for /f %%r in ('git ls-remote "%MIRROR%" "refs/tags/pinned/%CURRENT%"') do set "RECEIVED=1"
if not defined RECEIVED (
    echo mirror-upstream: the mirror did not receive %CURRENT% 1>&2
    exit /b 1
)

git -C "%MIRROR_CACHE%" bundle create -q "%MIRROR_BUNDLE%.tmp" --all || exit /b 1
move /y "%MIRROR_BUNDLE%.tmp" "%MIRROR_BUNDLE%" >nul || exit /b 1

if defined LOCAL (
    git config submodule.upstream.url "%MIRROR%"
    git -C upstream remote set-url origin "%MIRROR%"
) else (
    git submodule set-url -- upstream "%MIRROR%"
)
git -C upstream fetch -q origin 2>nul ^
    || echo mirror-upstream: the submodule could not fetch from the mirror yet; check the URL and your access 1>&2

echo Mirrored %COUNT% pinned commits; the submodule now uses %MIRROR%
echo Bundle, keep a copy off this machine: %MIRROR_BUNDLE%
git diff --quiet -- .gitmodules || echo Commit .gitmodules to share the new URL.
exit /b 0

rem keep_pin <sha>: tags the pinned commit in the cache, fetching it from the local submodule if needed.
:keep_pin
git -C "%MIRROR_CACHE%" cat-file -e "%~1^{commit}" 2>nul && goto :tag_pin
if defined SUBMODULE_GIT git -C "%MIRROR_CACHE%" fetch -q --no-tags "%SUBMODULE_GIT%" %~1 2>nul
git -C "%MIRROR_CACHE%" cat-file -e "%~1^{commit}" 2>nul && goto :tag_pin
if "%~1"=="%CURRENT%" (
    echo mirror-upstream: the pinned commit %~1 is neither upstream, in the cache nor in the submodule 1>&2
    set "FATAL=1"
) else (
    echo mirror-upstream: an old pin, %~1, is nowhere to be found; old commits using it will not build 1>&2
)
exit /b 0
:tag_pin
git -C "%MIRROR_CACHE%" update-ref "refs/tags/pinned/%~1" %~1
exit /b 0
