#!/usr/bin/env python3
"""
Einzel-Einstiegspunkt für die NK-Zell-Analyse-Pipeline.
==========================================================
Erstellt automatisch einen datierten Ausgabe-Ordner im measurements-Verzeichnis:

    <measurements_dir>/Analyse_export_YYYY-MM-DD/
        QuPath_Rohdaten.xlsx    <- Rohdaten + Pivot-Übersicht (qupath_to_excel)
        QuPath_Auswertung.xlsx  <- Prism-fertige Tabellen   (make_prism_tables)

Voraussetzung: openpyxl installiert  (pip install openpyxl)

Aufruf:
    python run_analysis.py  <measurements_dir>

Beispiel (Windows PowerShell):
    python run_analysis.py  "U:\\QuPath-Projekt\\measurements"
"""

import sys
import os
import datetime

# -- Skripte im selben Ordner importieren ------------------------------------
_here = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, _here)

try:
    import qupath_to_excel
    import make_prism_tables
except ImportError as e:
    print(f"FEHLER: Modul nicht gefunden – {e}")
    print(f"  Stelle sicher, dass qupath_to_excel.py und make_prism_tables.py")
    print(f"  im selben Ordner liegen wie run_analysis.py ({_here})")
    sys.exit(1)


def main():
    measdir = os.path.abspath(sys.argv[1] if len(sys.argv) > 1 else ".")

    if not os.path.isdir(measdir):
        print(f"FEHLER: Ordner nicht gefunden: {measdir}")
        sys.exit(1)

    today   = datetime.date.today().strftime("%Y-%m-%d")
    outdir  = os.path.join(measdir, f"Analyse_export_{today}")
    os.makedirs(outdir, exist_ok=True)

    rohdaten   = os.path.join(outdir, "QuPath_Rohdaten.xlsx")
    auswertung = os.path.join(outdir, "QuPath_Auswertung.xlsx")

    print("=" * 60)
    print(f"Eingabe-Ordner : {measdir}")
    print(f"Ausgabe-Ordner : {outdir}")
    print("=" * 60)

    print("\n[1/2] Rohdaten-Workbook (QuPath_Rohdaten.xlsx) ...")
    qupath_to_excel.build(measdir, rohdaten)

    print("\n[2/2] Auswertungs-Workbook (QuPath_Auswertung.xlsx) ...")
    make_prism_tables.main(measdir, auswertung)

    print("\n" + "=" * 60)
    print("Fertig!")
    print(f"  {rohdaten}")
    print(f"  {auswertung}")
    print("=" * 60)


if __name__ == "__main__":
    main()
