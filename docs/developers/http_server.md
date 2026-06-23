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

For request bodies, use `application/x-www-form-urlencoded` (preferred) or
`text/plain`. This is useful for large `nogos`, `polylines` or `polygons`
payloads that would otherwise hit browser, proxy or server URL-length limits.

The standalone startup script now defaults to request bodies slightly above
5 MiB:

* `BROUTER_MAX_REQUEST_LENGTH=6291456`
* `BROUTER_JAVA_XMX=256M`
* `BROUTER_JAVA_XMS=256M`
* `BROUTER_JAVA_XMN=16M`

You can override those values via environment variables, for example:

```sh
BROUTER_MAX_REQUEST_LENGTH=8388608 BROUTER_JAVA_XMX=512M ./misc/scripts/standalone/server.sh
```

Containerized deployments can pass the same environment variables through
their startup wrapper before invoking `misc/scripts/standalone/server.sh`.

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
