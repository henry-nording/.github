/**
 * QuPath – Ausgewählte Annotationen verschmelzen & Gefäße klassifizieren
 * --------------------------------------------------------------------------------
 * Projekt: NK-Zell- / Gefäß-Distanzanalyse (IB4 vs. Synaptophysin), Maus-Muskelschnitte
 *
 * Was das Skript macht:
 *  1. Es betrachtet NUR die im Viewer aktuell AUSGEWÄHLTEN flächigen Annotationen
 *     (nicht alle im Bild).
 *  2. Annotationen, die sehr nah beieinander liegen (Abstand <= mergeDistance) oder
 *     sich berühren/überlappen, werden zu einer Annotation zusammengeführt.
 *     -> behebt die Fragmentierung, die der Pixel-Classifier erzeugt hat.
 *  3. Jede resultierende (verschmolzene) Annotation wird in zwei wählbare Klassen
 *     eingeteilt – anhand von Fläche UND max. Durchmesser:
 *     "große" Klasse, sobald die Fläche ODER der max. Durchmesser über der Schwelle
 *     liegt, sonst "kleine" Klasse.
 *
 * Alle Mess- und Klassen-Parameter werden beim Start in einem DIALOGFENSTER abgefragt,
 * dadurch ist das Skript auch für andere Färbungen (z. B. Synaptophysin) nutzbar.
 *
 * Bedienung:
 *  - Im Viewer die zu bearbeitenden Annotationen markieren
 *    (z. B. per Strg/Cmd-Klick mehrere, oder Objektliste -> mehrere selektieren).
 *  - Skript ausführen (Run) -> Parameter & Klassen im Fenster wählen -> OK.
 */

import qupath.lib.objects.PathObjects
import qupath.lib.roi.GeometryTools
import qupath.lib.plugins.parameters.ParameterList
import qupath.lib.gui.dialogs.ParameterPanelFX
import org.locationtech.jts.operation.union.UnaryUnionOp
import org.locationtech.jts.algorithm.MinimumBoundingCircle
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

// --- 2) Vorhandene Projekt-Klassen als Auswahl vorbereiten ---
def availableClasses = (getProject()?.getPathClasses() ?: []).findAll { it != null }
def classNames = availableClasses.collect { it.toString() }
if (classNames.isEmpty()) {
    Dialogs.showWarningNotification("Keine Klassen",
        "Im Projekt sind keine Klassen definiert. Lege zuerst die gewünschten Klassen an (z. B. 'small vessel' / 'large vessel').")
    return
}
// Sinnvolle Vorauswahl, falls vorhanden
def defaultSmall = classNames.find { it.equalsIgnoreCase("small vessel") } ?: classNames[0]
def defaultLarge = classNames.find { it.equalsIgnoreCase("large vessel") } ?: classNames[-1]

// --- 3) Mess- und Klassen-Parameter per Dialogfenster abfragen ---
def params = new ParameterList()
    .addChoiceParameter("smallClass", "Klasse für KLEINE Strukturen", defaultSmall, classNames,
        "Klasse, die Annotationen unterhalb beider Schwellen zugewiesen wird.")
    .addChoiceParameter("largeClass", "Klasse für GROSSE Strukturen", defaultLarge, classNames,
        "Klasse, die Annotationen ab Erreichen einer der Schwellen zugewiesen wird.")
    .addDoubleParameter("mergeDistance", "Merge-Distanz", 5.0, "µm",
        "Maximaler Abstand, bis zu dem benachbarte Annotationen verschmolzen werden (0 = nur berührende/überlappende).")
    .addDoubleParameter("areaThreshold", "Flächen-Schwelle", 1000.0, "µm²",
        "Annotationen mit dieser Fläche oder größer gelten als 'große' Klasse.")
    .addDoubleParameter("diameterThreshold", "Durchmesser-Schwelle (max.)", 50.0, "µm",
        "Annotationen mit diesem max. Durchmesser oder größer gelten als 'große' Klasse.")
    .addBooleanParameter("useMicrons", "Schwellen in µm / µm² (sonst in Pixel)", true,
        "Nutzt die Pixelkalibrierung des Bildes. Ohne Kalibrierung wird automatisch in Pixel gerechnet.")
    .addBooleanParameter("removeOriginals", "Originale nach Merge löschen", true,
        "Wenn deaktiviert, bleiben die ursprünglich ausgewählten Annotationen zusätzlich erhalten.")

