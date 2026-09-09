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

Routing requests to `/brouter` can use either:

* `GET` with the existing URL query parameters
* `POST` or `PUT` with the same parameter string in the request body

Request bodies are disabled by default. To enable them, set the system
property `usePOSTRequests` to `true` in the start script
(`misc/scripts/standalone/server.sh`):

```sh
JAVA_OPTS="-Xmx128M -Xms128M -Xmn8M -DmaxRunningTime=300 -DuseRFCMimeType=false -DusePOSTRequests=true"
```

For request bodies, use `application/x-www-form-urlencoded` (preferred) or
`text/plain`. This is useful for large `nogos`, `polylines` or `polygons`
payloads that would otherwise hit browser, proxy or server URL-length limits.

The accepted body size is capped by the `maxRequestLength` system property
in bytes (default 1000000), for example `-DmaxRequestLength=6291456` to
allow bodies slightly above 5 MiB.

For large polygon uploads it is usually easier to put the full parameter string
into a file and send it with `PUT`:

```sh
curl -X PUT http://localhost:17777/brouter \
	-H 'Content-Type: application/x-www-form-urlencoded; charset=UTF-8' \
	--data-binary @request-body.txt
```

Example `request-body.txt` content:

```text
lonlats=8.723037,50.000491|8.712737,50.002899&profile=trekking&alternativeidx=0&format=geojson&polygons=8.81,50.05,8.8101,50.0501,8.8102,50.0502
```

Please see also [IBRouterService.aidl](./android_service.md) for calling parameter.
