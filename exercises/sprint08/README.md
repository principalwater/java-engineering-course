# Практика спринта 8: Spring Security

Два самостоятельных упражнения показывают разные границы безопасности: пользовательский OIDC-вход и вызов API от имени сервиса.

| Упражнение | Что проверено |
| --- | --- |
| [Вход через Keycloak](oidc-login/README.md) | Authorization Code + PKCE, identity, callback state, CSRF и локальный logout |
| [Пара WebFlux-сервисов](reactive-service-pair/README.md) | Form login, Client Credentials, JWT signature/audience, SERVICE authority и жизненный цикл session cookie |

У каждого стенда свои ports, зависимости, локальные секреты, команды запуска и сквозная HTTP-проверка. Полноценные спринтовые приложения находятся в `projects/`.
