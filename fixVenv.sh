#!/bin/bash
sed -i s:VIRTUAL_ENV=\.\*:VIRTUAL_ENV=\"`pwd`/virtualenv\": virtualenv/bin/activate
sed -i 1s:^.*$:#!`pwd`/virtualenv/bin/python: virtualenv/bin/fab
