import hashlib
import io
from pathlib import Path
import shutil
import sys
from urllib.request import urlopen
from zipfile import ZipFile


URL = "https://code.s3.yandex.net/middle-java/my-blog-front-app.zip"
SHA256 = "17609f3fd2bb452117583f06e57ccb4f7cdd98b29f43b246c7580c4f9e11003e"
OLD_COMMENT_ROUTE = 'function zR(n,a,i){fetch(Xt()+"/api/posts/"+n.id+"/comments/"+n.id,'
NEW_COMMENT_ROUTE = OLD_COMMENT_ROUTE.replace('+n.id+"/comments/"', '+n.postId+"/comments/"')


def prepare(output: Path, archive: bytes) -> None:
    if hashlib.sha256(archive).hexdigest() != SHA256:
        raise ValueError("Контрольная сумма фронтенда не совпала; сборка остановлена")
    with ZipFile(io.BytesIO(archive)) as bundle:
        files = [name for name in bundle.namelist() if "/dist/" in name and not name.endswith("/")]
        if not files:
            raise ValueError("В архиве отсутствует готовый фронтенд")
        for name in files:
            relative = Path(name.split("/dist/", 1)[1])
            if relative.is_absolute() or ".." in relative.parts:
                raise ValueError("Недопустимый путь в архиве фронтенда")
            destination = output / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes(bundle.read(name))
    scripts = list((output / "assets").glob("*.js"))
    owners = [path for path in scripts if OLD_COMMENT_ROUTE in path.read_text()]
    if len(owners) != 1:
        raise ValueError("Маршрут комментария изменился: требуется проверить совместимость клиента")
    client = owners[0].read_text()
    if client.count(OLD_COMMENT_ROUTE) != 1:
        raise ValueError("Маршрут комментария неоднозначен")
    # В предоставленном клиенте postId ошибочно подменён идентификатором комментария.
    owners[0].write_text(client.replace(OLD_COMMENT_ROUTE, NEW_COMMENT_ROUTE, 1))
    index = output / "index.html"
    html = index.read_text()
    if html.count("</head>") != 1 or html.count("</body>") != 1:
        raise ValueError("Неизвестная структура главной страницы")
    html = html.replace('lang="en"', 'lang="ru"').replace("<title>My Blog</title>", "<title>Тетрадь инженера</title>")
    # Версия содержимого предотвращает загрузку старого клиента и оформления из кеша после пересборки.
    client_source = f'src="/assets/{owners[0].name}"'
    if html.count(client_source) != 1:
        raise ValueError("Неизвестное подключение JavaScript-клиента")
    client_version = hashlib.sha256(owners[0].read_bytes()).hexdigest()[:12]
    html = html.replace(client_source, f'src="/assets/{owners[0].name}?v={client_version}"', 1)
    css_version = hashlib.sha256(Path(__file__).with_name("theme.css").read_bytes()).hexdigest()[:12]
    js_version = hashlib.sha256(Path(__file__).with_name("accessibility.js").read_bytes()).hexdigest()[:12]
    html = html.replace("</head>", f'<link rel="stylesheet" href="/theme.css?v={css_version}">\n</head>')
    html = html.replace("</body>", f'<script defer src="/accessibility.js?v={js_version}"></script>\n</body>')
    index.write_text(html)
    for name in ("theme.css", "accessibility.js"):
        shutil.copyfile(Path(__file__).with_name(name), output / name)


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("Использование: python prepare.py <каталог готового фронтенда>")
    with urlopen(URL, timeout=60) as response:
        prepare(Path(sys.argv[1]), response.read(20_000_001))
