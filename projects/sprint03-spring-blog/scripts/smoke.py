"""Проверка реального WAR и PostgreSQL: MockMvc не проверяет classloader Tomcat."""

import json
from urllib.error import HTTPError
from urllib.request import Request, urlopen

BASE = "http://localhost:8080/api/posts"


def request(method, path="", body=None):
    data = None if body is None else json.dumps(body).encode()
    headers = {} if data is None else {"Content-Type": "application/json"}
    with urlopen(Request(BASE + path, data=data, headers=headers, method=method), timeout=10) as response:
        raw = response.read()
        return json.loads(raw) if raw else None


if __name__ == "__main__":
    post = request("POST", body={"title": "WAR smoke", "text": "Проверка контейнера", "tags": ["smoke"]})
    path = "/" + str(post["id"])
    try:
        assert request("GET", path) == post
        assert request("POST", path + "/likes") == 1
        comment = request("POST", path + "/comments", {"text": "UTF-8 проверен", "postId": post["id"]})
        assert request("GET", path)["commentsCount"] == 1
        try:
            request("POST", path)
        except HTTPError as error:
            assert error.code == 405
        else:
            raise AssertionError("Чтение поста должно принимать только GET")
    finally:
        request("DELETE", path)
    for suffix in [path, path + "/comments/" + str(comment["id"])]:
        try:
            request("GET", suffix)
        except HTTPError as error:
            assert error.code == 404
        else:
            raise AssertionError("Удалённый пост и комментарий остались доступны")
    print("WAR/PostgreSQL: чтение, запись, UTF-8, лайк и каскадное удаление проверены")
