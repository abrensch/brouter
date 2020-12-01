#!/bin/bash

REPOSITORY="can/brouter"
VERSION="1.0b"

BRANCH=$(git rev-parse --abbrev-ref HEAD)

if [ "$BRANCH" = "master" ]; then
    TAGS=("$VERSION" "latest")
else
    TAGS=("${VERSION}-${BRANCH}" "dev")
fi;

docker build ${TAGS[@]/#/--tag $REPOSITORY:} .
