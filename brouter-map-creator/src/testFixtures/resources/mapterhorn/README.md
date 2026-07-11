# Mapterhorn terrain tile fixtures (shared)

Real Terrarium-encoded VP8L WebP terrain tiles from the Mapterhorn planet archive
(https://mapterhorn.com), bundled as **shared test fixtures** so every module can decode
them from a single copy. They sit on the test/integrationTest classpath of any module
that depends on `testFixtures(project(':brouter-map-creator'))` (currently
brouter-map-creator itself and brouter-server) and are loaded from the classpath as
`/mapterhorn/12_<x>_<y>.webp`.

All tiles are zoom 12, 512 px, Terrarium-encoded. Terrain data compiled by Mapterhorn
from open sources including swisstopo swissALTI3D; attribution:
https://mapterhorn.com/attribution/ (May 2026 build).

## Tiles

Swiss climb validation — `SwissAlti3dValidationTest`
(brouter-map-creator `src/integrationTest`):

- `12_2145_1427.webp`, `12_2145_1428.webp` — Randenstrasse climb at Beggingen (SH)
- `12_2137_1446.webp` — Saxetenstrasse climb at Wilderswil (BE)

Real-world routing regression — `MapterhornRealWorldTest`
(brouter-server `src/integrationTest`):

- `12_2145_1410.webp`, `12_2145_1411.webp` — Meisterntunnel at Bad Wildbad
- `12_2137_1311.webp`, `12_2137_1312.webp`, `12_2138_1311.webp`, `12_2138_1312.webp`
  — Helgoland (the tiny ones are mostly ocean)

## Regenerating

Fetch tiles from `https://tiles.mapterhorn.com/12/{x}/{y}.webp`.

The non-tile fixtures each suite compares against live next to that suite:

- swissALTI3D reference CSVs — `brouter-map-creator/src/integrationTest/resources/mapterhorn/`
- OSM `.pbf` extracts — `brouter-server/src/integrationTest/resources/mapterhorn/`
