#!/usr/bin/env python3
## Licensed under the terms of http://www.apache.org/licenses/LICENSE-2.0
"""Serve the demo app and proxy Fuseki requests through the same origin.

Copied from ../demo/serve_app.py. Two things here are load-bearing rather than
incidental: the Cache-Control rewrite that makes per-IRI label lookups actually cacheable,
and the ADMIN_ALLOWED gate, which stops this proxy from laundering Fuseki's
localhost-only admin check.
"""

from __future__ import annotations

import argparse
import http.client
import io
import posixpath
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import Request, urlopen


# Labels change rarely; the app busts this with the ?v= salt from app-config.js when it
# needs to. A day is long enough that a returning view costs no network transfer at all.
LABEL_MAX_AGE = 86400


def _is_label_request(path: str) -> bool:
    """A label lookup is a GET query carrying the app's cache salt (see labels.js)."""
    query = urlsplit(path).query
    return "v=" in query and "query=" in query


# Admin paths this proxy is willing to forward, and the methods it will forward them
# with. Everything else under Fuseki's "/$/" space is refused here.
#
# This matters more than it looks. Fuseki's default shiro.ini guards "/$/**" with
# LocalhostFilter, which compares the *socket's* remote address. This proxy connects to
# Fuseki from localhost, so every request it forwards satisfies that filter no matter
# where the browser was. Without this list, running the demo on a machine reachable by
# anyone else puts dataset creation and deletion (POST/DELETE /$/datasets), backups and
# compaction one URL away for them - the localhost gate is laundered, not enforced.
#
# The app needs exactly three paths: {dataset}/query, /$/config and /$/config/{id}.
ADMIN_PREFIX = "/$/"
ADMIN_ALLOWED = (
    ("/$/config", ("GET",)),
)


def _admin_forward_allowed(path: str, method: str) -> bool:
    """Whether an admin path may be forwarded. Non-admin paths are not this function's business."""
    bare = urlsplit(path).path
    for prefix, methods in ADMIN_ALLOWED:
        if (bare == prefix or bare.startswith(prefix + "/")) and method in methods:
            return True
    return False


class DemoAppHandler(SimpleHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def __init__(self, *args, directory: str, backend: str, proxy_prefix: str, **kwargs):
        self.backend = backend.rstrip("/")
        self.proxy_prefix = "/" + proxy_prefix.strip("/")
        super().__init__(*args, directory=directory, **kwargs)

    def do_GET(self):
        if self.path.startswith(self.proxy_prefix + "/"):
            self._proxy_request()
            return
        super().do_GET()

    def do_POST(self):
        if self.path.startswith(self.proxy_prefix + "/"):
            self._proxy_request()
            return
        self.send_error(405, "Method Not Allowed")

    def do_OPTIONS(self):
        if self.path.startswith(self.proxy_prefix + "/"):
            self.send_response(204)
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        self.send_error(405, "Method Not Allowed")

    def translate_path(self, path: str) -> str:
        path = path.split("?", 1)[0].split("#", 1)[0]
        trailing_slash = path.rstrip().endswith("/")
        parts = [part for part in posixpath.normpath(path).split("/") if part and part not in (".", "..")]
        resolved = Path(self.directory)
        for part in parts:
            resolved /= part
        if trailing_slash:
            resolved /= ""
        return str(resolved)

    def _proxy_request(self):
        backend_path = self.path[len(self.proxy_prefix):]
        if backend_path.startswith(ADMIN_PREFIX) and not _admin_forward_allowed(backend_path, self.command):
            # Refused here rather than passed to Fuseki, which would see it as a
            # localhost request and allow it. See ADMIN_ALLOWED.
            self.send_error(403, "Admin path not proxied by the demo server")
            return
        target = self.backend + backend_path
        cacheable = self.command == "GET" and _is_label_request(self.path)
        body = None
        content_length = self.headers.get("Content-Length")
        if content_length:
            body = self.rfile.read(int(content_length))

        headers = {}
        for name in ("Content-Type", "Accept", "Authorization"):
            value = self.headers.get(name)
            if value:
                headers[name] = value

        request = Request(target, data=body, headers=headers, method=self.command)

        try:
            with urlopen(request) as response:
                payload = response.read()
                self.send_response(response.status)
                self._copy_response_headers(response.headers, len(payload), cacheable)
                self.end_headers()
                if payload:
                    self.wfile.write(payload)
        except HTTPError as error:
            payload = error.read()
            self.send_response(error.code, error.reason)
            self._copy_response_headers(error.headers, len(payload))
            self.end_headers()
            if payload:
                self.wfile.write(payload)
        except URLError as error:
            message = f"Proxy error: {error.reason}\n".encode("utf-8")
            self.send_response(502, "Bad Gateway")
            self.send_header("Content-Type", "text/plain; charset=utf-8")
            self.send_header("Content-Length", str(len(message)))
            self.end_headers()
            self.wfile.write(message)

    def _copy_response_headers(self, headers: http.client.HTTPMessage,
                               content_length: int, cacheable: bool = False):
        excluded = {"connection", "content-length", "transfer-encoding", "server", "date"}
        if cacheable:
            # Fuseki answers every query with "must-revalidate,no-cache,no-store", which
            # makes the browser cache useless for label lookups. Labels are the one thing
            # the app fetches per-IRI over GET, so they are individually cacheable — drop
            # Fuseki's header and set our own. Bump the ?v= salt to invalidate.
            excluded |= {"cache-control", "pragma", "expires"}
        for key, value in headers.items():
            if key.lower() not in excluded:
                self.send_header(key, value)
        if cacheable:
            self.send_header("Cache-Control", f"public, max-age={LABEL_MAX_AGE}")
        self.send_header("Content-Length", str(content_length))


def main():
    parser = argparse.ArgumentParser(description="Serve the demo app with a Fuseki proxy.")
    parser.add_argument("--port", type=int, default=8000)
    parser.add_argument("--directory", default="app-static")
    parser.add_argument("--backend", required=True)
    parser.add_argument("--proxy-prefix", default="/fuseki")
    args = parser.parse_args()

    handler = partial(
        DemoAppHandler,
        directory=str(Path(args.directory).resolve()),
        backend=args.backend,
        proxy_prefix=args.proxy_prefix,
    )
    server = ThreadingHTTPServer(("0.0.0.0", args.port), handler)
    print(f"Serving app at http://localhost:{args.port}")
    print(f"Proxying {args.proxy_prefix} -> {args.backend}")
    server.serve_forever()


if __name__ == "__main__":
    main()
