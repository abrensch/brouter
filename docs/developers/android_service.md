---
parent: Developers
---

# Android Service

BRouter exposes an [Android
Service](https://developer.android.com/guide/components/services) which can be
used by other applications to calculate routes. See `IBRouterService.aidl` for
the interface definition.

Since version 1.6.3 the interface has more options.
First of all the positions a stored in one parameter 'lonlats'. This equivalent to the BRouter web and enables more features.
All apps can ask direct for the prefered output. Please use 'timode[1..8]' instead of 'turnInstructionFormat=[osmand,locus]'.
