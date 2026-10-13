#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""P8a 本地安全验收：真实 MySQL + Redis + RabbitMQ + 自签 mTLS 桩上的四项收口。

覆盖 §11 最终验收清单里除"真证书联调"之外需要活体证据的部分：

[A] 两个租户各用独立证书并发申请，数据/PPID/token 完全隔离
[B] 任一租户证书错误/过期不影响其他租户消费
[C] 断掉 MQ 重启后，兜底扫描能自愈未完成分片
[D] 跨租户渗透自测：§8 每个端点都要把"隔壁租户的资源"当成不存在
[E] 日志与产物红线（本轮日志增量内不得出现凭证材料、PPID 明文与 SQL 实参）

只依赖标准库。口令、库密码、桩地址一律从环境变量读，脚本不打印任何材料内容。
"""

import base64
import json
import os
import re
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
from tempfile import NamedTemporaryFile

BASE = os.environ.get("MFI_BASE", "http://localhost:8090")
STUB_URL = os.environ.get("MFI_STUB_URL", "https://localhost:8443")
MYSQL = os.environ.get("MFI_MYSQL", r"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe")
RABBITMQCTL = os.environ.get("MFI_RABBITMQCTL", r"D:\tools\rabbitmq_server-3.13.7\sbin\rabbitmqctl.bat")
RABBITMQ_SERVER = os.environ.get("MFI_RABBITMQ_SERVER", r"D:\tools\rabbitmq_server-3.13.7\sbin\rabbitmq-server.bat")
RABBITMQ_BASE = os.environ.get("MFI_RABBITMQ_BASE", r"D:\tools\rabbitmq_var")
ERLANG_HOME = os.environ.get("MFI_ERLANG_HOME", r"D:\tools\erlang")
ERTS = os.environ.get("MFI_ERTS", "erts-14.2.5.4")
APP_LOG = os.environ.get("MFI_APP_LOG", ".dev-certs/app-p7.log")
STUB_LOG = os.environ.get("MFI_STUB_LOG", ".dev-certs/stub-p8.log")
EXPORT_DIR = os.environ.get("MFI_EXPORT_DIR", ".dev-certs/export-p7")
PASSWORD = os.environ.get("MFI_TEST_PASSWORD", "Passw0rd123")

# 桩造的 token 原文以 mfi-stub-token 开头，base64 后前缀稳定；用它反查原文有没有漏进日志
TOKEN_MARK = base64.b64encode(b"mfi-stub-token").decode("utf-8")[:12].rstrip("=")
VERIFY_MARK = "clientCert="

results = []
skips = []


def check(name, passed, detail=""):
    results.append((name, bool(passed)))
    print(("  PASS  " if passed else "  FAIL  ") + name
          + ("" if passed else "  -> " + str(detail)[:300]))
    return passed


def skip(name, reason):
    skips.append(name)
    print("  SKIP  " + name + "  -> " + reason)


def ok(payload):
    return payload.get("code") == "0"


def call(method, path, body=None, token=None):
    data = None if body is None else json.dumps(body).encode("utf-8")
    request = urllib.request.Request(BASE + path, data=data, method=method)
    request.add_header("Content-Type", "application/json")
    if token:
        request.add_header("mfi-token", token)
    try:
        with urllib.request.urlopen(request, timeout=180) as response:
            return response.status, json.loads(response.read().decode("utf-8") or "{}")
    except urllib.error.HTTPError as error:
        raw = error.read().decode("utf-8") or "{}"
        try:
            return error.code, json.loads(raw)
        except json.JSONDecodeError:
            return error.code, {"code": "?", "message": raw[:200], "data": None}


def raw_get(path, token=None):
    request = urllib.request.Request(BASE + path, method="GET")
    if token:
        request.add_header("mfi-token", token)
    try:
        with urllib.request.urlopen(request, timeout=180) as response:
            return response.status, response.read(), dict(response.headers or {})
    except urllib.error.HTTPError as error:
        return error.code, error.read(), dict(error.headers or {})


def mysql_run(sql):
    """账号密码走一次性 defaults 文件，不出现在命令行与进程列表里"""
    handle = NamedTemporaryFile("w", suffix=".cnf", delete=False, encoding="utf-8")
    handle.write("[client]\nuser=%s\npassword=%s\n" % (
        os.environ.get("MFI_DB_USERNAME", "root"), os.environ.get("MFI_DB_PASSWORD", "root")))
    handle.close()
    try:
        proc = subprocess.run([MYSQL, "--defaults-extra-file=" + handle.name,
                               "--default-character-set=utf8mb4", "-N", "-B", "-e", sql,
                               "apple_mfi_token"], capture_output=True, timeout=60)
        if proc.returncode != 0:
            print("  mysql 调用失败: " + proc.stderr.decode("utf-8", "replace")[:200])
            return ""
        return proc.stdout.decode("utf-8", "replace").strip()
    finally:
        os.unlink(handle.name)


def mysql_value(sql):
    out = mysql_run(sql)
    return out.splitlines()[0] if out else ""


def erlang_env():
    env = dict(os.environ)
    env["ERLANG_HOME"] = ERLANG_HOME
    env["RABBITMQ_BASE"] = RABBITMQ_BASE
    env["HOME"] = RABBITMQ_BASE
    env["PATH"] = env.get("PATH", "") + os.pathsep + os.path.join(ERLANG_HOME, "bin") \
        + os.pathsep + os.path.join(ERLANG_HOME, ERTS, "bin")
    return env


def queue_depths():
    if not os.path.exists(RABBITMQCTL):
        return {}
    try:
        proc = subprocess.run([RABBITMQCTL, "list_queues", "name", "messages", "--quiet"],
                              capture_output=True, timeout=180, env=erlang_env())
    except Exception as error:
        print("  rabbitmqctl 调用异常: " + str(error)[:200])
        return {}
    if proc.returncode != 0:
        return {}
    depths = {}
    for line in proc.stdout.decode("utf-8", "replace").splitlines():
        parts = line.split()
        if len(parts) >= 2 and parts[1].isdigit():
            depths[parts[0]] = int(parts[1])
    return depths


def broker_pid():
    """5672 上的监听进程就是 broker；比按映像名查找准确，也不会误伤别的 erlang 进程"""
    proc = subprocess.run(["netstat", "-ano"], capture_output=True, timeout=60)
    for line in proc.stdout.decode("utf-8", "replace").splitlines():
        if ":5672" in line and "LISTENING" in line:
            columns = line.split()
            return columns[-1]
    return ""


def stop_broker():
    pid = broker_pid()
    if not pid:
        return False
    subprocess.run(["taskkill", "/F", "/PID", pid], capture_output=True, timeout=60)
    for _ in range(20):
        if not broker_pid():
            return True
        time.sleep(2)
    return not broker_pid()


def start_broker():
    """和手工拉起时用同一条 Start-Process：脱离本脚本生命周期，脚本结束后 broker 仍在"""
    script = (
        "$env:ERLANG_HOME='%s'; $env:RABBITMQ_BASE='%s'; $env:HOME='%s';"
        " $env:Path='%s;%s;%s' + [IO.Path]::PathSeparator + $env:Path;"
        " Start-Process -FilePath '%s' -WindowStyle Hidden"
    ) % (ERLANG_HOME, RABBITMQ_BASE, RABBITMQ_BASE,
         os.path.join(ERLANG_HOME, "bin"), os.path.join(ERLANG_HOME, ERTS, "bin"),
         os.path.join(ERLANG_HOME, "bin"), RABBITMQ_SERVER)
    subprocess.run(["powershell", "-NoProfile", "-Command", script], capture_output=True, timeout=120)
    for _ in range(60):
        if broker_pid():
            return True
        time.sleep(3)
    return False


def log_text(path):
    if not os.path.exists(path):
        return ""
    with open(path, "r", encoding="utf-8", errors="replace") as handle:
        return handle.read()


def log_tail(text, start_len):
    """红线只看本轮增量：日志文件是跨轮累加的"""
    return text[start_len:] if start_len and len(text) > start_len else text


def register(account, name):
    status, payload = call("POST", "/api/auth/register",
                           {"account": account, "password": PASSWORD, "name": name,
                            "email": account + "@example.com", "tenantName": name})
    if not ok(payload):
        print("注册失败: %s %s" % (status, payload))
        sys.exit(1)
    data = payload.get("data") or {}
    return data.get("tokenValue"), (data.get("currentTenant") or {}).get("tenantId")


def upload_credential(token, cert_file, key_file):
    with open(cert_file, "r", encoding="utf-8") as handle:
        cert = handle.read()
    with open(key_file, "r", encoding="utf-8") as handle:
        key = handle.read()
    return call("PUT", "/api/tenant/credential", {"certPem": cert, "keyPem": key}, token)


def point_at_stub(token):
    """改 BaseURL 必须带上当前租户名，否则会被校验成「名字不能为空」"""
    _, profile = call("GET", "/api/tenant/profile", token=token)
    data = profile.get("data") or {}
    return call("PUT", "/api/tenant/profile",
                {"tenantName": data.get("tenantName"), "appleTechnology": data.get("appleTechnology") or "",
                 "appleBaseUrl": STUB_URL}, token)[1]


def create_ppid(token, ppid, model, total, spu):
    return call("POST", "/api/ppid",
                {"ppid": ppid, "spu": spu, "productModel": model, "totalQuantity": total,
                 "remark": "P8a 验收"}, token)


def apply(token, model, spu, quantity):
    return call("POST", "/api/applications",
                {"productModel": model, "spu": spu, "applyQuantity": quantity}, token)


def app_row(tenant_id, application_no):
    raw = mysql_value("select concat_ws('|',status,token_count,"
                      "ifnull(date_format(finish_time,'%%Y-%%m-%%d %%H:%%i:%%s'),''),"
                      "ifnull(fail_reason,'')) from t_application "
                      "where tenant_id=%s and application_no='%s'" % (tenant_id, application_no))
    if not raw:
        return None
    parts = raw.split("|")
    return {"status": parts[0], "token_count": parts[1], "finish_time": parts[2],
            "fail_reason": "|".join(parts[3:])}


def ppid_row(tenant_id, ppid, spu):
    raw = mysql_value("select concat_ws('|',total_quantity,applied_quantity,remaining_quantity,status)"
                      " from t_ppid where tenant_id=%s and ppid='%s' and spu='%s'"
                      % (tenant_id, ppid, spu))
    if not raw:
        return None
    parts = raw.split("|")
    return {"total": parts[0], "applied": parts[1], "remaining": parts[2], "status": parts[3]}


def token_counts(tenant_id, ppid):
    raw = mysql_value("select concat_ws('|',count(*),sum(status='REGISTERED'),"
                      "count(distinct manufacture_sn),sum(auth_entity_id is not null)) from t_token "
                      "where tenant_id=%s and ppid='%s'" % (tenant_id, ppid))
    parts = raw.split("|") if raw else []
    return [int(p) if p.isdigit() else -1 for p in parts] if len(parts) == 4 else None


def wait_for(predicate, timeout_seconds, interval_seconds=3):
    deadline = time.time() + timeout_seconds
    value = None
    while time.time() < deadline:
        value = predicate()
        if value:
            return value
        time.sleep(interval_seconds)
    return value


def wait_application(tenant_id, application_no, timeout_seconds=240):
    return wait_for(lambda: (lambda row: row if row and row["status"] in
                             ("SUCCESS", "FAILED", "PARTIAL_FAILED") else None)(
        app_row(tenant_id, application_no)), timeout_seconds, 3) or app_row(tenant_id, application_no)


def main():
    stamp = time.strftime("%m%d%H%M%S")
    print("P8a 本地安全验收  stamp=%s  base=%s" % (stamp, BASE))

    print("\n[0] 前置：三个租户 + 各自的材料")
    log_before = len(log_text(APP_LOG))
    log_run_start = log_before
    stub_before = len(log_text(STUB_LOG))
    token_a, tenant_a = register("p8a_own_a_%s" % stamp, "P8a 甲租户 " + stamp)
    token_b, tenant_b = register("p8a_own_b_%s" % stamp, "P8a 乙租户 " + stamp)
    token_x, tenant_x = register("p8a_own_x_%s" % stamp, "P8a 攻击者租户 " + stamp)
    check("三个租户注册成功且互不相同", len({tenant_a, tenant_b, tenant_x}) == 3,
          [tenant_a, tenant_b, tenant_x])

    _, cred_a = upload_credential(token_a, ".dev-certs/tenant-a.crt", ".dev-certs/tenant-a.pk8.key")
    _, cred_b = upload_credential(token_b, ".dev-certs/tenant-b.crt", ".dev-certs/tenant-b.pk1.key")
    fp_a = (cred_a.get("data") or {}).get("fingerprint") or ""
    fp_b = (cred_b.get("data") or {}).get("fingerprint") or ""
    check("甲租户 PKCS#8 私钥入库成功", ok(cred_a) and fp_a, cred_a)
    check("乙租户 PKCS#1 私钥入库成功", ok(cred_b) and fp_b, cred_b)
    check("两个租户用的是两套不同材料（指纹不同）", fp_a and fp_b and fp_a != fp_b, [fp_a[:8], fp_b[:8]])
    _, subject_b = call("GET", "/api/tenant/credential", token=token_b)
    check("乙租户证书主体是 Tenant B",
          "Tenant B" in json.dumps(subject_b, ensure_ascii=False), subject_b)

    print("\n[A] 双租户并发的链路材料")
    point_a = point_at_stub(token_a)
    point_b = point_at_stub(token_b)
    check("两个租户都把 BaseURL 指向桩", ok(point_a) and ok(point_b), [point_a, point_b])
    _, verify_a = call("POST", "/api/tenant/credential/verify", {}, token_a)
    _, verify_b = call("POST", "/api/tenant/credential/verify", {}, token_b)
    check("甲租户连通性自检通过", (verify_a.get("data") or {}).get("reachable") is True, verify_a)
    check("乙租户连通性自检通过", (verify_b.get("data") or {}).get("reachable") is True, verify_b)
    stub_text = log_text(STUB_LOG)[stub_before:]
    check("桩侧确实按租户各自出示客户端证书",
          "CN=MFI Tenant A Client" in stub_text and "CN=MFI Tenant B Client" in stub_text,
          [line for line in stub_text.splitlines() if VERIFY_MARK in line][-2:])

    # 同一个 PPID + SPU 在两个租户各自成立，是"唯一键含租户"最直接的证据
    shared_ppid = "P8A-SHARED-%s" % stamp
    shared_spu = "P8A-SPU"
    # 池子给到 12：[B] 各扣 3、[C] 再扣 2、[D] 断 MQ 时还要能落一单，够跑到 7
    _, made_a = create_ppid(token_a, shared_ppid, "P8AMODELA" + stamp[-4:], 12, shared_spu)
    _, made_b = create_ppid(token_b, shared_ppid, "P8AMODELB" + stamp[-4:], 12, shared_spu)
    check("同 PPID+SPU 在两个租户各建一行", ok(made_a) and ok(made_b), [made_a, made_b])
    rows = mysql_value("select count(*) from t_ppid where ppid='%s' and spu='%s'" % (shared_ppid, shared_spu))
    check("库里这两行分属两个租户", rows == "2", rows)

    print("\n[B] 两个租户同时各发一单")
    model_a = "P8AMODELA" + stamp[-4:]
    model_b = "P8AMODELB" + stamp[-4:]
    no_a = [None]
    no_b = [None]

    def submit(token, model, slot):
        status, payload = apply(token, model, shared_spu, 3)
        slot[0] = (payload.get("data") or {}).get("applicationNo")

    threads = [threading.Thread(target=submit, args=(token_a, model_a, no_a)),
               threading.Thread(target=submit, args=(token_b, model_b, no_b))]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()
    check("两单同时受理且各自拿到单号", bool(no_a[0]) and bool(no_b[0]) and no_a[0] != no_b[0],
          [no_a[0], no_b[0]])

    done_a = wait_application(tenant_a, no_a[0]) if no_a[0] else None
    done_b = wait_application(tenant_b, no_b[0]) if no_b[0] else None
    check("甲租户的单走到 SUCCESS", (done_a or {}).get("status") == "SUCCESS", done_a)
    check("乙租户的单走到 SUCCESS", (done_b or {}).get("status") == "SUCCESS", done_b)
    counts_a = token_counts(tenant_a, shared_ppid)
    counts_b = token_counts(tenant_b, shared_ppid)
    check("同一个 PPID 名下两侧各 3 行且全部注册成功",
          counts_a == [3, 3, 3, 3] and counts_b == [3, 3, 3, 3], [counts_a, counts_b])
    pool_a = ppid_row(tenant_a, shared_ppid, shared_spu)
    pool_b = ppid_row(tenant_b, shared_ppid, shared_spu)
    check("额度各自扣 3，互不挪用",
          pool_a["applied"] == "3" and pool_a["remaining"] == "9"
          and pool_b["applied"] == "3" and pool_b["remaining"] == "9", [pool_a, pool_b])
    _, page_a = call("GET", "/api/tokens/page?pageSize=50", token=token_a)
    records_a = ((page_a.get("data") or {}).get("records") or [])
    _, page_b = call("GET", "/api/tokens/page?pageSize=50", token=token_b)
    records_b = ((page_b.get("data") or {}).get("records") or [])
    _, page_x = call("GET", "/api/tokens/page?pageSize=50", token=token_x)
    records_x = ((page_x.get("data") or {}).get("records") or [])
    ids_a = {r.get("id") for r in records_a}
    ids_b = {r.get("id") for r in records_b}
    check("甲租户 token 分页只含自己那一 PPID 的 3 行", len(records_a) == 3, len(records_a))
    check("乙租户 token 分页只含自己的 3 行", len(records_b) == 3, len(records_b))
    check("第三个租户一条都看不到", len(records_x) == 0, records_x[:1])

    print("\n[C] 任一租户证书错误/过期不影响其他租户消费")
    # 攻击者租户先用过期证书，再干脆删掉凭证，两次提交都该在入口被拒
    _, expired = upload_credential(token_x, ".dev-certs/tenant-expired.crt", ".dev-certs/tenant-expired.key")
    check("过期证书在入库前就被拒（不落库）", not ok(expired) and "过期" in (expired.get("message") or ""),
          expired)
    _, no_cred = call("GET", "/api/tenant/credential", token=token_x)
    check("该租户仍未配置可用凭证", not ok(no_cred), no_cred)
    _, made_x = create_ppid(token_x, "P8A-X-%s" % stamp, "P8AMODELX" + stamp[-4:], 6, shared_spu)
    status, rejected = apply(token_x, "P8AMODELX" + stamp[-4:], shared_spu, 2)
    check("没凭证的租户提交被业务错误拒掉（不是 500）",
          not ok(rejected) and rejected.get("code") in ("B0404", "B0500"), [status, rejected])

    # 与此同时，甲租户再发一单，照常走完
    status, again = apply(token_a, model_a, shared_spu, 2)
    no_a2 = (again.get("data") or {}).get("applicationNo")
    check("甲租户随后一单照常受理", ok(again) and no_a2, again)
    done_a2 = wait_application(tenant_a, no_a2) if no_a2 else None
    check("甲租户的第二单也走到 SUCCESS", (done_a2 or {}).get("status") == "SUCCESS", done_a2)
    check("甲租户累计扣减 5、剩余 7",
          (ppid_row(tenant_a, shared_ppid, shared_spu) or {}).get("remaining") == "7",
          ppid_row(tenant_a, shared_ppid, shared_spu))

    print("\n[D] 断掉 MQ 再起来：兜底扫描补发在飞分片")
    if not stop_broker():
        skip("断 MQ 自愈整节", "没能找到或停掉本机 broker（5672 无监听）")
        broker_stopped = False
    else:
        broker_stopped = True
        check("broker 已停（5672 无监听）", not broker_pid(), broker_pid())
        status, during = apply(token_a, model_a, shared_spu, 2)
        no_d = (during.get("data") or {}).get("applicationNo")
        check("MQ 停机期间申请仍然受理（单子已生效，投递挂提交后回调）",
              ok(during) and bool(no_d), [status, during])
        time.sleep(3)
        shard = mysql_value("select concat_ws('|',p.stage,p.stage_status) from t_application_ppid p "
                            "join t_application a on a.id=p.application_id "
                            "where a.tenant_id=%s and a.application_no='%s'" % (tenant_a, no_d))
        check("投递失败后分片停在 PENDING 等兜底", shard.startswith("APPLY|PENDING"), shard)
        check("运行日志记下了「投递失败，等待兜底扫描补发」",
              "等待兜底扫描补发" in log_text(APP_LOG)[log_before:], "")
        log_before = len(log_text(APP_LOG))

        if not start_broker():
            skip("重启后的自愈观察", "broker 没能在 180 秒内起来")
            broker_stopped = False
        else:
            # 容器重连 + Declarables 重声明 + 扫描器补发，全给足时间
            healed = wait_application(tenant_a, no_d, 360) if no_d else None
            check("broker 恢复后分片被补发并走到 SUCCESS",
                  (healed or {}).get("status") == "SUCCESS", healed)
            if no_d:
                shard_count = mysql_value("select count(*) from t_application_ppid p "
                                          "join t_application a on a.id=p.application_id "
                                          "where a.tenant_id=%s and a.application_no='%s'"
                                          % (tenant_a, no_d))
                check("补发没有多出一个分片，也没有重复消费（该单只有一个分片）",
                      shard_count == "1", shard_count)
                counts_after = token_counts(tenant_a, shared_ppid)
                check("甲租户名下累计 7 行、7 个不重复 SN（补发没有重号也没有漏行）",
                      counts_after == [7, 7, 7, 7], counts_after)
            depths = queue_depths()
            check("拓扑仍在（五条阶段队列、DLQ 与 15 条延迟队列全部重新可见）",
                  all(prefix in depths for prefix in ("mfi.apply.queue", "mfi.name.queue",
                                                      "mfi.content.queue", "mfi.register.queue",
                                                      "mfi.finish.queue", "mfi.token.dlq"))
                  and sum(1 for name in depths if name.startswith("mfi.delay.")) == 15,
                  sorted(depths)[:6])
            check("收口时所有队列零积压", sum(depths.values()) == 0, depths)
            recovered = log_text(APP_LOG)[log_before:]
            check("兜底扫描确实补发了 PENDING 分片",
                  "补发 PENDING" in recovered or "复位 PROCESSING" in recovered, "")
            check("重连与重声明过程没有出现未预期异常",
                  "未预期的系统异常" not in recovered, "")

    print("\n[E] 跨租户渗透：§8 每个端点都要把隔壁租户当成不存在")
    # 甲的数据：单号 no_a[0]、PPID shared_ppid、任务稍后由乙发起
    _, a_detail = call("GET", "/api/applications/%s" % no_a[0], token=token_b)
    check("乙读不到甲的申请详情（按不存在处理，不区分无权与不存在）",
          not ok(a_detail) and a_detail.get("code") == "A0422"
          and "不存在" in (a_detail.get("message") or ""), a_detail)
    _, a_files = call("GET", "/api/applications/%s/files" % no_a[0], token=token_b)
    check("乙读不到甲的文件明细", not ok(a_files), a_files)
    _, a_list = call("GET", "/api/applications/page?pageSize=50", token=token_b)
    body = json.dumps(a_list, ensure_ascii=False)
    check("乙的申请列表里不出现甲的单号", no_a[0] not in body, body[:120])
    shard_state = ("select concat_ws('|',stage,stage_status,retry_count) from t_application_ppid p "
                   "join t_application a on a.id=p.application_id "
                   "where a.tenant_id=%s and a.application_no='%s'") % (tenant_a, no_a[0])
    before_status = mysql_value(shard_state)
    _, replay = call("POST", "/api/applications/%s/retry" % no_a[0], {}, token=token_b)
    after_status = mysql_value(shard_state)
    check("乙重放不了甲的分片，且甲的分片一行未动",
          not ok(replay) and before_status == after_status, [replay, before_status, after_status])

    ppid_id_a = mysql_value("select id from t_ppid where tenant_id=%s and ppid='%s' and spu='%s'"
                            % (tenant_a, shared_ppid, shared_spu))
    pool_before = ppid_row(tenant_a, shared_ppid, shared_spu)
    _, touched = call("PUT", "/api/ppid/%s" % ppid_id_a,
                      {"totalQuantity": 99, "remark": "隔壁租户改总量"}, token=token_b)
    _, stopped = call("DELETE", "/api/ppid/%s" % ppid_id_a, token=token_b)
    _, deleted = call("DELETE", "/api/ppid/%s" % ppid_id_a, token=token_x)
    pool_after = ppid_row(tenant_a, shared_ppid, shared_spu)
    check("乙改不动、停不掉、删不掉甲的 PPID（三个写入口全部无效）",
          pool_before == pool_after, [touched, stopped, deleted, pool_after])

    _, x_pool = call("GET", "/api/ppid/page?ppid=%s&pageSize=20" % shared_ppid, token=token_x)
    check("同 PPID 字符串只有属主看得见，隔壁查是空",
          ok(x_pool) and ((x_pool.get("data") or {}).get("records") or []) == [], x_pool.get("data"))

    _, x_stats = call("GET", "/api/ppid/stats", token=token_x)
    check("池子总览不带隔壁租户的行",
          shared_ppid not in json.dumps(x_stats, ensure_ascii=False), x_stats)
    status, x_export_body, _ = raw_get("/api/ppid/export", token=token_x)
    check("PPID 导出不含隔壁数据",
          status == 200 and shared_ppid.encode() not in (x_export_body or b""), status)

    _, token_page = call("GET", "/api/tokens/page?applicationNo=%s&pageSize=50" % no_a[0], token=token_b)
    check("乙按甲的单号筛 token 得 0 行",
          ((token_page.get("data") or {}).get("records") or []) == [], token_page.get("data"))
    sn_sample = records_a[0].get("manufactureSn") if records_a else ""
    _, sn_hit = call("GET", "/api/tokens/page?manufactureSn=%s" % sn_sample, token=token_b)
    hit_ids = {r.get("id") for r in ((sn_hit.get("data") or {}).get("records") or [])}
    # SN 前缀由 PPID 派生，两个租户用同一 PPID 字符串时号段各自从 1 开始，SN 本身可能重号；
    # 因此判定口径是"返回的行全是乙自己的"，而不是"查不到行"。
    check("乙按甲的 SN 反查只可能命中自己的行，绝不含甲的行号",
          hit_ids and not (hit_ids & ids_a) and hit_ids <= ids_b, [sorted(hit_ids), sorted(ids_a)])

    destroy_before = mysql_value("select sum(status='DESTROYED') from t_token where tenant_id=%s" % tenant_a)
    _, destroy_try = call("POST", "/api/tokens/destroy",
                          {"applicationNo": no_a[0], "confirmApplicationNo": no_a[0]}, token=token_b)
    destroy_after = mysql_value("select sum(status='DESTROYED') from t_token where tenant_id=%s" % tenant_a)
    check("乙销毁不了甲的实体，状态一行未变",
          not ok(destroy_try) and destroy_before == destroy_after, [destroy_try, destroy_before, destroy_after])

    _, task_b = call("POST", "/api/tokens/export", {"applicationNo": no_a[0], "format": "CSV"}, token=token_b)
    task_no_x = (task_b.get("data") or {}).get("taskNo")
    if ok(task_b) and task_no_x:
        # 任务能被乙发起（发起本身不是跨租户写），但取件与内容都必须是空的
        x_done = wait_for(lambda: (lambda vo: vo if vo.get("status") in
                                   ("SUCCESS", "FAILED") else None)(
            (call("GET", "/api/export-tasks/%s" % task_no_x, token=token_b)[1].get("data") or {})), 240, 2) or {}
        status, x_body, _ = raw_get("/api/export-tasks/%s/download" % task_no_x, token=token_b)
        check("乙按甲的单号发起导出，产物里一行甲的数据都没有",
              x_done.get("totalRows") in (0, "0", None) and (not x_body or b"P8A-SHARED" not in x_body),
              [x_done.get("status"), x_done.get("totalRows"), status])
    else:
        check("乙按甲的单号发起导出被直接拒掉", not ok(task_b), task_b)

    _, own_task = call("POST", "/api/tokens/export", {"applicationNo": no_a[0], "format": "CSV"}, token=token_a)
    own_no = (own_task.get("data") or {}).get("taskNo")
    if own_no:
        finished = wait_for(lambda: (lambda vo: vo if vo.get("status") in
                                     ("SUCCESS", "FAILED") else None)(
            (call("GET", "/api/export-tasks/%s" % own_no, token=token_a)[1].get("data") or {})), 240, 2)
        check("甲自己的导出正常完成", (finished or {}).get("status") == "SUCCESS", finished)
        _, read_by_b = call("GET", "/api/export-tasks/%s" % own_no, token=token_b)
        check("乙读不到甲的任务状态（只认发起人）", not ok(read_by_b), read_by_b)
        status, stranger_body, _ = raw_get("/api/export-tasks/%s/download" % own_no, token=token_b)
        check("乙下载不到甲的产物（产物里是 token 原文）",
              status != 200 or TOKEN_MARK.encode() not in (stranger_body or b""),
              [status, len(stranger_body or b"")])
        status, own_body, headers = raw_get("/api/export-tasks/%s/download" % own_no, token=token_a)
        check("甲能下载到自己的产物",
              status == 200 and TOKEN_MARK.encode() in (own_body or b""),
              [status, len(own_body or b"")])
        check("产物文件名按 RFC 5987 给出",
              "filename*=UTF-8''" in (headers.get("Content-Disposition") or ""), headers)
    else:
        skip("导出下载四断", "甲没能发起导出任务：%s" % own_task)

    members_x = call("GET", "/api/members", token=token_x)[1]
    check("成员列表只含当前租户", "P8a 攻击者" in json.dumps(members_x, ensure_ascii=False)
          and "P8a 甲" not in json.dumps(members_x, ensure_ascii=False), members_x)
    pair = mysql_value("select concat_ws('|',m.id,m.user_id) from t_tenant_member m "
                       "where m.tenant_id=%s limit 1" % tenant_a).split("|")
    member_id_a = pair[0] if pair else ""
    user_id_a = pair[1] if len(pair) > 1 else ""
    _, invite_ghost = call("POST", "/api/members/invite", {"account": "p8a_ghost_%s" % stamp}, token=token_x)
    check("邀请不存在的账号被拒（账号必须先存在）", not ok(invite_ghost), invite_ghost)
    _, status_flip = call("PATCH", "/api/members/%s/status" % member_id_a, {"status": 0},
                          token=token_b)
    member_state = mysql_value("select status from t_tenant_member where id=%s" % member_id_a)
    check("乙停不掉甲的成员", member_state != "0" and not ok(status_flip),
          [status_flip, member_state])
    _, remove_try = call("DELETE", "/api/members/%s" % member_id_a, token=token_b)
    still = mysql_value("select count(*) from t_tenant_member where id=%s" % member_id_a)
    check("乙删不掉甲的成员", still == "1" and not ok(remove_try), [remove_try, still])
    _, transfer = call("POST", "/api/members/transfer-owner", {"userId": user_id_a}, token=token_b)
    owners = mysql_value("select count(*) from t_tenant_member where tenant_id=%s and owner_flag=1"
                         % tenant_a)
    check("乙转让不了甲租户的 owner", owners == "1" and not ok(transfer), [transfer, owners])

    _, cred_peek = call("GET", "/api/tenant/credential", token=token_b)
    fp_seen = (cred_peek.get("data") or {}).get("fingerprint") or ""
    # 凭证端点根本没有"指定租户"的入参，永远解析到调用方自己的租户；能读到但读到的是乙那套才算隔离
    check("凭证只有本租户入口，乙读到的指纹与甲不同",
          ok(cred_peek) and fp_seen and fp_seen != fp_a, [fp_seen[:8], fp_a[:8]])
    _, cfg_peek = call("GET", "/api/tenant/config", token=token_b)
    config_before = mysql_value("select count(*) from t_tenant_config where tenant_id=%s" % tenant_a)
    _, cfg_put = call("PUT", "/api/tenant/config",
                      {"values": {"APPLY_MAX_QUANTITY": "999999"}},
                      token=token_b)
    config_after = mysql_value("select count(*) from t_tenant_config where tenant_id=%s" % tenant_a)
    check("乙改不到甲的租户配置（行数与值都不动）",
          config_before == config_after and not ok(cfg_put), [cfg_put, config_before, config_after])

    print("\n[F] 红线：本轮日志增量里没有任何材料，产物目录只有任务文件")
    run_log = log_tail(log_text(APP_LOG), log_run_start)
    whole_log = log_text(APP_LOG)
    for marker, label in ((shared_ppid, "PPID 明文"), (TOKEN_MARK, "token 原文的 base64 前缀"),
                          ("PRIVATE KEY", "私钥 PEM 标记"), ("BEGIN CERTIFICATE", "证书 PEM 标记"),
                          ("==> Parameters", "SQL 实参")):
        needle = marker if label != "PPID 明文" else shared_ppid
        check("整轮日志 grep 不到 %s" % label, needle not in whole_log, "")
    # 库里的敏感列逐列抽样回查日志：只有拿真实值去 grep 才算"没漏"，靠字段名推断没有意义
    for column, label in (("auth_entity_id", "Token ID"), ("accessory_uuid", "配件 UUID"),
                          ("secret_key", "secret_key（苹果 CRC32）"), ("encoded_token", "token 原文"),
                          ("manufacture_sn", "制造序列号")):
        values = [v for v in mysql_run(
            "select %s from t_token where tenant_id in (%s,%s) and %s is not null limit 60"
            % (column, tenant_a, tenant_b, column)).split() if v]
        leaked = [v for v in values if v in whole_log]
        check("日志里看不到 %s 的真实值（抽样 %d 个）" % (label, len(values)), not leaked, leaked[:2])
    check("本轮没有出现「未预期的系统异常」", "未预期的系统异常" not in run_log, "")
    yml = ""
    for path in ("mfi-service/src/main/resources/application.yml",):
        if os.path.exists(path):
            with open(path, "r", encoding="utf-8") as handle:
                yml += handle.read()
    # 允许出现这行（它是给人看的警示注释），但不许是非注释状态的 DEBUG
    mapper_lines = [line for line in yml.splitlines() if "com.galaplat.mfi.dao.mapper" in line]
    check("mapper 包日志级别没有被启用成 DEBUG（只允许注释状态）",
          all(line.lstrip().startswith("#") or "DEBUG" not in line for line in mapper_lines),
          mapper_lines)
    artifacts = os.listdir(EXPORT_DIR) if os.path.isdir(EXPORT_DIR) else []
    bad = [name for name in artifacts if not re.match(r"^[A-Za-z0-9._-]{1,128}$", name)]
    check("导出产物目录只含 <任务号>.<后缀>", not bad, bad[:3])
    tables = mysql_run("select table_name from information_schema.tables where table_schema='apple_mfi_token'")
    lowered = tables.lower()
    check("库里没有任何角色/权限/菜单表",
          not re.search(r"role|permission|menu|auth_?scope|data_?scope", lowered),
          [t for t in lowered.split() if re.search("role|permission|menu", t)][:5])
    frontend = ""
    for root, dirs, files in os.walk("web/src"):
        dirs[:] = [d for d in dirs if d not in ("node_modules", "dist")]
        for name in files:
            with open(os.path.join(root, name), "r", encoding="utf-8", errors="replace") as handle:
                frontend += handle.read()
    check("前端没有权限树/权限点/角色判断",
          not re.search(r"v-permission|hasPermission|roles\s*=|permissionCodes", frontend), "")

    print("\n[G] 收口")
    if broker_stopped:
        depths = queue_depths()
        check("结束时 broker 在跑且队列零积压", bool(depths) and sum(depths.values()) == 0, depths)
    passed = sum(1 for _, value in results if value)
    print("\n断言 %d 项，通过 %d，失败 %d，跳过 %d" %
          (len(results), passed, len(results) - passed, len(skips)))
    for name, value in results:
        if not value:
            print("  未通过: " + name)
    for name in skips:
        print("  跳过: " + name)
    return 0 if passed == len(results) else 1


if __name__ == "__main__":
    sys.exit(main())
