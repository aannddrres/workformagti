"""A10: tripwires that keep ASVS "not applicable" and "by construction" rows true.

Each test pins a fact about the source that an ASVS row depends on: no XML is
parsed, no OS command runs, the one trust bypass builds a fixed-origin URL,
and so on. They read files, run nothing, and fail with the file and line that
broke the fact, so that a later change which makes a row applicable again is
caught by the build instead of by the next audit. The register that cites
them is docs/security/ASVS_L2_REVIEW_2026-09-26.csv.

An allowlist entry is a reviewed exception, never a way to silence a hit:
each one says why it is safe.
"""

from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA_MAIN = ROOT / "java-backend" / "src" / "main" / "java"
RESOURCES = ROOT / "java-backend" / "src" / "main" / "resources"
MIGRATIONS = RESOURCES / "db" / "migration"
POM = ROOT / "java-backend" / "pom.xml"
FRONTEND = ROOT / "angular-frontend"
FRONTEND_APP = FRONTEND / "src"
NGINX = FRONTEND / "nginx.conf.template"


def java_sources() -> list[Path]:
    return sorted(JAVA_MAIN.rglob("*.java"))


def frontend_sources(*suffixes: str) -> list[Path]:
    return sorted(p for p in FRONTEND_APP.rglob("*")
                  if p.suffix in suffixes and not p.name.endswith(".spec.ts"))


def hits(paths: list[Path], pattern: str, flags: int = 0) -> list[str]:
    """Every `path:line: text` where the pattern matches."""
    regex = re.compile(pattern, flags)
    found = []
    for path in paths:
        for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
            if regex.search(line):
                # as_posix: the allow-lists are written with "/", and on Windows
                # relative_to() yields "\", which made every tripwire fire there.
                found.append(f"{path.relative_to(ROOT).as_posix()}:{number}: {line.strip()}")
    return found


def outside(found: list[str], *allowed: str) -> list[str]:
    return [line for line in found if not any(line.startswith(prefix) for prefix in allowed)]


# ── V1 encoding, injection and parsing ─────────────────────────────────────


def test_url_decoding_only_for_the_client_credential():
    # The client credential is form-encoded per RFC 6749 §2.3.1; nothing else
    # decodes by hand, so request input is decoded once, by the container.
    assert outside(hits(java_sources(), r"URLDecoder|UriUtils\.decode"),
                   "java-backend/src/main/java/ge/magti/portal/security/CorporateClientCredential.java") == []
    # Legacy Google Sites links are decoded only to compare them with known
    # page names; the result is never used as markup or as a URL.
    assert outside(hits(frontend_sources(".ts"), r"decodeURI(Component)?\("),
                   "angular-frontend/src/app/shared/format-article-content.ts") == []


def test_trust_bypass_only_for_the_youtube_embed():
    found = hits(frontend_sources(".ts"), r"bypassSecurityTrust")
    player = "angular-frontend/src/app/features/videos/video-detail-page.ts"
    assert outside(found, player) == []
    calls = [line for line in found if "bypassSecurityTrust" in line.split(": ", 1)[1] and "import" not in line]
    assert calls and all("bypassSecurityTrustResourceUrl(embed)" in line for line in calls), calls
    source = (ROOT / player).read_text(encoding="utf-8")
    assert "const embed = v ? toYoutubeEmbedUrl(v.video_url) : null;" in source
    youtube = (FRONTEND_APP / "app" / "shared" / "youtube.ts").read_text(encoding="utf-8")
    assert "return id ? `https://www.youtube.com/embed/${id}?rel=0` : null;" in youtube


def test_no_json_built_by_hand():
    # One constant body for the 401 written before MVC exists.
    assert outside(hits(java_sources(), r'"\{\\"'),
                   "java-backend/src/main/java/ge/magti/portal/security/SecurityConfig.java") == []


