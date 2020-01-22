#!/bin/sh
cd "$(dirname "$0")"
cd ../profiles2

# Car variants
sed -e "s/^assign[[:space:]]\+vmax.*\(#.*\)/assign vmax = 160 \1/" car-vario.brf > car-fast.brf
sed -e "s/^assign[[:space:]]\+vmax.*\(#.*\)/assign vmax = 90 \1/" car-vario.brf > car-eco.brf

# Fastbike variants
sed -e "s/^assign[[:space:]]\+allow_motorways.*\(#.*\)/assign allow_motorways = true \1/" fastbike.brf > fastbike-asia-pacific.brf
sed -e "s/^assign[[:space:]]\+consider_traffic.*\(#.*\)/assign consider_traffic = true \1/" fastbike.brf > fastbike-lowtraffic.brf

# Trekking variants
sed -e "s/^assign[[:space:]]\+ignore_cycleroutes.*\(#.*\)/assign ignore_cycleroutes = true \1/" trekking.brf > trekking-ignore-cr.brf
sed -e "s/^assign[[:space:]]\+allow_ferries.*\(#.*\)/assign allow_ferries = false \1/" trekking.brf > trekking-noferries.brf
sed -e "s/^assign[[:space:]]\+allow_steps.*\(#.*\)/assign allow_steps = false \1/" trekking.brf > trekking-nosteps.brf
sed -e "s/^assign[[:space:]]\+consider_elevation.*\(#.*\)/assign consider_elevation = false \1/" trekking.brf > trekking-steep.brf
sed -e "s/^assign[[:space:]]\+avoid_unsafe.*\(#.*\)/assign avoid_unsafe = true \1/" trekking.brf > safety.brf
