---
parent: Developers
title: HTTP server
---

# Run the BRouter HTTP server

Helpers scripts are provided in `misc/scripts/standalone` to quickly spawn a
BRouter HTTP server for various platforms.

* Linux/Mac OS: `./misc/scripts/standalone/server.sh`
* Windows (using Bash): `./misc/scripts/standalone/server.sh`
* Windows (using CMD): `misc\scripts\standalone\server.cmd`

The API endpoints exposed by this HTTP server are documented in the
`ServerHandler.java`

Please see also [IBRouterService.aidl](./android_service.md) for calling parameter.
