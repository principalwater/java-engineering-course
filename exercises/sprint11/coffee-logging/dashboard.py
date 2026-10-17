#!/usr/bin/env python3
"""Создаёт пять панелей, экспортирует зависимости и проверяет импорт в отдельный Space."""
import json
from urllib.error import HTTPError

from bootstrap import ROOT, request, wait_for


def saved(kind, object_id, attributes, references=None):
    return request(f"/api/saved_objects/{kind}/{object_id}?overwrite=true", {
        "attributes": attributes, "references": references or [],
    }, "POST", user="study", kibana=True)


def visualization(object_id, title, aggregation, encoding, mark):
    spec = {
        "$schema": "https://vega.github.io/schema/vega-lite/v5.json",
        "data": {"url": {"%context%": True, "%timefield%": "@timestamp", "index": "coffee-logs-*",
                         "body": {"size": 0, "aggs": {"values": aggregation}}},
                 "format": {"property": "aggregations.values.buckets"}},
        "mark": mark, "encoding": encoding,
    }
    saved("visualization", object_id, {
        "title": title,
        "visState": json.dumps({"title": title, "type": "vega", "aggs": [], "params": {"spec": json.dumps(spec)}}),
        "uiStateJSON": "{}", "kibanaSavedObjectMeta": {"searchSourceJSON": "{}"},
    })


def setup():
    wait_for(lambda: request("/api/status", user="study", kibana=True)["status"]["overall"]["level"] == "available", "Kibana")
    wait_for(lambda: request("/api/data_views/data_view", {"data_view": {
        "id": "coffee-logs", "name": "Coffee Logs", "title": "coffee-logs-*", "timeFieldName": "@timestamp",
    }, "override": True}, "POST", user="study", kibana=True), "Kibana authorized Data View creation")
    visualization("coffee-activity", "Активность по времени", {
        "date_histogram": {"field": "@timestamp", "fixed_interval": "1m", "min_doc_count": 0},
    }, {"x": {"field": "key", "type": "temporal", "title": "Время приёма, UTC", "scale": {"type": "utc"}, "axis": {"format": "%H:%M"}},
        "y": {"field": "doc_count", "type": "quantitative", "title": "События"}}, {"type": "line", "point": True})
    for object_id, title, field, radius in [
        ("coffee-types", "Типы кофе", "coffeeType", 0),
        ("coffee-sorts", "Сорта кофе", "coffeeSort", 60),
    ]:
        visualization(object_id, title, {"terms": {"field": field, "size": 10}}, {
            "theta": {"field": "doc_count", "type": "quantitative"},
            "color": {"field": "key", "type": "nominal", "title": None},
            "tooltip": [{"field": "key", "title": "Кофе"}, {"field": "doc_count", "title": "События"}],
        }, {"type": "arc", "innerRadius": radius})
    visualization("coffee-levels", "Количество по уровням", {"terms": {"field": "log_level", "size": 10}}, {
        "column": {"field": "key", "type": "nominal", "title": None},
        "text": {"field": "doc_count", "type": "quantitative"},
        "color": {"field": "key", "type": "nominal", "legend": None},
    }, {"type": "text", "fontSize": 54, "fontWeight": "bold"})
    saved("search", "coffee-latest", {
        "title": "Последние действия", "columns": ["log_level", "coffeeType", "coffeeSort", "volume"],
        "sort": [["@timestamp", "desc"]],
        "kibanaSavedObjectMeta": {"searchSourceJSON": json.dumps({
            "indexRefName": "kibanaSavedObjectMeta.searchSourceJSON.index", "query": {"query": "", "language": "kuery"}, "filter": [],
        })},
    }, [{"name": "kibanaSavedObjectMeta.searchSourceJSON.index", "id": "coffee-logs", "type": "index-pattern"}])
    panels, references = [], []
    layout = [("coffee-activity", "visualization", 0, 0, 30, 12),
              ("coffee-levels", "visualization", 30, 0, 18, 12),
              ("coffee-types", "visualization", 0, 12, 24, 13),
              ("coffee-sorts", "visualization", 24, 12, 24, 13),
              ("coffee-latest", "search", 0, 25, 48, 14)]
    for position, (object_id, kind, x, y, width, height) in enumerate(layout, 1):
        reference = f"panel_{position}"
        panels.append({"panelIndex": str(position), "panelRefName": reference, "type": kind,
                       "gridData": {"x": x, "y": y, "w": width, "h": height, "i": str(position)},
                       "embeddableConfig": {}, "version": "9.0.0"})
        references.append({"name": reference, "type": kind, "id": object_id})
    saved("dashboard", "coffee-dashboard", {
        "title": "Coffee Dashboard", "description": "События кофе-машины: приём, распределение, ошибки и последние действия",
        "panelsJSON": json.dumps(panels), "optionsJSON": json.dumps({"useMargins": True, "hidePanelTitles": False}),
        "timeRestore": True, "timeFrom": "now-24h", "timeTo": "now",
        "kibanaSavedObjectMeta": {"searchSourceJSON": json.dumps({"query": {"query": "", "language": "kuery"}, "filter": []})},
    }, references)
    export = request("/api/saved_objects/_export", {
        "objects": [{"type": "dashboard", "id": "coffee-dashboard"}], "includeReferencesDeep": True, "excludeExportDetails": True,
    }, "POST", user="study", kibana=True, raw=True)
    objects = [json.loads(line) for line in export.splitlines()]
    assert {item["id"] for item in objects} == {"coffee-dashboard", "coffee-logs", *(row[0] for row in layout)}
    (ROOT / "kibana/coffee-dashboard.ndjson").write_bytes(export)
    try:
        request("/api/spaces/space", {"id": "coffee-import", "name": "Coffee import", "disabledFeatures": []}, "POST", user="study", kibana=True)
    except HTTPError as error:
        if error.code != 409:
            raise
    boundary = "coffee-import-boundary"
    body = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"coffee-dashboard.ndjson\"\r\n"
            "Content-Type: application/x-ndjson\r\n\r\n").encode() + export + f"\r\n--{boundary}--\r\n".encode()
    imported = request("/s/coffee-import/api/saved_objects/_import?overwrite=true", body, "POST", user="study", kibana=True,
                       content_type=f"multipart/form-data; boundary={boundary}")
    assert imported["success"] and imported["successCount"] == len(objects), "Saved object import failed"
    # Kibana меняет ID при импорте в другой Space, сохраняя originId.
    found = request("/s/coffee-import/api/saved_objects/_find?type=dashboard&per_page=100", user="study", kibana=True)
    dashboard = next(item for item in found["saved_objects"] if item.get("originId", item["id"]) == "coffee-dashboard")
    assert len(json.loads(dashboard["attributes"]["panelsJSON"])) == 5
    (ROOT / "target/dashboard-links.json").write_text(json.dumps({
        "source": "http://127.0.0.1:19601/app/dashboards#/view/coffee-dashboard",
        "imported": f"http://127.0.0.1:19601/s/coffee-import/app/dashboards#/view/{dashboard['id']}",
    }))
    print("Five panels and Data View exported and imported with all seven dependencies")


if __name__ == "__main__":
    setup()
