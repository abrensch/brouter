---
parent: Developers
title: Mapterhorn elevation
---

# Mapterhorn elevation data (PMTiles)

`ConvertMapterhornTile` (brouter-map-creator) builds BRouter `.bef` elevation rasters
from [Mapterhorn](https://mapterhorn.com) PMTiles archives — planet-wide, openly
licensed terrain tiles (Terrarium-encoded lossless WebP, Web Mercator, max zoom 12,
about 13 m ground resolution at Alpine latitudes). This addresses the Mapterhorn part
of issue #558.

## Design

The converter deliberately emits the **existing** `.bef` format at 1 or 3 arc seconds
(5x5-degree cells, hgt naming), so `PosUnifier` and everything downstream run
unchanged — following the goal from #558 of one normalized elevation format with all
source-specific logic in the converter. The pieces:

- `PmTilesArchive` — PMTiles v3 reader written against the public spec; works on a
  local archive file or directly against the HTTP endpoint with range requests
  (`accept-ranges: bytes`), so the 700 GB planet archive never needs a full download.
  Byte-contiguous tiles (the archive is clustered in Hilbert order) are fetched with
  coalesced range reads.
- `TerrariumTileDecoder` — Terrarium RGB decoding (`(R*256 + G + B/256) - 32768`) with
  a hard lossless-VP8L check: lossy WebP would silently turn quantization noise into
  elevation error (1 count in the R channel is 256 m).
- `ConvertMapterhornTile` — resamples Web Mercator pixels onto the lat/lon grid with an
  area-weighted box filter (each output cell covers roughly 2.4 x 1.6 source pixels at
  1"; point sampling would alias straight into total-ascent). Writes are atomic, every
  written `.bef` is decoded back and compared pixel-for-pixel, and a tile cache
  directory is cryptographically bound to the archive it was filled from.

Usage:

```
java -Xmx3g -cp ... btools.mapcreator.ConvertMapterhornTile \
    https://download.mapterhorn.com/planet.pmtiles ./bef srtm_38_03 \
    -arcsec 1 -cache /var/tmp/mapterhorn
```

`all` instead of a cell name converts the world grid (resumable: existing cells are
skipped, failed cells reported at the end). `-bbox` windows a single cell for testing.

## Using it in a segment build pipeline

The output is a drop-in replacement for the SRTM/hgt-derived `.bef` tiles described in
[build segments](build_segments.md) — same 5x5-degree grid, same `srtm_x_y.bef` naming
(including the negative indices above 60N), same format. Point the converter's output
at the folder your `PosUnifier` call consumes and nothing else changes:

```
# 1. generate .bef tiles for the cells your region touches (1 arcsec)
java -Xmx3g -cp brouter-<version>-all.jar btools.mapcreator.ConvertMapterhornTile \
    https://download.mapterhorn.com/planet.pmtiles ./srtm1_bef srtm_38_03 \
    -arcsec 1 -cache ./mapterhorn-cache

# 2. the standard pipeline picks them up unchanged (as in process_pbf_planet.sh)
java ... btools.mapcreator.PosUnifier nodes55 unodes55 bordernids.dat bordernodes.dat \
    ./srtm1_bef ./srtm3_bef
```

Practical notes:

- **Which cells?** One `.bef` covers the same 5x5-degree area as one `.rd5`. A cell can
  be named either way: `srtm_38_03` (legacy index scheme) or the corner form `5,45`
  (south-west corner, both meaning lon 5..10, lat 45..50). If unsure, run `PosUnifier`
  once — it logs the tile names it looks for.
- **Everything is in the fat jar.** `brouter-<version>-all.jar` (the `fatJar` build)
  contains the converter and the bundled WebP decoder; no extra installation.
- **No download step.** The converter reads the 700 GB planet archive with HTTP range
  requests; a single 5x5-degree cell transfers roughly 1.5 GB at 1 arcsec. Use
  `-cache <dir>` so re-runs are free (the cache is bound to the archive build and
  refuses to mix versions). Alternatively, run against a local `planet.pmtiles` or a
  regional cut made with the official `pmtiles extract` tool.
- **Mixing sources.** Mapterhorn `.bef` files can sit in the primary folder with a
  CGIAR/hgt-derived pool as the `PosUnifier` fallback folder, or replace it entirely —
  the archive is planet-wide, so a fallback is optional.
- **Sea-level zeros.** Unlike the hgt converter (whose SRTM input overloads 0 as
  "void"), 0 m is kept as a valid elevation — coastal land and polders keep their
  height, and lake/sea surfaces carry the water level.

## Validation

**Vertical datum.** Mapterhorn heights are orthometric (MSL), same convention as
SRTM — verified on lake surfaces (Lac Leman reads 372.0 m against the known 372.0 m).
Water inside land-bearing tiles carries the water-surface elevation; fully-ocean tiles
are absent from the archive and become no-data.

**Against swissALTI3D (Swiss national LiDAR, +/-0.5 m).** Elevations were sampled every
20 m along two professionally measured Swiss Cycling Talent-ID test climbs
(Randenstrasse at Beggingen SH, Saxetenstrasse at Wilderswil BE; official start
markers) and compared with the swisstopo height service:

| metric (1" .bef vs swissALTI3D)  | Beggingen | Wilderswil |
|----------------------------------|-----------|------------|
| cumulative climb gain, 1.76 km   | -0.35 m   | +0.05 m    |
| point bias                       | +0.38 m   | +0.09 m    |
| point RMSE                       | 1.23 m    | 1.13 m     |
| worst single point               | 3.1 m     | 3.1 m      |

Cumulative gain — what routing sums into total-ascent — matches the national reference
to well under half a metre over both climbs. The worst single points sit where a 30 m
cell mixes the road with an embankment, inherent to any raster DEM at this resolution.
`SwissAlti3dValidationTest` freezes the original Mapterhorn tiles and the swissALTI3D
profiles as fixtures and re-checks these bounds offline in every CI run.

**End-to-end.** `MapterhornSegmentTest` (brouter-server) runs the full chain — PMTiles
to `.bef` to `PosUnifier` to `WayLinker` to an `rd5` — and routes over the produced
segment, asserting the elevation of every track point. A live check against the planet
archive routed a Dreieich track within 0.55 m of raw Mapterhorn data.

**Helgoland** (suggested as a test area in #558): the isolated island converts
correctly — Oberland plateau ~44 m at the lighthouse (true ~46 m), Unterland and Duene
in the 2-6 m range, surrounding sea absent/0. Along the hiking route suggested in
#558 (183 points, 2.3 km), Mapterhorn and brouter.de's new 1" elevation agree to
+1.9 m bias / 3.9 m RMSE everywhere except the ~40 m cliff transition between
Unterland and Oberland, where any two DEMs diverge by tens of metres under small
horizontal shifts on a near-vertical face.

**The tunnel case** (car route through the Meisterntunnel at Bad Wildbad, from #558):
the raw DTM reads up to **+146 m above the route** over the tunnel, because a faithful
DTM reports the hill the tunnel passes under — but that never reaches routing. BRouter
already handles tunnels: `WayLinker` marks nodes lying only on tunnel/bridge ways
(`NO_TUNNEL_BIT`/`NO_BRIDGE_BIT`), `OsmNodeP.getSElev()` reports them as no-data, and
the engine charges no elevation cost there and interpolates the output profile from
portal to portal. Verified end-to-end by building an rd5 for the area from Mapterhorn
data and routing the exact #558 case: filtered ascend 38 m (brouter.de with the new
1" data: 40 m), and the portal step — the residual artifact, caused by the portal
node's surface elevation mixing in the hillside above the cut — **shrinks from 9.75 m
to 5.0 m** with Mapterhorn. On open road the datasets agree to -1.4 m bias / 4.7 m
RMSE. Conclusion for #558: the tunnel mechanism composes correctly with Mapterhorn,
and the sharper source roughly halves the portal artifact.

Both cases are frozen as offline CI tests in `MapterhornRealWorldTest`
(brouter-server): real Mapterhorn tiles and real OSM extracts are bundled as fixtures,
the full pipeline builds an rd5, and the exact #558 routes are asserted — the tunnel
hill must stay out of the profile (max elevation, portal step, and ascent bounded),
and the Helgoland route must climb the cliff with a plausible island profile.

## Known characteristics

- Coastal/shore cells average land with adjacent water pixels; a road within one cell
  (~31 m) of a cliff edge can read up to ~20 m low. Inherent to raster DEMs at this
  resolution.
- Elevations above 8191.75 m overflow `ElevationRaster.getElevation`'s quarter-metre
  short — a pre-existing limitation shared with the hgt path.
- 1-arcsecond cells need roughly `-Xmx2g` (a 648 MB raster plus a transient copy during
  the read-back verification).
