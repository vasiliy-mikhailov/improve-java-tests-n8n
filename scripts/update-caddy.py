#!/usr/bin/env python3
"""Rewrite ONLY the improve-java-tests-n8n.mikhailov.tech block of the shared Caddyfile,
keeping the existing basic_auth hash. Brace-aware — never touches other site blocks.

Took an n8n-auth JWT as argv[1] and injected it as a cookie on every non-/dashboard
request, because n8n owned those routes and Caddy logged you into it. The Spring
orchestrator serves them itself behind Caddy's own basic_auth, so there is no second
credential to forward and no ten-year token to rotate."""
import re

CADDYFILE = "/home/vmihaylov/java_8_11_17_to_java_21/proxy/Caddyfile"
SITE = "improve-java-tests-n8n.mikhailov.tech"

def main():
    text = open(CADDYFILE).read()

    start = text.find(SITE + " {")
    assert start != -1, f"site block {SITE} not found"
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
		reverse_proxy ijtn8n:3000
	}}
	handle {{
		reverse_proxy ijtn8n:3000
	}}
}}"""
    if new_block == old_block:
        print("caddy block already up to date")
        return
    open(CADDYFILE, "w").write(text[:start] + new_block + text[end:])
    print("caddy block updated")

if __name__ == "__main__":
    main()
