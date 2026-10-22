@echo off
rem Runs scripts\test-repo-tools.sh, the tests of the git hooks and of mirror-upstream.cmd, with Git for
rem Windows' bash: the hooks are bash scripts, since git runs every hook with that bash.
setlocal EnableExtensions
set "GITEXE="
for /f "delims=" %%g in ('where git.exe 2^>nul') do if not defined GITEXE set "GITEXE=%%g"
if not defined GITEXE (
    echo test-repo-tools: git is not on PATH 1>&2
    exit /b 1
)
rem git.exe is in Git\cmd from cmd, or in Git\mingw64\bin from Git Bash; bash.exe is in Git\bin.
for %%d in ("%GITEXE%\..\..") do set "GITROOT=%%~fd"
if not exist "%GITROOT%\bin\bash.exe" for %%d in ("%GITROOT%\..") do set "GITROOT=%%~fd"
"%GITROOT%\bin\bash.exe" "%~dp0test-repo-tools.sh"