# Files whose SQL is assembled from pieces. Each was read on 2026-09-26: the
# concatenated pieces are literals, constants or column lists chosen by the
# code, and every request value is a bind variable. A new file here means a
# new review.
REVIEWED_DYNAMIC_SQL = {
    "java-backend/src/main/java/ge/magti/portal/article/ArticleViewQueryService.java",
    # 2026-10-02 (PO-51): the whole-ledger check splices canonicalCall()'s
    # column list under a code-chosen alias; the batch bounds are binds.
    "java-backend/src/main/java/ge/magti/portal/audit/AuditChainService.java",
    "java-backend/src/main/java/ge/magti/portal/audit/AuditLogQueryService.java",
    "java-backend/src/main/java/ge/magti/portal/content/ContentLifecycleService.java",
    "java-backend/src/main/java/ge/magti/portal/export/AdminExportQueryService.java",
}


def test_sql_is_never_built_from_request_values():
    # A SQL literal joined to something that is neither a literal nor an
    # UPPER_CASE constant. Literal-only concatenation across lines is fine.
    sql = re.compile(r"\b(SELECT|INSERT|UPDATE|DELETE|WHERE|FROM|AND|OR|ORDER BY|JOIN|SET|VALUES)\b")
    literal = r'"(?:[^"\\\n]|\\.)*"'
    operand = r"([A-Za-z_][\w.]*(?:\([^()]*\))?)"
    joined_after = re.compile(literal + r"\s*\+\s*(?!\")" + operand)
    joined_before = re.compile(operand + r"\s*\+\s*(" + literal + ")")
    dynamic = set()
    for path in java_sources():
        text = path.read_text(encoding="utf-8")
        for match in joined_after.finditer(text):
            if sql.search(match.group(0).split("+")[0]) and not re.fullmatch(r"[A-Z][A-Z0-9_]*", match.group(1)):
                dynamic.add(path.relative_to(ROOT).as_posix())
        for match in joined_before.finditer(text):
            if sql.search(match.group(2)) and not re.fullmatch(r"[A-Z][A-Z0-9_]*", match.group(1)):
                dynamic.add(path.relative_to(ROOT).as_posix())
    assert dynamic <= REVIEWED_DYNAMIC_SQL, sorted(dynamic - REVIEWED_DYNAMIC_SQL)


def test_no_os_command_execution():
    assert hits(java_sources(), r"\bProcessBuilder\b|Runtime\.getRuntime\(\)\.exec|\.exec\(") == []


def test_no_ldap_or_jndi_lookups():
    assert hits(java_sources(), r"javax\.naming|InitialContext|LdapTemplate|DirContext|jndi:") == []


def test_no_xml_parsing():
    parsers = (r"DocumentBuilderFactory|SAXParserFactory|XMLInputFactory|XMLReader|TransformerFactory"
               r"|XPathFactory|Unmarshaller|JAXBContext|XmlMapper")
    assert hits(java_sources(), parsers) == []


def test_no_regex_built_from_input():
    compiled = hits(java_sources(), r"Pattern\.compile\(")
    # A pattern is either a literal or a constant compiled once.
    loose = [line for line in compiled
             if not re.search(r'Pattern\.compile\(\s*"', line) and "static final Pattern" not in line]
    assert loose == []
    assert hits(frontend_sources(".ts"), r"new RegExp\(") == []


def test_no_dynamic_code_in_the_browser():
    dynamic = r"\beval\(|new Function\(|set(Timeout|Interval)\(\s*['\"`]|document\.write\("
    assert hits(frontend_sources(".ts", ".html"), dynamic) == []


def test_no_expression_or_template_engines():
    engines = r"org\.springframework\.expression|SpelExpressionParser|freemarker|thymeleaf|velocity|mustache|jinjava"
    assert hits(java_sources(), engines, re.IGNORECASE) == []
    assert re.search(engines, POM.read_text(encoding="utf-8"), re.IGNORECASE) is None


def test_no_memcache_or_redis_client():
    clients = r"RedisTemplate|Jedis|Lettuce|MemcachedClient|spymemcached|Redisson"
    assert hits(java_sources(), clients) == []
    assert re.search(r"redis|memcache", POM.read_text(encoding="utf-8"), re.IGNORECASE) is None


