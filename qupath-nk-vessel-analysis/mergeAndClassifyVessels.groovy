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
 *  3. Jede resultierende (verschmolzene) Annotation wird in "small vessel" bzw.
 *     "large vessel" eingeteilt – anhand von Fläche UND max. Durchmesser:
 *     "large", sobald die Fläche ODER der max. Durchmesser über der Schwelle liegt.
 *
 * Die Messparameter werden beim Start in einem DIALOGFENSTER abgefragt.
 *
 * Bedienung:
 *  - Im Viewer die zu bearbeitenden Annotationen markieren
 *    (z. B. per Strg/Cmd-Klick mehrere, oder Objektliste -> mehrere selektieren).
 *  - Skript ausführen (Run) -> Parameter im Fenster eingeben -> OK.
 */

import qupath.lib.objects.PathObjects
import qupath.lib.roi.GeometryTools
import qupath.lib.plugins.parameters.ParameterList
import qupath.lib.gui.dialogs.Dialogs
import org.locationtech.jts.operation.union.UnaryUnionOp
import org.locationtech.jts.algorithm.MinimumBoundingCircle

// ====================== feste Einstellungen (selten zu ändern) ======================
// Klassennamen – müssen den im Projekt vorhandenen Klassen entsprechen
// (Groß-/Kleinschreibung egal, wird vorhandenen Klassen zugeordnet).
String  smallClassName = "small vessel"
String  largeClassName = "large vessel"
// ====================================================================================


// --- 1) Auswahl einsammeln (nur flächige Annotationen) ---
def selected = getSelectedObjects().findAll {
    it.isAnnotation() && it.getROI() != null && it.getROI().isArea()
}
if (selected.isEmpty()) {
    Dialogs.showWarningNotification("Keine Auswahl",
        "Keine flächigen Annotationen ausgewählt. Bitte im Viewer markieren und erneut ausführen.")
    return
}

// --- 2) Messparameter per Dialogfenster abfragen ---
def params = new ParameterList()
    .addDoubleParameter("mergeDistance", "Merge-Distanz", 5.0, "µm",
        "Maximaler Abstand, bis zu dem benachbarte Annotationen verschmolzen werden (0 = nur berührende/überlappende).")
    .addDoubleParameter("areaThreshold", "Flächen-Schwelle", 1000.0, "µm²",
        "Annotationen mit dieser Fläche oder größer gelten als 'large vessel'.")
    .addDoubleParameter("diameterThreshold", "Durchmesser-Schwelle (max.)", 50.0, "µm",
        "Annotationen mit diesem max. Durchmesser oder größer gelten als 'large vessel'.")
    .addBooleanParameter("useMicrons", "Schwellen in µm / µm² (sonst in Pixel)", true,
        "Nutzt die Pixelkalibrierung des Bildes. Ohne Kalibrierung wird automatisch in Pixel gerechnet.")
    .addBooleanParameter("removeOriginals", "Originale nach Merge löschen", true,
        "Wenn deaktiviert, bleiben die ursprünglich ausgewählten Annotationen zusätzlich erhalten.")

if (!Dialogs.showParameterDialog("Gefäße verschmelzen & klassifizieren", params))
    return   // Abbrechen gedrückt

double  mergeDistance           = params.getDoubleParameterValue("mergeDistance")
double  vesselAreaThreshold     = params.getDoubleParameterValue("areaThreshold")
double  vesselDiameterThreshold = params.getDoubleParameterValue("diameterThreshold")
boolean useMicrons              = params.getBooleanParameterValue("useMicrons")
boolean removeOriginals         = params.getBooleanParameterValue("removeOriginals")

// --- 3) Pixelkalibrierung / Einheiten ---
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

// --- 4) Geometrien verschmelzen ---
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

// --- 5) Klassifizieren nach Fläche & max. Durchmesser, neue Annotationen erzeugen ---
// Vorhandene Projekt-Klassen wiederverwenden (case-insensitive), statt neue anzulegen.
def availableClasses = getProject()?.getPathClasses() ?: []
def resolveClass = { String name ->
    def existing = availableClasses.find { it != null && it.toString().equalsIgnoreCase(name) }
    if (existing != null)
        return existing
    println "WARNUNG: Klasse '${name}' nicht im Projekt vorhanden – sie wird neu angelegt. " +
            "Prüfe die Schreibweise in smallClassName/largeClassName."
    return getPathClass(name)
}
def smallClass = resolveClass(smallClassName)
def largeClass = resolveClass(largeClassName)

def newAnnotations = []
int nSmall = 0, nLarge = 0
for (def g : mergedGeoms) {
    def roi   = GeometryTools.geometryToROI(g, plane)
    double areaPx    = g.getArea()                                    // Fläche in Pixel²
    double maxDiamPx = 2.0 * new MinimumBoundingCircle(g).getRadius() // max. Durchmesser in Pixel
    // ODER-Verknüpfung: large, wenn Fläche ODER Durchmesser über der Schwelle liegt
    boolean isLarge = (areaPx >= areaPixelThreshold) || (maxDiamPx >= diamPixelThreshold)
    def ann = PathObjects.createAnnotationObject(roi, isLarge ? largeClass : smallClass)
    newAnnotations << ann
    if (isLarge) nLarge++ else nSmall++
}

// --- 6) Hierarchie aktualisieren ---
if (removeOriginals)
    removeObjects(selected, true)
addObjects(newAnnotations)
fireHierarchyUpdate()

// --- 7) Zusammenfassung ---
println "----------------------------------------------------"
println "Ausgewählte Annotationen:   ${selected.size()}"
println "Merge-Distanz:              ${mergeDistance} ${toMicrons ? 'µm' : 'px'}  (= ${(distPixels as double).round(2)} px)"
println "Klassifizierung:            large, wenn Fläche ODER max. Durchmesser >= Schwelle"
println "  Flächen-Schwelle:         ${vesselAreaThreshold} ${toMicrons ? 'µm²' : 'px²'}"
println "  Durchmesser-Schwelle:     ${vesselDiameterThreshold} ${toMicrons ? 'µm' : 'px'}"
println "Resultierende Annotationen: ${newAnnotations.size()}"
println "  -> ${smallClassName}: ${nSmall}"
println "  -> ${largeClassName}: ${nLarge}"
println "Originale entfernt:         ${removeOriginals}"
println "----------------------------------------------------"
Dialogs.showInfoNotification("Fertig",
    "${newAnnotations.size()} Annotation(en): ${nLarge}x large, ${nSmall}x small vessel.")
