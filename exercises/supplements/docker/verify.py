"""Проверяет настоящий HTTP/JDBC-путь и данные после пересоздания БД."""
import json
from pathlib import Path
import subprocess
import time
from urllib.error import URLError
from urllib.request import urlopen

ROOT = Path(__file__).parent
COMPOSE = ["docker", "compose", "-p", "java-supplement-docker"]
BASE = "http://127.0.0.1:18380"


def compose(*args):
    return subprocess.check_output(COMPOSE + list(args), cwd=ROOT, text=True).strip()


def read(path):
    deadline = time.monotonic() + 60
    while True:
        try:
            with urlopen(BASE + path, timeout=3) as response:
                assert response.status == 200
                return response.read().decode()
        except (URLError, TimeoutError):
            if time.monotonic() >= deadline:
                raise
            time.sleep(1)


assert read("/hello") == "Добро пожаловать на борт!"
movies = json.loads(read("/movies"))
assert {"id": 1, "name": "Star Wars: Episode I - The Phantom Menace", "type": "Action", "releaseYear": 1999} in movies
print("HTTP -> Nginx -> Java -> PostgreSQL: passed")

compose("exec", "-T", "database", "psql", "-U", "study", "-d", "study", "-v", "ON_ERROR_STOP=1", "-c",
        "INSERT INTO movies VALUES (2, 'Persistence marker', 'Fixture', 2026) ON CONFLICT (id) DO NOTHING")
old_id = compose("ps", "-q", "database")
compose("up", "-d", "--force-recreate", "--wait", "--wait-timeout", "60", "database")
assert compose("ps", "-q", "database") != old_id
assert {"id": 2, "name": "Persistence marker", "type": "Fixture", "releaseYear": 2026} in json.loads(read("/movies"))
print("Named volume data after database recreation: passed")

compose("stop", "app")
compose("start", "app")
assert read("/hello") == "Добро пожаловать на борт!"
print("Application stop/start and gateway recovery: passed")
