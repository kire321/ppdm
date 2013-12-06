from pexpect import spawn
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
        self.hook(parser)

        def sshified(args):
            self._engine(args, func)
        parser.set_defaults(func=sshified)
        return sshified

    def _engine(self, args, cmdGenerator):
        for host in args.hosts.split(','):
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
    hosts = args.hosts.split(',')
    random.shuffle(hosts)
    mkdir = 'ls | grep $HOSTNAME || mkdir $HOSTNAME'
    writeFile = 'echo -e "%s" >$HOSTNAME/peers.list' % '\\n'.join(hosts)
    return [mkdir, writeFile]


startDaemonCmd = 'java -jar daemon/target/scala-2.10/daemon-assembly-1.0.jar %s >$HOSTNAME/daemon.log 2>$HOSTNAME/daemon.log &'
recordPID = 'echo $! > $HOSTNAME/daemon.pid'
runClient = 'java -jar client/target/scala-2.10/client-assembly-0.1-SNAPSHOT.jar'
stopDaemonCmd = 'kill `cat $HOSTNAME/daemon.pid`'


@ssh("tests client-daemon communication")
def testClientDaemon(args):
    return [startDaemonCmd % 'one.sh', recordPID, runClient, stopDaemonCmd]


@ssh(hook=lambda parser: parser.add_argument('secret', help="This should \
    be a shell command that does not contain any spaces and outputs an integer. \
    It will be called once, and the output used as the daemon's secret."))
def startDaemon(args):
    return [startDaemonCmd % args.secret, recordPID]


@ssh()
def stopDaemon(args):
    return [stopDaemonCmd]

if __name__ == "__main__":
    args = globalParser.parse_args()
    args.func(args)
