"""Run the main demo journey against a running API. Usage: py scripts/smoke.py [base-url]"""
import http.cookiejar
import json
import pathlib
import sys
import urllib.error
import urllib.request
import uuid

BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080").rstrip("/")


class Client:
    def __init__(self):
        self.jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.jar))
        self.csrf = ""

    def call(self, method, path, body=None, key=None, expected=None, multipart=None):
        headers = {}
        if method != "GET" and self.csrf:
            headers["X-CSRF-Token"] = self.csrf
        if key:
            headers["Idempotency-Key"] = key
        if multipart:
            boundary = "----AlfaSmoke" + uuid.uuid4().hex
            raw = b""
            for name, value in multipart.items():
                filename = 'filename="stable_statement.csv"; ' if name == "file" else ""
                raw += (f"--{boundary}\r\nContent-Disposition: form-data; name=\"{name}\"; {filename}\r\n\r\n").encode() + (value if isinstance(value, bytes) else str(value).encode()) + b"\r\n"
            raw += f"--{boundary}--\r\n".encode()
            headers["Content-Type"] = "multipart/form-data; boundary=" + boundary
        elif body is not None:
            raw = json.dumps(body).encode()
            headers["Content-Type"] = "application/json"
        else:
            raw = None
        request = urllib.request.Request(BASE + "/api/v1" + path, data=raw, headers=headers, method=method)
        try:
            response = self.opener.open(request)
            status = response.status
            data = json.load(response)
        except urllib.error.HTTPError as error:
            status = error.code
            data = json.load(error)
        if expected is not None:
            assert status == expected, (path, status, data)
        else:
            assert status < 400, (path, status, data)
        return data

    def login(self, key):
        self.csrf = self.call("POST", "/auth/sessions", {"login": key, "password": "Alfa2026!"})["csrf"]
        return self.call("GET", "/me")


def draft_and_submit(client, amount="3000000", purpose="EXPANSION", months=36, days=None):
    draft = client.call("POST", "/drafts")
    draft = client.call("PATCH", "/drafts/" + draft["id"], {
        "expected_version": draft["version"], "purpose": purpose, "amount": amount,
        "term_months": months, "duration_days": days, "needed_by": "2026-10-22"})
    quote = client.call("POST", "/drafts/" + draft["id"] + "/quotes", {"expected_version": draft["version"]})
    selected = next(option for option in quote["options"] if option.get("quote_id"))
    assert float(selected["schedule"][-1]["remaining"]) == 0
    assert client.call("POST", "/drafts/" + draft["id"] + "/quotes", {"expected_version": 1}, expected=409)["code"] == "DRAFT_VERSION_CONFLICT"
    body = {"draft_id": draft["id"], "expected_version": draft["version"], "quote_id": selected["quote_id"], "consents": True}
    key = uuid.uuid4().hex
    app = client.call("POST", "/applications", body, key)
    assert client.call("POST", "/applications", body, key)["id"] == app["id"]
    return app


