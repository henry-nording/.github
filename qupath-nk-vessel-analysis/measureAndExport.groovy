/**
 * QuPath – Maximaler Mess-Export (projektweit) für Annotationen & Detections
 * --------------------------------------------------------------------------------
 * Projekt: NK-Zell- / Gefäß-Distanzanalyse (IB4 vs. Synaptophysin), Maus-Muskelschnitte
 *
 * Ziel: ALLE exportierbaren Messwerte aus Annotationen UND Detections, projektweit,
 *       in je EINER übersichtlichen Tabelle – mit Bild-Spalte zur eindeutigen
 *       Zuordnung in Excel.
 *
 * Ablauf:
 *  1. Für JEDES Bild im Projekt werden Form-Messwerte (Fläche, Umfang, Durchmesser,
 *     Circularity, Solidity) zu Annotationen und Detections ergänzt und GESPEICHERT.
 *     (Ohne diesen Schritt kann der Exporter z. B. keine Annotation-Flächen liefern.)
 *  2. Anschließend exportiert der MeasurementExporter projektweit:
 *       - <Projekt>/measurements/ALL_annotations.<ext>
 *       - <Projekt>/measurements/ALL_detections.<ext>
 *     Jede Zeile enthält die Spalte "Image" -> eindeutige Zuordnung zu Maus/Schnitt.
 *
 * Bedienung:
 *  - Projekt geöffnet haben. Skript EINMAL ausführen (Run) – es verarbeitet alle Bilder.
 *  - Im Dialog Trennzeichen wählen -> OK.
 *
 * Hinweis: Schritt 1 ändert und speichert die Bilddaten (fügt Messwerte hinzu).
 */

import qupath.lib.analysis.features.ObjectMeasurements
import static qupath.lib.analysis.features.ObjectMeasurements.ShapeFeatures.*
import qupath.lib.gui.tools.MeasurementExporter
import qupath.lib.objects.PathAnnotationObject
import qupath.lib.objects.PathDetectionObject
import qupath.lib.plugins.parameters.ParameterList
import qupath.lib.gui.dialogs.ParameterPanelFX
import javafx.application.Platform
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
// Hinweis: 'Dialogs' (qupath.fx.dialogs.Dialogs) wird von QuPath automatisch importiert.


// --- 0) Projekt prüfen ---
def project = getProject()
if (project == null) {
    Dialogs.showWarningNotification("Kein Projekt",
        "Bitte zuerst ein QuPath-Projekt öffnen – der projektweite Export braucht ein Projekt.")
    return
}

// --- 1) Optionen abfragen ---
def params = new ParameterList()
    .addChoiceParameter("sep", "Trennzeichen (für Excel)", "Tab",
        ["Tab", "Komma (,)", "Semikolon (;)"],
        "Tab (.tsv) ist am robustesten. Deutsches Excel öffnet Semikolon-CSV per Doppelklick direkt.")
    .addBooleanParameter("addShapes", "Form-Messwerte ergänzen & speichern", true,
        "Fügt Area/Perimeter/Durchmesser/Circularity/Solidity hinzu. Beim ersten Mal nötig, danach optional.")
    .addBooleanParameter("doDetections", "Detections ebenfalls exportieren", true,
        "Zusätzlich zur Annotation-Tabelle auch die Detection-Tabelle (NK-cells) exportieren.")

def showDialog = {
    def pane = new ParameterPanelFX(params).getPane()
    return Dialogs.showConfirmDialog("Maximaler Mess-Export (projektweit)", pane)
} as Callable<Boolean>

boolean confirmed
if (Platform.isFxApplicationThread())
    confirmed = showDialog.call()
else {
    def task = new FutureTask<Boolean>(showDialog)
    Platform.runLater(task)
    confirmed = task.get()
}
if (!confirmed)
    return

def sepChoice    = params.getChoiceParameterValue("sep") as String
boolean addShapes   = params.getBooleanParameterValue("addShapes")
boolean doDetections = params.getBooleanParameterValue("doDetections")

String separator
String ext
switch (sepChoice) {
    case "Komma (,)":     separator = ",";  ext = ".csv"; break
    case "Semikolon (;)": separator = ";";  ext = ".csv"; break
    default:              separator = "\t"; ext = ".tsv"; break   // Tab
}

// Welche Form-Messwerte
def shapeFeatures = [AREA, LENGTH, CIRCULARITY, SOLIDITY, MAX_DIAMETER, MIN_DIAMETER] as ObjectMeasurements.ShapeFeatures[]

// --- 2) Form-Messwerte je Bild ergänzen & speichern ---
def imageList = project.getImageList()
int nObjs = 0
if (addShapes) {
    print "Ergänze Form-Messwerte ..."
    for (entry in imageList) {
        def imageData = entry.readImageData()
        def hierarchy = imageData.getHierarchy()
        def cal = imageData.getServer().getPixelCalibration()
        def objs = hierarchy.getAnnotationObjects() + hierarchy.getDetectionObjects()
        objs.each { obj ->
            if (obj.getROI() != null && obj.getROI().isArea()) {
                ObjectMeasurements.addShapeMeasurements(obj, cal, shapeFeatures)
                nObjs++
            }
        }
        entry.saveImageData(imageData)
    }
    println " fertig (${nObjs} Objekte in ${imageList.size()} Bild(ern))."
}

// --- 3) Export-Ziel ---
def outDir = new File(buildFilePath(PROJECT_BASE_DIR, "measurements"))
outDir.mkdirs()
def annFile = new File(outDir, "ALL_annotations" + ext)
def detFile = new File(outDir, "ALL_detections" + ext)

// --- 4) Projektweiter Export (eine Tabelle pro Objekttyp, inkl. Spalte "Image") ---
new MeasurementExporter()
    .imageList(imageList)
    .separator(separator)
    .exportType(PathAnnotationObject.class)
    .exportMeasurements(annFile)

if (doDetections) {
    new MeasurementExporter()
        .imageList(imageList)
        .separator(separator)
        .exportType(PathDetectionObject.class)
        .exportMeasurements(detFile)
}

// --- 5) Zusammenfassung ---
println "----------------------------------------------------"
println "Bilder im Projekt:   ${imageList.size()}"
println "Trennzeichen:        ${sepChoice}"
println "Annotationen ->      ${annFile.getAbsolutePath()}"
if (doDetections)
    println "Detections   ->      ${detFile.getAbsolutePath()}"
println "----------------------------------------------------"
Dialogs.showInfoNotification("Export fertig",
    "Projektweit exportiert nach:\n${outDir.getAbsolutePath()}")
