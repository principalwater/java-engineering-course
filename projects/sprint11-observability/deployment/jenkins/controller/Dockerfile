# Официальный Jenkins LTS (2.580.1, JDK 21), закреплён digest мультиархитектурного индекса.
FROM jenkins/jenkins:2.580.1-lts-jdk21@sha256:a660310e39ade10631f774bacd5219de767dfd08947ea5c32a739b5e5bb382c1

# Версии плагинов закреплены в plugins.txt; зависимости разрешаются в минимально нужные версии,
# поэтому одни и те же метаданные update center всегда дают один набор плагинов.
COPY --chown=jenkins:jenkins plugins.txt /usr/share/jenkins/ref/plugins.txt
RUN jenkins-plugin-cli --plugin-file /usr/share/jenkins/ref/plugins.txt --latest false

# Суффикс заставляет entrypoint перезаписывать скрипт в постоянном JENKINS_HOME при каждом старте,
# поэтому пересобранный образ всегда заново применяет настройки безопасности.
COPY --chown=jenkins:jenkins init.groovy.d/10-bank-ci.groovy /usr/share/jenkins/ref/init.groovy.d/10-bank-ci.groovy.override

# Постоянный home может хранить старые копии плагинов; при старте они заменяются закреплёнными версиями.
ENV PLUGINS_FORCE_UPGRADE=true

# Снимок Jenkinsfile — bind mount только для чтения, принадлежащий пользователю хоста, а не uid этого образа.
USER root
RUN git config --system --add safe.directory /srv/bank11-source.git
COPY --chmod=0755 entrypoint.sh /usr/local/bin/bank-controller-entrypoint
USER jenkins

# Тот же entrypoint под tini, что и в базовом образе, но с параметрами HTTPS, собранными из смонтированных секретов.
ENTRYPOINT ["/usr/bin/tini", "--", "/usr/local/bin/bank-controller-entrypoint"]
