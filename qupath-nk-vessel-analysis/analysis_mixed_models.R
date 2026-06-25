# ============================================================================
# NK-Zell / Gefäß / Nerv  –  statistische Auswertung
# ----------------------------------------------------------------------------
# Liest die QuPath-Exporte (SUMMARY_per_image.*) und macht:
#   (A) Deskriptive Tier-Mittel (richtiges biologisches Replikat)
#   (B) Gemischte Modelle: Bild genestet in Tier  (lmerTest, falls installiert)
#   (C) Anreicherungstest: NK- vs. Nicht-NK-Zellen (perivaskulär & Distanzen)
#
# WICHTIG: Mit wenigen Tieren (hier n=2) sind p-Werte NICHT belastbar -> Pilot.
#          Die gemischten Modelle sind als Vorlage für n>=4-5 Tiere gedacht.
#
# Aufruf:  Rscript analysis_mixed_models.R  <measurements_dir>
# ============================================================================

args <- commandArgs(trailingOnly = TRUE)
measdir <- if (length(args) >= 1) args[1] else "."

read_qupath <- function(stem) {
  for (ext in c(".tsv", ".csv")) {
    p <- file.path(measdir, paste0(stem, ext))
    if (file.exists(p)) {
      sep <- if (ext == ".tsv") "\t" else {
        l <- readLines(p, n = 1); if (grepl(";", l)) ";" else ","
      }
      return(read.table(p, header = TRUE, sep = sep, check.names = FALSE,
                        stringsAsFactors = FALSE, quote = "\""))
    }
  }
  stop(paste("Nicht gefunden:", stem))
}

s <- read_qupath("SUMMARY_per_image")

# ---- Faktoren aus dem Bildnamen ableiten ----
img <- s$Image
s$animal <- sub(".*_(\\d{4,6})_.*", "\\1", img)
s$sex    <- sub(".*_\\d{4,6}_([fm])_.*", "\\1", img)
s$cond   <- ifelse(grepl("gcOP", img), "OP", ifelse(grepl("gcN", img), "N", NA))
s$panel  <- ifelse(grepl("IB4", img), "IB4", ifelse(grepl("Syn", img), "Syn", NA))
s$is_control <- grepl("control", tolower(img))
s <- s[!s$is_control, ]
s$animal <- factor(s$animal); s$cond <- factor(s$cond, levels = c("N", "OP")); s$panel <- factor(s$panel)

# ---- Struktur-Dichte & -Flaeche aus den Annotationen (small/large vessel, Synaptophysin) ----
# Wird je Bild aus ALL_vessels_annotations berechnet und in s gemergt:
#   syn_struct_density    = Synaptophysin-Strukturen / mm² Gewebe
#   syn_struct_area_mean  = mittlere Synaptophysin-Flaeche [µm²]
#   syn_area_fraction_pct = Synaptophysin-Flaechenanteil am Gewebe [%]
#   small/large_vessel_area_mean = mittlere Gefaessflaeche [µm²]
va_raw <- tryCatch(read_qupath("ALL_vessels_annotations"), error = function(e) NULL)
if (!is.null(va_raw)) {
  area_col <- grep("area", names(va_raw), ignore.case = TRUE, value = TRUE)[1]
  cls_col  <- names(va_raw)[tolower(trimws(names(va_raw))) == "classification"][1]
  img_col  <- names(va_raw)[1]
  if (!is.na(area_col) && !is.null(cls_col) && !is.na(cls_col)) {
    area <- suppressWarnings(as.numeric(gsub(",", ".", va_raw[[area_col]])))
    cls  <- trimws(as.character(va_raw[[cls_col]]))
    img  <- as.character(va_raw[[img_col]])
    ok   <- !is.na(area)
    agg_by <- function(mask, fun) {
      m <- ok & mask
      if (!any(m)) return(setNames(numeric(0), character(0)))
      tapply(area[m], img[m], fun)
    }
    tissue   <- agg_by(cls == "Tissue", sum)            # µm² je Bild
    syn_n    <- agg_by(cls == "Synaptophysin", length)
    syn_mean <- agg_by(cls == "Synaptophysin", mean)
    syn_sum  <- agg_by(cls == "Synaptophysin", sum)
    sm_mean  <- agg_by(cls == "small vessel", mean)
    lg_mean  <- agg_by(cls == "large vessel", mean)
    imgs <- unique(img)
    gv   <- function(v, nm) as.numeric(v[nm])
    tum2 <- gv(tissue, imgs)
    st <- data.frame(
      Image                  = imgs,
      syn_struct_density     = gv(syn_n, imgs) / (tum2 / 1e6),
      syn_struct_area_mean   = gv(syn_mean, imgs),
      syn_area_fraction_pct  = gv(syn_sum, imgs) * 100 / tum2,
      small_vessel_area_mean = gv(sm_mean, imgs),
      large_vessel_area_mean = gv(lg_mean, imgs),
      stringsAsFactors = FALSE, check.names = FALSE
    )
    s <- merge(s, st, by = "Image", all.x = TRUE, sort = FALSE)
    cat("\n[Struktur-Metriken aus ALL_vessels_annotations gemergt.]\n")
  }
} else {
  cat("\n[ALL_vessels_annotations nicht gefunden -> Struktur-Dichte/-Flaeche uebersprungen.]\n")
}

