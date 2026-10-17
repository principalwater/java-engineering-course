#!/usr/bin/env python3
"""Проверка реальных Authorization Code + PKCE, JWT, M2M и межсервисных ограничений."""
import base64
from functools import partial
import hashlib
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import sys
import time
from urllib.error import HTTPError, URLError
from urllib.parse import parse_qs, urlencode, urljoin, urlsplit
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT.parents[1] / 'sprint08'))
from http_fixture import Forms, browser as create_browser, request as send_request

UI = 'http://127.0.0.1:19930'
GATEWAY = 'http://127.0.0.1:19931'
TRANSFER = 'http://127.0.0.1:19932'
ACCOUNTS = 'http://127.0.0.1:19933'
KC = 'http://127.0.0.1:19934'
ISSUER = KC + '/realms/bank-realm'
CALLBACK = 'http://127.0.0.1:19939/callback'
ORIGINS = {UI, GATEWAY, TRANSFER, ACCOUNTS, KC, 'http://127.0.0.1:19939'}
browser = partial(create_browser, ORIGINS)
request = partial(send_request, allowed_origins=ORIGINS)


def api(url, token=None, body=None):
    headers = {'Content-Type': 'application/json'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    try:
        response = urlopen(Request(url, data=json.dumps(body).encode() if body is not None else None,
                                   headers=headers), timeout=12)
    except HTTPError as error:
        response = error
    with response:
        return response.status, response.read().decode()


def wait_for(operation, accepted, timeout=90):
    deadline = time.monotonic() + timeout
    while True:
        try:
            value = operation()
            if accepted(value):
                return value
        except (URLError, TimeoutError, ConnectionError):
            pass
        if time.monotonic() >= deadline:
            raise AssertionError('Timed out waiting for the required state')
        time.sleep(0.2)


def user_token(username, password):
    follow, direct = browser()
    verifier = secrets.token_urlsafe(48)
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).decode().rstrip('=')
    state = secrets.token_urlsafe(24)
    url = ISSUER + '/protocol/openid-connect/auth?' + urlencode({
        'client_id': 'validation-probe', 'response_type': 'code', 'scope': 'openid profile',
        'redirect_uri': CALLBACK, 'state': state, 'code_challenge_method': 'S256', 'code_challenge': challenge,
    })
    status, _, body, _ = request(follow, url)
    assert status == 200
    form = next(form for form in Forms(body).forms if form['id'] == 'kc-form-login')
    form['fields'].update(username=username, password=password)
    status, headers, _, _ = request(direct, form['action'], form['fields'])
    location = urlsplit(headers.get('Location', ''))
    parameters = parse_qs(location.query)
    assert status == 302 and location.scheme + '://' + location.netloc + location.path == CALLBACK
    assert parameters['state'] == [state] and parameters['code']
    status, _, body, _ = request(direct, ISSUER + '/protocol/openid-connect/token', {
        'client_id': 'validation-probe', 'grant_type': 'authorization_code',
        'code': parameters['code'][0], 'redirect_uri': CALLBACK, 'code_verifier': verifier,
    })
    assert status == 200, 'Real Authorization Code exchange must succeed'
    return json.loads(body)['access_token']


def service_token(client, secret):
    _, direct = browser()
    authorization = 'Basic ' + base64.b64encode(f'{client}:{secret}'.encode()).decode()
    status, _, body, _ = request(direct, ISSUER + '/protocol/openid-connect/token',
                               {'grant_type': 'client_credentials'}, {'Authorization': authorization})
    assert status == 200
    return json.loads(body)['access_token']