// Dialog auf dem JavaFX-Thread aufbauen und anzeigen (ersetzt das in neueren
// QuPath-Versionen entfernte Dialogs.showParameterDialog).
def showParamDialog = {
    def pane = new ParameterPanelFX(params).getPane()
    return Dialogs.showConfirmDialog("Annotationen verschmelzen & klassifizieren", pane)
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

String  smallClassName          = params.getChoiceParameterValue("smallClass") as String
String  largeClassName          = params.getChoiceParameterValue("largeClass") as String
double  mergeDistance           = params.getDoubleParameterValue("mergeDistance")
double  vesselAreaThreshold     = params.getDoubleParameterValue("areaThreshold")
double  vesselDiameterThreshold = params.getDoubleParameterValue("diameterThreshold")
boolean useMicrons              = params.getBooleanParameterValue("useMicrons")
boolean removeOriginals         = params.getBooleanParameterValue("removeOriginals")

if (smallClassName == largeClassName) {
    Dialogs.showWarningNotification("Gleiche Klasse",
        "Kleine und große Klasse sind identisch ('${smallClassName}'). Bitte zwei verschiedene Klassen wählen.")
    return
}

// --- 4) Pixelkalibrierung / Einheiten ---
def server = getCurrentServer()
def cal    = server.getPixelCalibration()
boolean hasCal = cal.hasPixelSizeMicrons()
double pxW = hasCal ? cal.getPixelWidthMicrons()  : 1.0
double pxH = hasCal ? cal.getPixelHeightMicrons() : 1.0
double avgPx = (pxW + pxH) / 2.0

boolean toMicrons = useMicrons && hasCal
if (useMicrons && !hasCal)
    println "WARNUNG: Keine Pixelkalibrierung im Bild gefunden – Schwellen werden als PIXEL interpretiert."

// Schwellen in Pixel-Einheiten umrechnen (Geometrien liegen in Pixelkoordinaten vor)
double distPixels         = toMicrons ? (mergeDistance / avgPx)             : mergeDistance
double areaPixelThreshold = toMicrons ? (vesselAreaThreshold / (pxW * pxH)) : vesselAreaThreshold
double diamPixelThreshold = toMicrons ? (vesselDiameterThreshold / avgPx)   : vesselDiameterThreshold
double bufferAmt          = distPixels / 2.0

// --- 5) Geometrien verschmelzen ---
def plane = selected[0].getROI().getImagePlane()
def geoms = selected.collect { it.getROI().getGeometry() }

// Bei mergeDistance > 0: Geometrien aufpuffern (schließt Lücken bis mergeDistance),
// vereinigen, danach wieder zurückpuffern -> nahe Fragmente werden zu einem Stück.
// Bei mergeDistance = 0: reine Vereinigung berührender/überlappender Geometrien.
def toUnion = bufferAmt > 0 ? geoms.collect { it.buffer(bufferAmt) } : geoms
def unioned = UnaryUnionOp.union(toUnion)

def mergedGeoms = []
for (int i = 0; i < unioned.getNumGeometries(); i++) {
    def part = unioned.getGeometryN(i)
    if (bufferAmt > 0) {
        def shrunk = part.buffer(-bufferAmt)
        part = (shrunk == null || shrunk.isEmpty()) ? part : shrunk   // sehr dünne Teile nicht wegschrumpfen
    }
    if (part != null && !part.isEmpty())
        mergedGeoms << part
}

// --- 6) Klassifizieren nach Fläche & max. Durchmesser, neue Annotationen erzeugen ---
// Gewählte Klassen auf die echten PathClass-Objekte abbilden.
def resolveClass = { String name ->
    def existing = availableClasses.find { it.toString().equalsIgnoreCase(name) }
    return existing != null ? existing : getPathClass(name)
}
def smallClass = resolveClass(smallClassName)
def largeClass = resolveClass(largeClassName)

def newAnnotations = []
int nSmall = 0, nLarge = 0
for (def g : mergedGeoms) {
    def roi   = GeometryTools.geometryToROI(g, plane)
    double areaPx    = g.getArea()                                    // Fläche in Pixel²
    double maxDiamPx = 2.0 * new MinimumBoundingCircle(g).getRadius() // max. Durchmesser in Pixel
    // ODER-Verknüpfung: große Klasse, wenn Fläche ODER Durchmesser über der Schwelle liegt
    boolean isLarge = (areaPx >= areaPixelThreshold) || (maxDiamPx >= diamPixelThreshold)
    def ann = PathObjects.createAnnotationObject(roi, isLarge ? largeClass : smallClass)
    newAnnotations << ann
    if (isLarge) nLarge++ else nSmall++
}

// --- 7) Hierarchie aktualisieren ---
if (removeOriginals)
    removeObjects(selected, true)
addObjects(newAnnotations)
fireHierarchyUpdate()

// --- 8) Zusammenfassung ---
println "----------------------------------------------------"
println "Ausgewählte Annotationen:   ${selected.size()}"
println "Merge-Distanz:              ${mergeDistance} ${toMicrons ? 'µm' : 'px'}  (= ${(distPixels as double).round(2)} px)"
println "Klassifizierung:            '${largeClassName}', wenn Fläche ODER max. Durchmesser >= Schwelle, sonst '${smallClassName}'"
println "  Flächen-Schwelle:         ${vesselAreaThreshold} ${toMicrons ? 'µm²' : 'px²'}"
println "  Durchmesser-Schwelle:     ${vesselDiameterThreshold} ${toMicrons ? 'µm' : 'px'}"
println "Resultierende Annotationen: ${newAnnotations.size()}"
println "  -> ${largeClassName}: ${nLarge}"
println "  -> ${smallClassName}: ${nSmall}"
println "Originale entfernt:         ${removeOriginals}"
println "----------------------------------------------------"
Dialogs.showInfoNotification("Fertig",
    "${newAnnotations.size()} Annotation(en): ${nLarge}x '${largeClassName}', ${nSmall}x '${smallClassName}'.")
