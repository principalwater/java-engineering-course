"""Проверка настоящего JAR и REST-контракта; удаляются только созданные сценарием посты."""

import argparse
import base64
import json
from urllib.error import HTTPError
from urllib.request import Request, urlopen


IMAGE = base64.b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII="
)


def request(url, method="GET", body=None, content_type="application/json"):
    data = body if isinstance(body, bytes) else None if body is None else json.dumps(body).encode()
    headers = {} if data is None else {"Content-Type": content_type}
    with urlopen(Request(url, data=data, headers=headers, method=method), timeout=15) as response:
        raw = response.read()
        media_type = response.headers.get_content_type()
        return json.loads(raw) if raw and (media_type == "application/json" or media_type.endswith("+json")) else raw


def expect_error(url, method, status, body=None):
    try:
        request(url, method, body)
    except HTTPError as error:
        assert error.code == status, f"Expected HTTP {status}, received {error.code}"
    else:
        raise AssertionError(f"Expected HTTP {status}")


def main(base, frontend=None):
    assert request(base + "/actuator/health/readiness")["status"] == "UP"
    assert request(base + "/actuator/info")["build"]["name"] == "blog"
    posts = base + "/api/posts"
    text = "😀" * 129
    post = request(posts, "POST", {"title": "JAR smoke", "text": text, "tags": ["boot", "smoke"]})
    path = posts + "/" + str(post["id"])
    try:
        assert request(path) == post
        page = request(posts + "?search=JAR%20%23boot%20%23smoke&pageNumber=1&pageSize=5")
        assert any(item["id"] == post["id"] and item["text"] == "😀" * 128 + "…" for item in page["posts"])
        assert request(path + "/likes", "POST") == 1
        comment = request(path + "/comments", "POST", {"text": "UTF-8 проверен", "postId": post["id"]})
        assert request(path)["commentsCount"] == 1
        boundary = "blog-smoke-boundary"
        upload = (f'--{boundary}\r\nContent-Disposition: form-data; name="image"; filename="probe.png"\r\n'
                  'Content-Type: application/octet-stream\r\n\r\n').encode() + IMAGE + f"\r\n--{boundary}--\r\n".encode()
        request(path + "/image", "PUT", upload, "multipart/form-data; boundary=" + boundary)
        assert request(path + "/image") == IMAGE
        updated = request(path, "PUT", {"id": post["id"], "title": "Edited JAR smoke", "text": "Updated",
                                        "tags": ["boot"]})
        assert updated["likesCount"] == 1 and updated["commentsCount"] == 1
        assert request(path + "/image") == IMAGE
        comment["text"] = "Комментарий изменён"
        assert request(path + "/comments/" + str(comment["id"]), "PUT", comment) == comment
        expect_error(path, "POST", 405)
        expect_error(path, "PUT", 400, {"id": post["id"] + 1, "title": "Mismatch", "text": "Body", "tags": []})
    finally:
        request(path, "DELETE")
    expect_error(path, "GET", 404)
    expect_error(path + "/comments/" + str(comment["id"]), "GET", 404)
    if frontend:
        # Публичный origin и Host совпадают, но не входят в CORS allowlist бэкенда.
        url = frontend.rstrip("/") + "/api/posts?search=&pageNumber=1&pageSize=5"
        headers = {"Host": "blog.example:8084", "Origin": "http://blog.example:8084"}
        with urlopen(Request(url, headers=headers), timeout=15) as response:
            assert response.status == 200
            assert isinstance(json.load(response)["posts"], list)
        print("Frontend proxy: same-origin requests preserve the Host port")
    print("Executable JAR: readiness, build metadata, CRUD, Unicode, search, likes, image and cascade passed")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Check the running Spring Boot blog")
    parser.add_argument("--base-url", default="http://localhost:18084")
    parser.add_argument("--frontend-url", help="Also check same-origin requests through Nginx")
    arguments = parser.parse_args()
    main(arguments.base_url.rstrip("/"), arguments.frontend_url)