cat("\n=== Bilder pro Tier x Bedingung x Panel ===\n")
print(table(s$animal, s$cond, s$panel))

n_animals <- nlevels(s$animal)
cat(sprintf("\n*** n = %d Tiere -> Inferenz nur als Pilot interpretieren! ***\n", n_animals))

# ----------------------------------------------------------------------------
# (A) Deskriptive Tier-Mittel (Bilder -> Tier gemittelt)
# ----------------------------------------------------------------------------
agg_animal <- function(var, panel = NULL) {
  d <- s; if (!is.null(panel)) d <- d[d$panel == panel, ]
  d <- d[!is.na(d[[var]]), ]
  aggregate(d[[var]], list(animal = d$animal, cond = d$cond), mean)
}
cat("\n=== (A) Tier-Mittel: NK-Dichte (IB4) ===\n");        print(agg_animal("NK_density_per_mm2", "IB4"))
cat("\n=== (A) Tier-Mittel: perivaskulärer Anteil % (IB4) ===\n"); print(agg_animal("perivascular_pct", "IB4"))
cat("\n=== (A) Tier-Mittel: Distanz NK->Synaptophysin (Syn) ===\n"); print(agg_animal("mean_dist_syn_um", "Syn"))
if ("syn_struct_density" %in% names(s)) {
  cat("\n=== (A) Tier-Mittel: Synaptophysin-Dichte /mm² (Syn) ===\n");       print(agg_animal("syn_struct_density", "Syn"))
  cat("\n=== (A) Tier-Mittel: Synaptophysin-Flaeche µm² (Syn) ===\n");       print(agg_animal("syn_struct_area_mean", "Syn"))
  cat("\n=== (A) Tier-Mittel: Synaptophysin-Flaechenanteil % (Syn) ===\n");  print(agg_animal("syn_area_fraction_pct", "Syn"))
  cat("\n=== (A) Tier-Mittel: small-vessel-Flaeche µm² (IB4) ===\n");        print(agg_animal("small_vessel_area_mean", "IB4"))
  cat("\n=== (A) Tier-Mittel: large-vessel-Flaeche µm² (IB4) ===\n");        print(agg_animal("large_vessel_area_mean", "IB4"))
}

# ----------------------------------------------------------------------------
# (B) Gemischte Modelle (Bild in Tier genestet)  – Vorlage für ausreichendes n
# ----------------------------------------------------------------------------
have_lmer <- requireNamespace("lmerTest", quietly = TRUE)
fit_mixed <- function(var, panel, label) {
  d <- s[s$panel == panel & !is.na(s[[var]]), ]
  cat(sprintf("\n--- (B) %s ~ cond + (1|animal)   [%s, %d Bilder] ---\n", label, panel, nrow(d)))
  if (!have_lmer) { cat("  lmerTest nicht installiert: install.packages('lmerTest')\n"); return(invisible()) }
  if (nlevels(droplevels(d$animal)) < 3)
    cat("  WARNUNG: <3 Tiere -> Zufallseffekt instabil, p-Werte rein illustrativ.\n")
  m <- try(lmerTest::lmer(d[[var]] ~ d$cond + (1 | d$animal)), silent = TRUE)
  if (inherits(m, "try-error")) { cat("  Modell nicht schätzbar.\n"); return(invisible()) }
  print(summary(m)$coefficients)
}
fit_mixed("NK_density_per_mm2", "IB4", "NK-Dichte OP vs N")
fit_mixed("perivascular_pct",   "IB4", "Perivaskulärer Anteil OP vs N")
if ("small_vessel_density_per_mm2" %in% names(s))
  fit_mixed("small_vessel_density_per_mm2", "IB4", "Small-vessel-Dichte OP vs N")
if ("large_vessel_density_per_mm2" %in% names(s))
  fit_mixed("large_vessel_density_per_mm2", "IB4", "Large-vessel-Dichte OP vs N")
fit_mixed("mean_dist_syn_um",   "Syn", "Distanz zu Synaptophysin OP vs N")
if ("syn_struct_density" %in% names(s)) {
  fit_mixed("syn_struct_density",     "Syn", "Synaptophysin-Dichte OP vs N")
  fit_mixed("syn_struct_area_mean",   "Syn", "Synaptophysin-Flaeche OP vs N")
  fit_mixed("syn_area_fraction_pct",  "Syn", "Synaptophysin-Flaechenanteil OP vs N")
  fit_mixed("small_vessel_area_mean", "IB4", "Small-vessel-Flaeche OP vs N")
  fit_mixed("large_vessel_area_mean", "IB4", "Large-vessel-Flaeche OP vs N")
}

