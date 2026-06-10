# NK-Zell- / Gefäß-Distanzanalyse (IB4 vs. Synaptophysin)

QuPath-Workflow für Maus-Muskelschnitte. NK-Zellen (NKp46) sind als *Detections*
festgelegt; Gefäße als *Annotations* (Pixel-Classifier). Ziel: Distanz der
NK-Zellen zu Isolectin-B4- bzw. Synaptophysin-markierten Strukturen vergleichen,
getrennt nach **Small vessel** / **Large vessel**.

## Skripte

### `mergeAndClassifyVessels.groovy`
Bereinigt die vom Pixel-Classifier erzeugte Fragmentierung und teilt Gefäße
sauber nach Fläche ein.

**Ablauf**
1. Arbeitet nur mit den im Viewer **aktuell ausgewählten** flächigen Annotationen.
2. Verschmilzt Annotationen mit Abstand ≤ `mergeDistance` (bzw. berührende/überlappende).
3. Teilt jede entstandene Annotation nach Fläche in `Small vessel` / `Large vessel`.

**Einstellbar im CONFIG-Block**
| Parameter | Bedeutung | Standard |
|---|---|---|
| `mergeDistance` | Max. Abstand zum Verschmelzen (0 = nur berührende) | `5.0` µm |
| `vesselAreaThreshold` | Flächengrenze Small/Large | `1000.0` µm² |
| `useMicrons` | Schwellen in µm/µm² (sonst px/px²) | `true` |
| `smallClassName` / `largeClassName` | Klassennamen | `Small vessel` / `Large vessel` |
| `removeOriginals` | Originale nach Merge löschen | `true` |

**Bedienung**: Annotationen im Viewer markieren → Skript im Script-Editor ausführen (Run).

> Hinweis: Verschmelzung und Flächen nutzen die Pixelkalibrierung des Bildes.
> Fehlt die Kalibrierung, werden die Werte als Pixel interpretiert (Warnung im Log).