def test_format_strings_are_literals():
    # String.format(literal, …) and "literal".formatted(…) only; the format
    # string may start on the next line.
    for path in java_sources():
        text = path.read_text(encoding="utf-8")
        for match in re.finditer(r"String\.format\(\s*", text):
            following = text[match.end():match.end() + 12]
            assert following.startswith(('"', "Locale.")), f"{path.relative_to(ROOT)}: {following!r}"


def test_no_mail_client():
    assert hits(java_sources(), r"jakarta\.mail|javax\.mail|JavaMailSender") == []
    assert "spring-boot-starter-mail" not in POM.read_text(encoding="utf-8")


def test_no_memory_unsafe_code():
    assert hits(java_sources(), r"sun\.misc\.Unsafe|jdk\.internal\.misc|\bnative\s+\w+\s*\(|System\.loadLibrary") == []
    assert hits(frontend_sources(".ts"), r"\bWebAssembly\b") == []


def test_no_native_deserialization():
    unsafe = (r"ObjectInputStream|XMLDecoder|\.readObject\(|enableDefaultTyping|activateDefaultTyping"
              r"|JsonTypeInfo\.Id\.CLASS|SerializationUtils\.deserialize")
    assert hits(java_sources(), unsafe) == []


# ── V3 browser-facing ─────────────────────────────────────────────────────


def test_inner_html_only_for_sanitised_article_content():
    # With no bypassSecurityTrustHtml anywhere, Angular sanitises every one
    # of these bindings; the list is kept short on purpose.
    bound = {line.split(":", 1)[0] for line in hits(frontend_sources(".html"), r"\[innerHTML\]")}
    assert bound == {
        "angular-frontend/src/app/features/admin-content/article-edit-drawer/article-edit-drawer.html",
        "angular-frontend/src/app/features/admin-content/article-history-modal/article-history-modal.html",
        "angular-frontend/src/app/features/article-detail/article-detail-page.html",
        "angular-frontend/src/app/features/article-detail/article-version-history-overlay/article-version-history-overlay.html",
        "angular-frontend/src/app/features/news/news-detail-page.html",
    }
    assert hits(frontend_sources(".ts"), r"bypassSecurityTrustHtml") == []
    # The editor parses pasted HTML into an inert <template> after cleaning it.
    assert outside(hits(frontend_sources(".ts"), r"\.innerHTML\s*="),
                   "angular-frontend/src/app/shared/rich-text-editor/rich-text-editor.ts") == []


PERSISTENCE_WRITE = re.compile(
    r"\b\w*(Repository|repository|Service|service|jdbcTemplate|entityManager)\s*\.\s*"
    r"(save\w*|delete\w*|insert\w*|update|persist|merge|remove|record\w*|acknowledge\w*|revoke\w*|create)\(")


def test_get_handlers_do_not_change_state():
    offenders = []
    for path in sorted(JAVA_MAIN.rglob("*Controller.java")):
        source = path.read_text(encoding="utf-8")
        for mapping in re.finditer(r"@GetMapping[^\n]*\n(?:\s*@[^\n]*\n)*\s*public [^{]*\{", source):
            depth, index = 1, mapping.end()
            while depth and index < len(source):
                depth += {"{": 1, "}": -1}.get(source[index], 0)
                index += 1
            for write in PERSISTENCE_WRITE.finditer(source[mapping.end():index]):
                offenders.append(f"{path.name}: {write.group(0)}")
    assert offenders == []


def test_no_postmessage_listeners():
    assert hits(frontend_sources(".ts", ".html"), r"addEventListener\(\s*['\"]message['\"]|\bonmessage\b|postMessage\(") == []


def test_no_plugin_content():
    assert hits(frontend_sources(".html"), r"<(object|embed|applet)\b", re.IGNORECASE) == []
    assert "object-src 'none'" in NGINX.read_text(encoding="utf-8")


