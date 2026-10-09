"""Run the existing HTTP contract checks against both packaged distributions."""

from pathlib import Path
import socket
import subprocess
import time
from zipfile import ZipFile

MODULE = Path(__file__).resolve().parents[2] / "exercises/sprint04/boot-actuator"
PORT = 18082


def main() -> None:
    try:
        with socket.create_connection(("127.0.0.1", PORT), timeout=0.2):
            raise RuntimeError(f"Port {PORT} is already in use")
    except ConnectionRefusedError:
        pass
    gradle_jar = MODULE / "build/libs/boot-actuator-practice-1.0-SNAPSHOT.jar"
    with gradle_jar.open("rb") as source:
        assert source.read(2) == b"#!", "Missing executable launch script"
    with ZipFile(gradle_jar) as archive:
        assert "BOOT-INF/lib/commons-collections4-4.4.jar" in archive.namelist()
    for builder, directory in (("maven", "target"), ("gradle", "build/libs")):
        for profile in ("default", "lab", "probes"):
            log = MODULE / "target" / f"http-{builder}-{profile}.log"
            with log.open("w") as output:
                args = ["java", "-jar", str(MODULE / directory / "boot-actuator-practice-1.0-SNAPSHOT.jar")]
                if profile != "default":
                    args.append(f"--spring.profiles.active={profile}")
                process = subprocess.Popen(args, stdout=output, stderr=subprocess.STDOUT)
                try:
                    deadline = time.monotonic() + 30
                    while True:
                        if process.poll() is not None:
                            raise RuntimeError(f"Server exited; see {log}")
                        try:
                            with socket.create_connection(("127.0.0.1", PORT), timeout=0.2):
                                break
                        except (ConnectionRefusedError, TimeoutError):
                            if time.monotonic() >= deadline:
                                raise TimeoutError(f"Server did not start; see {log}")
                            time.sleep(0.1)
                    subprocess.run(["python3", "verify.py", profile, builder], cwd=MODULE, check=True)
                finally:
                    process.terminate()
                    try:
                        process.wait(timeout=10)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait()
            print(f"Packaged HTTP checks passed: {builder}/{profile}")
    classpath = str(MODULE / "target/classes") + ":" + (MODULE / "target/runtime-classpath.txt").read_text().strip()
    subprocess.run(["java", "-cp", classpath, "dev.principalwater.study.actuator.AvailabilityDemo"],
                   cwd=MODULE, check=True, timeout=30)


if __name__ == "__main__":
    main()
