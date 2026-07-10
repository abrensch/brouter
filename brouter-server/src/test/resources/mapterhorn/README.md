# Real-world elevation test fixtures (issue #558 cases)

Fixtures for `MapterhornRealWorldTest`: the two test cases suggested by @afischerdev
in issue #558, frozen for offline CI.

## Files

- `12_<x>_<y>.webp` — original Terrarium-encoded VP8L WebP tiles (zoom 12, 512 px) from
  the Mapterhorn planet archive (https://mapterhorn.com, May 2026 build; attribution:
  https://mapterhorn.com/attribution/). Tiles 2145/1410+1411 cover the Meisterntunnel
  at Bad Wildbad; tiles 2137+2138/1311+1312 cover Helgoland (the tiny ones are mostly
  ocean).
- `badwildbad.pbf` — OSM extract of the Meisterntunnel corridor
  (bbox 8.541,48.737,8.560,48.763). © OpenStreetMap contributors, ODbL 1.0.
- `helgoland.pbf` — OSM extract of Helgoland (bbox 7.86,54.16,7.94,54.20).
  © OpenStreetMap contributors, ODbL 1.0.

## What the tests prove

- Tunnel: the DTM reads up to +146 m above the route over the tunnel hill; the tunnel
  machinery (NO_TUNNEL_BIT no-data interiors + engine portal interpolation) must keep
  that hill out of the routed profile, with the portal step bounded (~5 m with
  Mapterhorn vs ~10 m with legacy data).
- Helgoland: absent ocean tiles, the Unterland-Oberland cliff, and water-surface
  elevations all flow through the full pipeline into a plausible routed profile.

## Regenerating

Tiles: `https://tiles.mapterhorn.com/12/{x}/{y}.webp`. OSM: Overpass bbox query,
converted with `osmium cat <xml> -o <pbf>`.