def test_no_open_redirects():
    assert hits(java_sources(), r"sendRedirect\(|\"redirect:|HttpStatus\.(FOUND|SEE_OTHER|TEMPORARY_REDIRECT)") == []
    # Navigation by assignment only ever goes to a fixed path of this app.
    navigation = hits(frontend_sources(".ts"), r"location\.(href\s*=|assign\(|replace\()|window\.open\(")
    assert [line for line in navigation if not re.search(r"""(href\s*=|assign\(|replace\()\s*['"]/""", line)] == []


# ── V4, V17: protocols that do not exist here ─────────────────────────────


def test_no_websocket_graphql_or_webrtc():
    assert hits(java_sources(), r"org\.springframework\.web\.socket|jakarta\.websocket|graphql", re.IGNORECASE) == []
    assert re.search(r"websocket|graphql", POM.read_text(encoding="utf-8"), re.IGNORECASE) is None
    assert hits(frontend_sources(".ts"), r"new WebSocket\(|RTCPeerConnection|graphql", re.IGNORECASE) == []


# ── V5 files ──────────────────────────────────────────────────────────────


def test_no_archive_extraction():
    assert hits(java_sources(), r"ZipInputStream|ZipFile|GZIPInputStream|InflaterInputStream|JarInputStream|ArchiveInputStream") == []


def test_download_names_are_generated():
    dispositions = hits(java_sources(), r"CONTENT_DISPOSITION|Content-Disposition")
    assert {line.split(":", 1)[0] for line in dispositions} <= {
        "java-backend/src/main/java/ge/magti/portal/web/AuditLogController.java",
        "java-backend/src/main/java/ge/magti/portal/web/ExportController.java",
        # 2026-10-01: uploads name themselves instead of Spring's "f.txt". The
        # name is the server-minted UUID the file is stored under (UploadController),
        # served only after FileStorageService finds a stored file by it.
        "java-backend/src/main/java/ge/magti/portal/web/UploadedFileController.java",
    }
    # The one variable name comes from the worker: prefix, job id, type.
    worker = (JAVA_MAIN / "ge/magti/portal/export/ExportJobWorker.java").read_text(encoding="utf-8")
    assert 'filenamePrefix + "_" + jobId + "." + exportType' in worker


# ── V6, V9, V10: authentication mechanisms that are absent ────────────────


def test_no_one_time_codes():
    assert hits(java_sources(), r"\b(Totp|Hotp|OneTimePassword|Otp\w*|SmsGateway|sendSms)\b") == []


def test_no_saml():
    assert hits(java_sources(), r"saml", re.IGNORECASE) == []
    assert "saml" not in POM.read_text(encoding="utf-8").lower()


def test_one_token_type_is_minted():
    builders = hits(java_sources(), r"Jwts\.builder\(")
    assert [line.split(":", 1)[0] for line in builders] == [
        "java-backend/src/main/java/ge/magti/portal/security/JwtService.java"]


def test_no_id_tokens_are_consumed():
    assert hits(java_sources(), r"id_token|IdToken|OidcUser|openid-configuration") == []


# ── V11, V12 cryptography and TLS ─────────────────────────────────────────


def test_no_hand_rolled_cryptography():
    assert hits(java_sources(), r"javax\.crypto\.Cipher|Cipher\.getInstance|KeyGenerator|PBEKeySpec|SecretKeyFactory") == []


def test_no_weak_hashes():
    weak = r"\bMD5\b|\bMD4\b|SHA-?1\b|HASH_MD5|HASH_SH1"
    assert hits(java_sources(), weak, re.IGNORECASE) == []
    assert hits(sorted(MIGRATIONS.glob("*.sql")), weak, re.IGNORECASE) == []


def test_no_insecure_randomness():
    assert hits(java_sources(), r"new Random\(|java\.util\.Random\b|ThreadLocalRandom") == []
    assert hits(frontend_sources(".ts"), r"Math\.random\(") == []


