---
parent: Features
---

# Ofline routing on Android phones

BRouter is first and foremost an offline tool. It runs on any Android phone. The
online version offered here is just for a trial and for convenience. While many
smartphone routing software use online services to do the actual route
calculations, the advantages of the offline approach are evident:

- It is more reliable, data connection problems and server-side problems are no
  issue

- It works in foreign countries where data connections are either not available
  or very expensive

- It does not raise any data privacy issues

- You can use a cheap dedicated, second phone for navigation, without having to
  put your business smartphone on an untrusted bike mount and run it's battery
  low

The downside is that advanced route calculations are difficult to do on a
smartphone with limited computing and memory resources, which may lead
developers to implement simplifications into the routing algorithm that affect
the quality of the routing results. BRouter always does it's best on the
quality, but has a processing time that scales quadratic with distance, leading
to a limit at about 150km in air-distance, which is enough for a bikers daytrip.
