Unicode True
!include "MUI2.nsh"

Name "Tomato ${VERSION}"
OutFile "${OUTPUT_FILE}"
InstallDir "$PROGRAMFILES64\Tomato"
InstallDirRegKey HKLM "Software\tangluobo\Tomato" "InstallDir"
RequestExecutionLevel admin
SetCompressor /SOLID lzma
Icon "${ICON_FILE}"

!define MUI_ABORTWARNING
!define MUI_ICON "${ICON_FILE}"
!define MUI_UNICON "${ICON_FILE}"
!insertmacro MUI_PAGE_WELCOME
!insertmacro MUI_PAGE_DIRECTORY
!insertmacro MUI_PAGE_INSTFILES
!insertmacro MUI_PAGE_FINISH
!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES
!insertmacro MUI_LANGUAGE "SimpChinese"

Section "Tomato" MainSection
  SetShellVarContext all
  SetRegView 64
  SetOutPath "$INSTDIR"
  File /r "${SOURCE_DIR}\*"
  WriteUninstaller "$INSTDIR\Uninstall.exe"
  CreateDirectory "$SMPROGRAMS\Tomato"
  CreateShortcut "$SMPROGRAMS\Tomato\Tomato.lnk" "$INSTDIR\tomato.exe" "" "$INSTDIR\tomato.exe"
  CreateShortcut "$DESKTOP\Tomato.lnk" "$INSTDIR\tomato.exe" "" "$INSTDIR\tomato.exe"
  WriteRegStr HKLM "Software\tangluobo\Tomato" "InstallDir" "$INSTDIR"
  WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\Tomato" "DisplayName" "Tomato"
  WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\Tomato" "DisplayVersion" "${VERSION}"
  WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\Tomato" "Publisher" "tangluobo"
  WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\Tomato" "DisplayIcon" "$INSTDIR\tomato.exe"
  WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\Tomato" "UninstallString" "$INSTDIR\Uninstall.exe"
SectionEnd

Section "Uninstall"
  SetShellVarContext all
  SetRegView 64
  Delete "$DESKTOP\Tomato.lnk"
  Delete "$SMPROGRAMS\Tomato\Tomato.lnk"
  RMDir "$SMPROGRAMS\Tomato"
  RMDir /r "$INSTDIR"
  DeleteRegKey HKLM "Software\tangluobo\Tomato"
  DeleteRegKey HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\Tomato"
SectionEnd
