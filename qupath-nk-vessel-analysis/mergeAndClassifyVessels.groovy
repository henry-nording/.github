/**
 * QuPath – Ausgewählte Annotationen verschmelzen & Gefäße nach Fläche klassifizieren
 * --------------------------------------------------------------------------------
 * Projekt: NK-Zell- / Gefäß-Distanzanalyse (IB4 vs. Synaptophysin), Maus-Muskelschnitte
 *
 * Was das Skript macht:
 *  1. Es betrachtet NUR die im Viewer aktuell AUSGEWÄHLTEN flächigen Annotationen
 *     (nicht alle im Bild).
 *  2. Annotationen, die sehr nah beieinander liegen (Abstand <= mergeDistance) oder
 *     sich berühren/überlappen, werden zu einer Annotation zusammengeführt.
 *     -> behebt die Fragmentierung, die der Pixel-Classifier erzeugt hat.
 *  3. Jede resultierende (verschmolzene) Annotation wird anhand ihrer Fläche in
 *     "Small vessel" bzw. "Large vessel" eingeteilt.
 *
 * Beide Schwellen (Abstand & Fläche) sind im CONFIG-Block unten frei einstellbar.
 *
 * Bedienung:
 *  - Im Viewer die zu bearbeitenden Annotationen markieren
 *    (z. B. per Strg/Cmd-Klick mehrere, oder Objektliste -> mehrere selektieren).
 *  - Skript ausführen (Run). Originale werden ersetzt (siehe removeOriginals).
 */

import qupath.lib.objects.PathObjects
import qupath.lib.roi.GeometryTools
import org.locationtech.jts.operation.union.UnaryUnionOp

// ============================ CONFIG (anpassen) ============================
// Maximaler Abstand, bis zu dem benachbarte Annotationen verschmolzen werden.
// 0 = nur sich berührende/überlappende Annotationen werden vereinigt.
double  mergeDistance        = 5.0       // Einheit: µm   (bzw. px, falls useMicrons=false)

// Flächen-Schwelle: Fläche <  Schwelle  -> "Small vessel"
//                   Fläche >= Schwelle  -> "Large vessel"
double  vesselAreaThreshold  = 1000.0    // Einheit: µm²  (bzw. px², falls useMicrons=false)

// true  = Schwellen in µm / µm² (nutzt die Pixelkalibrierung des Bildes)
// false = Schwellen direkt in Pixel / Pixel²
boolean useMicrons           = true

// Klassennamen für die Einteilung – müssen den im Projekt vorhandenen Klassen
// entsprechen (Groß-/Kleinschreibung egal, wird vorhandenen Klassen zugeordnet).
String  smallClassName       = "small vessel"
String  largeClassName       = "large vessel"

// Ursprünglich ausgewählte Annotationen nach dem Merge löschen?
// false = Originale bleiben zusätzlich erhalten (zum Vergleichen/Prüfen).
boolean removeOriginals      = true
// ==========================================================================


// --- 1) Auswahl einsammeln (nur flächige Annotationen) ---
def selected = getSelectedObjects().findAll {
    it.isAnnotation() && it.getROI() != null && it.getROI().isArea()
}
if (selected.isEmpty()) {
    println "Keine flächigen Annotationen ausgewählt. Bitte im Viewer Annotationen markieren und erneut ausführen."
    return
}

// --- 2) Pixelkalibrierung / Einheiten ---
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
double distPixels         = toMicrons ? (mergeDistance / avgPx)        : mergeDistance
double areaPixelThreshold = toMicrons ? (vesselAreaThreshold / (pxW * pxH)) : vesselAreaThreshold
double bufferAmt          = distPixels / 2.0

// --- 3) Geometrien verschmelzen ---
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

// --- 4) Klassifizieren nach Fläche & neue Annotationen erzeugen ---
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
    double areaPx = g.getArea()                       // in Pixel²
    boolean isSmall = areaPx < areaPixelThreshold
    def ann = PathObjects.createAnnotationObject(roi, isSmall ? smallClass : largeClass)
    newAnnotations << ann
    if (isSmall) nSmall++ else nLarge++
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
println "Flächen-Schwelle:           ${vesselAreaThreshold} ${toMicrons ? 'µm²' : 'px²'}"
println "Resultierende Annotationen: ${newAnnotations.size()}"
println "  -> ${smallClassName}: ${nSmall}"
println "  -> ${largeClassName}: ${nLarge}"
println "Originale entfernt:         ${removeOriginals}"
println "----------------------------------------------------"
