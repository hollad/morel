#!/bin/bash
#
# Generates an asciinema screencast from a script.
# You must install asciinema first; try 'brew install asciinema'.
# Warning: the default script destroys ~/smlj.
DIR=$(cd $(dirname $0); pwd)
FILE=${DIR}/script.txt
cat ${FILE} |
    ${DIR}/play.sh |
    asciinema rec --stdin --overwrite $(basename ${FILE} .txt).cast

# End build.sh
