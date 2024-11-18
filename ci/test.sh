#!/bin/bash

RABBITMQ_BRANCH=$(ci/extract-rabbitmq-branch-from-binary-url.sh $BINARY_URL)

echo $BINARY_URL 
echo $RABBITMQ_BRANCH
