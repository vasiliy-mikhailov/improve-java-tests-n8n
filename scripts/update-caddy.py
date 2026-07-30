#!/usr/bin/env python3
"""Rewrite ONLY the improve-java-tests-spring.mikhailov.tech block of the shared Caddyfile,
keeping the existing basic_auth hash. Brace-aware — never touches other site blocks.

Took an n8n-auth JWT as argv[1] and injected it as a cookie on every non-/dashboard
request, because n8n owned those routes and Caddy logged you into it. The Spring
orchestrator serves them itself behind Caddy's own basic_auth, so there is no second
credential to forward and no ten-year token to rotate."""
import re
import socket

CADDYFILE = "/home/vmihaylov/java_8_11_17_to_java_21/proxy/Caddyfile"
# Both, deliberately, and for as long as it takes.
#
# The A-record for the -spring name was still propagating when the rename landed, and Caddy
# cannot obtain a certificate for a hostname that does not resolve — cutting straight over
# would have taken the site down until DNS caught up, with a cert failure as the symptom.
# Caddy accepts a comma-separated list, so both names serve the same container and the old
# one can be dropped once the new certificate is issued.
#
# The block is FOUND by either name, so this script is idempotent across the transition:
# run it before DNS lands, run it after, same result.
NEW_SITE = "improve-java-tests-spring.mikhailov.tech"
OLD_SITE = "improve-java-tests-n8n.mikhailov.tech"


def hostnames():
    """Both names once the new one resolves; the old one alone until then.

    Caddy obtains certificates automatically, and it cannot obtain one for a hostname that
    does not resolve — listing the -spring name before its A-record propagates buys a steady
    trickle of failed ACME challenges and nothing else. So the list is decided at deploy
    time by asking DNS, which makes this script safe to run on either side of propagation
    and correct without anyone remembering to come back and edit it.
    """
    try:
        socket.getaddrinfo(NEW_SITE, 443)
    except socket.gaierror:
        print(f"note: {NEW_SITE} does not resolve yet — serving {OLD_SITE} only")
        return OLD_SITE
    return f"{NEW_SITE}, {OLD_SITE}"

def main():
    text = open(CADDYFILE).read()

    SITE = hostnames()
    start = -1
    for candidate in (SITE, f"{NEW_SITE}, {OLD_SITE}", NEW_SITE, OLD_SITE):
        start = text.find(candidate + " {")
        if start != -1:
            break
    assert start != -1, f"no site block for {NEW_SITE} or {OLD_SITE} found"
    # find matching closing brace
    depth = 0
    end = None
    for i in range(start, len(text)):
        if text[i] == "{":
            depth += 1
        elif text[i] == "}":
            depth -= 1
            if depth == 0:
                end = i + 1
                break
    assert end, "unbalanced braces"
    old_block = text[start:end]

    m = re.search(r"basic_auth\s*\{([^}]*)\}", old_block, re.S)
    basic_auth_body = m.group(1).strip() if m else ""
    assert basic_auth_body, "existing basic_auth credentials not found — refusing to drop auth"

    new_block = f"""{SITE} {{
	basic_auth {{
		{basic_auth_body}
	}}
	redir /dashboard /dashboard/
	handle_path /dashboard/* {{
		reverse_proxy ijtspring:3000
	}}
	handle {{
		reverse_proxy ijtspring:3000
	}}
}}"""
    if new_block == old_block:
        print("caddy block already up to date")
        return
    open(CADDYFILE, "w").write(text[:start] + new_block + text[end:])
    print("caddy block updated")

if __name__ == "__main__":
    main()