def main():
    values = dict(line.split('=', 1) for line in (ROOT / 'target/lab.env').read_text().splitlines())
    wait_for(lambda: api(ISSUER + '/.well-known/openid-configuration'), lambda value: value[0] == 200)
    java = str(Path(os.environ['JAVA_HOME']) / 'bin/java') if 'JAVA_HOME' in os.environ else 'java'
    processes, logs = [], []
    try:
        for port in (19930, 19931, 19932, 19933, 19939):
            with socket.socket() as probe:
                probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
                probe.bind(('127.0.0.1', port))
        for module, port in [('accounts-service', 19933), ('transfer-service', 19932),
                             ('bank-gateway', 19931), ('bank-ui', 19930)]:
            log = (ROOT / f'target/{module}.log').open('w')
            logs.append(log)
            process = subprocess.Popen([java, '-Xmx192m', '-jar', str(ROOT / module / f'build/libs/{module}.jar')],
                                       cwd=ROOT, env={**{key: value for key, value in os.environ.items() if key not in values},
                                                      **({'UI_CLIENT_SECRET': values['UI_CLIENT_SECRET']} if module == 'bank-ui' else {}),
                                                      **({'TRANSFER_CLIENT_SECRET': values['TRANSFER_CLIENT_SECRET']} if module == 'transfer-service' else {})},
                                       stdout=log, stderr=subprocess.STDOUT)
            processes.append(process)
            wait_for(lambda: api(f'http://127.0.0.1:{port}/actuator/health'), lambda value: value[0] == 200)
            assert process.poll() is None
        transfer = {'fromAccountId': 'ACC-001', 'toAccountId': 'ACC-002', 'amount': '10.00'}
        assert api(GATEWAY + '/api/transfers/preview')[0] == 401
        assert api(TRANSFER + '/transfers/preview')[0] == 401
        assert api(ACCOUNTS + '/accounts/ACC-001/owner')[0] == 401

        user = user_token('test-user', values['LAB_USER_PASSWORD'])
        reader = user_token('read-user', values['LAB_USER_PASSWORD'])
        other = user_token('other-user', values['LAB_USER_PASSWORD'])
        assert api(GATEWAY + '/api/transfers/preview', user)[0] == 200
        result = api(GATEWAY + '/api/transfers', user, transfer)
        assert result[0] == 200, result
        assert api(GATEWAY + '/api/transfers/preview', reader)[0] == 200
        assert api(GATEWAY + '/api/transfers', reader, transfer)[0] == 403
        assert api(GATEWAY + '/api/transfers', other, transfer)[0] == 403
        assert api(GATEWAY + '/api/transfers', other, {**transfer, 'fromAccountId': 'ACC-002', 'toAccountId': 'ACC-001'})[0] == 200
        assert api(GATEWAY + '/api/transfers', user, {**transfer, 'amount': '-1'})[0] == 400
        assert api(GATEWAY + '/api/transfers', user, {**transfer, 'fromAccountId': 'invalid'})[0] == 400
        assert api(GATEWAY + '/api/transfers', user, {**transfer, 'fromAccountId': 'ACC-999'})[0] == 404
        assert api(ACCOUNTS + '/accounts/ACC-001/owner', user)[0] == 401
        print('Real user JWT relay, USER/TRANSFER_WRITE, ownership, DTO validation and separate downstream audience: passed')

        service = service_token('transfer-service', values['TRANSFER_CLIENT_SECRET'])
        status, body = api(ACCOUNTS + '/accounts/ACC-001/owner', service)
        assert status == 200 and json.loads(body) == {'accountId': 'ACC-001', 'ownerUsername': 'test-user'}
        assert api(ACCOUNTS + '/accounts/transfer', service, transfer)[0] == 200
        wrong_client = service_token('other-service', values['OTHER_CLIENT_SECRET'])
        assert api(ACCOUNTS + '/accounts/ACC-001/owner', wrong_client)[0] == 403
        wrong_aud = service_token('wrong-audience', values['WRONG_CLIENT_SECRET'])
        assert api(ACCOUNTS + '/accounts/ACC-001/owner', wrong_aud)[0] == 401
        header, payload, signature = service.split('.')
        data = json.loads(base64.urlsafe_b64decode(payload + '=' * (-len(payload) % 4)))
        data['probe'] = 'tampered'
        changed = base64.urlsafe_b64encode(json.dumps(data).encode()).decode().rstrip('=')
        assert api(ACCOUNTS + '/accounts/ACC-001/owner', '.'.join((header, changed, signature)))[0] == 401
        print('Real Client Credentials, allowed service, wrong client with valid roles, wrong audience and altered signature: passed')
        # Меняется только роль собственного fixture service account; исходное состояние возвращается в finally.
        _, direct = browser()
        status, _, body, _ = request(direct, KC + '/realms/master/protocol/openid-connect/token', {
            'grant_type': 'password', 'client_id': 'admin-cli', 'username': 'admin',
            'password': values['KC_BOOTSTRAP_ADMIN_PASSWORD'],
        })
        assert status == 200
        admin_token = json.loads(body)['access_token']
        def admin(path, method='GET', body=None):
            with urlopen(Request(KC + '/admin/realms/bank-realm' + path, method=method,
                                 data=json.dumps(body).encode() if body is not None else None,
                                 headers={'Authorization': 'Bearer ' + admin_token, 'Content-Type': 'application/json'}), timeout=8) as response:
                data = response.read()
                return json.loads(data) if data else None
        clients = admin('/clients?clientId=transfer-service')
        assert len(clients) == 1 and clients[0]['clientId'] == 'transfer-service'
        account = admin('/clients/' + clients[0]['id'] + '/service-account-user')
        role = admin('/roles/ACCOUNTS_WRITE')
        role_path = '/users/' + account['id'] + '/role-mappings/realm'
        assert any(value['id'] == role['id'] for value in admin(role_path))
        admin(role_path, 'DELETE', [role])
        try:
            limited = service_token('transfer-service', values['TRANSFER_CLIENT_SECRET'])
            assert api(ACCOUNTS + '/accounts/ACC-001/owner', limited)[0] == 200
            assert api(ACCOUNTS + '/accounts/transfer', limited, transfer)[0] == 403
        finally:
            admin(role_path, 'POST', [role])
        print('The same real service identity can read but cannot write without ACCOUNTS_WRITE; fixture role restored: passed')


        follow, direct = browser()
        status, _, body, _ = request(follow, UI + '/transfer')
        assert status == 200
        form = next(form for form in Forms(body).forms if form['id'] == 'kc-form-login')
        form['fields'].update(username='test-user', password=values['LAB_USER_PASSWORD'])
        status, _, body, location = request(follow, form['action'], form['fields'])
        assert status == 200 and location == UI + '/transfer' and 'test-user' in body
        form = next(form for form in Forms(body).forms if urlsplit(form['action']).path == '/transfer')
        assert form['fields'].get('_csrf')
        assert request(direct, UI + '/transfer', transfer)[0] == 403
        form['fields'].update(transfer)
        status, _, body, _ = request(follow, urljoin(UI, form['action']), form['fields'])
        assert status == 200 and 'Запрос принят' in body and 'Денежные остатки не изменились' in body
        status, _, body, _ = request(follow, UI + '/transfer')
        logout = next(form for form in Forms(body).forms if urlsplit(form['action']).path == '/logout')
        assert request(direct, UI + '/logout', {})[0] == 403
        assert request(follow, urljoin(UI, logout['action']), logout['fields'])[0] == 200
        assert request(direct, UI + '/transfer')[0] == 302
        print('Browser Authorization Code + PKCE, CSRF-protected form, four-service confirmation and local logout: passed')

        processes[0].terminate()
        processes[0].wait(timeout=15)
        assert api(GATEWAY + '/api/transfers', user, transfer)[0] == 503
        print('Unavailable accounts dependency is reported as HTTP 503: passed')
    finally:
        for process in processes:
            if process.poll() is None:
                process.terminate()
        for process in processes:
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)
        for log in logs:
            log.close()


if __name__ == '__main__':
    main()
