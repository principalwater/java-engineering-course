import hashlib
import io
from pathlib import Path
import shutil
import sys
from urllib.request import urlopen
from zipfile import ZipFile


URL = "https://code.s3.yandex.net/middle-java/my-blog-front-app.zip"
SHA256 = "17609f3fd2bb452117583f06e57ccb4f7cdd98b29f43b246c7580c4f9e11003e"
OLD_API_URL = 'function Xt(){return"http://"+yR()+":"+vR()}function yR(){return"localhost"}function vR(){return"8080"}'
NEW_API_URL = 'function Xt(){return window.location.origin}'
OLD_IMAGE_UPLOAD = 'function CR(n,a){const i=new FormData;'
NEW_IMAGE_UPLOAD = 'function CR(n,a){if(!(n.data instanceof Blob))return;const i=new FormData;'
OLD_IMAGE_READ = '.then(r=>r.blob()).then(r=>URL.createObjectURL(r))'
NEW_IMAGE_READ = '.then(r=>r.status===404?null:r.blob()).then(r=>r?URL.createObjectURL(r):undefined)'
OLD_FEED_IMAGE_READ = '.then(o=>o.blob()).then(o=>URL.createObjectURL(o))'
NEW_FEED_IMAGE_READ = '.then(o=>o.status===404?null:o.blob()).then(o=>o?URL.createObjectURL(o):undefined)'
OLD_COMMENT_ROUTE = 'function zR(n,a,i){fetch(Xt()+"/api/posts/"+n.id+"/comments/"+n.id,'
NEW_COMMENT_ROUTE = OLD_COMMENT_ROUTE.replace('+n.id+"/comments/"', '+n.postId+"/comments/"')
OLD_COMMENT_LOAD = 'function AR(n,a,i){fetch(Xt()+"/api/posts/"+n+"/comments").then(r=>r.json()).then(a).catch(i)}'
NEW_COMMENT_LOAD = (
    'function AR(n,a,i){fetch(Xt()+"/api/posts/"+n+"/comments").then(async r=>{'
    'const p=await r.json();if(!r.ok)throw new Error(p?.error||"Comments could not be loaded");'
    'if(!Array.isArray(p))throw new Error("Invalid comments response");'
    'return p}).then(a).catch(i)}'
)
OLD_COMMENT_EFFECT = 'w.useEffect(()=>{!n.isPreview&&AR(n.postId,p=>i(h=>({...h,comments:p})),p=>n.handleError(p.message))},[]);'
NEW_COMMENT_EFFECT = OLD_COMMENT_EFFECT.replace(
    '!n.isPreview&&AR(', '!n.isPreview&&Number.isSafeInteger(n.postId)&&n.postId>0&&AR('
).replace('},[]);', '},[n.isPreview,n.postId]);')


def prepare(output: Path, archive: bytes) -> None:
    if hashlib.sha256(archive).hexdigest() != SHA256:
        raise ValueError("Frontend checksum mismatch; build aborted")
    with ZipFile(io.BytesIO(archive)) as bundle:
        files = [name for name in bundle.namelist() if "/dist/" in name and not name.endswith("/")]
        if not files:
            raise ValueError("Frontend distribution is missing from the archive")
        for name in files:
            relative = Path(name.split("/dist/", 1)[1])
            if relative.is_absolute() or ".." in relative.parts:
                raise ValueError("Invalid path in the frontend archive")
            destination = output / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes(bundle.read(name))
    scripts = list((output / "assets").glob("*.js"))
    owners = [path for path in scripts if OLD_COMMENT_ROUTE in path.read_text()]
    if len(owners) != 1:
        raise ValueError("Comment route changed; client compatibility must be reviewed")
    client = owners[0].read_text()
    # Same-origin запросы проходят через Nginx; сохраняем исправления загрузки и маршрута комментариев.
    for old, new in (
        (OLD_API_URL, NEW_API_URL),
        (OLD_IMAGE_UPLOAD, NEW_IMAGE_UPLOAD),
        (OLD_IMAGE_READ, NEW_IMAGE_READ),
        (OLD_FEED_IMAGE_READ, NEW_FEED_IMAGE_READ),
        (OLD_COMMENT_ROUTE, NEW_COMMENT_ROUTE),
        (OLD_COMMENT_LOAD, NEW_COMMENT_LOAD),
        (OLD_COMMENT_EFFECT, NEW_COMMENT_EFFECT),
    ):
        if client.count(old) != 1:
            raise ValueError("Client changed; comment loading and routes must be reviewed")
        client = client.replace(old, new, 1)
    owners[0].write_text(client)
    index = output / "index.html"
    html = index.read_text()
    if html.count("</head>") != 1 or html.count("</body>") != 1:
        raise ValueError("Unknown index page structure")
    html = html.replace('lang="en"', 'lang="ru"').replace("<title>My Blog</title>", "<title>Тетрадь инженера</title>")
    # Версия содержимого предотвращает загрузку старого клиента и оформления из кеша после пересборки.
    client_source = f'src="/assets/{owners[0].name}"'
    if html.count(client_source) != 1:
        raise ValueError("Unknown JavaScript client reference")
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
        raise SystemExit("Usage: python prepare.py <frontend directory>")
    with urlopen(URL, timeout=60) as response:
        prepare(Path(sys.argv[1]), response.read(20_000_001))
