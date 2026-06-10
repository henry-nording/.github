/**
 * QuPath – Ausgewählte Annotationen verschmelzen & Kanten begradigen (vereinfacht)
 * --------------------------------------------------------------------------------
 * Für Schnitte mit EINER gefärbten Struktur (ohne Unterscheidung small/large).
 *
 * Was das Skript macht:
 *  1. Es betrachtet NUR die im Viewer aktuell AUSGEWÄHLTEN flächigen Annotationen.
 *  2. Annotationen, die sehr nah beieinander liegen (Abstand <= mergeDistance) oder
 *     sich berühren/überlappen, werden zu einer Annotation zusammengeführt; dabei
 *     werden durch das Auf- und Zurückpuffern auch die Kanten leicht begradigt
 *     (gleiches Verfahren wie im großen Skript).
 *  3. Die verschmolzenen Annotationen behalten die Klasse der Auswahl.
 *
 * Bedienung:
 *  - Im Viewer die zu bearbeitenden Annotationen markieren.
 *  - Skript ausführen (Run) -> Merge-Distanz im Fenster wählen -> OK.
 */

import qupath.lib.objects.PathObjects
import qupath.lib.roi.GeometryTools
import qupath.lib.plugins.parameters.ParameterList
import qupath.lib.gui.dialogs.ParameterPanelFX
import org.locationtech.jts.operation.union.UnaryUnionOp
import javafx.application.Platform
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
// Hinweis: 'Dialogs' (qupath.fx.dialogs.Dialogs) wird von QuPath automatisch importiert.


// --- 1) Auswahl einsammeln (nur flächige Annotationen) ---
def selected = getSelectedObjects().findAll {
    it.isAnnotation() && it.getROI() != null && it.getROI().isArea()
}
if (selected.isEmpty()) {
    Dialogs.showWarningNotification("Keine Auswahl",
        "Keine flächigen Annotationen ausgewählt. Bitte im Viewer markieren und erneut ausführen.")
    return
}

// --- 2) Parameter per Dialogfenster abfragen ---
def params = new ParameterList()
    .addDoubleParameter("mergeDistance", "Merge-Distanz", 5.0, "µm",
        "Maximaler Abstand, bis zu dem benachbarte Annotationen verschmolzen werden (0 = nur berührende/überlappende).")
    .addBooleanParameter("useMicrons", "Distanz in µm (sonst in Pixel)", true,
        "Nutzt die Pixelkalibrierung des Bildes. Ohne Kalibrierung wird automatisch in Pixel gerechnet.")
    .addBooleanParameter("removeOriginals", "Originale nach Merge löschen", true,
        "Wenn deaktiviert, bleiben die ursprünglich ausgewählten Annotationen zusätzlich erhalten.")

def showParamDialog = {
    def pane = new ParameterPanelFX(params).getPane()
    return Dialogs.showConfirmDialog("Annotationen verschmelzen & begradigen", pane)
} as Callable<Boolean>

boolean confirmed
if (Platform.isFxApplicationThread()) {
    confirmed = showParamDialog.call()
} else {
    def task = new FutureTask<Boolean>(showParamDialog)
    Platform.runLater(task)
    confirmed = task.get()
}
if (!confirmed)
    return   // Abbrechen gedrückt

double  mergeDistance   = params.getDoubleParameterValue("mergeDistance")
boolean useMicrons      = params.getBooleanParameterValue("useMicrons")
boolean removeOriginals = params.getBooleanParameterValue("removeOriginals")

// --- 3) Pixelkalibrierung / Einheiten ---
def cal = getCurrentServer().getPixelCalibration()
boolean hasCal = cal.hasPixelSizeMicrons()
double avgPx = hasCal ? (cal.getPixelWidthMicrons() + cal.getPixelHeightMicrons()) / 2.0 : 1.0
boolean toMicrons = useMicrons && hasCal
if (useMicrons && !hasCal)
    println "WARNUNG: Keine Pixelkalibrierung gefunden – Merge-Distanz wird als PIXEL interpretiert."

double distPixels = toMicrons ? (mergeDistance / avgPx) : mergeDistance
double bufferAmt  = distPixels / 2.0

// --- 4) Geometrien verschmelzen (und durch Puffern Kanten begradigen) ---
def plane = selected[0].getROI().getImagePlane()
def pathClass = selected[0].getPathClass()   // Klasse der Auswahl beibehalten
def geoms = selected.collect { it.getROI().getGeometry() }

def toUnion = bufferAmt > 0 ? geoms.collect { it.buffer(bufferAmt) } : geoms
def unioned = UnaryUnionOp.union(toUnion)

def newAnnotations = []
for (int i = 0; i < unioned.getNumGeometries(); i++) {
    def part = unioned.getGeometryN(i)
    if (bufferAmt > 0) {
        def shrunk = part.buffer(-bufferAmt)
        part = (shrunk == null || shrunk.isEmpty()) ? part : shrunk   // sehr dünne Teile nicht wegschrumpfen
    }
    if (part != null && !part.isEmpty()) {
        def roi = GeometryTools.geometryToROI(part, plane)
        newAnnotations << PathObjects.createAnnotationObject(roi, pathClass)
    }
}

// --- 5) Hierarchie aktualisieren ---
if (removeOriginals)
    removeObjects(selected, true)
addObjects(newAnnotations)
fireHierarchyUpdate()

// --- 6) Zusammenfassung ---
println "----------------------------------------------------"
println "Ausgewählte Annotationen:   ${selected.size()}"
println "Merge-Distanz:              ${mergeDistance} ${toMicrons ? 'µm' : 'px'}  (= ${(distPixels as double).round(2)} px)"
println "Resultierende Annotationen: ${newAnnotations.size()}"
println "Klasse:                     ${pathClass ?: '(keine)'}"
println "Originale entfernt:         ${removeOriginals}"
println "----------------------------------------------------"
Dialogs.showInfoNotification("Fertig",
    "${selected.size()} -> ${newAnnotations.size()} Annotation(en) verschmolzen.")