# ----------------------------------------------------------------------------
# (C) Anreicherung: NK vs. Nicht-NK (Hintergrund)  – braucht nonNK_*-Spalten
# ----------------------------------------------------------------------------
if ("nonNK_perivascular_pct" %in% names(s)) {
  cat("\n=== (C) Anreicherung NK vs Nicht-NK (perivaskulärer Anteil, IB4) ===\n")
  d <- s[s$panel == "IB4" & !is.na(s$perivascular_pct) & !is.na(s$nonNK_perivascular_pct), ]
  # Langformat: je Bild zwei Werte (NK / nonNK)
  long <- data.frame(
    animal = rep(d$animal, 2), image = rep(d$Image, 2),
    celltype = factor(rep(c("NK", "nonNK"), each = nrow(d)), levels = c("nonNK", "NK")),
    perivasc = c(d$perivascular_pct, d$nonNK_perivascular_pct)
  )
  cat("Bild-weise gepaart (NK - nonNK), Mittel je Tier:\n")
  diff <- d$perivascular_pct - d$nonNK_perivascular_pct
  print(aggregate(diff, list(animal = d$animal), mean))
  cat(sprintf("Gepaarter Wilcoxon (Bilder, deskriptiv): "))
  print(suppressWarnings(wilcox.test(d$perivascular_pct, d$nonNK_perivascular_pct, paired = TRUE)))
  if (have_lmer) {
    cat("Gemischtes Modell perivasc ~ celltype + (1|animal/image):\n")
    m <- try(lmerTest::lmer(perivasc ~ celltype + (1 | animal/image), data = long), silent = TRUE)
    if (!inherits(m, "try-error")) print(summary(m)$coefficients)
  }

  cat("\n=== (C) Anreicherung: Distanz zu Synaptophysin, NK vs nonNK (Syn) ===\n")
  d2 <- s[s$panel == "Syn" & !is.na(s$mean_dist_syn_um) & !is.na(s$nonNK_mean_dist_syn_um), ]
  cat("Differenz NK - nonNK (negativ = NK näher am Nerv), Tier-Mittel:\n")
  print(aggregate(d2$mean_dist_syn_um - d2$nonNK_mean_dist_syn_um, list(animal = d2$animal), mean))
} else {
  cat("\n(C) übersprungen: Spalten nonNK_* fehlen.\n",
      "    -> QuPath-Skript measureAndExport.groovy erneut laufen lassen (liefert jetzt Hintergrund).\n")
}

# ----------------------------------------------------------------------------
# (D) Plots (falls ggplot2 vorhanden)
# ----------------------------------------------------------------------------
if (requireNamespace("ggplot2", quietly = TRUE)) {
  library(ggplot2)
  pdf(file.path(measdir, "analysis_plots.pdf"), width = 7, height = 5)
  ib4 <- s[s$panel == "IB4", ]
  print(ggplot(ib4, aes(cond, NK_density_per_mm2)) +
          geom_boxplot(outlier.shape = NA) +
          geom_jitter(aes(color = animal), width = 0.15, size = 2) +
          labs(title = "NK-Dichte OP vs N (IB4)", y = "NK / mm²", x = NULL) + theme_bw())
  print(ggplot(ib4, aes(cond, perivascular_pct)) +
          geom_boxplot(outlier.shape = NA) +
          geom_jitter(aes(color = animal), width = 0.15, size = 2) +
          labs(title = "Perivaskulärer Anteil OP vs N (IB4)", y = "% NK <20µm am Gefäß", x = NULL) + theme_bw())
  syn <- s[s$panel == "Syn", ]
  print(ggplot(syn, aes(cond, mean_dist_syn_um)) +
          geom_boxplot(outlier.shape = NA) +
          geom_jitter(aes(color = animal), width = 0.15, size = 2) +
          labs(title = "Distanz NK->Synaptophysin OP vs N", y = "mittlere Distanz [µm]", x = NULL) + theme_bw())
  # Struktur-Dichte & -Flaeche
  pbox <- function(df, yvar, title, ylab) {
    df <- df[!is.na(df[[yvar]]), ]
    if (nrow(df) == 0) return(invisible())
    print(ggplot(df, aes(cond, .data[[yvar]])) +
            geom_boxplot(outlier.shape = NA) +
            geom_jitter(aes(color = animal), width = 0.15, size = 2) +
            labs(title = title, y = ylab, x = NULL) + theme_bw())
  }
  if ("syn_struct_density" %in% names(s)) {
    pbox(syn, "syn_struct_density",     "Synaptophysin-Dichte OP vs N",        "Strukturen / mm²")
    pbox(syn, "syn_struct_area_mean",   "Synaptophysin-Flaeche OP vs N",        "mittlere Flaeche [µm²]")
    pbox(syn, "syn_area_fraction_pct",  "Synaptophysin-Flaechenanteil OP vs N", "% Gewebeflaeche")
    pbox(ib4, "small_vessel_area_mean", "Small-vessel-Flaeche OP vs N",         "mittlere Flaeche [µm²]")
    pbox(ib4, "large_vessel_area_mean", "Large-vessel-Flaeche OP vs N",         "mittlere Flaeche [µm²]")
  }
  dev.off()
  cat("\nPlots gespeichert: analysis_plots.pdf\n")
} else {
  cat("\nggplot2 nicht installiert -> keine Plots (install.packages('ggplot2')).\n")
}

cat("\nFertig.\n")
