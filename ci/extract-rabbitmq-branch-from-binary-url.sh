#!/bin/bash
BINARY_URL=$1

# e.g. https://github.com/rabbitmq/server-packages/releases/download/alphas.1731926502914/rabbitmq-server-generic-unix-4.1.0-alpha.047cc5a0.tar.xz
FILENAME=$(basename $BINARY_URL)
VERSION=$(echo $FILENAME | sed 's/rabbitmq\-server\-generic\-unix\-//' | sed 's/\.tar\.xz//')

MAJOR=$(echo $VERSION | cut -f1 -d'.')
MINOR=$(echo $VERSION | cut -f2 -d'.')
BRANCH=$MAJOR$MINOR

echo "$BRANCH"

