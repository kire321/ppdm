#!/bin/bash
set -e

curl -O https://pypi.python.org/packages/source/v/virtualenv/virtualenv-1.10.tar.gz
tar xvfz virtualenv-1.10.tar.gz
python virtualenv-1.10/virtualenv.py venv
source venv/bin/activate
pip install fabric
pip install pexpect
fab --help
