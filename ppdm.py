from pexpect import spawn
from copy import deepcopy
import random
import argparse
import sys


def ssh(hosts, username, password, cmdGenerator):
    for host in hosts:
        proc = spawn('ssh %s@%s' % (username, host), logfile=sys.stdout, searchwindowsize=10)
        proc.expect('password:')
        proc.sendline(password)
        proc.expect_exact('% ')
        for cmd in ['bash', 'cd ppdm'] + cmdGenerator(hosts):
            proc.sendline(cmd)
            proc.expect_exact('$ ')
        proc.close()


def link(hosts):
    hostsCopy = deepcopy(hosts)
    random.shuffle(hostsCopy)
    mkdir = 'ls | grep $HOSTNAME || mkdir $HOSTNAME'
    writeFile = 'echo "%s" >$HOSTNAME/peers.list' % '\\n'.join(hostsCopy)
    return [mkdir, writeFile]


startDaemonCmd = 'java -jar daemon/target/scala-2.10/daemon-assembly-1.0.jar >$HOSTNAME/daemon.log 2>$HOSTNAME/daemon.log &'
recordPID = 'echo $! > $HOSTNAME/daemon.pid'
runClient = 'java -jar client/target/scala-2.10/client-assembly-0.1-SNAPSHOT.jar'
stopDaemonCmd = 'kill `cat $HOSTNAME/daemon.pid`'


def testClientDaemon(hosts):
    return [startDaemonCmd, recordPID, runClient, stopDaemonCmd]


def startDaemon(hosts):
    return [startDaemonCmd, recordPID]


def stopDaemon(hosts):
    return [stopDaemonCmd]


if __name__ == "__main__":
    actions = {}
    actions['link'] = (link, "makes each machine aware of the others' existence")
    actions['test'] = (testClientDaemon, "tests client-daemon communication")
    actions['startDaemon'] = (startDaemon, "starts the daemon")
    actions['stopDaemon'] = (stopDaemon, "stops the daemon")
    parser = argparse.ArgumentParser()
    parser.add_argument("username", help="Username on remote machines")
    parser.add_argument("password", help="Password on remote machines")
    actionsHelp = '; '.join(['%s, which %s' % (item[0], item[1][1]) for item in actions.items()])
    parser.add_argument("action", help="The action you want to take. Available actions are: %s" % actionsHelp)
    parser.add_argument("hosts", help="Comma-seperated list of machines to act on")
    args = parser.parse_args()
    if args.action not in actions:
        print "Don't know action %s" % args.action
        sys.exit(1)
    hosts = args.hosts.split(',')
    cmdGenerator = actions[args.action][0]
    ssh(hosts, args.username, args.password, cmdGenerator)
