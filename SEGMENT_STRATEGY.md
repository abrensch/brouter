# Segment Strategy for Production Routing

## Overview

BRouter requires map segments (`.rd5` files) for routing. There are two sources:

1. **Local segments** - Generated from OSM extracts (e.g., Norway-only)
2. **Online segments** - Downloaded from brouter.de (full planet/Europe coverage)

## Comparison

### Local Segments
- **Pros:**
  - Can include POI nodes (water points, cabins) if generated with modified NodeFilter
  - Full control over generation process
  - No download required
  
- **Cons:**
  - Much smaller (10-60x smaller than online segments)
  - Missing significant road network data
  - May have connectivity gaps in remote areas
  - Requires full OSM extract and regeneration process

### Online Segments (brouter.de)
- **Pros:**
  - Complete road network coverage (10-60x more data)
  - Reliable connectivity across all areas
  - No generation required
  - Regularly updated
  
- **Cons:**
  - May not include POI nodes (standalone nodes with tags)
  - Requires internet connection for download
  - Larger file sizes

## Recommendation

**Use online segments for production routing** because:
1. They have complete road network data (enables successful routing)
2. They are regularly updated and maintained
3. They work reliably across all areas

**POI Support:**
- Online segments may not include POI nodes (water points, cabins)
- POI search will work if online segments include POI nodes
- If POIs are not found, routing still works (just without POI information)
- For full POI support, use locally generated segments with modified NodeFilter

## Implementation

The routing scripts (`generate_car_route.sh`, `generate_cycling_route.sh`, etc.) now:
1. Check for online segments via `download_online_segments.sh`
2. Download segments if missing or suspiciously small (< 1MB)
3. Use online segments by default for better coverage
4. Fall back to local segments if download fails

## Segment Download

Use `download_online_segments.sh` to download required segments:

```bash
./download_online_segments.sh test_segments
```

This script:
- Downloads segments from brouter.de if missing
- Replaces segments that are too small (< 1MB, indicating missing data)
- Preserves larger local segments

## POI Testing

To test if online segments include POI nodes:
1. Generate a route with POI search enabled
2. Check waypoint names in the GPX file
3. If waypoints show "Water: Xm" or "Cabin: Xm", POIs are included
4. If waypoints only show rest stop distances, POIs are not included

## Segment Sizes (Example)

| Segment | Local | Online | Ratio |
|---------|-------|--------|-------|
| E30_N65 | 136K  | 3.6M   | 26x   |
| E25_N65 | 616K  | 9.1M   | 15x   |
| E10_N55 | 9.9M  | 61M    | 6x    |

The size difference indicates online segments include much more road network data.

