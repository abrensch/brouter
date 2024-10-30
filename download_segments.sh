#!/bin/bash
BASE_URL=https://brouter.de/brouter/segments4/
rd5_files=()
if [ "$#" -lt 1 ]; then
    echo "Please provide a target directory"
    exit
fi


if [ "$#" -eq 2 ] && [ "$2" == "germany" ]; then
    # Germany
    rd5_files=("E5_N50.rd5" "E10_N50.rd5" "E5_N45.rd5" "E10_N45.rd5")
else
    # all
    for l in $(curl http://brouter.de/brouter/segments4/ | grep -Po 'href="\K.*.rd5?(?=")');
        do
            rd5_files+=("$l");
        done
fi

for i in "${rd5_files[@]}"
do
   echo "Downloading $BASE_URL$i to $1/$i"
   curl $BASE_URL$i -o $1/$i;
done
