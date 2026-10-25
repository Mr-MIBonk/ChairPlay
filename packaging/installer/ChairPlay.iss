; ChairPlay's installer, built by `./gradlew installer -Pchairplay.version=X.Y.Z` (gradle/installer.gradle.kts), which
; passes AppVersion, SourceDir (the jpackage app image) and OutputDir.
;
; ChairPlay keeps its data next to its exe (settings, controls and window files, identity.properties, pairings\,
; offline-mfi\, carplay-icon.png, logs\, screenshots\, ChairPlay.lock). The installer ships none of them, deletes only
; the program folders app\ and runtime\ before an update, and leaves everything it did not install on uninstall: a lost
; identity or pairing makes the iPhone see a new car.

#ifndef AppVersion
  #error Pass the version: ISCC /DAppVersion=X.Y.Z
#endif
#ifndef SourceDir
  #error Pass the app image: ISCC /DSourceDir=<build\jpackage\ChairPlay>
#endif
#ifndef OutputDir
  #define OutputDir "."
#endif

[Setup]
; Fixed for good: it is how a newer installer finds and updates an existing install.
AppId={{25CBBAE6-C3C2-48CB-AB46-25D3386168D8}
AppName=ChairPlay
AppVersion={#AppVersion}
AppPublisher=Oleksiy Strelets
; Per user and without admin rights: the app writes its data into its own folder.
DefaultDirName={localappdata}\Programs\ChairPlay
DisableDirPage=yes
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
MinVersion=10.0
; Never close a running ChairPlay: a hard stop leaves the iPhone ignoring new sessions. [Code] asks the user instead.
CloseApplications=no
RestartApplications=no
SetupIconFile=..\chairplay.ico
UninstallDisplayIcon={app}\ChairPlay.exe
; Installed apps shows the version in its own column: the name goes without it.
UninstallDisplayName=ChairPlay
WizardStyle=modern
OutputDir={#OutputDir}
OutputBaseFilename=ChairPlay-{#AppVersion}-setup

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"; Flags: unchecked

; Jars and runtime modules a newer version dropped must not linger. Never a wildcard on {app} itself: the data is there.
[InstallDelete]
Type: filesandordirs; Name: "{app}\app"
Type: filesandordirs; Name: "{app}\runtime"

[Files]
Source: "{#SourceDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autoprograms}\ChairPlay"; Filename: "{app}\ChairPlay.exe"
Name: "{autodesktop}\ChairPlay"; Filename: "{app}\ChairPlay.exe"; Tasks: desktopicon

[Run]
Filename: "{app}\ChairPlay.exe"; Description: "{cm:LaunchProgram,ChairPlay}"; Flags: postinstall nowait skipifsilent

[Code]
const
  GENERIC_READ = $80000000;
  OPEN_EXISTING = 3;
  INVALID_HANDLE_VALUE = -1;
  ERROR_SHARING_VIOLATION = 32;
  RunningMessage = 'ChairPlay is running. Close its window, then click Retry.';

function CreateFile(lpFileName: String; dwDesiredAccess, dwShareMode, lpSecurityAttributes,
  dwCreationDisposition, dwFlagsAndAttributes: Cardinal; hTemplateFile: THandle): THandle;
external 'CreateFileW@kernel32.dll stdcall';

function CloseHandle(hObject: THandle): Boolean;
external 'CloseHandle@kernel32.dll stdcall';

// A running ChairPlay holds ChairPlay.lock open from start to the end of its graceful shutdown, so opening it with no
// sharing fails with a sharing violation. A missing file or folder means no run.
function IsRunning(): Boolean;
var
  Handle: THandle;
begin
  Handle := CreateFile(ExpandConstant('{app}\ChairPlay.lock'), GENERIC_READ, 0, 0, OPEN_EXISTING,
    FILE_ATTRIBUTE_NORMAL, 0);
  if Handle = INVALID_HANDLE_VALUE then
    Result := DLLGetLastError() = ERROR_SHARING_VIOLATION
  else begin
    CloseHandle(Handle);
    Result := False;
  end;
end;

// Asks until ChairPlay is closed; False when the user cancels (and in a silent run with suppressed messages).
function WaitUntilClosed(): Boolean;
begin
  Result := True;
  while Result and IsRunning() do
    Result := SuppressibleMsgBox(RunningMessage, mbError, MB_RETRYCANCEL, IDCANCEL) = IDRETRY;
end;

function PrepareToInstall(var NeedsRestart: Boolean): String;
begin
  if WaitUntilClosed() then
    Result := ''
  else
    Result := 'ChairPlay is running. Close its window, then run Setup again.';
end;

function InitializeUninstall(): Boolean;
begin
  Result := WaitUntilClosed();
end;
