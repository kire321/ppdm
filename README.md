PPDM: Privacy Preserving Data Mining
====================================

PPDM is useful in situations where you have data distributed across multiple computers.
It lets you calculate aggregate statistics, but each computer never transmits its data anywhere else, so its still private.

Specifically, you provide a shell script that outputs a number, and run ppdm.py.
ppdm.py will run the script on each computer that you specify and sum the outputs securely.
It will write the total to a file.

Running the demo application
----------------------------

Using bash, in your home directory on each computer you want to be able to ask for secure sums, run this command:

`git clone http://github.com/kire321/ppdm && cd ppdm && source mkVenv.sh`

This will create a folder called `ppdm` containing everything you need.
`(venv)` should be prepended to your prompt. This indicates that the name `python` refers to the Python you just downloaded.
Type `which python`, which tells you where the name `python` points. You should see that it points inside the `ppdm` directory you just created.
Take a look at `one.sh` by typing `less one.sh`. This is a simple program that always outputs one.
If we securely sum with this script, it will count the number of machines involved in the secure summing.
Type `q` to exit `less`. Run:

`python ppdm.py secureSum username password hostname1,hostname2,hostname3 one.sh`

substituting username, password, and hostnameX as appropriate. There can by any number of hostnames.
A file called `sum` should have appeared in the `ppdm` directory on hostname1.
You can loook look at it by typing `less sum`.
It should contain the number of hostnames you entered.

More useful applications
------------------------	

That was a really fancy way of counting the number of hostnames you entered.
What can this do that's useful?
Most aggregate statistics can be found using PPDM.
For instance, to find the standard deviation in people's incomes, where each machine has a different piece of an income-containing database, you could:

- write a script, call it `secret`, that queries the database and finds the sum of everybody's income on that machine
- run PPDM with `one.sh`, to count how many people are in the databases
- run PPDM with `secret`, to find the total income. Divide by the count to find the average.
- write a script that returns `(avg - secret)^2`. Call it `variance`. 
- run PPDM with `variance`, to find the total variance. Square root to find the standard deviation.

Happy data mining!
