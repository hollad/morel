#!/bin/bash
#
# Generates an asciinema screencast from a script.
# You must install asciinema first; try 'brew install asciinema'.
# Warning: the default script destroys ~/smlj.
DIR=$(dirname $0)
FILE=${DIR}/script.txt
cat ${FILE} |
    ${DIR}/play.sh |
    asciinema rec --stdin --overwrite $(basename ${FILE} .txt).cast

# End build.sh
