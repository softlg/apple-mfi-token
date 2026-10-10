#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""P2 验收：租户凭证、加密落库、MFI 客户端工厂与真实 mTLS 握手。

前置：
  1. bash scripts/gen-dev-certs.sh                # 生成 .dev-certs/
  2. java scripts/MfiStubServer.java 8443         # 起本地 mTLS 桩
  3. 应用以 local profile 启动，并注入 MFI_CREDENTIAL_MASTER_KEY 与 MFI_APPLE_TRUSTED_CA_PEM
  4. 库里存在一个 owner 账号（P1 冒号数据）

用法：
  PYTHONIOENCODING=utf-8 python scripts/verify-p2.py --account own_a_p2 --password Passw0rd123

只做只读断言与可重复写入；不打印任何 PEM 内容。
"""
import argparse
import base64
import json
import os
import re
import subprocess
import sys
import tempfile
import urllib.error
import urllib.request

BASE = os.environ.get("MFI_BASE", "http://localhost:8090")
APP_LOG = os.environ.get("MFI_APP_LOG", "logs/dev-run-p2.log")
STUB_LOG = os.environ.get("MFI_STUB_LOG", "logs/stub-p2.log")
MYSQL = os.environ.get("MFI_MYSQL", r"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe")

results = []


def check(name, passed, detail=""):
    results.append((name, bool(passed)))
    print(("  PASS  " if passed else "  FAIL  ") + name + ("" if passed else "  -> " + str(detail)[:300]))
    return passed


def call(method, path, body=None, token=None):
    data = None if body is None else json.dumps(body).encode("utf-8")
    request = urllib.request.Request(BASE + path, data=data, method=method)
    request.add_header("Content-Type", "application/json")
    if token:
        request.add_header("mfi-token", token)
    try:
        with urllib.request.urlopen(request, timeout=90) as response:
            return response.status, json.loads(response.read().decode("utf-8") or "{}")
    except urllib.error.HTTPError as error:
        raw = error.read().decode("utf-8") or "{}"
        try:
            return error.code, json.loads(raw)
        except json.JSONDecodeError:
            return error.code, {"code": "?", "message": raw[:200], "data": None}


def ok(payload):
    return payload.get("code") == "0"


def read(path):
    with open(path, "r", encoding="utf-8") as handle:
        return handle.read()


def cert_pem(path):
    found = re.search(r"-----BEGIN CERTIFICATE-----.*?-----END CERTIFICATE-----", read(path), re.S)
    if not found:
        raise AssertionError(path + " 没有证书段")
    return found.group(0) + "\n"


def chain_pem(path):
    blocks = re.findall(r"-----BEGIN CERTIFICATE-----.*?-----END CERTIFICATE-----", read(path), re.S)
    if not blocks:
        raise AssertionError(path + " 没有证书段")
    return "\n".join(blocks) + "\n"


def key_pem(path):
    found = re.search(r"-----BEGIN (?:RSA )?PRIVATE KEY-----.*?-----END (?:RSA )?PRIVATE KEY-----", read(path), re.S)
    if not found:
        raise AssertionError(path + " 没有私钥段")
    return found.group(0) + "\n"


def mysql_row(sql):
    """用一次性 defaults 文件传账号密码，避免口令出现在命令行与进程列表里"""
    handle = tempfile.NamedTemporaryFile("w", suffix=".cnf", delete=False, encoding="utf-8")
    handle.write("[client]\nuser=%s\npassword=%s\n" % (
        os.environ.get("MFI_DB_USERNAME", "root"), os.environ.get("MFI_DB_PASSWORD", "root")))
    handle.close()
    try:
        proc = subprocess.run([MYSQL, "--defaults-extra-file=" + handle.name, "-N", "-B", "-e", sql,
                               "apple_mfi_token"], capture_output=True, timeout=60)
        out = proc.stdout.decode("utf-8", "replace").strip()
        if proc.returncode != 0:
            print("  mysql 调用失败: " + proc.stderr.decode("utf-8", "replace")[:200])
        return out
    finally:
        os.unlink(handle.name)


def count_bytes(pattern, path):
    if not os.path.exists(path):
        return -1
    with open(path, "rb") as handle:
        return len(re.findall(pattern, handle.read()))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--account", default="own_a_p2")
    parser.add_argument("--password", default="Passw0rd123")
    parser.add_argument("--probe-account", default="mem_p2_probe")
    parser.add_argument("--stub-port", default="8443")
    parser.add_argument("--certs", default=".dev-certs")
    args = parser.parse_args()
    certs = args.certs

    status, login = call("POST", "/api/auth/login", {"account": args.account, "password": args.password})
    if not (status == 200 and ok(login)):
        print("登录失败，后续断言无意义: %s %s" % (status, json.dumps(login, ensure_ascii=False)[:300]))
        sys.exit(1)
    payload = login["data"]
    token = payload["tokenValue"]
    tenant_id = payload["currentTenant"]["tenantId"]
    print("已登录 tenantId=%s owner=%s" % (tenant_id, payload["currentTenant"]["owner"]))

    # 日志是跨轮次追加的，先记录基线，后面全部按增量断言，脚本才可重复执行
    app_mark = count_bytes(rb"baseUrl=https://localhost:8443, fingerprint=", APP_LOG)
    stub_b_mark = count_bytes(rb"clientCert=CN=MFI Tenant B Client", STUB_LOG)
    stub_a_mark = count_bytes(rb"clientCert=CN=MFI Tenant A Client", STUB_LOG)
    privkey_mark = count_bytes(rb"BEGIN (RSA )?PRIVATE KEY", APP_LOG)

    # 先把租户指回官方地址，让本脚本可重复执行（上一轮会把 BaseURL 留在桩地址上）
    _, head = call("GET", "/api/tenant/profile", token=token)
    call("PUT", "/api/tenant/profile",
         {"tenantName": (head.get("data") or {}).get("tenantName") or "P2 验收租户",
          "appleTechnology": "", "appleBaseUrl": ""}, token)

    print("\n[1] 租户信息与地址分流")
    _, profile = call("GET", "/api/tenant/profile", token=token)
    data = profile.get("data") or {}
    check("profile 可读", ok(profile) and data.get("tenantCode"), profile.get("message"))
    check("未覆盖时生效地址为苹果官方 swa",
          data.get("effectiveBaseUrl") == "https://swa.apple.com:443", data.get("effectiveBaseUrl"))
    _, homekit = call("PUT", "/api/tenant/profile",
                      {"tenantName": data.get("tenantName"), "appleTechnology": "HomeKit",
                       "appleBaseUrl": ""}, token)
    check("HomeKit 分流到 sauth-external",
          (homekit.get("data") or {}).get("effectiveBaseUrl") == "https://sauth-external.apple.com:443",
          homekit.get("data"))
    call("PUT", "/api/tenant/profile",
         {"tenantName": data.get("tenantName"), "appleTechnology": "", "appleBaseUrl": ""}, token)
    status, bogus = call("PUT", "/api/tenant/profile",
                         {"tenantName": "x", "appleTechnology": "Bluetooth"}, token)
    check("未知 technology 被拒", status == 400, "%s %s" % (status, bogus.get("message")))

    print("\n[2] 业务参数校验与生效值")
    status, bad = call("PUT", "/api/tenant/config", {"values": {"APPLE_REGISTER_BATCH_SIZE": "2000"}}, token)
    check("批量注册超苹果上限被拒", status == 400, "%s %s" % (status, bad.get("message")))
    status, unknown = call("PUT", "/api/tenant/config", {"values": {"NOT_A_KEY": "1"}}, token)
    check("未知配置键被拒", status == 400 and "NOT_A_KEY" in str(unknown.get("message")), unknown.get("message"))
    _, updated = call("PUT", "/api/tenant/config", {"values": {"APPLY_MAX_QUANTITY": "100"}}, token)
    by_key = {item["key"]: item for item in (updated.get("data") or [])}
    check("写入后生效值为租户值且标记被覆盖",
          by_key.get("APPLY_MAX_QUANTITY", {}).get("effectiveValue") == "100"
          and by_key.get("APPLY_MAX_QUANTITY", {}).get("overridden") is True,
          by_key.get("APPLY_MAX_QUANTITY"))
    check("未提交的键保持全局默认",
          by_key.get("PIPELINE_MAX_RETRY", {}).get("effectiveValue") == "3"
          and by_key.get("PIPELINE_MAX_RETRY", {}).get("overridden") is False,
          by_key.get("PIPELINE_MAX_RETRY"))
    _, reset = call("PUT", "/api/tenant/config", {"values": {"APPLY_MAX_QUANTITY": ""}}, token)
    reset_map = {item["key"]: item for item in (reset.get("data") or [])}
    check("留空即回到全局默认",
          reset_map.get("APPLY_MAX_QUANTITY", {}).get("effectiveValue") == "0"
          and reset_map.get("APPLY_MAX_QUANTITY", {}).get("overridden") is False,
          reset_map.get("APPLY_MAX_QUANTITY"))

    print("\n[3] 凭证入库前的三道校验")
    _, missing = call("GET", "/api/tenant/credential", token=token)
    check("未配置时给明确业务码 B0404", missing.get("code") == "B0404", missing.get("code"))
    status, garbage = call("PUT", "/api/tenant/credential",
                           {"certPem": "not a pem", "keyPem": "not a pem"}, token)
    check("垃圾输入被拒", status == 400 and "解析失败" in str(garbage.get("message")), garbage.get("message"))
    a_cert = cert_pem(certs + "/tenant-a.crt")
    b_key = key_pem(certs + "/tenant-b.pk1.pem")
    status, mismatch = call("PUT", "/api/tenant/credential", {"certPem": a_cert, "keyPem": b_key}, token)
    check("证书与私钥不配对被拒", status == 400 and "不配对" in str(mismatch.get("message")), mismatch.get("message"))
    status, expired = call("PUT", "/api/tenant/credential",
                           {"certPem": cert_pem(certs + "/tenant-expired.crt"),
                            "keyPem": key_pem(certs + "/tenant-expired.key")}, token)
    check("过期证书被拒且业务码 B0410",
          status == 400 and expired.get("code") == "B0410",
          "%s %s" % (expired.get("code"), expired.get("message")))

    print("\n[4] PKCS#1 材料入库（厂商常见交付格式）")
    _, saved = call("PUT", "/api/tenant/credential",
                    {"certPem": chain_pem(certs + "/tenant-b-chain.crt"), "keyPem": b_key}, token)
    credential = saved.get("data") or {}
    check("上传成功并返回元数据",
          credential.get("version") is not None and credential.get("fingerprint"), saved.get("message"))
    body_text = json.dumps(saved, ensure_ascii=False)
    check("返回体不含证书与私钥内容", "PRIVATE KEY" not in body_text and "certPem" not in body_text, None)
    fingerprint_b = credential.get("fingerprint")
    version_b = credential.get("version") or 0

    print("\n[5] 真实 mTLS 握手（指向本地桩）")
    _, pointed = call("PUT", "/api/tenant/profile",
                      {"tenantName": data.get("tenantName") or "P2 验收租户",
                       "appleTechnology": "AirPlay Audio",
                       "appleBaseUrl": "https://localhost:" + args.stub_port}, token)
    check("租户 BaseURL 已指向桩",
          (pointed.get("data") or {}).get("effectiveBaseUrl") == "https://localhost:" + args.stub_port,
          pointed.get("data"))
    status, insecure = call("PUT", "/api/tenant/profile",
                            {"tenantName": "x", "appleBaseUrl": "http://localhost:8443"}, token)
    check("非 https 的 BaseURL 被拒", status == 400, "%s %s" % (status, insecure.get("message")))

    _, verify = call("POST", "/api/tenant/credential/verify", {}, token)
    first = verify.get("data") or {}
    check("握手成功并拿到 200", first.get("reachable") is True and first.get("httpStatus") == 200, first)
    check("自检回显指纹与入库一致", first.get("fingerprint") == fingerprint_b, first.get("fingerprint"))
    _, verify_again = call("POST", "/api/tenant/credential/verify", {}, token)
    check("第二次自检仍然可达", (verify_again.get("data") or {}).get("reachable") is True, verify_again.get("data"))
    check("桩侧确实收到客户端证书主体",
          count_bytes(rb"clientCert=CN=MFI Tenant B Client", STUB_LOG) > stub_b_mark, "见 " + STUB_LOG)

    print("\n[6] 明文不落地")
    row = mysql_row("select cert_cipher, key_cipher, encrypt_key_id, fingerprint, version "
                    "from t_tenant_credential where tenant_id=%s and deleted=0" % tenant_id)
    columns = row.split("\t") if row else []
    check("库里有一行凭证", len(columns) == 5, row[:120])
    if len(columns) == 5:
        cert_cipher, key_cipher, key_id, db_fingerprint, _ = columns
        check("密文里不含 PEM 标记", "BEGIN" not in cert_cipher + key_cipher, "BEGIN 出现即明文入库")
        try:
            raw = base64.b64decode(cert_cipher)
            shaped = len(raw) > 28
        except Exception as error:
            shaped = error
        check("密文是合法 Base64 且长度含 IV+TAG", shaped is True, shaped)
        check("证书与私钥密文不同（各自独立 IV）", cert_cipher != key_cipher, None)
        check("密钥标识与库内指纹符合",
              key_id == "v1" and db_fingerprint == fingerprint_b, "%s %s" % (key_id, db_fingerprint))
    check("应用日志里没有新增私钥文本",
          count_bytes(rb"BEGIN (RSA )?PRIVATE KEY", APP_LOG) == privkey_mark, "见 " + APP_LOG)

    print("\n[7] 缓存复用与替换后失效")
    builds = count_bytes(rb"baseUrl=https://localhost:8443, fingerprint=", APP_LOG) - app_mark
    check("两次自检只装配一次客户端（命中缓存）", builds == 1, "装配增量=" + str(builds))
    a_key = key_pem(certs + "/tenant-a.pk8.key")
    _, replaced = call("PUT", "/api/tenant/credential",
                       {"certPem": chain_pem(certs + "/tenant-a-chain.crt"), "keyPem": a_key}, token)
    replaced_data = replaced.get("data") or {}
    fingerprint_a = replaced_data.get("fingerprint")
    check("替换后 version 递增", replaced_data.get("version") == version_b + 1, replaced_data)
    _, verify_after = call("POST", "/api/tenant/credential/verify", {}, token)
    after = verify_after.get("data") or {}
    check("替换后仍能握手（evict 生效，没复用旧材料）", after.get("reachable") is True, after)
    check("替换后指纹随之变化",
          after.get("fingerprint") == fingerprint_a and fingerprint_a != fingerprint_b, after.get("fingerprint"))
    builds_after = count_bytes(rb"baseUrl=https://localhost:8443, fingerprint=", APP_LOG) - app_mark
    check("替换触发了一次重新装配", builds_after == builds + 1, "%s -> %s" % (builds, builds_after))
    check("桩侧看到了新证书主体", count_bytes(rb"clientCert=CN=MFI Tenant A Client", STUB_LOG) > stub_a_mark, None)

    print("\n[8] 跨租户与 owner 边界")
    status, stranger = call("POST", "/api/auth/register",
                            {"account": args.probe_account, "password": "Passw0rd123",
                             "name": "普通成员", "tenantName": "另一个租户"})
    stranger_token = (stranger.get("data") or {}).get("tokenValue")
    check("注册第二个账号（自带独立租户）", status == 200 and stranger_token, stranger.get("message"))
    if stranger_token:
        _, other_view = call("GET", "/api/tenant/credential", token=stranger_token)
        check("别的租户读不到本租户凭证", other_view.get("code") == "B0404", other_view.get("code"))
        _, invited = call("POST", "/api/members/invite", {"account": args.probe_account}, token)
        check("owner 把它邀请进本租户", ok(invited) or invited.get("code") == "A0409", invited.get("message"))
        _, switched = call("POST", "/api/auth/switch-tenant", {"tenantId": tenant_id}, stranger_token)
        if ok(switched):
            check("成员切换到本租户", True)
            status, forbidden = call("PUT", "/api/tenant/credential",
                                     {"certPem": a_cert, "keyPem": a_key}, stranger_token)
            check("非 owner 不能改凭证（403 + A0403）",
                  status == 403 and forbidden.get("code") == "A0403", "%s %s" % (status, forbidden.get("code")))
            _, forbidden_config = call("PUT", "/api/tenant/config",
                                       {"values": {"APPLY_MAX_QUANTITY": "9"}}, stranger_token)
            check("非 owner 不能改业务参数", forbidden_config.get("code") == "A0403", forbidden_config.get("code"))
            _, readable = call("GET", "/api/tenant/config", token=stranger_token)
            check("普通成员可以读业务参数", ok(readable), readable.get("code"))
        else:
            check("成员切换到本租户", False, switched.get("message"))
    else:
        check("其余边界断言因注册失败跳过", False, str(stranger)[:200])

    print("\n[9] 删除凭证")
    _, removed = call("DELETE", "/api/tenant/credential", token=token)
    check("owner 可删除凭证", ok(removed), removed.get("message"))
    _, after_delete = call("GET", "/api/tenant/credential", token=token)
    check("删除后回到未配置状态", after_delete.get("code") == "B0404", after_delete.get("code"))
    status, verify_gone = call("POST", "/api/tenant/credential/verify", {}, token)
    check("删除后自检给业务错误而不是 500",
          status in (400, 404) and verify_gone.get("code") == "B0404", "%s %s" % (status, verify_gone.get("code")))

    print("\n[10] 接口可见性")
    _, docs = call("GET", "/v3/api-docs")
    paths = json.dumps(docs)
    for path in ["/api/tenant/profile", "/api/tenant/config", "/api/tenant/credential",
                 "/api/tenant/credential/verify"]:
        check("openapi 暴露 " + path, path in paths, None)
    check("openapi 不泄漏密文列名", "cert_cipher" not in paths and "keyCipher" not in paths, None)

    failed = [name for name, passed in results if not passed]
    print("\n==== 结果: %d/%d 通过 ====" % (len(results) - len(failed), len(results)))
    for name in failed:
        print("  未通过: " + name)
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
