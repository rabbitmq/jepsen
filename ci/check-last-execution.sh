#!/bin/bash

CURRENT_TIME=$(date '+%s')  
RABBITMQ_BRANCH=$(ci/extract-rabbitmq-branch-from-binary-url.sh $BINARY_URL)
LAST_EXECUTION_ARTIFACT="last-execution-jepsen-rabbitmq-$RABBITMQ_BRANCH"

echo "UTC is $(date --utc --rfc-3339=seconds --date=@$CURRENT_TIME)"

gh run --repo rabbitmq/jepsen download --name $LAST_EXECUTION_ARTIFACT

if [ -e last-execution.txt ]
then
    LAST_EXECUTION=$(cat last-execution.txt)
    echo "Last execution was $(date --utc --rfc-3339=seconds --date=@$LAST_EXECUTION)"
    DIFF=$(($CURRENT_TIME - $LAST_EXECUTION))
    echo "Last execution was" $(date -ud "@$DIFF" +"$(( $DIFF/3600/24 )) day(s) %H hour(s) %M minute(s) %S second(s) ago")
    LIMIT=86400
    echo "Limit is" $(date -ud "@$LIMIT" +"$(( $LIMIT/3600/24 )) day(s) %H hour(s) %M minute(s) %S second(s)")
    if [ "$DIFF" -gt "$LIMIT" ]; then
        echo "Limit exceeded, allowing new execution."
        echo $CURRENT_TIME > last-execution.txt
        ALLOW_EXECUTION="true"
    else
        echo "Limit not exceeded, skipping new execution."
        ALLOW_EXECUTION="false"
    fi
else
    echo $CURRENT_TIME > last-execution.txt
    ALLOW_EXECUTION=true
fi

if [ "$SKIP_CHECK" = true ] ; then
    echo "Configured to skip check, ignoring result and forcing execution"
    echo $CURRENT_TIME > last-execution.txt
    ALLOW_EXECUTION="true"
fi

echo "Allow execution? $ALLOW_EXECUTION"
echo "allow_execution=$ALLOW_EXECUTION" >> $GITHUB_OUTPUT
echo "LAST_EXECUTION_ARTIFACT=$LAST_EXECUTION_ARTIFACT" >> $GITHUB_ENV
