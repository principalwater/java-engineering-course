"""Проверка упакованного потребителя стартера на HTTP; Python 3.11+."""

import json
from pathlib import Path
import socket
import subprocess
import time
from urllib.request import urlopen
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parent
PORT = 18083
MARKER = "private-query-marker"


def main() -> None:
    with ZipFile(ROOT / "starter/target/http-logger-spring-boot-starter-1.0-SNAPSHOT.jar") as jar:
        assert not any(name.startswith("BOOT-INF/") for name in jar.namelist())
        metadata = json.loads(jar.read("META-INF/spring-configuration-metadata.json"))
        properties = {item["name"]: item for item in metadata["properties"]}
        assert properties["application.http.logging.level"]["defaultValue"] == "info"
        assert properties["application.http.logging.enabled"]["defaultValue"] is True
    try:
        with socket.create_connection(("127.0.0.1", PORT), timeout=0.2):
            raise RuntimeError(f"Порт {PORT} занят: остановите свой предыдущий запуск")
    except ConnectionRefusedError:
        pass
    cases = [
        ("default", [], "INFO"),
        ("debug", ["--application.http.logging.level=DEBUG",
                   "--logging.level.dev.principalwater.study.http=DEBUG"], "DEBUG"),
        ("disabled", ["--application.http.logging.enabled=false"], None),
    ]
    (ROOT / "target").mkdir(exist_ok=True)
    for name, args, level in cases:
        log = ROOT / "target" / f"server-{name}.log"
        with log.open("w") as output:
            process = subprocess.Popen(["java", "-jar", str(ROOT / "demo/target/http-logger-demo-1.0-SNAPSHOT.jar"), *args],
                                       stdout=output, stderr=subprocess.STDOUT)
            try:
                deadline = time.monotonic() + 30
                while True:
                    if process.poll() is not None:
                        raise RuntimeError(f"Сервер завершился: {log}")
                    try:
                        with socket.create_connection(("127.0.0.1", PORT), timeout=0.2):
                            break
                    except (ConnectionRefusedError, TimeoutError):
                        if time.monotonic() >= deadline:
                            raise TimeoutError(f"Сервер не запустился: {log}")
                        time.sleep(0.1)
                with urlopen(f"http://127.0.0.1:{PORT}/demo?token={MARKER}", timeout=5) as response:
                    assert response.status == 200 and response.read().decode() == "Hello!"
            finally:
                process.terminate()
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()
        content = log.read_text()
        lines = [line for line in content.splitlines() if "Получен GET запрос /demo" in line]
        assert len(lines) == (1 if level else 0), (name, lines)
        if level:
            assert level in lines[0], lines[0]
        assert MARKER not in content
        print(f"HTTP-стартер: {name}, проверка прошла")


if __name__ == "__main__":
    main()
