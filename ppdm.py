from pexpect import spawn
from copy import deepcopy
import random
import argparse
import sys


def ssh(hosts, username, password, cmdGenerator):
    newPromptRegex = '% '
    for host in hosts:
        proc = spawn('ssh %s@%s' % (username, host), logfile=sys.stdout, searchwindowsize=10)
        proc.expect('password:')
        proc.sendline(password)
        proc.expect(newPromptRegex)
        for cmd in cmdGenerator(hosts):
            proc.sendline(cmd)
            proc.expect(newPromptRegex)
        proc.close()


def link(hosts):
    hostsCopy = deepcopy(hosts)
    random.shuffle(hostsCopy)
    mkdir = 'ls ppdm | grep $HOSTNAME || mkdir ppdm/$HOSTNAME'
    writeFile = 'echo "%s" >ppdm/$HOSTNAME/peers.list' % '\n'.join(hostsCopy)
    return [mkdir, writeFile]


def testClientDaemon(hosts):
    startDaemon = 'nohup java -jar ppdm/daemon/target/scala-2.10/daemon-assembly-1.0.jar &'
    recordPID = 'echo $! > ppdm/daemon.pid'
    runClient = 'java -jar ppdm/client/target/scala-2.10/client-assembly-0.1-SNAPSHOT.jar'
    stopDaemon = 'kill `cat ppdm/daemon.pid`'
    return [startDaemon, recordPID, runClient, stopDaemon]


if __name__ == "__main__":
    actions = {}
    actions['link'] = (link, "makes each machine aware of the others' existence")
    actions['test'] = (testClientDaemon, "tests client-daemon communication")
    parser = argparse.ArgumentParser()
    parser.add_argument("username", help="Username on remote machines")
    parser.add_argument("password", help="Password on remote machines")
    actionsHelp = '; '.join(['%s, which %s' % (item[0], item[1][1]) for item in actions.items()])
    parser.add_argument("action", help="The action you want to take. Available actions are: %s" % actionsHelp)
    parser.add_argument("hosts", help="Comma-seperated list of machines to act on")
    args = parser.parse_args()
    if args.action not in actions:
        print "Don't know action %s" % args.action
    hosts = args.hosts.split(',')
    cmdGenerator = actions[args.action][0]
    ssh(hosts, args.username, args.password, cmdGenerator)
