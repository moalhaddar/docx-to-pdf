#!/bin/bash

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
NC='\033[0m'

if [ "$#" -ne 2 ]; then
    echo -e "${RED}Error: Missing arguments.${NC}"
    echo -e "${YELLOW}Usage:${NC} $0 <input_path> <output_path>"
    echo -e "${YELLOW}Example:${NC} $0 /path/to/input/file.docx /path/to/output/file.pdf"
    exit 1
fi

input_path="$1"
output_path="$2"

if [ ! -e "$input_path" ]; then
    echo -e "${RED}Error: Input path '$input_path' does not exist.${NC}"
    exit 2
fi

echo -e "${YELLOW}Watching and converting $input_path${NC}"

while true; do 
    while inotifywait -qq -e close "$input_path"; do
        curl -s \
        --location 'http://localhost:8080/pdf' \
        --form 'document=@'"$input_path"'' \
        --output "$output_path"

        echo -e "${GREEN}Converted${NC}"; 
    done;
done;
