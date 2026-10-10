import argparse
import json
import os
import re
from pathlib import Path
import subprocess

MIRROR = "public.ecr.aws/docker/library/"


def mirrored(image):
    if not re.fullmatch(r"[a-z0-9-]+:[^@\s]+@sha256:[0-9a-f]{64}", image):
        raise ValueError("Expected a pinned Docker Official Image")
    return MIRROR + image


def prepare_image(image):
    # Both registries must serve the exact pinned digest; never fall back to a mutable tag.
    for candidate in (mirrored(image), image):
        try:
            result = subprocess.run(["docker", "pull", candidate], capture_output=True, text=True, timeout=120)
        except subprocess.TimeoutExpired:
            print("Pinned image pull timed out: " + candidate)
            continue
        if result.returncode == 0:
            print("Pinned image ready: " + candidate)
            return candidate
        print("Pinned image pull failed: " + candidate + "\n" + result.stderr.strip())
    raise RuntimeError("Pinned official image is unavailable from both registries: " + image)


parser = argparse.ArgumentParser()
parser.add_argument("--exercise", action="store_true")
exercise = parser.parse_args().exercise
project = Path.cwd()
output = Path(os.environ["RUNNER_TEMP"]) / (project.name + "-compose")
output.mkdir(parents=True, exist_ok=True)
images = re.findall(r"^    image: (\S+)$", (project / "compose.yaml").read_text(), re.MULTILINE)
if len(images) != 1:
    raise ValueError("Expected one pinned image in the Compose file")
services = {"web" if exercise else "db": {"image": prepare_image(images[0])}}
builds = () if exercise else (("backend", project), ("frontend", project / "frontend"))
for service, context in builds:
    source = (context / "Dockerfile").read_text()
    # Prefer the official mirror; rate limits on either registry must not change the digest.
    rendered, count = re.subn(r"^FROM (\S+)(.*)$",
                             lambda match: "FROM " + prepare_image(match[1]) + match[2],
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
print("CI Compose override prepared with pinned official images and registry fallback")
