from fabric.api import *
from pexpect import spawn
from copy import deepcopy
import random


def ssh(cmds):
    prompt("username on remote:", 'user', env.user)
    prompt("password on remote:", 'password', env.password)
    for host in env.hosts:
        proc = spawn('ssh %s@%s' % (env.user, host))
        proc.expect('password:')
        proc.sendline(env.password)
        proc.expect(' ')
        for cmd in cmds:
            proc.sendline(cmd)
            proc.expect(' ')
        proc.close()


def stopDaemon():
    #TODO: doesn't actually work, since writing down the PID fails for some reason
    run('kill `cat ppdm/daemon.pid`')


def startDaemon():
    start = 'nohup java -jar ppdm/daemon/target/scala-2.10/daemon-assembly-1.0.jar &'
    recordPID = 'echo $! > ppdm/daemon.pid'
    ssh([start, recordPID])


def runClient():
    run('java -jar ppdm/client/target/scala-2.10/client-assembly-0.1-SNAPSHOT.jar')


def link():
    hosts = deepcopy(env.hosts)
    random.shuffle(hosts)
    mkdir = 'ls ppdm | grep $HOSTNAME || mkdir ppdm/$HOSTNAME'
    writeFile = 'echo "%s" >ppdm/$HOSTNAME/peers.list' % '\n'.join(hosts)
    ssh([mkdir, writeFile])


def testClientDaemon():
    startDaemon()
    runClient()
    stopDaemon()