def test_tls_validation_is_never_disabled():
    assert hits(java_sources(), r"TrustManager|HostnameVerifier|trustAll|SSLContext\.getInstance|NoopHostname") == []


# ── V13 configuration ─────────────────────────────────────────────────────


def test_outbound_http_only_to_the_directory():
    clients = (r"java\.net\.http|RestTemplate|RestClient|WebClient|URLConnection|openConnection\("
               r"|new (java\.net\.)?URL\(|OkHttp|new (java\.net\.)?Socket\(")
    assert outside(hits(java_sources(), clients),
                   "java-backend/src/main/java/ge/magti/portal/security/CorporateAuthClient.java") == []


def test_images_ship_no_source_control_metadata():
    for context in ("angular-frontend", "java-backend"):
        ignore = ROOT / context / ".dockerignore"
        assert ignore.is_file(), f"{context} has no .dockerignore"
        assert re.search(r"^\.git/?$", ignore.read_text(encoding="utf-8"), re.MULTILINE), f"{context}/.dockerignore does not exclude .git"


def test_no_api_documentation_is_served():
    assert re.search(r"springdoc|swagger", POM.read_text(encoding="utf-8"), re.IGNORECASE) is None
    assert re.search(r"location\s+[^{]*(swagger|openapi|api-docs)", NGINX.read_text(encoding="utf-8")) is None


# ── V14 data protection ───────────────────────────────────────────────────


def test_no_secrets_in_urls():
    assert hits(frontend_sources(".ts"), r"[?&](access_)?token=|[?&]password=") == []
    token_reader = (JAVA_MAIN / "ge/magti/portal/security/JwtAuthenticationFilter.java").read_text(encoding="utf-8")
    assert "getParameter(" not in token_reader


def test_no_third_party_scripts_or_trackers():
    index = (FRONTEND_APP / "index.html").read_text(encoding="utf-8")
    assert re.search(r"<script[^>]+src=[\"']https?:", index) is None
    package = (FRONTEND / "package.json").read_text(encoding="utf-8")
    assert re.search(r"analytics|gtag|segment|hotjar|mixpanel|amplitude|clarity", package, re.IGNORECASE) is None
    csp = NGINX.read_text(encoding="utf-8")
    assert "script-src 'self'" in csp and "connect-src 'self'" in csp


def test_browser_storage_holds_only_display_preferences():
    # Display preferences, plus one owner decision (PO-50, 2026-10-02): the
    # article editor's unsaved title and text, keyed by the signed-in
    # address and dropped after a week. No token, no personal data.
    stored = hits(frontend_sources(".ts"), r"localStorage\.setItem\(")
    assert {line.split(":", 1)[0] for line in stored} == {
        "angular-frontend/src/app/core/accessibility/font-scale.service.ts",
        "angular-frontend/src/app/features/admin-content/article-edit-drawer/article-draft-store.ts",
        "angular-frontend/src/app/core/theme/theme.service.ts",
        "angular-frontend/src/app/shell/app-shell.ts",
    }
    assert hits(frontend_sources(".ts"), r"sessionStorage|indexedDB|document\.cookie\s*=") == []


# ── V15 code patterns ─────────────────────────────────────────────────────


def test_no_deep_merge_of_untrusted_objects():
    assert hits(frontend_sources(".ts"), r"__proto__|constructor\.prototype|defaultsDeep|lodash") == []
    assert re.search(r'"(lodash|deepmerge|merge-deep)', (FRONTEND / "package.json").read_text(encoding="utf-8")) is None


# ── V16 logging ───────────────────────────────────────────────────────────


def test_logs_go_to_standard_output_only():
    configs = list(RESOURCES.glob("logback*.xml")) + list(RESOURCES.glob("log4j2*.xml"))
    for config in configs:
        assert "FileAppender" not in config.read_text(encoding="utf-8"), config.name
    application = (RESOURCES / "application.yml").read_text(encoding="utf-8")
    assert re.search(r"^\s*file:\s*$|logging\.file|^\s*name:\s*.*\.log\s*$", application, re.MULTILINE) is None
