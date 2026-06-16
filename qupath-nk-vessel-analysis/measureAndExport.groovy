/**
 * QuPath – Maximaler, pivot-fähiger Mess-Export (projektweit)
 * --------------------------------------------------------------------------------
 * Projekt: NK-Zell- / Gefäß-Distanzanalyse (IB4 vs. Synaptophysin), Maus-Muskelschnitte
 *
 * Liefert drei Tabellen (Trennzeichen wählbar) im Ordner <Projekt>/measurements/:
 *   1) ALL_detections_NKcells.<ext>  -> PIVOT-QUELLE: eine Zeile je NK-Zelle,
 *        mit Morphologie (Area/Perimeter/Durchmesser/Circularity/Solidity),
 *        Distanzen (small/large vessel, Synaptophysin), perivaskulär-Flag,
 *        sowie je Zeile die BILD-Kennzahlen (NK-Zahl, Gefäßzahlen, Gewebefläche,
 *        NK-Dichte, Gefäß-Flächenanteil) -> alles in einer Pivot auswertbar.
 *   2) ALL_vessels_annotations.<ext> -> eine Zeile je Annotation (Gefäß-Kaliber,
 *        Fläche, Durchmesser, + NK-Zellen im Umkreis).
 *   3) SUMMARY_per_image.<ext>       -> eine Zeile je Bild (absolute NK-Zahl,
 *        Gefäßzahlen, Gewebefläche, Dichten, Distanz-Statistiken, perivaskulär-%).
 *
 * Bedienung: Projekt öffnen, Skript EINMAL ausführen (Run). Im Dialog Klassen,
 *            Schwellen und Trennzeichen wählen -> OK. Es verarbeitet alle Bilder.
 *
 * Hinweis: Das Skript ergänzt Form-Messwerte und speichert die Bilddaten.
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
import java.util.Locale
// Hinweis: 'Dialogs' (qupath.fx.dialogs.Dialogs) wird von QuPath automatisch importiert.


// ====================== Hilfsfunktionen (versions-robust) ======================
def NONE = "(nicht vorhanden)"

// Measurement-Namen einer Liste (verschiedene API-Namen abfangen)
def measNames = { obj ->
    def ml = obj.getMeasurementList()
    try { return ml.getMeasurementNames() } catch (e) { return ml.getNames() }
}
// Wert lesen (NaN, falls nicht vorhanden)
def getVal = { obj, String name ->
    if (name == null) return Double.NaN
    def ml = obj.getMeasurementList()
    try { return ml.get(name) } catch (e) {
        try { return ml.getMeasurementValue(name) } catch (e2) { return Double.NaN }
    }
}
// Wert schreiben
def putVal = { obj, String name, double val ->
    def ml = obj.getMeasurementList()
    try { ml.put(name, val) } catch (e) { ml.addMeasurement(name, val) }
}
// Distanz-Messwert (Spatial Analysis) zu einer Klasse finden – Namensschema variiert
def findDistName = { names, String classKeyword ->
    if (classKeyword == null || classKeyword == NONE) return null
    def k = classKeyword.toLowerCase()
    return names.find { it.toLowerCase().contains("distance") && it.toLowerCase().contains(k) }
}
// Zahl mit Punkt als Dezimaltrennzeichen formatieren (locale-unabhängig)
def fmt = { def v ->
    if (v == null) return ""
    double d = v as double
    if (Double.isNaN(d)) return ""
    return String.format(Locale.US, "%.4f", d)
}
def median = { List<Double> xs ->
    def v = xs.findAll { it != null && !Double.isNaN(it) }.sort()
    if (v.isEmpty()) return Double.NaN
    int n = v.size()
    return (n % 2 == 1) ? v[(int)(n/2)] : (v[n/2 - 1] + v[n/2]) / 2.0
}
def mean = { List<Double> xs ->
    def v = xs.findAll { it != null && !Double.isNaN(it) }
    return v.isEmpty() ? Double.NaN : v.sum() / v.size()
}


// ====================== 0) Projekt prüfen ======================
def project = getProject()
if (project == null) {
    Dialogs.showWarningNotification("Kein Projekt",
        "Bitte zuerst ein QuPath-Projekt öffnen – der projektweite Export braucht ein Projekt.")
    return
}
def availableClasses = (project.getPathClasses() ?: []).findAll { it != null }.collect { it.toString() }
def classOptions = [NONE] + availableClasses
def pick = { String wanted -> availableClasses.find { it.equalsIgnoreCase(wanted) } ?: NONE }


// ====================== 1) Dialog ======================
def params = new ParameterList()
    .addChoiceParameter("smallClass", "Klasse: small vessel", pick("small vessel"), classOptions, "Klasse der kleinen Gefäße.")
    .addChoiceParameter("largeClass", "Klasse: large vessel", pick("large vessel"), classOptions, "Klasse der großen Gefäße.")
    .addChoiceParameter("synClass",   "Klasse: Synaptophysin", pick("Synaptophysin"), classOptions, "Klasse der Synaptophysin-Struktur.")
    .addChoiceParameter("tissueClass","Klasse: Gewebe/Region", pick("Tissue"), classOptions, "Annotation, deren Fläche als Gewebefläche (für Dichten) dient.")
    .addDoubleParameter("periThr", "Perivaskulär-Schwelle", 20.0, "µm", "NK-Zelle gilt als perivaskulär, wenn Distanz zum nächsten Gefäß <= Schwelle.")
    .addDoubleParameter("nkRadius", "NK-pro-Gefäß Radius", 20.0, "µm", "Umkreis um jedes Gefäß, in dem NK-Zellen gezählt werden.")
    .addChoiceParameter("sep", "Trennzeichen (Excel)", "Tab", ["Tab", "Komma (,)", "Semikolon (;)"], "Tab (.tsv) ist am robustesten; deutsches Excel mag Semikolon-CSV.")
    .addBooleanParameter("addShapes", "Form-Messwerte ergänzen & speichern", true, "Beim ersten Lauf nötig (Area/Perimeter/Durchmesser ...).")
    .addBooleanParameter("recalcInt", "Intensitäten NEU berechnen (langsam)", false, "Vorhandene Intensitäten werden ohnehin exportiert. Nur aktivieren, wenn keine vorhanden sind.")

def showDialog = {
    def pane = new ParameterPanelFX(params).getPane()
    return Dialogs.showConfirmDialog("Maximaler Mess-Export (pivot-fähig)", pane)
} as Callable<Boolean>
boolean confirmed
if (Platform.isFxApplicationThread()) confirmed = showDialog.call()
else { def t = new FutureTask<Boolean>(showDialog); Platform.runLater(t); confirmed = t.get() }
if (!confirmed) return

String smallClass  = params.getChoiceParameterValue("smallClass") as String
String largeClass  = params.getChoiceParameterValue("largeClass") as String
String synClass    = params.getChoiceParameterValue("synClass") as String
String tissueClass = params.getChoiceParameterValue("tissueClass") as String
double periThr     = params.getDoubleParameterValue("periThr")
double nkRadius    = params.getDoubleParameterValue("nkRadius")
String sepChoice   = params.getChoiceParameterValue("sep") as String
boolean addShapes  = params.getBooleanParameterValue("addShapes")
boolean recalcInt  = params.getBooleanParameterValue("recalcInt")

String sep; String ext
switch (sepChoice) {
    case "Komma (,)":     sep = ",";  ext = ".csv"; break
    case "Semikolon (;)": sep = ";";  ext = ".csv"; break
    default:              sep = "\t"; ext = ".tsv"; break
}
def shapeFeatures = [AREA, LENGTH, CIRCULARITY, SOLIDITY, MAX_DIAMETER, MIN_DIAMETER] as ObjectMeasurements.ShapeFeatures[]
def intensityMeas = [ObjectMeasurements.Measurements.MEAN, ObjectMeasurements.Measurements.MAX,
                     ObjectMeasurements.Measurements.MIN, ObjectMeasurements.Measurements.STD_DEV] as List


// ====================== 2) Pro Bild verarbeiten ======================
def imageList = project.getImageList()
def summaryRows = []   // je Bild eine Map

for (entry in imageList) {
    try {
        def imageData = entry.readImageData()
        def hierarchy = imageData.getHierarchy()
        def server    = imageData.getServer()
        def cal       = server.getPixelCalibration()
        double pxW = cal.hasPixelSizeMicrons() ? cal.getPixelWidthMicrons()  : 1.0
        double pxH = cal.hasPixelSizeMicrons() ? cal.getPixelHeightMicrons() : 1.0
        double avgPx = (pxW + pxH) / 2.0
        String imgName = entry.getImageName()

        def annotations = hierarchy.getAnnotationObjects()
        def detections  = hierarchy.getDetectionObjects()   // = NK-Zellen

        // 2a) Form-Messwerte (Annotationen + Detections)
        if (addShapes) {
            (annotations + detections).each { o ->
                if (o.getROI() != null && o.getROI().isArea())
                    ObjectMeasurements.addShapeMeasurements(o, cal, shapeFeatures)
            }
        }
        // 2b) Intensitäten optional neu berechnen
        if (recalcInt) {
            detections.each { o ->
                try { ObjectMeasurements.addIntensityMeasurements(o, server, 1.0, intensityMeas, []) } catch (e) {}
            }
        }

        // 2c) Distanz-Messwertnamen (Spatial Analysis) bestimmen
        def anyNames = detections.isEmpty() ? [] : measNames(detections[0])
        def dSmallNm = findDistName(anyNames, smallClass)
        def dLargeNm = findDistName(anyNames, largeClass)
        def dSynNm   = findDistName(anyNames, synClass)

        // 2d) Gewebe-/Gefäß-Flächen (µm²) und Zählungen
        def isClass = { o, String c -> c != NONE && o.getPathClass() != null && o.getPathClass().toString().equalsIgnoreCase(c) }
        double tissueAreaUm = annotations.findAll { isClass(it, tissueClass) }.sum { it.getROI().getScaledArea(pxW, pxH) } ?: 0.0
        double smallAreaUm  = annotations.findAll { isClass(it, smallClass)  }.sum { it.getROI().getScaledArea(pxW, pxH) } ?: 0.0
        double largeAreaUm  = annotations.findAll { isClass(it, largeClass)  }.sum { it.getROI().getScaledArea(pxW, pxH) } ?: 0.0
        double synAreaUm    = annotations.findAll { isClass(it, synClass)    }.sum { it.getROI().getScaledArea(pxW, pxH) } ?: 0.0
        int nSmall = annotations.count { isClass(it, smallClass) }
        int nLarge = annotations.count { isClass(it, largeClass) }
        int nVessels = nSmall + nLarge
        int nkCount  = detections.size()

        double tissueAreaMm2 = tissueAreaUm / 1_000_000.0
        double nkDensity = tissueAreaMm2 > 0 ? nkCount / tissueAreaMm2 : Double.NaN
        double vesselAreaFraction = tissueAreaUm > 0 ? (smallAreaUm + largeAreaUm) / tissueAreaUm * 100.0 : Double.NaN
        double synAreaFraction    = tissueAreaUm > 0 ? synAreaUm / tissueAreaUm * 100.0 : Double.NaN

        // 2e) Distanz-Statistik + perivaskulärer Anteil (über die NK-Zellen)
        def dsSmall = []; def dsLarge = []; def dsSyn = []
        int nPeri = 0
        detections.each { det ->
            double ds = getVal(det, dSmallNm)
            double dl = getVal(det, dLargeNm)
            double dy = getVal(det, dSynNm)
            dsSmall << ds; dsLarge << dl; dsSyn << dy
            // nächste Gefäßdistanz (min über small/large, NaN ignorierend)
            def vd = [ds, dl].findAll { !Double.isNaN(it) }
            double nearestVessel = vd.isEmpty() ? Double.NaN : vd.min()
            boolean peri = !Double.isNaN(nearestVessel) && nearestVessel <= periThr
            if (peri) nPeri++
            boolean nearestLarge = (!Double.isNaN(dl)) && (Double.isNaN(ds) || dl < ds)

            // 2f) je NK-Zelle: bereinigte + abgeleitete Spalten und Bild-Kennzahlen
            putVal(det, "Dist small vessel um", ds)
            putVal(det, "Dist large vessel um", dl)
            putVal(det, "Dist synaptophysin um", dy)
            putVal(det, "Dist nearest vessel um", nearestVessel)
            putVal(det, "Perivascular (0/1)", peri ? 1.0 : 0.0)
            putVal(det, "Nearest vessel is large (0/1)", nearestLarge ? 1.0 : 0.0)
            putVal(det, "Image NK count", (double) nkCount)
            putVal(det, "Image small vessel count", (double) nSmall)
            putVal(det, "Image large vessel count", (double) nLarge)
            putVal(det, "Image tissue area mm2", tissueAreaMm2)
            putVal(det, "Image NK density per mm2", nkDensity)
            putVal(det, "Image vessel area fraction pct", vesselAreaFraction)
        }

        // 2g) NK-Zellen pro Gefäß-Annotation (im Umkreis nkRadius)
        double radiusPx = avgPx > 0 ? (nkRadius / avgPx) : nkRadius
        def detGeoms = detections.collect { it.getROI().getGeometry() }
        annotations.findAll { isClass(it, smallClass) || isClass(it, largeClass) }.each { ves ->
            def vg = ves.getROI().getGeometry()
            int c = detGeoms.count { vg.distance(it) <= radiusPx }
            putVal(ves, "NK within ${(int)nkRadius}um count", (double) c)
        }

        entry.saveImageData(imageData)

        summaryRows << [
            Image: imgName, NK_count: nkCount,
            small_vessels: nSmall, large_vessels: nLarge, vessels_total: nVessels,
            tissue_area_mm2: tissueAreaMm2, NK_density_per_mm2: nkDensity,
            vessel_area_fraction_pct: vesselAreaFraction, synaptophysin_area_fraction_pct: synAreaFraction,
            median_dist_small_um: median(dsSmall), mean_dist_small_um: mean(dsSmall),
            median_dist_large_um: median(dsLarge), mean_dist_large_um: mean(dsLarge),
            median_dist_syn_um: median(dsSyn),     mean_dist_syn_um: mean(dsSyn),
            perivascular_pct: nkCount > 0 ? (nPeri * 100.0 / nkCount) : Double.NaN
        ]
        println "verarbeitet: ${imgName}  (NK=${nkCount}, small=${nSmall}, large=${nLarge})"
    } catch (ex) {
        println "FEHLER bei Bild '${entry.getImageName()}': ${ex.getMessage()}"
    }
}


// ====================== 3) Export-Ziel ======================
def outDir = new File(buildFilePath(PROJECT_BASE_DIR, "measurements"))
outDir.mkdirs()
def detFile = new File(outDir, "ALL_detections_NKcells" + ext)
def annFile = new File(outDir, "ALL_vessels_annotations" + ext)
def sumFile = new File(outDir, "SUMMARY_per_image" + ext)

// ====================== 4) Projektweiter Objekt-Export ======================
new MeasurementExporter().imageList(imageList).separator(sep)
    .exportType(PathDetectionObject.class).exportMeasurements(detFile)
new MeasurementExporter().imageList(imageList).separator(sep)
    .exportType(PathAnnotationObject.class).exportMeasurements(annFile)

// ====================== 5) Summary-Tabelle schreiben ======================
def cols = ["Image","NK_count","small_vessels","large_vessels","vessels_total",
            "tissue_area_mm2","NK_density_per_mm2","vessel_area_fraction_pct","synaptophysin_area_fraction_pct",
            "median_dist_small_um","mean_dist_small_um","median_dist_large_um","mean_dist_large_um",
            "median_dist_syn_um","mean_dist_syn_um","perivascular_pct"]
sumFile.withWriter("UTF-8") { w ->
    w.writeLine(cols.join(sep))
    summaryRows.each { row ->
        w.writeLine(cols.collect { c ->
            def v = row[c]
            (v instanceof Number && !(v instanceof Integer)) ? fmt(v) : (v == null ? "" : v.toString())
        }.join(sep))
    }
}

// ====================== 6) Zusammenfassung ======================
int totalNK = summaryRows.sum { it.NK_count } ?: 0
println "===================================================="
println "Bilder verarbeitet:   ${summaryRows.size()}"
println "NK-Zellen gesamt:     ${totalNK}"
println "Detections  -> ${detFile.getAbsolutePath()}"
println "Annotationen-> ${annFile.getAbsolutePath()}"
println "Summary     -> ${sumFile.getAbsolutePath()}"
println "===================================================="
Dialogs.showInfoNotification("Export fertig",
    "Bilder: ${summaryRows.size()}, NK gesamt: ${totalNK}\nOrdner: ${outDir.getAbsolutePath()}")
