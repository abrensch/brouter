# OSM extracts (MapterhornRealWorldTest)

OSM route geometry for `MapterhornRealWorldTest`: the two real-world test cases suggested
by @afischerdev in issue #558, frozen as bundled fixtures so the suite runs fully offline
and deterministically (via `./gradlew integrationTest`). The suite builds .bef cells from
the shared real Mapterhorn WebP tiles (in
`brouter-map-creator/src/testFixtures/resources/mapterhorn/`), runs the full map-creation
pipeline over these extracts, and routes over the result.

## Files

- `badwildbad.pbf` — OSM extract of the Meisterntunnel corridor
  (bbox 8.541,48.737,8.560,48.763). © OpenStreetMap contributors, ODbL 1.0.
- `helgoland.pbf` — OSM extract of Helgoland (bbox 7.86,54.16,7.94,54.20).
  © OpenStreetMap contributors, ODbL 1.0.

The WebP terrain tiles this suite converts (Bad Wildbad 2145/1410+1411, Helgoland
2137+2138/1311+1312) are shared fixtures — see
`brouter-map-creator/src/testFixtures/resources/mapterhorn/README.md`.

## What the tests prove

- Tunnel: the DTM reads up to +146 m above the route over the tunnel hill; the tunnel
  machinery (NO_TUNNEL_BIT no-data interiors + engine portal interpolation) must keep that
  hill out of the routed profile, with the portal step bounded (~5 m with Mapterhorn vs
  ~10 m with legacy data).
- Helgoland: absent ocean tiles, the Unterland-Oberland cliff, and water-surface
  elevations all flow through the full pipeline into a plausible routed profile.

## Regenerating

OSM: Overpass bbox query, converted with `osmium cat <xml> -o <pbf>`.
