---
title: Developers
has_children: true
nav_order: 4
---

# Developer Information

## Profile Development

BRouter offers [freely configurable routing
profiles](../features/costfunctions.md). To extend existing profiles or develop
you own profile see [Profile Developers Guide](profile_developers_guide.md) for
a technical reference.

### (Optional) Generate profile variants

This repository holds examples of BRouter profiles for many different
transportation modes. Most of these can be easily customized by setting
variables in the first `global` context of the profiles files.

An helper script is available in `misc/scripts/generate_profile_variants.sh`
to help you quickly generate variants based on the default profiles, to create
a default set of profiles covering most of the basic use cases.

## Run the BRouter HTTP server

Helpers scripts are provided in `misc/scripts/standalone` to quickly spawn a
BRouter HTTP server for various platforms.

* Linux/Mac OS: `./misc/scripts/standalone/server.sh`
* Windows (using Bash): `./misc/scripts/standalone/server.sh`
* Windows (using CMD): `misc\scripts\standalone\server.cmd`

The API endpoints exposed by this HTTP server are documented in the
[`brouter-server/src/main/java/btools/server/request/ServerHandler.java`](brouter-server/src/main/java/btools/server/request/ServerHandler.java)
file.

## Android Service

BRouter exposes an [Android
Service](https://developer.android.com/guide/components/services) which can be
used by other applications to calculate routes. See
[`brouter-routing-app/src/main/aidl/btools/routingapp/IBRouterService.aidl`](brouter-routing-app/src/main/aidl/btools/routingapp/IBRouterService.aidl)
for the interface definition.
