"""Проверка брокера и независимого восстановления проекций на работающем стенде."""

import hashlib
import uuid


def verify_analytics_failures(runtime_module, users, containers):
    """Использует уже проверенные сессии; останавливает только Cassandra/ClickHouse своего Kubernetes namespace."""
    runtime = runtime_module
    alice, analyst = users["alice"], users["analyst"]
    runtime.require(analyst is not None, "Projection recovery requires the analyst fixture")

    def available(user, path):
        def loaded():
            value = user.json("GET", path)
            return value if value.get("available") else None
        return runtime.wait_for("available projection", loaded)

    def event_matches(event_id, cassandra, warehouse):
        condition = ("cassandra_done = " + str(cassandra).lower()
                     + " AND warehouse_done = " + str(warehouse).lower())
        return runtime.scalar(containers, "SELECT count(*) FROM analytics.analytics_inbox WHERE event_id='"
                              + event_id + "' AND " + condition) == 1

    for dependency, amount, unavailable_user, unavailable_path, independent_user, independent_path in (
            ("cassandra", 11, alice, "/ui/analytics", analyst, "/ui/analytics/business"),
            ("clickhouse", 13, analyst, "/ui/analytics/business", alice, "/ui/analytics")):
        runtime.wait_for("previous source and projection deliveries", lambda: runtime.scalar(containers,
            "SELECT (SELECT count(*) FROM accounts.bank_kafka_outbox WHERE topic='bank.accounts.analytics.v1' AND status='PENDING')"
            " + (SELECT count(*) FROM analytics.analytics_inbox WHERE NOT (cassandra_done AND warehouse_done))") == 0)
        personal_before = available(alice, "/ui/analytics")
        business_before = available(analyst, "/ui/analytics/business")
        account = alice.account()
        runtime.require(account.get("profileComplete"), "Projection fixture requires a complete profile")
        owner = runtime.checked_uuid(account["accountId"])
        command = runtime.cash(amount)
        identity = owner + ":" + owner + ":DEPOSIT:" + command["operationId"]
        event_id = str(uuid.UUID(bytes=hashlib.md5(identity.encode("utf-8")).digest(), version=3))
        ledger_sql = ("SELECT count(*) FROM accounts.ledger_entry WHERE owner_id='" + owner
                      + "' AND command_owner_id='" + owner + "' AND operation_type='DEPOSIT' AND request_id='"
                      + runtime.checked_uuid(command["operationId"]) + "'")
        runtime.require(runtime.scalar(containers, ledger_sql) == 0, "Projection fixture command already exists")
        try:
            runtime.change_state(containers, dependency, "stop")
            original = alice.money("/ui/cash", command)
            runtime.assert_success(original)
            runtime.require(original["balanceMinor"] == account["balanceMinor"] + amount,
                            "Projection outage blocked the committed deposit")
            runtime.wait_for("independent projection delivery", lambda: event_matches(
                event_id, dependency not in ("cassandra", "kafka"), dependency not in ("clickhouse", "kafka")))
            runtime.require(runtime.scalar(containers, ledger_sql) == 1,
                            "Projection outage changed the ledger effect count")
            unavailable = unavailable_user.json("GET", unavailable_path)
            runtime.require(unavailable.get("available") is False
                            and unavailable.get("operationsCount") is None,
                            "Unavailable projection invented a financial count")
            available(independent_user, independent_path)
        finally:
            # Даже провал проверки возвращает исходное хранилище без удаления его volume.
            runtime.change_state(containers, dependency, "start")

        runtime.wait_for("both projection acknowledgements", lambda: event_matches(event_id, True, True))
        personal_after = available(alice, "/ui/analytics")
        business_after = available(analyst, "/ui/analytics/business")
        runtime.require(personal_after["operationsCount"] == personal_before["operationsCount"] + 1
                        and business_after["operationsCount"] == business_before["operationsCount"] + 1,
                        "Projection recovery lost or duplicated a financial fact")
        runtime.require(alice.money("/ui/cash", command) == original, "Recovered deposit replay changed its outcome")
        runtime.require(alice.account()["balanceMinor"] == account["balanceMinor"] + amount
                        and runtime.scalar(containers, ledger_sql) == 1,
                        "Projection recovery repeated a money effect")
        runtime.require(available(alice, "/ui/analytics")["operationsCount"] == personal_after["operationsCount"]
                        and available(analyst, "/ui/analytics/business")["operationsCount"] == business_after["operationsCount"],
                        "Immutable command replay duplicated a projected operation")
        print("PASS " + dependency + " outage: durable projection recovery and one money effect")
