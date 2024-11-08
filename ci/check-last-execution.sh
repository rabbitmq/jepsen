#!/bin/bash

CURRENT_TIME=$(date '+%s')  

echo "UTC is $(date --utc --rfc-3339=seconds --date=@$CURRENT_TIME)"

if [ -e last-execution.txt ]
then
    LAST_EXECUTION=$(cat last-execution.txt)
    echo "Last execution was $(date --utc --rfc-3339=seconds --date=@$LAST_EXECUTION)"
    echo $LAST_EXECUTION
    DIFF=$(($CURRENT_TIME - $LAST_EXECUTION))
    echo $DIFF
    echo "Last execution was" $(date -ud "@$DIFF" +"$(( $DIFF/3600/24 )) day(s) %H hour(s) %M minute(s) %S second(s) ago")
    LIMIT=60
    if [ "$DIFF" -gt "$LIMIT" ]; then
        echo "Limit exceeded, allowing new execution."
        echo $CURRENT_TIME > last-execution.txt
        NEW_EXECUTION=true
    else
        echo "Limit exceeded, skipping new execution."
        NEW_EXECUTION=false
    fi
else
    echo $CURRENT_TIME > last-execution.txt
    NEW_EXECUTION=true
fi

cat last-execution.txt
echo "New execution? $NEW_EXECUTION"
