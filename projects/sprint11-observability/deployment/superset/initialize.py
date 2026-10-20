"""Однократная native-инициализация Superset, без пароля в argv/environment."""

from superset.app import create_app
from superset_config import secret


application = create_app()
with application.app_context():
    security = application.appbuilder.sm
    security.add_role('BankAnalyst')
    existing = security.find_user(username='bank-bootstrap')
    if existing is None:
        result = security.add_user(
            username='bank-bootstrap', first_name='Bank', last_name='Bootstrap',
            email='bootstrap@example.test', role=security.find_role('Admin'),
            password=secret('superset_admin_password'),
        )
        if not result:
            raise RuntimeError('Superset bootstrap identity could not be initialized')
