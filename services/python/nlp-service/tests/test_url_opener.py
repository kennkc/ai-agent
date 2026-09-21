from app.url_opener import proxy_bypass_host


def test_proxy_bypass_host_matches_exact_suffix_and_wildcard():
    assert proxy_bypass_host("openrouter.ai", "openrouter.ai") is True
    assert proxy_bypass_host("api.openrouter.ai", "openrouter.ai") is True
    assert proxy_bypass_host("example.com", "openrouter.ai") is False
    assert proxy_bypass_host("anything.example", "*") is True
