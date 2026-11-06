import ipaddress
import os


def normalize_proxy_exclusions():
    """HTTPX expects bare IPv6 literals in NO_PROXY, including on Windows."""
    for key in ("NO_PROXY", "no_proxy"):
        value = os.environ.get(key)
        if value is None:
            continue
        entries = []
        for item in value.split(","):
            item = item.strip()
            if item.startswith("[") and item.endswith("]"):
                try:
                    ipaddress.IPv6Address(item[1:-1])
                    item = item[1:-1]
                except ValueError:
                    pass
            entries.append(item)
        os.environ[key] = ",".join(dict.fromkeys(entries))
