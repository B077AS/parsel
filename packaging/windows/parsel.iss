; Inno Setup script for the Parsel installer (a branded wizard: welcome banner, install folder, desktop icon, start when done).
; Compiled by packaging/package.mjs (`windows-installer`), which first builds the jpackage app-image (Parsel.exe, the app and its
; private Java runtime) and then passes these defines:
;   /DAppVersion=...     the version shown in the installer and in Apps & features, such as 0.1.0
;   /DFileVersion=...    the same as digits only (1.2.3), for the file properties
;   /DAppImageDir=...    the jpackage app-image folder to package
;   /DOutputDir=...      where to write the setup exe
;   /DOutputName=...     the file name of the setup exe, without .exe
;   /DIconFile=...       Parsel's .ico

#ifndef AppVersion
  #define AppVersion "0.0.0"
#endif
#ifndef FileVersion
  #define FileVersion "0.0.0"
#endif
#ifndef AppImageDir
  #define AppImageDir "..\..\target\jpackage\Parsel"
#endif
#ifndef OutputDir
  #define OutputDir "..\..\target\dist"
#endif
#ifndef OutputName
  #define OutputName "Parsel-" + AppVersion + "-windows-x64-setup"
#endif
#ifndef IconFile
  #define IconFile "..\..\src\main\resources\icon.ico"
#endif

[Setup]
; Never change AppId: it is how a newer installer finds an installed Parsel and upgrades it in place.
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
; For this user only: no administrator prompt, and Parsel lands in %LOCALAPPDATA%\Programs\Parsel.
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
OutputBaseFilename={#OutputName}
Compression=lzma2
SolidCompression=yes
; Parsel may be running (even hidden in the tray) when a newer one is installed: close it, install, offer to start it again.
CloseApplications=yes
RestartApplications=no

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"

[Files]
Source: "{#AppImageDir}\*"; DestDir: "{app}"; Flags: recursesubdirs createallsubdirs ignoreversion

[Icons]
Name: "{group}\Parsel"; Filename: "{app}\Parsel.exe"
Name: "{autodesktop}\Parsel"; Filename: "{app}\Parsel.exe"; Tasks: desktopicon

; Uninstalling removes the program, not your mail: the downloaded mail and settings live in %LOCALAPPDATA%\com.parsel.app and are
; left alone, and your passwords stay in Windows Credential Manager.

[Run]
Filename: "{app}\Parsel.exe"; Description: "{cm:LaunchProgram,Parsel}"; Flags: nowait postinstall skipifsilent
