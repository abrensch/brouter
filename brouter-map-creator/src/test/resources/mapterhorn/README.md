# Mapterhorn validation fixtures

Ground-truth regression fixtures for `SwissAlti3dValidationTest`: the converter is run
over REAL Mapterhorn terrain tiles and compared against the Swiss national LiDAR
reference along two professionally measured Swiss Cycling Talent-ID test climbs.

## Files

- `12_<x>_<y>.webp` — original Terrarium-encoded VP8L WebP tiles (zoom 12, 512 px) from
  the Mapterhorn planet archive (https://mapterhorn.com, May 2026 build). Terrain data
  compiled by Mapterhorn from open sources including swisstopo swissALTI3D; see
  https://mapterhorn.com/attribution/. Tiles 2145/1427+1428 cover the Randenstrasse
  climb at Beggingen (SH); tile 2137/1446 covers the Saxetenstrasse climb at
  Wilderswil (BE).
- `swissalti3d_<site>.csv` — `lon,lat,height`: 89 points at 20 m spacing along each
  climb road (OSM geometry, starting at the official Swiss Cycling start markers),
  with heights from the swisstopo height service (swissALTI3D, ±0.5 m accuracy),
  queried 2026-07-10. © swisstopo, Swiss open government data.

## Regenerating

Fetch tiles from `https://tiles.mapterhorn.com/12/{x}/{y}.webp`. Reference heights come
from `https://api3.geo.admin.ch/rest/services/height?easting=E&northing=N` (LV95) at the
CSV coordinates. The climbs are the roads through the official start points published on
https://www.swiss-cycling.ch/de/infocenter/ausdauer-teststrecken/ (Beggingen
47.7574449 N 8.5482389 E, Wilderswil 46.6575602 N 7.8536613 E), followed uphill.
