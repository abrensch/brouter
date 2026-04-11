---
parent: Using BRouter
title: Skating
---

# BRouter Profile for Long-Distance Inline Skating

When we started skating long-distance point-to-point routes, the experience was sometimes tough, busy roads with constant traffic, main roads that cycling profiles happily sent us over, and routes that just weren't enjoyable. We wanted to find tracks we could actually like: quiet paths where you can switch off and unwind, steep short uphills followed by gentle downhills where you cruise effortlessly for kilometers, safe roads away from traffic, while still taking a reasonably optimal route between two points.

Standard cycling or walking profiles in BRouter don't understand what makes a good skating route. A steep cobblestone path that works on a bike can be dangerous or slow on skates. So we built our own profile, calibrated against real data from our long-distance sessions skating across Switzerland and France.

## How to Use

1. Go to [brouter-web](https://brouter.de/brouter-web/)
2. Select **Profile** tab on the right and paste the contents of the `.brf` file
3. Set your start and end points, the route will favor skating friendly paths

## Inline Skating Routing differences

* **Surface** Skaters need smooth asphalt or concrete. Cobblestones are rough but tolerable for short stretches. Unpaved is avoided.

* **Elevation** Uphills are exhausting, and (too) steep downhills force constant braking. A gentle 2% descent is the sweet spot, free speed with no effort. The data shows that even a 10% downhill only gives you 22 km/h, barely faster than cruising flat at 20 km/h, because you tend to brake, especially on long distance routes when you are tired and on unknown terrain.

* **Sidewalks** On skates, hopping onto the sidewalk is easy. The profile uses them when beneficial.

* **Quiet roads** On a bike you can keep up with traffic. On skates you're a bit slower, take more width and more vulnerable. Quiet residential streets and dedicated paths let you relax and enjoy the ride.

## Routing preferences

| | Sweet spot | Acceptable | Penalized |
|---|---|---|---|
| **Surface** | Asphalt, concrete | Paving stones, cobblestone (short) | Unpaved, dirt |
| **Road type** | Cycleways, residential, footways | Tertiary, secondary | Primary, trunk |
| **Downhill** | -2 to -5% (22 km/h, effortless) | -5 to -10% (fast but steep) | > -10% (braking caps you at ~22 km/h avg) |
| **Flat** | -1.5 to +1.5% (20 km/h cruise) | | |
| **Uphill** | Short steep > long gentle | 5-10% (11 km/h, +7 bpm Strava HR) | Long gentle 2-5% wastes more time than short steep >10% for same elevation |

## Calibration

Every parameter is calibrated against Strava data from 289 km across two long-distance routes (Geneva-Annecy and Tour du Lac Léman):

* Flat speed model targets 20 km/h, matching the measured 20 km/h average
* Downhill/uphill cost ratio (0.37) from speed-by-slope analysis
* Elevation cutoff at 1.5%, confirmed by data showing speed barely changes at gentle gradients

We improve this profile by comparing planned routes against routes actually skated, and adjusting parameters where the data tells us to. The router won't produce a perfect path, but it gets you in the ballpark. On the day you still have to look around and adjust, but on unknown long distance routes that baseline is incredibly useful. It also helps optimize known routes and discover better paths, like the [Tour du Lac Léman](https://downhillwings.ch/guides/tour-du-lac-leman.html) section from Thonon to Geneva.