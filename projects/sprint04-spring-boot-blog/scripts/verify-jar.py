"""Запуск готового JAR с H2 в отдельном каталоге и проверка его через HTTP."""

from pathlib import Path
import re
import subprocess
import tempfile
import time
from urllib.request import urlopen

import smoke


def main():
    jar = Path(__file__).resolve().parents[1] / "build/libs/blog.jar"
    with tempfile.TemporaryDirectory(prefix="blog-jar-") as directory:
        log = Path(directory) / "startup.log"
        with log.open("w") as output:
            process = subprocess.Popen(["java", "-jar", str(jar), "--server.address=127.0.0.1", "--server.port=0"],
                                       cwd=directory, stdout=output, stderr=subprocess.STDOUT)
            try:
                for attempt in range(180):
                    if process.poll() is not None:
                        raise RuntimeError("Executable JAR exited before becoming ready")
                    port = re.search(r"Tomcat started on port (\d+) ", log.read_text())
                    if port:
                        base = "http://127.0.0.1:" + port.group(1)
                        try:
                            with urlopen(base + "/actuator/health/readiness", timeout=1):
                                break
                        except OSError:
                            pass
                    time.sleep(0.25)
                else:
                    raise TimeoutError("Executable JAR did not become ready")
                smoke.main(base)
            except Exception:
                print(log.read_text())
                raise
            finally:
                process.terminate()
                try:
                    process.wait(timeout=25)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=5)


if __name__ == "__main__":
    main()
