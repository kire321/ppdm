from fabric.api import *

def install():
    run('git clone https://github.com/kire321/ppdm')

def uninstall():
    run('rm -rf ppdm')

def daemonize(cmd):
    run('nohup %s &' % cmd)
    run('echo $! > daemon.pid')

def stopDaemon():
    run('kill -9 `cat daemon.pid`')

def startDaemon():
    daemonize('java -jar daemon/target/scala-2.10/daemon-assembly-1.0.jar')

def runClient():
    run('java -jar client/target/scala-2.10/client-assembly-0.1-SNAPSHOT.jar')

def testClientDaemon():
    with cd('ppdm'):
        #startDaemon()
        runClient()
        #stopDaemon()
