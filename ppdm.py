from pexpect import spawn
from copy import deepcopy
import random
import argparse
import sys

globalParser = argparse.ArgumentParser(prog="python ppdm.py", description="type an action followed by --help for more info on that action")
subParsers = globalParser.add_subparsers(title="Actions")


class ssh(object):

    def __init__(self, description=None, hook=lambda parser: None):
        self.description = description
        self.hook = hook

    def __call__(self, func):
        parser = subParsers.add_parser(func.func_name, description=self.description)
        parser.add_argument("username", help="Username on remote machines")
        parser.add_argument("password", help="Password on remote machines")
        parser.add_argument("hosts", help="Comma-seperated list of machines to act on. These need to be names, not ip addresses")

        def sshified(args):
            parsedHosts = deepcopy(args)
            parsedHosts.hosts = args.hosts.split(',')
            self._engine(parsedHosts, func)
        parser.set_defaults(func=sshified)
        self.hook(parser)
        return sshified

    def _engine(self, args, cmdGenerator):
        for host in args.hosts:
            proc = spawn('ssh %s@%s' % (args.username, host), logfile=sys.stdout, searchwindowsize=10)
            proc.expect('password:')
            proc.sendline(args.password)
            proc.expect_exact('% ')
            for cmd in ['bash', 'cd ppdm'] + cmdGenerator(args):
                proc.sendline(cmd)
                proc.expect_exact('$ ')
            proc.close()


@ssh("makes each machine aware of the others' existence")
def link(args):
    hosts = deepcopy(args.hosts)
    random.shuffle(hosts)
    mkdir = 'ls | grep $HOSTNAME || mkdir $HOSTNAME'
    writeFile = 'echo -e "%s" >$HOSTNAME/peers.list' % '\\n'.join(hosts)
    return [mkdir, writeFile]


startDaemonCmd = 'java -jar daemon/target/scala-2.10/daemon-assembly-1.0.jar %s >$HOSTNAME/daemon.log 2>$HOSTNAME/daemon.log &'
recordPIDCmd = 'echo $! > $HOSTNAME/daemon.pid'
runClientCmd = 'java -jar client/target/scala-2.10/client-assembly-0.1-SNAPSHOT.jar'
stopDaemonCmd = 'kill `cat $HOSTNAME/daemon.pid`'


@ssh("tests client-daemon communication")
def testClientDaemon(args):
    return [startDaemonCmd % 'one.sh', recordPIDCmd, runClientCmd, stopDaemonCmd]


def askForSecret(parser):
    parser.add_argument('secret', help="This should \
    be the name of an executable in the ppdm directory that does not need any arguments and outputs an integer. \
    It will be called once, and the output used as the daemon's secret.")


@ssh(hook=askForSecret)
def startDaemon(args):
    return [startDaemonCmd % args.secret, recordPIDCmd]


@ssh("ask the remote daemon (which should already be running!) to sum securely")
def runClient(args):
    return [runClientCmd]


@ssh()
def stopDaemon(args):
    return [stopDaemonCmd]


def secureSumHook(parser):
    askForSecret(parser)

    def sequentialTasks(args):
        link(args)
        startDaemon(args)
        oneHost = deepcopy(args)
        oneHost.hosts = oneHost.hosts[0]
        runClient(oneHost)
        stopDaemon(args)
    parser.set_defaults(func=sequentialTasks)


@ssh("run the entire secure sum process", secureSumHook)
def secureSum(args):
    pass


if __name__ == "__main__":
    args = globalParser.parse_args()
    args.func(args)
