"""Exercise the local client → employee → contract → credit API journey.

Usage: py scripts/smoke.py [base-url]. Creates one application in the local database.
"""
from datetime import date, datetime, timedelta, timezone
import http.cookiejar
import json
import sys
import urllib.error
import urllib.request
import uuid

BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080").rstrip("/")


class Client:
    def __init__(self):
        jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
        self.csrf = ""

    def call(self, method, path, body=None, key=None, expected=200):
        headers = {}
        if body is not None:
            headers["Content-Type"] = "application/json"
        if method != "GET" and self.csrf:
            headers["X-CSRF-Token"] = self.csrf
        if key:
            headers["Idempotency-Key"] = key
        request = urllib.request.Request(BASE + "/api/v1" + path,
            data=json.dumps(body).encode() if body is not None else None,
            headers=headers, method=method)
        try:
            response = self.opener.open(request)
            status, data = response.status, json.load(response)
        except urllib.error.HTTPError as error:
            status, data = error.code, json.load(error)
        assert status == expected, (path, status, data)
        return data

    def login(self, login):
        self.csrf = self.call("POST", "/auth/sessions",
            {"login": login, "password": "Alfa2026!"})["csrf"]


employee = Client()
employee.login("staff")
clients = []
for login in ("stable", "gap"):
    candidate = Client()
    candidate.login(login)
    bank_today = date.fromisoformat(candidate.call("GET", "/dashboard")["business_today"])
    needed_by = bank_today + timedelta(days=30)
    limits = candidate.call("GET", "/financing/limits?purpose=EXPANSION&needed_by=" + needed_by.isoformat())
    development = next(row for row in limits["products"] if row["product_id"] == "DEVELOPMENT")
    term = next((row for row in development["terms"] if row["term"] == 24), None)
    if term:
        clients.append((candidate, login, needed_by, min(1_000_000, int(term["max_amount"]))))
if not clients:
    raise RuntimeError("Нет доступного лимита для контрольной заявки на 24 месяца; используйте свежую тестовую БД")
client, client_login, needed_by, amount = clients[0]
outsider = next(candidate for candidate, login, _, _ in clients if login != client_login) if len(clients) > 1 else Client()
if len(clients) == 1:
    outsider.login("gap" if client_login == "stable" else "stable")
draft = client.call("POST", "/drafts")
draft_id = draft["id"]
draft = client.call("PATCH", "/drafts/" + draft_id, {
    "expected_version": draft["version"], "purpose": "EXPANSION",
    "amount": str(amount), "term_months": 24,
    "needed_by": needed_by.isoformat(),
})
quotes = client.call("POST", "/drafts/" + draft_id + "/quotes",
    {"expected_version": draft["version"]})
quote = next(row for row in quotes["options"] if row.get("product_id") == "DEVELOPMENT" and row.get("quote_id"))
app = client.call("POST", "/applications", {
    "draft_id": draft_id, "quote_id": quote["quote_id"],
    "expected_version": draft["version"], "consents": True,
}, uuid.uuid4().hex)
app_id = app["id"]
assert app["status"] == "AWAITING_DECISION" and app["offer"] is None
assert outsider.call("GET", "/applications/" + app_id, expected=404)["code"] == "NOT_FOUND"
assert any(row["id"] == app_id for row in employee.call("GET", "/staff/applications"))
app = employee.call("POST", "/staff/applications/" + app_id + "/claim",
    {"expected_version": app["version"]})
preview = employee.call("POST", "/staff/applications/" + app_id + "/preview", {})
assert preview["report"]["outcome"] in ("APPROVED", "CONDITIONAL", "REJECTED")
body = {
    "expected_version": app["version"], "decision_type": "APPROVE",
    "calculation_token": preview["calculation_token"],
    "amount": preview["amount"], "term_months": preview["term_months"],
    "internal_reason": "Проверены денежный поток, нагрузка и условия продукта",
    "client_message": "Предложение по вашей заявке доступно в кабинете",
    "expires_at": (datetime.now(timezone.utc) + timedelta(days=7)).isoformat(),
}
if preview["report"]["outcome"] == "REJECTED":
    body["divergence_reason"] = "Дополнительная проверка источника погашения подтверждает обслуживание долга"
decision = employee.call("POST", "/staff/applications/" + app_id + "/decision", body)
assert decision["status"] == "APPROVED"
assert employee.call("POST", "/staff/applications/" + app_id + "/decision",
    body, expected=409)["code"] == "STATE_CHANGED"
notice = next(row for row in client.call("GET", "/application-notifications")
    if row["application_id"] == app_id)
assert notice["read_at"] is None
offer = client.call("GET", "/applications/" + app_id)["offer"]
accepted = client.call("POST", "/applications/" + app_id + "/offer/accept",
    {"offer_id": offer["id"], "offer_version": offer["version"]}, uuid.uuid4().hex)
assert accepted["status"] == "OFFER_ACCEPTED"
assert accepted["contract"]["offer_id"] == offer["id"]
key = uuid.uuid4().hex
issued = client.call("POST", "/applications/" + app_id + "/contract/sign",
    {"confirmation": True}, key)
assert issued["status"] == "DISBURSED" and len(issued["schedule"]) == 24
assert client.call("POST", "/applications/" + app_id + "/contract/sign",
    {"confirmation": True}, key)["status"] == "DISBURSED"
assert len([loan for loan in client.call("GET", "/loans") if loan["application_id"] == app_id]) == 1
assert client.call("GET", "/applications/" + app_id)["status"] == "DISBURSED"
print("PASS: HTTP workflow, ownership, employee decision, notification, acceptance, signature, one credit")
