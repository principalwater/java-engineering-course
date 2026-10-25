"""Проверка первичной, повторной и неполной записи конфигурации в настоящий ZooKeeper."""

from pathlib import Path
import shutil
import subprocess
import unittest

import yaml


@unittest.skipUnless(shutil.which("docker"), "Docker is required for the ZooKeeper bootstrap contract")
class ConfigSeedTest(unittest.TestCase):
    def test_seed_is_idempotent_and_never_confirms_a_partial_write(self):
        project = Path(__file__).resolve().parents[2]
        image = yaml.safe_load((project / "deployment/charts/bank/values.yaml").read_text(encoding="utf-8"))["platform"]["zookeeperImage"]
        container = self.command(
            "create", "--network", "none", "--add-host", "discovery:127.0.0.1",
            "--memory", "512m", "--cpus", "1",
            "--tmpfs", "/data", "--tmpfs", "/datalog", "--tmpfs", "/logs",
            "-e", "JVMFLAGS=-Xms64m -Xmx128m -XX:ActiveProcessorCount=1",
            "-e", "CLIENT_JVMFLAGS=-Xms32m -Xmx64m -XX:ActiveProcessorCount=1",
            "-e", "ZOO_ADMINSERVER_ENABLED=false", image, timeout=300).stdout.strip()
        self.addCleanup(self.command, "rm", "--force", "--volumes", container)
        self.command("cp", str(project / "deployment/discovery/init-config.sh"), container + ":/tmp/config-seed.sh")
        self.command("start", container)
        self.seed(container)
        self.cli(container, "set /config/application/bank.client.read-timeout 99s")
        self.seed(container)
        for key, value in (("bank.client.connect-timeout", "1s"), ("bank.client.read-timeout", "3s"),
                           ("spring.threads.virtual.enabled", "true"), ("bank.config.revision", "sprint11-v1")):
            with self.subTest(key=key):
                self.assertIn(value, self.cli(container, "get /config/application/" + key).stdout.splitlines())
        # Старая ревизия уже существует: отказ записи другого ключа всё равно обязан остановить seed.
        self.cli(container, "set /config/application/bank.client.read-timeout 99s",
                 "set /config/application/bank.config.revision previous-revision",
                 "setAcl /config/application/bank.client.read-timeout world:anyone:r")
        result = self.seed(container, check=False)
        self.assertNotEqual(0, result.returncode, "A revision alone must not confirm an incomplete configuration")
        self.assertNotIn("Shared bank configuration is ready", result.stdout)
        self.assertIn("99s", self.cli(container, "get /config/application/bank.client.read-timeout").stdout.splitlines())
        self.assertIn("previous-revision", self.cli(container, "get /config/application/bank.config.revision").stdout.splitlines())

    def seed(self, container, *, check=True):
        return self.command("exec", container, "sh", "/tmp/config-seed.sh", check=check, timeout=290)

    def cli(self, container, *commands):
        return self.command("exec", "-i", container, "zkCli.sh", "-server", "discovery:2181",
                            "-waitforconnection", "-timeout", "5000", input="\n".join((*commands, "quit")) + "\n")

    @staticmethod
    def command(*arguments, **options):
        return subprocess.run(["docker", *arguments], capture_output=True, text=True, encoding="utf-8",
                              **({"check": True, "timeout": 30} | options))


if __name__ == "__main__":
    unittest.main()
