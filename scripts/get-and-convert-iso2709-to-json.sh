#!/bin/bash
TYPE=$1
ID=$2
if [[ "$TYPE" == "" ]] || [[ "$ID" == "" ]]; then
    echo "Usage $(basename $0) <bib|auth> ID"
    exit
fi
set -e -v

WHELKS_BUILD=../whelk-extensions/build
MARCPATH=/tmp/$TYPE-$ID.iso2709
JSONPATH=/tmp/$TYPE-$ID.json
curl -s http://libris.kb.se/data/$TYPE/$ID?format=ISO2709 -o $MARCPATH

$(dirname $0)/convert-iso2709-to-json.sh $MARCPATH > $JSONPATH
