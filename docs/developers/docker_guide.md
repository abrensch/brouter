---
parent: Developers
---

# Docker help

In addition to the intro in readme.md about Docker, here are a few commands for daily work with the system.

Build the Docker with a version based name
```
$ docker build -t brouter-1.7.2 .
```

Start Docker with name additional to the Docker image name.
Please note:
The path for segments are on a Windows system.
Here the port used in server.sh is published.
```
$ docker run --rm -v "I:/Data/test/segment4":/segments4 --publish 17777:17777 --name brouter-1.7.2 brouter-1.7.2
```

and with a mount for profiles as well
```
$ docker run --rm -v "I:/Data/test/segment4":/segments4 -v "I:/Data/test/profiles2":/profiles2 --name brouter-1.7.2 brouter-1.7.2
```

Show the running Docker processes
```
$ docker ps

output:
CONTAINER ID   IMAGE           COMMAND                  CREATED         STATUS         PORTS                      NAMES
b23518e8791d   brouter-1.7.2   "/bin/sh -c /bin/ser…"   5 minutes ago   Up 5 minutes   0.0.0.0:17777->17777/tcp   brouter-1.7.2
```

Fire some curl or wget commands to test if is realy useful running.

Stop a running Docker image - please note, this only works when starts docker image with name, see above
```
$ docker stop brouter-1.7.2
```

Docker available images

```
$ docker images

output:
REPOSITORY                     TAG                                        IMAGE ID       CREATED              SIZE
brouter-1.7.2                  latest                                     e39703dec2fa   2 hours ago          410MB
brouter                        latest                                     728f122c7388   3 hours ago          410MB
```

Control
## Docker with docker-compose

Use a git clone to build a local folder with last version.
Make a Docker container with version number inside your repository folder.
```
$ docker build -t brouter:1.7.2 .

$ docker images

REPOSITORY                     TAG                                        IMAGE ID       CREATED          SIZE
brouter-1.7.2                  latest                                     e39703dec2fa   3 hours ago      410MB
brouter                        1.7.2                                      e39703dec2fa   3 hours ago      410MB
```

Start a container with composer
This needs a docker config file docker-compose.yml
Something like this:
```
version: '2'
services:
  brouter:
    image: brouter:1.7.2
    restart: unless-stopped
    ports:
      - 17777:17777
    volumes:
      - type: bind
        source: "I:/Data/test/segment4"
        target: /segments4
#      - type: bind
#        source: "I:/Data/test/profiles2"
#        target: /profiles2
```

Start it
```
$ docker-compose up -d
```

Have a look what is running
```
$ docker-compose ps
or
$ docker-compose ls
or
$ docker ps
```


Now update your repository (git pull) and build your Docker container with the new version tag
```
$ docker build -t brouter:1.7.3 .

$ docker images

REPOSITORY                     TAG                                        IMAGE ID       CREATED         SIZE
brouter                        1.7.3                                      5edc998cb5ae   3 hours ago     410MB
brouter-1.7.2                  latest                                     e39703dec2fa   6 hours ago     410MB
```

Replace the version in Docker config file docker-compose.yml
```
    image: brouter:1.7.3
```

Stop old running container and start the new one
```
$ docker-compose down

$ docker-compose up -d
```
