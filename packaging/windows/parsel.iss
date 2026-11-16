; Inno Setup script for the Parsel installer (a branded wizard: welcome banner, install folder, desktop icon, start when done).
;
; It is a thin wrapper around the install folder the jpack Maven plugin builds (target/jpack/image/windows-x64: Parsel.exe = the jpack
; stub, jpack.json, runtime/, app/, launcher/). Updating Parsel, the launcher, the Java runtime and the stub itself is done by the stub
; from then on, so this installer never has to run again. The release workflow calls it with:
;   /DAppVersion=...     the version shown in the installer and in Apps & features, such as 0.1.0
;   /DFileVersion=...    the same as digits only (1.2.3), for the file properties
;   /DImageDir=...       the jpack install folder to package
;   /DOutputDir=...      where to write the setup exe
;   /DIconFile=...       Parsel's .ico
; The file is always called Parsel-Setup.exe, so releases/latest/download/Parsel-Setup.exe never changes.

#ifndef AppVersion
  #define AppVersion "0.0.0"
#endif
#ifndef FileVersion
  #define FileVersion "0.0.0"
#endif
#ifndef ImageDir
  #define ImageDir "..\..\target\jpack\image\windows-x64"
#endif
#ifndef OutputDir
  #define OutputDir "..\..\target\installer"
#endif
#ifndef IconFile
  #define IconFile "..\..\src\main\resources\icon.ico"
#endif

[Setup]
; Never change AppId: it is how a newer installer finds an installed Parsel and upgrades it in place. Parsel repeats the same value
; in its code (install/AppsFeatures.java) to keep the version in Apps & features current after a self-update.
AppId={{B8F3C1DE-2B1A-4F0E-9A52-6D2E7A1C4F10}
AppName=Parsel
AppVersion={#AppVersion}
AppVerName=Parsel {#AppVersion}
AppPublisher=Parsel
AppPublisherURL=https://github.com/B077AS/parsel
AppSupportURL=https://github.com/B077AS/parsel/issues
AppUpdatesURL=https://github.com/B077AS/parsel/releases
VersionInfoVersion={#FileVersion}
VersionInfoDescription=Parsel installer
; For this user only: no administrator prompt, Parsel lands in %LOCALAPPDATA%\Programs\Parsel, and the folder stays writable for the
; stub's updates.
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
DefaultDirName={autopf}\Parsel
DefaultGroupName=Parsel
; A first install may pick the folder; an upgrade silently reuses the one Parsel is already in.
DisableDirPage=auto
DisableProgramGroupPage=yes
DisableWelcomePage=no
WizardStyle=modern
WizardImageFile=wizard-large.bmp,wizard-large-150.bmp,wizard-large-200.bmp
WizardSmallImageFile=wizard-small.bmp,wizard-small-150.bmp,wizard-small-200.bmp
SetupIconFile={#IconFile}
UninstallDisplayIcon={app}\Parsel.exe
UninstallDisplayName=Parsel
OutputDir={#OutputDir}
OutputBaseFilename=Parsel-Setup
Compression=lzma2
SolidCompression=yes
; Parsel may be running (even hidden in the tray) when a newer one is installed: close it, install, offer to start it again.
CloseApplications=yes
RestartApplications=no

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"

[InstallDelete]
; Running the installer over an install that has updated itself (or over a 0.1.x jpackage install, which had app\ and runtime\ too)
; starts from a clean slate: these folders are replaced as a whole, and a half-downloaded update or the folder a swap set aside must
; not outlive the files they belong to. Your mail is untouched.
Type: filesandordirs; Name: "{app}\runtime"
Type: filesandordirs; Name: "{app}\app"
Type: filesandordirs; Name: "{app}\launcher"
Type: filesandordirs; Name: "{app}\update"
Type: filesandordirs; Name: "{app}\old"

[Files]
Source: "{#ImageDir}\*"; DestDir: "{app}"; Flags: recursesubdirs createallsubdirs ignoreversion

[Icons]
Name: "{group}\Parsel"; Filename: "{app}\Parsel.exe"
Name: "{autodesktop}\Parsel"; Filename: "{app}\Parsel.exe"; Tasks: desktopicon

[UninstallDelete]
; Updates add and replace files the installer never saw (a new runtime, the update and old folders, a stub that was renamed aside):
; remove the whole install folder, not just what Setup copied.
; Uninstalling removes the program, not your mail: the downloaded mail and settings live in %LOCALAPPDATA%\com.parsel.app and are
; left alone, and your passwords stay in Windows Credential Manager.
Type: filesandordirs; Name: "{app}"

[Run]
Filename: "{app}\Parsel.exe"; Description: "{cm:LaunchProgram,Parsel}"; Flags: nowait postinstall skipifsilent
