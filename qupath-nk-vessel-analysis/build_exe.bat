@echo off
:: ============================================================
:: QuPath NK Analysis  –  .exe bauen (Windows, einmalig)
:: ============================================================
:: Voraussetzung:  Python 3.9+ installiert und im PATH
::
:: Einmalige Installation:
::   pip install pyinstaller openpyxl
::
:: Dann diese Datei doppelklicken oder in CMD ausführen.
:: Die fertige .exe landet in:  dist\QuPath_NK_Analysis.exe
:: ============================================================

echo.
echo === Prüfe Python ===
python --version
if errorlevel 1 (
    echo [FEHLER] Python nicht gefunden. Bitte Python 3.9+ installieren.
    pause
    exit /b 1
)

echo.
echo === Installiere / aktualisiere Abhängigkeiten ===
pip install --quiet --upgrade pyinstaller openpyxl
if errorlevel 1 (
    echo [FEHLER] pip install fehlgeschlagen.
    pause
    exit /b 1
)

echo.
echo === Baue .exe ===
pyinstaller ^
    --onefile ^
    --windowed ^
    --name "QuPath_NK_Analysis" ^
    --icon NONE ^
    qupath_launcher.py

if errorlevel 1 (
    echo.
    echo [FEHLER] Build fehlgeschlagen. Meldung oben prüfen.
    pause
    exit /b 1
)

echo.
echo ==============================================
echo  FERTIG!
echo  Datei: dist\QuPath_NK_Analysis.exe
echo  Kopiere die .exe auf den Ziel-PC und starte
echo  sie per Doppelklick – kein Python nötig.
echo ==============================================
echo.
pause
