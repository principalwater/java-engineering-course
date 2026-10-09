import argparse
import json
import os
import re
from pathlib import Path

MIRROR = "public.ecr.aws/docker/library/"


def mirrored(image):
    if not re.fullmatch(r"[a-z0-9-]+:[^@\s]+@sha256:[0-9a-f]{64}", image):
        raise ValueError("Expected a pinned Docker Official Image")
    return MIRROR + image


parser = argparse.ArgumentParser()
parser.add_argument("--exercise", action="store_true")
exercise = parser.parse_args().exercise
project = Path.cwd()
output = Path(os.environ["RUNNER_TEMP"]) / "sprint03-compose"
output.mkdir(parents=True, exist_ok=True)
images = re.findall(r"^    image: (\S+)$", (project / "compose.yaml").read_text(), re.MULTILINE)
if len(images) != 1:
    raise ValueError("Expected one pinned image in the sprint 03 Compose file")
services = {"web" if exercise else "db": {"image": mirrored(images[0])}}
builds = () if exercise else (("backend", project), ("frontend", project / "frontend"))
for service, context in builds:
    source = (context / "Dockerfile").read_text()
    # Keep digests while avoiding anonymous Docker Hub pull limits.
    rendered, count = re.subn(r"^FROM (\S+)(.*)$",
                             lambda match: "FROM " + mirrored(match[1]) + match[2],
                             source, flags=re.MULTILINE)
    if not count:
        raise ValueError("Expected uppercase FROM statements without platform flags")
    # Bake reads Dockerfiles within their build context without extra filesystem privileges.
    dockerfile = context / "target/ci.Dockerfile"
    dockerfile.parent.mkdir(parents=True, exist_ok=True)
    dockerfile.write_text(rendered)
    services[service] = {"build": {"context": str(context), "dockerfile": "target/ci.Dockerfile"}}
override = output / "compose.json"
override.write_text(json.dumps({"services": services}))
with Path(os.environ["GITHUB_ENV"]).open("a") as environment:
    environment.write(f"COMPOSE_FILE={project / 'compose.yaml'}:{override}\n")
print("CI Compose override prepared with unchanged image digests")
