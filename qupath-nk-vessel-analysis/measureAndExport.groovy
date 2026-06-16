/**
 * QuPath – Form-Messwerte ergänzen & Annotationen + Detections exportieren
 * --------------------------------------------------------------------------------
 * Projekt: NK-Zell- / Gefäß-Distanzanalyse (IB4 vs. Synaptophysin), Maus-Muskelschnitte
 *
 * Hintergrund:
 *  QuPath berechnet für ANNOTATIONS standardmäßig KEINE Flächen-/Form-Messwerte –
 *  deshalb lässt sich z. B. die Area nicht exportieren. Dieses Skript ergänzt die
 *  Form-Messwerte (Fläche, Umfang, Durchmesser, Circularity, Solidity) für
 *  Annotationen UND Detections und exportiert anschließend beide Messtabellen
 *  als CSV (inkl. aller vorhandenen Spalten, z. B. der Distanzen aus der Spatial
 *  Analysis auf den NK-cell-Detections).
 *
 * Bedienung:
 *  - Skript ausführen (Run). Es wirkt auf ALLE Annotationen und Detections des Bildes.
 *  - Für alle Bilder im Projekt: Run -> "Run for project".
 *
 * Export-Ort:
 *  <Projektordner>/measurements/<Bildname>_annotations.csv
 *  <Projektordner>/measurements/<Bildname>_detections.csv
 *  (ohne Projekt: im Benutzerordner unter QuPath_measurements)
 */

import qupath.lib.analysis.features.ObjectMeasurements
import static qupath.lib.analysis.features.ObjectMeasurements.ShapeFeatures.*

// --- 1) Form-Messwerte definieren ---
def cal = getCurrentServer().getPixelCalibration()
if (!cal.hasPixelSizeMicrons())
    println "WARNUNG: Keine Pixelkalibrierung – Flächen/Längen werden in Pixel statt µm angegeben."

// AREA = Fläche, LENGTH = Umfang, MAX_DIAMETER / MIN_DIAMETER = max./min. Durchmesser,
// CIRCULARITY = Rundheit, SOLIDITY = Konvexität
def shapeFeatures = [AREA, LENGTH, CIRCULARITY, SOLIDITY, MAX_DIAMETER, MIN_DIAMETER] as ObjectMeasurements.ShapeFeatures[]

// --- 2) Messwerte zu Annotationen UND Detections hinzufügen (nur flächige ROIs) ---
def annotations = getAnnotationObjects()
def detections  = getDetectionObjects()

int nMeasured = 0
[annotations, detections].each { collection ->
    collection.each { obj ->
        def roi = obj.getROI()
        if (roi != null && roi.isArea()) {
            ObjectMeasurements.addShapeMeasurements(obj, cal, shapeFeatures)
            nMeasured++
        }
    }
}
fireHierarchyUpdate()

// --- 3) Export-Ziel vorbereiten ---
def name = getCurrentImageNameWithoutExtension()
def project = getProject()
def outDir = project != null ? new File(buildFilePath(PROJECT_BASE_DIR, 'measurements'))
                             : new File(System.getProperty('user.home'), 'QuPath_measurements')
outDir.mkdirs()

def annPath = new File(outDir, name + '_annotations.csv').getAbsolutePath()
def detPath = new File(outDir, name + '_detections.csv').getAbsolutePath()

// --- 4) Exportieren (.csv -> Komma-getrennt; vollständige Messtabellen) ---
if (!annotations.isEmpty())
    saveAnnotationMeasurements(annPath)
if (!detections.isEmpty())
    saveDetectionMeasurements(detPath)

// --- 5) Zusammenfassung ---
println "----------------------------------------------------"
println "Form-Messwerte ergänzt für:  ${nMeasured} Objekt(e) (Annotationen + Detections)"
println "Annotationen:                ${annotations.size()}" + (annotations.isEmpty() ? " (nichts exportiert)" : " -> ${annPath}")
println "Detections:                  ${detections.size()}"  + (detections.isEmpty()  ? " (nichts exportiert)" : " -> ${detPath}")
println "----------------------------------------------------"
Dialogs.showInfoNotification("Export fertig",
    "Annotationen: ${annotations.size()}, Detections: ${detections.size()}\nOrdner: ${outDir.getAbsolutePath()}")
