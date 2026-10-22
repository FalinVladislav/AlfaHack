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
        "term_months": months, "duration_days": days, "needed_by": "2026-11-01"})
    quote = client.call("POST", "/drafts/" + draft["id"] + "/quotes", {"expected_version": draft["version"]})
    selected = next(option for option in quote["options"] if option.get("quote_id"))
    assert float(selected["schedule"][-1]["remaining"]) == 0
    assert client.call("POST", "/drafts/" + draft["id"] + "/quotes", {"expected_version": 1}, expected=409)["code"] == "DRAFT_VERSION_CONFLICT"
    body = {"draft_id": draft["id"], "expected_version": draft["version"], "quote_id": selected["quote_id"], "consents": True}
    key = uuid.uuid4().hex
    app = client.call("POST", "/applications", body, key)
    assert client.call("POST", "/applications", body, key)["id"] == app["id"]
    return app


def main():
    staff=Client(); staff.login("staff")
    clients={}
    for login in ["stable","gap","reject","missing","counter"]:
        client=Client();client.login(login);clients[login]=client
        dashboard=client.call("GET","/dashboard")
        assert dashboard["forecast"]["points"]
        finance=client.call("GET","/finance/overview")
        assert all(r["company_id"]==login.upper() for r in finance["transactions"])
        assert client.call("GET","/insights")["cards"]

    for login, expected in [("stable","APPROVED"),("reject","REJECTED"),("counter","COUNTEROFFER"),("missing","NEEDS_INFO")]:
        client=clients[login]
        app=draft_and_submit(client,"1000000",months=24)
        assert app["status"]==expected,(login,app)
        app_id=app["id"]
        assert staff.call("POST",f"/applications/{app_id}/checks",{"demo_profile":"IT"},expected=409)["code"]=="PROFILE_LOCKED"
        clients["gap"].call("GET",f"/applications/{app_id}",expected=404)
        if login=="missing":
            request=app["requests"][0]["id"]
            content=(pathlib.Path(__file__).resolve().parents[1]/"backend/src/main/resources/seed-documents/missing_statement.csv").read_bytes()
            client.call("POST",f"/applications/{app_id}/documents",multipart={"request_id":request,"file":content})
            app=client.call("POST",f"/applications/{app_id}/requests/{request}/complete")
            assert app["status"]=="APPROVED",app
        if login=="stable":
            client.call("POST",f"/applications/{app_id}/offer/accept",{"offer_version":app["offer_version"]},uuid.uuid4().hex)
            client.call("POST",f"/applications/{app_id}/contract/sign",{"confirmation":True},uuid.uuid4().hex)
            key=uuid.uuid4().hex
            issued=client.call("POST",f"/applications/{app_id}/disburse",key=key)
            assert client.call("POST",f"/applications/{app_id}/disburse",key=key)["loan"]["id"]==issued["loan"]["id"]
        print("PASS",login,expected,"events",len(app["events"]))

    client=clients["gap"]
    draft=client.call("POST","/drafts")
    draft=client.call("PATCH","/drafts/"+draft["id"],{"expected_version":draft["version"],"purpose":"CASH_GAP","amount":"200000","duration_days":45,"term_months":None,"needed_by":"2026-11-01"})
    quotes=client.call("POST","/drafts/"+draft["id"]+"/quotes",{"expected_version":draft["version"]})
    q=next(q for q in quotes["options"] if q.get("quote_id"))
    a=client.call("POST","/quotes/"+q["quote_id"]+"/analysis",{"horizon":"FULL","scenario":"BASE"})
    assert len(a["payments"])==1
    assert float(a["minimum_with"])==min(float(p["balance"]) for p in a["with_credit"])
    assert clients["stable"].call("POST","/assistant/messages",{"message":"Почему разрыв?"})["available"] is False
    # Parameter changes invalidate previous analysis and produce fresh daily series.
    old_quote=q["quote_id"]
    draft=client.call("PATCH","/drafts/"+draft["id"],{"expected_version":draft["version"],"purpose":"CASH_GAP","amount":"100000","duration_days":30,"term_months":None,"needed_by":"2026-11-02"})
    client.call("POST","/quotes/"+old_quote+"/analysis",{"horizon":"FULL"},expected=409)
    updated=client.call("POST","/drafts/"+draft["id"]+"/quotes",{"expected_version":draft["version"]})
    q2=next(q for q in updated["options"] if q.get("quote_id"))
    a2=client.call("POST","/quotes/"+q2["quote_id"]+"/analysis",{"horizon":"FULL","scenario":"BASE"})
    assert a2["with_credit"]!=a["with_credit"] and a2["issue_date"]=="2026-11-02"
    draft=client.call("PATCH","/drafts/"+draft["id"],{"expected_version":draft["version"],"purpose":"EXPANSION","amount":"1000000","duration_days":None,"term_months":24,"needed_by":"2026-11-02"})
    options=client.call("POST","/drafts/"+draft["id"]+"/quotes",{"expected_version":draft["version"]})["options"]
    comparisons=[client.call("POST","/quotes/"+option["quote_id"]+"/analysis",{"horizon":"FULL","scenario":"BASE"}) for option in options if option.get("quote_id")]
    assert len(comparisons)>1 and comparisons[0]["payments"]!=comparisons[1]["payments"]
    print("PASS: amount, term, issue date, purpose and product recalculate server series; stale quotes rejected")
    print("PASS: automatic company-bound outcomes, ownership, history, documents, idempotency, full-term analysis, unavailable model")


if __name__ == "__main__":
    main()
