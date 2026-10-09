import http.client
import json
import re
import uuid


def request(method: str, path: str, body: bytes, content_type: str) -> tuple[int, str, bytes]:
    connection = http.client.HTTPConnection("127.0.0.1", 18081, timeout=10)
    try:
        headers = {"Content-Type": content_type} if content_type else {}
        connection.request(method, "/spring-web-demo" + path, body, headers)
        response = connection.getresponse()
        return response.status, response.getheader("Content-Type", ""), response.read()
    finally:
        connection.close()


def main() -> None:
    status, content_type, body = request("GET", "/home", b"", "")
    assert status == 200 and "text/html" in content_type and b"Hello, world!" in body
    status, content_type, body = request("GET", "/users", b"", "")
    assert status == 200 and "application/json" in content_type
    before = json.loads(body)
    assert any(user["firstName"] == "Анна" and user["active"] for user in before)

    marker = str(uuid.uuid4())
    user = {"firstName": "O'Коннор", "lastName": marker, "age": 35, "active": True}
    payload = json.dumps(user, ensure_ascii=False).encode("utf-8")
    assert request("POST", "/users", payload, "application/json")[0] == 201
    users = json.loads(request("GET", "/users", b"", "")[2])
    created = next(item for item in users if item["lastName"] == marker)
    identifier = created["id"]
    try:
        assert created == {"id": identifier, **user}
        user["age"] = 36
        user["active"] = False
        updated = json.dumps(user, ensure_ascii=False).encode("utf-8")
        assert request("PUT", f"/users/{identifier}", updated, "application/json")[0] == 204
        users = json.loads(request("GET", "/users", b"", "")[2])
        assert next(item for item in users if item["id"] == identifier) == {"id": identifier, **user}
        assert request("POST", "/users", b'{"age":-1}', "application/json")[0] == 400
        assert json.loads(request("GET", "/users", b"", "")[2]) == users
    finally:
        # Удаляем только запись, созданную этим запуском, даже при падении проверки.
        assert request("DELETE", f"/users/{identifier}", b"", "")[0] == 204
    assert json.loads(request("GET", "/users", b"", "")[2]) == before
    assert request("DELETE", f"/users/{identifier}", b"", "")[0] == 404

    boundary = "Boundary" + uuid.uuid4().hex
    content = b"\x00\xff\x01" + "Проверка файла".encode("utf-8")
    prefix = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; '
                 'filename="../../outside.txt"\r\nContent-Type: application/octet-stream\r\n\r\n').encode()
    ending = f"\r\n--{boundary}--\r\n".encode()
    multipart = prefix + content + ending
    status, _, body = request("POST", "/files/upload", multipart, f"multipart/form-data; boundary={boundary}")
    filename = body.decode("utf-8")
    assert status == 200 and re.fullmatch(r"[0-9a-f-]{36}\.bin", filename)
    status, content_type, body = request("GET", "/files/download/" + filename, b"", "")
    assert status == 200 and "application/octet-stream" in content_type and body == content
    assert request("GET", "/files/download/not-allowed", b"", "")[0] == 400
    assert request("GET", "/files/download/" + str(uuid.uuid4()) + ".bin", b"", "")[0] == 404
    too_large = prefix + b"a" * (5 * 1024 * 1024 + 1) + ending
    assert request("POST", "/files/upload", too_large, f"multipart/form-data; boundary={boundary}")[0] == 413
    print("HTTP CRUD, UTF-8, validation and binary round-trip checks passed")


if __name__ == "__main__":
    main()
