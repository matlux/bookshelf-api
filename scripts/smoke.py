#!/usr/bin/env python3
"""Exercise a running local Compose stack, including Redis/PostgreSQL outages.

Temporarily stops those dependencies and always restarts them. Creates and then
removes only this run's authors and books. Run from the repository directory.
"""
import datetime
import json
import subprocess
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BASE = "http://127.0.0.1:3000"


def compose(*args):
    subprocess.run(["docker", "compose", *args], cwd=ROOT, check=True,
                   stdout=subprocess.DEVNULL)


def request(path, method="GET", body=None, headers=None):
    req = urllib.request.Request(
        BASE + path, method=method,
        data=None if body is None else json.dumps(body).encode(),
        headers={"Content-Type": "application/json", **(headers or {})})
    try:
        response = urllib.request.urlopen(req, timeout=10)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        raw = response.read()
        value = json.loads(raw) if raw and "application/json" in response.headers.get("Content-Type", "") else raw
        return response.status, value, response.headers


def eventually(check, timeout=30):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            if check():
                return
        except (OSError, KeyError, StopIteration):
            pass
        time.sleep(0.1)
    raise AssertionError("Condition did not become true before timeout")


def main():
    authors, books = [], []
    stopped = set()
    prefix = "smoke-" + uuid.uuid4().hex

    def counts(author_id):
        status, rows, _ = request("/api/v1/authors/book-counts")
        assert status == 200, rows
        return next(row for row in rows if row["author-id"] == author_id)

    try:
        eventually(lambda: request("/api/v1/health")[0] == 200)
        assert request("/swagger-ui")[0] == 200
        spec = request("/swagger.json")[1]
        assert "post" in spec["paths"]["/api/v1/authors"]
        assert "delete" in spec["paths"]["/api/v1/authors/{id}"]
        status, _, headers = request("/missing", headers={"X-Request-ID": prefix,
                                                         "Origin": "http://localhost:5173"})
        assert status == 404 and headers["X-Request-ID"] == prefix
        assert headers["Access-Control-Allow-Origin"] == "http://localhost:5173"
        status, _, _ = request("/api/v1/authors", "OPTIONS", headers={
            "Origin": "http://localhost:5173", "Access-Control-Request-Method": "POST",
            "Access-Control-Request-Headers": "Content-Type, X-Request-ID"})
        assert status == 204
        assert request("/api/v1/authors", "OPTIONS", headers={
            "Origin": "https://untrusted.example", "Access-Control-Request-Method": "POST"})[0] == 403
        for name in [prefix + " first", prefix + " second"]:
            status, author, headers = request("/api/v1/authors", "POST", {"name": name})
            assert status == 201, author
            authors.append(author["id"])
            assert headers["Location"].endswith(str(author["id"]))
            assert request(headers["Location"])[1] == author
        assert request(f"/api/v1/authors/{authors[0]}", "PUT", {"name": prefix + " renamed"})[0] == 200
        for title, year in [("Beta", 2002), ("Alpha", 2001), ("Alpha", 2003)]:
            status, book, _ = request("/api/v1/books", "POST", {
                "title": prefix + title, "author-id": authors[0], "published-year": year})
            assert status == 201, book
            books.append(book["id"])
        assert request(f"/api/v1/authors/{authors[0]}", "DELETE")[0] == 409
        query = f"/api/v1/books?author-id={authors[0]}&limit=1&offset=1&sort=title&order=asc&title={prefix}"
        status, result, _ = request(query)
        assert status == 200 and [book["id"] for book in result] == [books[2]], result
        assert request("/api/v1/books?limit=101")[0] == 400
        assert request("/api/v1/books", "POST", {"title": "bad FK", "author-id": 9223372036854775807,
                                                "published-year": 2001})[0] == 422
        eventually(lambda: counts(authors[0])["book-count"] == 3)
        refreshed = counts(authors[0])["refreshed-at"]
        datetime.datetime.fromisoformat(refreshed.replace("Z", "+00:00"))
        print("PASS: HTTP CRUD, FK conflict, pagination/sort/filter, Swagger, CORS, request IDs, timestamps", flush=True)

        stopped.add("redis")
        compose("stop", "redis")
        status, health, _ = request("/api/v1/health")
        assert status == 503 and health["redis"] == "unavailable", (status, health)
        assert request("/api/v1/health/live")[0] == 200
        status, _, _ = request(f"/api/v1/books/{books[0]}", "PUT", {
            "title": prefix + " reassigned while Redis offline", "author-id": authors[1], "published-year": 2004})
        assert status == 200  # Post-commit publication failure cannot undo CRUD.
        compose("start", "redis")
        stopped.remove("redis")
        eventually(lambda: request("/api/v1/health")[0] == 200)
        eventually(lambda: counts(authors[0])["book-count"] == 2 and counts(authors[1])["book-count"] == 1)
        assert counts(authors[0])["refreshed-at"] != refreshed
        print("PASS: actual Redis stop/start, readiness vs liveness, missed-event repair on resubscribe", flush=True)

        stopped.add("postgres")
        compose("stop", "postgres")
        assert request("/api/v1/health/live")[0] == 200
        assert request("/api/v1/health")[0] == 503
        compose("start", "postgres")
        stopped.remove("postgres")
        eventually(lambda: request("/api/v1/health")[0] == 200)
        assert request(f"/api/v1/books/{books[0]}")[1]["author-id"] == authors[1]
        print("PASS: PostgreSQL readiness failure/recovery and PostgreSQL 18 volume persistence", flush=True)
    finally:
        for service in stopped:
            compose("start", service)
        eventually(lambda: request("/api/v1/health")[0] == 200)
        for book_id in books:
            assert request(f"/api/v1/books/{book_id}", "DELETE")[0] in (204, 404)
        for author_id in authors:
            assert request(f"/api/v1/authors/{author_id}", "DELETE")[0] in (204, 404)
        if authors:
            status, rows, _ = request("/api/v1/authors/book-counts")
            assert status == 200 and not any(row["author-id"] in authors for row in rows)
        print("Temporary smoke-test data removed; dependencies running.", flush=True)


if __name__ == "__main__":
    main()
