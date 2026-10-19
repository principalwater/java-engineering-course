"""Конфигурация vendor-приложения; доставку событий и BI-активов выполняет Java."""

import os
from pathlib import Path
from urllib.parse import quote

from flask_appbuilder.security.manager import AUTH_OAUTH
from superset.security import SupersetSecurityManager


def secret(name):
    value = Path('/run/secrets', name).read_text(encoding='utf-8').strip()
    if len(value) != 64 or any(character not in '0123456789abcdef' for character in value):
        raise RuntimeError('Invalid Superset secret file')
    return value


SECRET_KEY = secret('superset_secret_key')
SQLALCHEMY_DATABASE_URI = (
    'postgresql+psycopg2://superset:' + quote(secret('db_superset_password'), safe='')
    + '@' + os.environ.get('BANK_BI_DATABASE_HOST', 'database') + ':5432/bank'
)
SQLALCHEMY_ENGINE_OPTIONS = {
    'pool_pre_ping': True,
    'pool_size': 3,
    'max_overflow': 1,
    'pool_timeout': 3,
    'connect_args': {'options': '-csearch_path=superset -cstatement_timeout=15000', 'connect_timeout': 5},
}
AUTH_TYPE = AUTH_OAUTH
AUTH_USER_REGISTRATION = True
AUTH_USER_REGISTRATION_ROLE = 'Gamma'
AUTH_ROLES_SYNC_AT_LOGIN = True
AUTH_ROLES_MAPPING = {'bank_analyst': ['Gamma', 'BankAnalyst']}
FAB_ADD_SECURITY_API = True
WTF_CSRF_ENABLED = True
SESSION_COOKIE_SECURE = True
SESSION_COOKIE_HTTPONLY = True
SESSION_COOKIE_SAMESITE = 'Lax'
ENABLE_PROXY_FIX = True
PROXY_FIX_CONFIG = {'x_for': 1, 'x_proto': 1, 'x_host': 1, 'x_port': 1, 'x_prefix': 0}
TALISMAN_ENABLED = True
TALISMAN_CONFIG = {
    'force_https': False,
    'content_security_policy': {
        'default-src': ["'self'"],
        'img-src': ["'self'", 'data:', 'blob:'],
        'style-src': ["'self'", "'unsafe-inline'"],
        'script-src': ["'self'", "'strict-dynamic'"],
        'connect-src': ["'self'"],
        'object-src': ["'none'"],
        'base-uri': ["'self'"],
        'frame-ancestors': ["'none'"],
    },
    'content_security_policy_nonce_in': ['script-src'],
    'session_cookie_secure': True,
}
FEATURE_FLAGS = {'SQLLAB_BACKEND_PERSISTENCE': False, 'GLOBAL_ASYNC_QUERIES': False}
SQL_MAX_ROW = 1000
ROW_LIMIT = 1000
SUPERSET_WEBSERVER_TIMEOUT = 30
SQLLAB_TIMEOUT = 15
WTF_CSRF_TIME_LIMIT = 3600
MAPBOX_API_KEY = ''
SECRET_KEY_WARNING = False
PUBLIC_ROLE_LIKE = None

PUBLIC_ISSUER = os.environ.get('BANK_BI_PUBLIC_AUTH_URL', 'https://auth-bank.valgrind-home.localhost:8450/realms/bank')
INTERNAL_ISSUER = os.environ.get('BANK_BI_AUTH_URL', 'http://authorization:8080/realms/bank')
OAUTH_PROVIDERS = [{
    'name': 'bank',
    'icon': 'fa-bank',
    'token_key': 'access_token',
    'remote_app': {
        'client_id': 'bank-insights',
        'client_secret': secret('oauth_insights_secret'),
        'api_base_url': INTERNAL_ISSUER + '/protocol/openid-connect/',
        'access_token_url': INTERNAL_ISSUER + '/protocol/openid-connect/token',
        'authorize_url': PUBLIC_ISSUER + '/protocol/openid-connect/auth',
        'jwks_uri': INTERNAL_ISSUER + '/protocol/openid-connect/certs',
        'issuer': PUBLIC_ISSUER,
        'client_kwargs': {'scope': 'openid profile email', 'code_challenge_method': 'S256'},
    },
}]


class AnalystSecurityManager(SupersetSecurityManager):
    def oauth_user_info(self, provider, response=None):
        if provider != 'bank':
            return {}
        result = self.oauth_remotes[provider].get('userinfo')
        result.raise_for_status()
        user = result.json()
        # Права берём из доверенного backchannel, не из email, формы или query string.
        if 'analyst' not in user.get('realm_access', {}).get('roles', []):
            return {}
        return {
            'username': 'bank-' + user['sub'],
            'first_name': user.get('given_name', ''),
            'last_name': user.get('family_name', ''),
            'email': user.get('email', ''),
            'role_keys': ['bank_analyst'],
        }


CUSTOM_SECURITY_MANAGER = AnalystSecurityManager
