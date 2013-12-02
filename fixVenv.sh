#!/bin/bash
sed -i s:VIRTUAL_ENV=\.\*:VIRTUAL_ENV=\"`pwd`/virtualenv\": virtualenv/bin/python
sed -i 1s:^.*$:#!`pwd`/virtualenv/bin/python: virtualenv/bin/fab
