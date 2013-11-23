from fabric.api import *
from pexpect import spawn


def install():
    run('git clone https://github.com/kire321/ppdm')


def uninstall():
    run('rm -rf ppdm')


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
    run('kill -9 `cat ppdm/daemon.pid`')


def startDaemon():
    start = 'nohup java -jar ppdm/daemon/target/scala-2.10/daemon-assembly-1.0.jar &'
    recordPID = 'echo $! > ppdm/daemon.pid'
    ssh([start, recordPID])


def runClient():
    run('java -jar ppdm/client/target/scala-2.10/client-assembly-0.1-SNAPSHOT.jar')


def testClientDaemon():
    startDaemon()
    runClient()
    stopDaemon()