stable = Client()
assert stable.call("GET", "/me", expected=401)["code"] == "UNAUTHORIZED"
assert stable.call("POST", "/auth/sessions", {"login": "stable", "password": "wrong"}, expected=401)["code"] == "INVALID_CREDENTIALS"
assert "Кофейня" in stable.login("stable")["label"]
saved_csrf = stable.csrf
stable.csrf = "invalid-csrf"
assert stable.call("POST", "/drafts", expected=403)["code"] == "CSRF"
stable.csrf = saved_csrf
assert len(stable.call("GET", "/insights")["cards"]) == 4
assert len(stable.call("GET", "/cashflow/summary")["months"]) == 6
finance = stable.call("GET", "/finance/overview")
assert finance["data_origin"] == "SYNTHETIC" and len(finance["months"]) == 6
assert len(finance["transactions"]) == 30 and len(finance["products"]) == 5
assert all(row["company_id"] == "STABLE" for row in finance["transactions"] + finance["loans"] + finance["needs"])
assert len([loan for loan in stable.call("GET", "/finance/loans") if loan["source"] == "EXISTING"]) == 2
gap = Client()
assert gap.login("gap")["company_id"] == "GAP"
f = gap.call("GET", "/cashflow/forecast?horizon=30D&scenario=BASE")
assert float(f["gap_amount"]) == 120000, f
app = draft_and_submit(stable)
analysis = stable.call("POST", "/quotes/" + app["quote_id"] + "/analysis", {"horizon": "30D", "scenario": "BASE"})
assert len(analysis["without_credit"]) == 30 and len(analysis["with_credit"]) == 30
assert gap.call("GET", "/applications/" + app["id"], expected=404)["code"] == "NOT_FOUND"
staff = Client()
staff.login("staff")
assert staff.call("GET", "/finance/overview", expected=403)["code"] == "FORBIDDEN"
assert len(staff.call("GET", "/synthetic/profiles")) == 7
assert stable.call("GET", "/synthetic/profiles", expected=403)["code"] == "FORBIDDEN"
assert staff.call("POST", "/applications/" + app["id"] + "/checks", {"demo_profile": "MISSING_INFO"})["status"] == "NEEDS_INFO"
app = stable.call("GET", "/applications/" + app["id"])
request_id = app["requests"][0]["id"]
unverified = stable.call("POST", "/applications/" + app["id"] + "/documents", multipart={"request_id": request_id, "file": b"company_id,monthly_debt\nSTABLE,0\n"}, expected=422)
assert unverified["code"] == "SYNTHETIC_ONLY"
assert stable.call("POST", "/applications/" + app["id"] + "/requests/" + request_id + "/complete", expected=409)["code"] == "DOCUMENT_NOT_VERIFIED"
file_bytes = (pathlib.Path(__file__).parent.parent / "backend/src/main/resources/seed-documents/stable_statement.csv").read_bytes()
uploaded = stable.call("POST", "/applications/" + app["id"] + "/documents", multipart={"request_id": request_id, "file": file_bytes})
assert uploaded["status"] == "VERIFIED", uploaded
with stable.opener.open(BASE + "/api/v1/applications/" + app["id"] + "/documents/" + uploaded["id"]) as downloaded:
    assert downloaded.read() == file_bytes
approved = stable.call("POST", "/applications/" + app["id"] + "/requests/" + request_id + "/complete")
assert approved["status"] == "APPROVED"
assert staff.call("GET", "/staff/applications/" + app["id"])["score"] >= 75
app = stable.call("GET", "/applications/" + app["id"])
accepted = stable.call("POST", "/applications/" + app["id"] + "/offer/accept", {"offer_version": app["offer_version"]}, uuid.uuid4().hex)
assert accepted["status"] == "OFFER_ACCEPTED"
assert stable.call("POST", "/applications/" + app["id"] + "/contract/sign", {"confirmation": True}, uuid.uuid4().hex)["status"] == "SIGNED"
key = uuid.uuid4().hex
assert stable.call("POST", "/applications/" + app["id"] + "/disburse", key=key)["status"] == "DISBURSED"
assert stable.call("POST", "/applications/" + app["id"] + "/disburse", key=key)["status"] == "DISBURSED"
assert len([loan for loan in stable.call("GET", "/loans") if loan["application_id"] == app["id"]]) == 1
issued_loan = next(loan for loan in stable.call("GET", "/loans") if loan["application_id"] == app["id"])
finance_loan = next(loan for loan in stable.call("GET", "/finance/loans") if loan["id"] == issued_loan["id"])
assert finance_loan["source"] == "ISSUED" and finance_loan["end_date"]
counter = draft_and_submit(stable)
counter = staff.call("POST", "/applications/" + counter["id"] + "/checks", {"demo_profile": "COUNTEROFFER"})
assert counter["status"] == "COUNTEROFFER" and float(counter["amount"]) < 3000000, counter
assert staff.call("POST", "/applications/" + counter["id"] + "/checks", {"demo_profile": "GOOD"}, expected=409)["code"] == "INVALID_STATE"
rejected = draft_and_submit(stable)
rejected = staff.call("POST", "/applications/" + rejected["id"] + "/checks", {"demo_profile": "HIGH_RISK"})
assert rejected["status"] == "REJECTED"
assert stable.call("POST", "/applications/" + rejected["id"] + "/contract/sign", {"confirmation": True}, uuid.uuid4().hex, expected=409)["code"] == "INVALID_STATE"
gap_app = draft_and_submit(gap, "120000", "CASH_GAP", None, 5)
assert staff.call("POST", "/applications/" + gap_app["id"] + "/checks", {"demo_profile": "GAP"})["status"] == "APPROVED"
failure = draft_and_submit(stable)
failure = staff.call("POST", "/applications/" + failure["id"] + "/checks", {"demo_profile": "MOCK_FAILURE"})
assert failure["status"] == "CHECKING" and failure["decision_report"]["outcome"] == "SOURCE_ERROR"
print("PASS: credentials, 4 sections, 7 profiles, GAP forecast, ownership, stale quote, idempotency, verified document, disbursement, decisions and mock failure")
