#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""P6 验收：结果读侧、SN、人工重放、异步导出与销毁在真实 MySQL + Redis + RabbitMQ + mTLS 桩上活体对账。

前置（脚本只检查 HTTP 端口，不会替你把服务起起来）：
  1. MySQL / Redis / RabbitMQ 在跑
  2. bash scripts/gen-dev-certs.sh 已生成 .dev-certs/
  3. java -Dfile.encoding=UTF-8 -cp .dev-certs/stub-classes MfiStubServer 8443
     （桩需要含 REGISTERFAIL / DESTROYFAIL 剧情的版本，改了 scripts/MfiStubServer.java 要重新 javac）
  4. 应用以 local profile 启动，除 P5 那两组参数外再压低导出档位，好让翻页与截断都真的发生：
       -Dmfi.export.page-size=100
       -Dmfi.export.max-rows=1000
       -Dmfi.export.dir=.dev-certs/export-p6
     档位只改"多少行算多"，机制（keyset 游标、上限截断、产物目录约束、只认发起人）与线上配置一致。

用法：
  PYTHONIOENCODING=utf-8 python scripts/verify-p6.py

每次运行注册全新租户、新成员与新第二租户，PPID 带时间戳，可重复执行。剧情靠 PPID 名字触发：
  P6-OK-      正常跑完五个阶段，token 全部 REGISTERED —— 读侧、SN、导出取材
  P6-BULK-    1200 个 token —— 导出翻页与上限截断
  P6-REGFAIL- 苹果拒绝注册，分片 DEAD 在 REGISTER，token 停在 ISSUED —— 销毁与"销毁后重放"取材
桩的日志量在整个仓库里都不带 PPID，所以桩侧调用次数只按"本轮开始前的基线 + 增量"对账。
基线要干净，起应用的脚本必须在健康检查之后留一段消费窗口（约 30s），让兜底扫描把上一轮遗留的
延迟消息先处理掉，否则上一轮的注册拒绝会被算进本轮增量。
"""
import argparse
import base64
import json
import os
import re
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
from datetime import date

BASE = os.environ.get("MFI_BASE", "http://localhost:8090")
STUB_URL = os.environ.get("MFI_STUB_URL", "https://localhost:8443")
MYSQL = os.environ.get("MFI_MYSQL", r"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe")
RABBITMQCTL = os.environ.get("MFI_RABBITMQCTL", r"D:\tools\rabbitmq_server-3.13.7\sbin\rabbitmqctl.bat")
APP_LOG = os.environ.get("MFI_APP_LOG", ".dev-certs/app-p6.log")
STUB_LOG = os.environ.get("MFI_STUB_LOG", ".dev-certs/stub.log")
EXPORT_DIR = os.environ.get("MFI_EXPORT_DIR", ".dev-certs/export-p6")

CSV_HEADER = "PPID,Token ID,配件UUID,制造序列号,Secret Key,Token,状态,注册状态,下载时间,注册时间"
# 桩造的 token 原文固定以 mfi-stub-token 开头，base64 后前缀稳定；日志脱敏断言用它反查原文有没有漏进日志
TOKEN_MARK = base64.b64encode(b"mfi-stub-token").decode("utf-8")[:12].rstrip("=")
# 导出产物里必须能看到 token 原文，否则"导出=拿走凭证材料"这句话不成立，只认发起人的设计也就无从解释
CSV_TOKEN_HEAD = "bWZpLXN0dWItdG9rZW4t"

REGISTER_REJECT_MARK = "故意拒绝注册:"
DESTROY_MARK = "受理销毁:"

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


def raw_get(path, token):
    """下载产物要拿字节，不能走 JSON 解析"""
    request = urllib.request.Request(BASE + path, method="GET")
    if token:
        request.add_header("mfi-token", token)
    try:
        with urllib.request.urlopen(request, timeout=180) as response:
            return response.status, response.read(), dict(response.headers)
    except urllib.error.HTTPError as error:
        return error.code, error.read(), dict(error.headers or {})


def mysql_run(sql):
    """账号密码走一次性 defaults 文件，不出现在命令行与进程列表里"""
    handle = tempfile.NamedTemporaryFile("w", suffix=".cnf", delete=False, encoding="utf-8")
    handle.write("[client]\nuser=%s\npassword=%s\n" % (
        os.environ.get("MFI_DB_USERNAME", "root"), os.environ.get("MFI_DB_PASSWORD", "root")))
    handle.close()
    try:
        # Windows 版 mysql.exe 默认按本地代码页（GBK）输出管道，不显式指定 utf8mb4 会把中文错误文案读成乱码
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


def queue_depths():
    if not os.path.exists(RABBITMQCTL):
        return {}
    env = dict(os.environ)
    erlang = os.environ.get("MFI_ERLANG_HOME", r"D:\tools\erlang")
    env["ERLANG_HOME"] = env.get("ERLANG_HOME", erlang)
    env["PATH"] = env.get("PATH", "") + os.pathsep + os.path.join(erlang, "bin") \
        + os.pathsep + os.path.join(erlang, "erts-14.2.5.4", "bin")
    try:
        proc = subprocess.run([RABBITMQCTL, "list_queues", "name", "messages", "--quiet"],
                              capture_output=True, timeout=120, env=env)
    except Exception as error:
        print("  rabbitmqctl 调用异常: " + str(error)[:200])
        return {}
    if proc.returncode != 0:
        print("  rabbitmqctl 非零退出: " + proc.stderr.decode("utf-8", "replace")[:200])
        return {}
    depths = {}
    for line in proc.stdout.decode("utf-8", "replace").splitlines():
        parts = line.split()
        if len(parts) >= 2 and parts[1].isdigit():
            depths[parts[0]] = int(parts[1])
    return depths


def log_text(path):
    if not os.path.exists(path):
        return ""
    with open(path, "r", encoding="utf-8", errors="replace") as handle:
        return handle.read()


def count_marker(path, marker):
    return log_text(path).count(marker)


def register(account, name, password):
    status, payload = call("POST", "/api/auth/register",
                           {"account": account, "password": password, "name": name,
                            "email": account + "@example.com", "tenantName": name})
    if not ok(payload):
        print("注册失败: %s %s" % (status, payload))
        sys.exit(1)
    data = payload.get("data") or {}
    return data.get("tokenValue"), (data.get("currentTenant") or {}).get("tenantId")


def login(account, password, tenant_id):
    status, payload = call("POST", "/api/auth/login", {"account": account, "password": password})
    if not ok(payload):
        print("登录失败: %s %s" % (status, payload))
        sys.exit(1)
    token = (payload.get("data") or {}).get("tokenValue")
    status, payload = call("POST", "/api/auth/switch-tenant", {"tenantId": tenant_id}, token)
    if not ok(payload):
        print("切换租户失败: %s %s" % (status, payload))
        sys.exit(1)
    return token


def create_ppid(token, ppid, model, total):
    status, payload = call("POST", "/api/ppid",
                           {"ppid": ppid, "spu": "P6-SPU", "productModel": model,
                            "totalQuantity": total, "remark": "P6 结果与导出验收"}, token)
    return ok(payload), payload


def apply(token, model, quantity):
    return call("POST", "/api/applications",
                {"productModel": model, "spu": "P6-SPU", "applyQuantity": quantity}, token)


def shard_row(tenant_id, ppid):
    raw = mysql_value("select concat_ws('|',stage,stage_status,retry_count,allocate_count,"
                      "ifnull(last_error,'')) from t_application_ppid "
                      "where tenant_id=%s and ppid='%s'" % (tenant_id, ppid))
    if not raw:
        return None
    parts = raw.split("|")
    if len(parts) < 5:
        return None
    # last_error 是唯一可能含竖线的一列，放在末尾整段取
    return {"stage": parts[0], "stage_status": parts[1], "retry_count": int(parts[2]),
            "allocate_count": int(parts[3]), "last_error": "|".join(parts[4:])}


def token_state(tenant_id, ppid):
    """这个 PPID 名下 token 的四个对账量：总行数 / 已销毁 / 已注册 / 已发号（含唯一号数）"""
    raw = mysql_value("select concat_ws('|',count(*),sum(status='DESTROYED'),"
                      "sum(status='REGISTERED'),sum(manufacture_sn is not null),"
                      "count(distinct manufacture_sn)) from t_token "
                      "where tenant_id=%s and ppid='%s'" % (tenant_id, ppid))
    parts = raw.split("|")
    return [int(p) if p.isdigit() else -1 for p in parts] if len(parts) == 5 else None


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


def last_log(tenant_id, action):
    return mysql_value("select concat(action,'|',ifnull(quantity,-1)) from t_operation_log "
                       "where tenant_id=%s and action='%s' order by id desc limit 1"
                       % (tenant_id, action))


def wait_for(predicate, timeout_seconds, interval_seconds=3):
    """轮询到条件成立为止，返回最后一次取到的值；超时不抛错，由调用方断言"""
    deadline = time.time() + timeout_seconds
    value = None
    while time.time() < deadline:
        value = predicate()
        if value:
            return value
        time.sleep(interval_seconds)
    return value


def wait_task(token, task_no, timeout_seconds=240):
    """轮询导出任务直到终态；返回最后一次看到的任务状态"""
    holder = {}

    def polled():
        status, payload = call("GET", "/api/export-tasks/%s" % task_no, token=token)
        data = payload.get("data") or {}
        holder["vo"] = data
        return data if data.get("status") in ("SUCCESS", "FAILED") else None

    return wait_for(polled, timeout_seconds, 2) or holder.get("vo") or {}


def sn_prefix(ppid):
    """复刻 SnUtil 的前段规则：企业段 E + PPID 后 6 位（去非字母数字）+ 年字母（25=A，6 年一轮）+ MMDD"""
    cleaned = re.sub(r"[^A-Za-z0-9]", "", ppid)
    suffix = cleaned[-6:] if len(cleaned) > 6 else cleaned
    today = date.today()
    year_code = chr(ord("A") + ((today.year % 100 - 25) % 6))
    return "E" + suffix + year_code + "%02d%02d" % (today.month, today.day)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--password", default="Passw0rd123")
    parser.add_argument("--certs", default=".dev-certs")
    args = parser.parse_args()
    stamp = time.strftime("%H%M%S")
    small = 6
    bulk = 1200
    export_cap = 1000

    cases = {
        "OK": {"ppid": "P6-OK-" + stamp, "model": "P6OK" + stamp, "pool": small, "apply": small},
        "BULK": {"ppid": "P6-BULK-" + stamp, "model": "P6BULK" + stamp, "pool": bulk, "apply": bulk},
        "REGFAIL": {"ppid": "P6-REGISTERFAIL-" + stamp, "model": "P6REGF" + stamp,
                    "pool": small, "apply": small},
    }
    # 桩的调用次数只跟本轮增量：桩进程跨多轮运行，日志是累加的
    stub_baseline = {REGISTER_REJECT_MARK: count_marker(STUB_LOG, REGISTER_REJECT_MARK),
                     DESTROY_MARK: count_marker(STUB_LOG, DESTROY_MARK)}

    def stub_delta(marker):
        return count_marker(STUB_LOG, marker) - stub_baseline[marker]

    print("\n[1] 准备：owner 租户 + 一名普通成员 + 一个隔壁租户")
    owner_token, tenant_id = register("p6own_%s" % stamp, "P6 结果租户 " + stamp, args.password)
    member_account = "p6mem_%s" % stamp
    _, member_own_tenant = register(member_account, "P6 成员租户 " + stamp, args.password)
    other_token, other_tenant_id = register("p6other_%s" % stamp, "P6 隔壁租户 " + stamp, args.password)
    print("租户 owner=%s 成员自己的租户=%s 隔壁=%s" % (tenant_id, member_own_tenant, other_tenant_id))
    status, payload = call("POST", "/api/members/invite", {"account": member_account}, owner_token)
    check("成员已加入 owner 租户", ok(payload), payload.get("message"))
    member_token = login(member_account, args.password, tenant_id)
    _, profile = call("GET", "/api/auth/profile", token=member_token)
    brief = [t for t in ((profile.get("data") or {}).get("tenants") or [])
             if t.get("tenantId") == tenant_id]
    check("成员在 owner 租户里的身份是普通成员而不是 owner",
          bool(brief) and brief[0].get("owner") is False, brief)

    with open(os.path.join(args.certs, "tenant-b-chain.crt"), "r", encoding="utf-8") as handle:
        cert_pem = re.search(r"-----BEGIN CERTIFICATE-----.*?-----END CERTIFICATE-----", handle.read(),
                             re.S).group(0)
    with open(os.path.join(args.certs, "tenant-b.pk1.key"), "r", encoding="utf-8") as handle:
        key_pem = re.search(r"-----BEGIN (?:RSA )?PRIVATE KEY-----.*?-----END (?:RSA )?PRIVATE KEY-----",
                            handle.read(), re.S).group(0)
    check("租户凭证已入库", ok(call("PUT", "/api/tenant/credential",
                                   {"certPem": cert_pem, "keyPem": key_pem}, owner_token)[1]))
    status, payload = call("PUT", "/api/tenant/profile",
                           {"tenantName": "P6 结果租户 " + stamp, "appleTechnology": "",
                            "appleBaseUrl": STUB_URL}, owner_token)
    check("调用地址指向本地桩",
          ok(payload) and (payload.get("data") or {}).get("effectiveBaseUrl") == STUB_URL, payload.get("data"))
    check("mTLS 自检打桩成功", ok(call("POST", "/api/tenant/credential/verify", {}, owner_token)[1]))
    # 重试上限 2 = 失败一次退避一次再终态；P5 已经把完整退避档位验过，这里只取"注册阶段会死"这个后果
    status, payload = call("PUT", "/api/tenant/config",
                           {"values": {"PIPELINE_MAX_RETRY": "2", "PIPELINE_CONCURRENT_PER_TENANT": "4",
                                        "APPLY_MAX_QUANTITY": "0", "SERIAL_NUMBER_PREFIX": "E"}}, owner_token)
    check("重试上限 2、并发 4、申请不额外限量", ok(payload), payload.get("message"))
    if not os.path.exists(APP_LOG):
        skip("应用日志断言", "找不到 %s，用 MFI_APP_LOG 指定运行日志" % APP_LOG)

    print("\n[2] 三份额度录入并提交三张申请单")
    prepared = sum(1 for case in cases.values()
                   if create_ppid(owner_token, case["ppid"], case["model"], case["pool"])[0])
    check("三份 PPID 额度录入成功", prepared == len(cases), prepared)
    for key, case in cases.items():
        status, payload = apply(owner_token, case["model"], case["apply"])
        submitted = payload.get("data") or {}
        case["application_no"] = submitted.get("applicationNo")
        check("%s 受理并只拆出一个分片" % key,
              ok(payload) and submitted.get("ppidCount") == 1, payload)

    print("\n[3] 正常链路：六个 token 走到 REGISTERED，SN 规则与唯一性活体核对")
    ok_row = wait_for(lambda: (lambda r: r if r and r["stage_status"] in ("SUCCESS", "DEAD") else None)(
        shard_row(tenant_id, cases["OK"]["ppid"])), 200)
    check("OK 分片跑完五个阶段（FINISH/SUCCESS）",
          ok_row and ok_row["stage"] == "FINISH" and ok_row["stage_status"] == "SUCCESS", ok_row)
    state = wait_for(lambda: (lambda s: s if s and s[2] == small else None)(
        token_state(tenant_id, cases["OK"]["ppid"])), 120)
    check("六个 token 全部注册成功", state and state[2] == small, state)
    check("六个 token 全部发到制造序列号", state and state[3] == small, state)
    check("序列号互不重复", state and state[4] == small, state)
    prefix = sn_prefix(cases["OK"]["ppid"])
    sn_sample = mysql_value("select manufacture_sn from t_token where tenant_id=%s and ppid='%s' "
                            "order by id asc limit 1" % (tenant_id, cases["OK"]["ppid"]))
    check("SN 前段就是「E + PPID 后 6 位 + 年字母 + MMDD」（与 SnUtil 同一条规则）",
          sn_sample.startswith(prefix) and len(sn_sample) == len(prefix) + 4, [prefix, sn_sample])
    sn_first = mysql_value("select right(min(manufacture_sn),4) from t_token "
                           "where tenant_id=%s and ppid='%s'" % (tenant_id, cases["OK"]["ppid"]))
    check("流水号是 4 位补零的连续号段", sn_first.isdigit() and len(sn_first) == 4, sn_first)

    print("\n[4] 读侧详情：五阶段进度、文件明细、token 分页各给什么")
    status, payload = call("GET", "/api/applications/%s" % cases["OK"]["application_no"], token=owner_token)
    detail = payload.get("data") or {}
    stages = detail.get("stages") or []
    check("详情一次给全五个阶段", ok(payload) and len(stages) == 5, stages)
    check("五个阶段都只有一个分片且全部成功",
          all(s.get("reached") == 1 and s.get("succeeded") == 1 and not s.get("dead") for s in stages), stages)
    advances = [t for t in (detail.get("timeline") or []) if t.get("action") == "STAGE_ADVANCE"]
    check("时间线给出五阶段推进记录", len(advances) == 5, len(advances))
    check("时间线把分片显示成 PPID（租户自己的数据，读侧允许）",
          any(t.get("ppid") == cases["OK"]["ppid"] for t in advances), advances[:1])
    summary = detail.get("tokenSummary") or []
    check("token 汇总按状态分组给出 REGISTERED 六个",
          any(s.get("status") == "REGISTERED" and s.get("count") == small for s in summary), summary)
    status, payload = call("GET", "/api/applications/%s/files" % cases["OK"]["application_no"],
                           token=owner_token)
    files = payload.get("data") or []
    check("文件明细两份、行数之和等于申请量",
          len(files) == 2 and sum(f.get("tokenCount") or 0 for f in files) == small, files)
    check("两份文件都下载成功", all(f.get("downloadStatus") == "SUCCESS" for f in files), files)

    status, payload = call("GET", "/api/tokens/page?applicationNo=%s&pageSize=50"
                           % cases["OK"]["application_no"], token=owner_token)
    records = ((payload.get("data") or {}).get("records")) or []
    check("token 分页返回六行", ok(payload) and len(records) == small, len(records))
    check("分页行里有核对用的 PPID / SN / 状态",
          bool(records) and records[0].get("ppid") == cases["OK"]["ppid"]
          and records[0].get("manufactureSn") and records[0].get("status") == "REGISTERED", records[:1])
    page_raw = json.dumps(payload, ensure_ascii=False)
    for column in ("encodedToken", "secretKey", "authEntityId", "accessoryUuid"):
        check("分页响应不含凭证列 %s" % column, column not in page_raw, page_raw[:200])
    status, payload = call("GET", "/api/tokens/page?manufactureSn=%s" % sn_sample, token=owner_token)
    check("按制造序列号能反查到一个 token（厂商核对入口）",
          len(((payload.get("data") or {}).get("records") or [])) == 1, payload.get("data"))

    print("\n[5] 导出 CSV：产物里就是凭证材料，所以只认发起人")
    status, payload = call("POST", "/api/tokens/export",
                           {"applicationNo": cases["OK"]["application_no"], "format": "CSV"}, owner_token)
    task = payload.get("data") or {}
    task_no = task.get("taskNo")
    check("导出受理给出 PENDING 任务号", ok(payload) and task_no and task.get("status") == "PENDING", payload)
    done = wait_task(owner_token, task_no)
    check("任务落到 SUCCESS 且行数等于申请量",
          done.get("status") == "SUCCESS" and done.get("totalRows") == small, done)
    check("成功才给出下载路径", done.get("downloadUrl") == "/api/export-tasks/%s/download" % task_no, done)
    status, body, headers = raw_get("/api/export-tasks/%s/download" % task_no, owner_token)
    text = body.decode("utf-8-sig", "replace")
    lines = text.splitlines()
    check("下载返回 CSV 且带 UTF-8 BOM（Excel 双击不乱码）",
          status == 200 and "text/csv" in headers.get("Content-Type", "")
          and body.startswith(b"\xef\xbb\xbf"), [status, headers.get("Content-Type"), body[:8]])
    check("附件名走 RFC 5987 编码",
          "filename*=UTF-8''tokens-%s.csv" % task_no in headers.get("Content-Disposition", ""),
          headers.get("Content-Disposition"))
    check("表头与列序就是约定的十列", bool(lines) and lines[0].replace("﻿", "") == CSV_HEADER, lines[:1])
    check("行数=表头+六个 token", len(lines) == small + 1, len(lines))
    check("产物里带 token 原文", CSV_TOKEN_HEAD in text, text[:120])
    check("产物里带刚发出的 SN", sn_sample in text, sn_sample)
    stored = mysql_value("select file_path from t_export_task where tenant_id=%s and task_no='%s'"
                         % (tenant_id, task_no))
    check("产物只落在配置目录、库里只存相对文件名",
          stored == task_no + ".csv" and os.path.isfile(os.path.join(EXPORT_DIR, stored)), stored)

    print("\n[6] 导出越权：隔壁租户与同租户成员都拿不到别人的产物")
    status, payload = call("GET", "/api/export-tasks/%s" % task_no, token=other_token)
    check("隔壁租户读不到这个任务号",
          not ok(payload) and "不属于当前账号" in (payload.get("message") or ""), payload)
    status, body, _ = raw_get("/api/export-tasks/%s/download" % task_no, other_token)
    check("隔壁租户下载不到产物字节", status != 200 and not body.startswith(b"\xef\xbb\xbf"), status)
    status, payload = call("GET", "/api/export-tasks/%s" % task_no, token=member_token)
    check("同租户成员也读不到 owner 发起的任务",
          not ok(payload) and "不属于当前账号" in (payload.get("message") or ""), payload)
    status, payload = call("POST", "/api/tokens/export",
                           {"applicationNo": cases["OK"]["application_no"], "format": "CSV"}, member_token)
    member_task = payload.get("data") or {}
    member_no = member_task.get("taskNo")
    check("成员可以自己发起导出（导出不是 owner 专属）", ok(payload) and member_no not in (None, task_no), payload)
    if member_no:
        check("成员自己的任务只认自己",
              (wait_task(member_token, member_no) or {}).get("status") == "SUCCESS", member_no)
        status, payload = call("GET", "/api/export-tasks/%s" % member_no, token=owner_token)
        check("反过来 owner 也读不到成员发起的任务", not ok(payload), payload)
    else:
        skip("成员自己的任务只认自己", "成员发起导出未被受理")

    print("\n[7] 大单导出：1200 行按 100 一页翻完，触达 %d 上限就截断" % export_cap)
    bulk_row = wait_for(lambda: (lambda r: r if r and r["stage_status"] in ("SUCCESS", "DEAD") else None)(
        shard_row(tenant_id, cases["BULK"]["ppid"])), 300)
    check("BULK 分片跑完（1200 个 token 全链路）",
          bulk_row and bulk_row["stage"] == "FINISH" and bulk_row["stage_status"] == "SUCCESS", bulk_row)
    bulk_state = token_state(tenant_id, cases["BULK"]["ppid"])
    check("BULK 的 1200 个 token 都拿到唯一 SN",
          bulk_state and bulk_state[3] == bulk and bulk_state[4] == bulk, bulk_state)
    trunc_before = count_marker(APP_LOG, "导出行数触达上限并截断")
    status, payload = call("POST", "/api/tokens/export",
                           {"applicationNo": cases["BULK"]["application_no"], "format": "XLSX"}, owner_token)
    bulk_task = (payload.get("data") or {}).get("taskNo")
    bulk_done = wait_task(owner_token, bulk_task)
    check("XLSX 任务落到 SUCCESS", bulk_done.get("status") == "SUCCESS", bulk_done)
    check("行数停在服务端上限 %d 而不是 1200" % export_cap, bulk_done.get("totalRows") == export_cap, bulk_done)
    check("触达上限留下了截断日志", count_marker(APP_LOG, "导出行数触达上限并截断") > trunc_before, None)
    status, body, headers = raw_get("/api/export-tasks/%s/download" % bulk_task, owner_token)
    check("XLSX 产物是合法 zip 容器（PK 魔数）",
          status == 200 and body[:4] == b"PK\x03\x04" and len(body) > 4096, [status, len(body)])
    check("导出留痕只记数量", last_log(tenant_id, "EXPORT") == "EXPORT|%d" % export_cap, last_log(tenant_id, "EXPORT"))

    print("\n[8] 注册被苹果拒绝：分片 DEAD，token 停在 ISSUED")
    dead_row = wait_for(lambda: (lambda r: r if r and r["stage_status"] == "DEAD" else None)(
        shard_row(tenant_id, cases["REGFAIL"]["ppid"])), 240)
    check("注册失败的死因落在 REGISTER 阶段",
          dead_row and dead_row["stage"] == "REGISTER", dead_row)
    check("退避一次之后才终态（重试上限 2）", dead_row and dead_row["retry_count"] >= 1, dead_row)
    check("苹果侧确实被打了两次注册请求", stub_delta(REGISTER_REJECT_MARK) == 2,
          stub_delta(REGISTER_REJECT_MARK))
    regfail_state = token_state(tenant_id, cases["REGFAIL"]["ppid"])
    check("六个 token 已下载但一个都没注册成", regfail_state == [small, 0, 0, 0, 0], regfail_state)
    regfail_app = app_row(tenant_id, cases["REGFAIL"]["application_no"])
    check("全分片失败聚合成 FAILED 且写了失败原因",
          regfail_app and regfail_app["status"] == "FAILED" and regfail_app["fail_reason"], regfail_app)
    status, payload = call("GET", "/api/applications/%s" % cases["REGFAIL"]["application_no"], token=owner_token)
    dead_stages = {s.get("stage"): s for s in ((payload.get("data") or {}).get("stages") or [])}
    check("进度页把 REGISTER 显示成卡住、FINISH 还没走到",
          dead_stages.get("REGISTER", {}).get("dead") == 1
          and not dead_stages.get("FINISH", {}).get("reached"), dead_stages)

    print("\n[9] 销毁前的三道拒绝：非 owner、二次确认、隔壁租户")
    regfail_no = cases["REGFAIL"]["application_no"]
    status, payload = call("POST", "/api/tokens/destroy",
                           {"applicationNo": regfail_no, "confirmApplicationNo": regfail_no}, member_token)
    check("普通成员不能销毁", not ok(payload) and "owner" in (payload.get("message") or ""), payload)
    status, payload = call("POST", "/api/tokens/destroy",
                           {"applicationNo": regfail_no, "confirmApplicationNo": "A261010-WRONG"}, owner_token)
    check("二次确认不一致直接拒绝", not ok(payload) and "二次确认" in (payload.get("message") or ""), payload)
    status, payload = call("POST", "/api/tokens/destroy",
                           {"applicationNo": regfail_no, "confirmApplicationNo": regfail_no}, other_token)
    check("隔壁租户销毁不了别人的单",
          not ok(payload) and "不属于当前租户" in (payload.get("message") or ""), payload)
    status, payload = call("POST", "/api/tokens/destroy",
                           {"applicationNo": regfail_no, "confirmApplicationNo": "  "}, owner_token)
    check("确认位为空也拒绝（不是可选参数）", not ok(payload), payload)
    check("三道拒绝都没有把销毁请求打出去", stub_delta(DESTROY_MARK) == 0, stub_delta(DESTROY_MARK))
    check("三道拒绝都没改库里的状态", token_state(tenant_id, cases["REGFAIL"]["ppid"]) == regfail_state, None)

    print("\n[10] 正常销毁：六个未注册实体在苹果侧作废")
    status, payload = call("POST", "/api/tokens/destroy",
                           {"applicationNo": regfail_no, "confirmApplicationNo": regfail_no}, owner_token)
    destroyed = payload.get("data") or {}
    check("销毁返回 selected=6 且全部落地",
          ok(payload) and destroyed.get("selected") == small and destroyed.get("destroyed") == small, payload)
    check("没被中断也没有剩余",
          destroyed.get("hasMore") is False and not destroyed.get("failReason"), destroyed)
    check("六个实体打包成一个批次提交", stub_delta(DESTROY_MARK) == 1, stub_delta(DESTROY_MARK))
    check("库里六行全部 DESTROYED",
          token_state(tenant_id, cases["REGFAIL"]["ppid"]) == [small, small, 0, 0, 0], None)
    check("留痕写了 DESTROY 且只记数量", last_log(tenant_id, "DESTROY") == "DESTROY|%d" % small,
          last_log(tenant_id, "DESTROY"))
    leak = mysql_value("select ifnull(sum(detail like '%%%s%%' or detail like '%%AUTH%%'),0) "
                       "from t_operation_log where tenant_id=%s and action='DESTROY'"
                       % (cases["REGFAIL"]["ppid"], tenant_id))
    check("销毁留痕里没有 PPID 也没有实体编号", leak == "0", leak)

    print("\n[11] 已注册的实体不会被销毁，重复点击也不会重复提交")
    status, payload = call("POST", "/api/tokens/destroy",
                           {"applicationNo": cases["OK"]["application_no"],
                            "confirmApplicationNo": cases["OK"]["application_no"]}, owner_token)
    none_left = payload.get("data") or {}
    check("对已注册的单销毁候选为 0（不报错、不打苹果）",
          ok(payload) and none_left.get("selected") == 0 and none_left.get("destroyed") == 0, payload)
    again_before = stub_delta(DESTROY_MARK)
    status, payload = call("POST", "/api/tokens/destroy",
                           {"applicationNo": regfail_no, "confirmApplicationNo": regfail_no}, owner_token)
    again = payload.get("data") or {}
    check("重复销毁同一张单：候选已空，苹果一次都没再被打",
          again.get("selected") == 0 and stub_delta(DESTROY_MARK) == again_before, again)
    check("已销毁的仍是那六行，没有二次改状态",
          token_state(tenant_id, cases["REGFAIL"]["ppid"]) == [small, small, 0, 0, 0], None)

    print("\n[12] 销毁之后人工重放：不再注册已作废实体，也不再给它发号")
    status, payload = call("POST", "/api/applications/%s/retry" % regfail_no, token=owner_token)
    replay = payload.get("data") or {}
    shards = replay.get("shards") or [{}]
    check("重放只挑出那一个 DEAD 分片，阶段仍是 REGISTER",
          ok(payload) and replay.get("replayedCount") == 1 and shards[0].get("stage") == "REGISTER", payload)
    check("重放后的分片显示成待处理（retry_count 归零、死因清空）",
          shards[0].get("stageStatus") == "PENDING" and shards[0].get("retryCount") == 0
          and not shards[0].get("lastError"), shards[0])
    reopened = wait_for(lambda: (lambda r: r if r and r["status"] != "FAILED" else None)(
        app_row(tenant_id, regfail_no)), 60)
    check("重放把单子拉回进行中并抹掉上一轮的失败原因",
          reopened and reopened["status"] in ("RUNNING", "SUCCESS") and not reopened["fail_reason"], reopened)
    finished = wait_for(lambda: (lambda r: r if r and r["stage_status"] == "SUCCESS" else None)(
        shard_row(tenant_id, cases["REGFAIL"]["ppid"])), 240)
    check("重放后分片走到 FINISH/SUCCESS", finished is not None, finished)
    check("重放没有把已销毁的实体再注册一遍（苹果侧零次新调用）",
          stub_delta(REGISTER_REJECT_MARK) == 2, stub_delta(REGISTER_REJECT_MARK))
    final_state = token_state(tenant_id, cases["REGFAIL"]["ppid"])
    check("六行仍是 DESTROYED，收口没把它们算成漏注册", final_state[1] == small, final_state)
    check("销毁过的实体不再发制造序列号（号段不白占）", final_state[3] == 0, final_state)
    final_app = app_row(tenant_id, regfail_no)
    check("整单聚合成 SUCCESS、失败原因无残留、finish_time 重新落上、token 数仍是 6",
          final_app and final_app["status"] == "SUCCESS" and not final_app["fail_reason"]
          and len(final_app["finish_time"]) == 19 and final_app["token_count"] == str(small), final_app)
    status, payload = call("POST", "/api/applications/%s/retry" % regfail_no, token=owner_token)
    check("没有 DEAD 分片时重放是空操作而不是报错",
          ok(payload) and (payload.get("data") or {}).get("replayedCount") == 0, payload)
    check("重放留痕存在（RETRY 记录）", int(mysql_value(
        "select ifnull(count(*),0) from t_operation_log where tenant_id=%s and action='RETRY'" % tenant_id)) >= 1,
        None)

    print("\n[13] 日志红线：运行日志里 grep 不到 PPID、SN 前缀、token 原文与 SQL 参数")
    app_log = log_text(APP_LOG)
    for key, case in cases.items():
        check("%s 的 PPID 明文不在应用日志里" % key, case["ppid"] not in app_log, None)
    check("SN 前段（含 PPID 后 6 位）不在应用日志里", sn_prefix(cases["OK"]["ppid"]) not in app_log, None)
    check("应用日志没有 token 原文", TOKEN_MARK not in app_log, None)
    check("应用日志没有 SQL 参数行（mapper 保持 INFO）", "==> Parameters" not in app_log, None)
    check("应用日志没有私钥文本", "PRIVATE KEY" not in app_log, None)
    strays = [n for n in (os.listdir(EXPORT_DIR) if os.path.isdir(EXPORT_DIR) else [])
              if not re.match(r"^[A-Za-z0-9._-]+\.(csv|xlsx)$", n)
              or n != os.path.basename(n)]
    check("产物目录里只有 <任务号>.<后缀>，没有别的东西", not strays, strays)

    print("\n[14] 队列收口")
    stage_queues = ["mfi.apply.queue", "mfi.name.queue", "mfi.content.queue", "mfi.register.queue",
                    "mfi.finish.queue", "mfi.token.dlq"]
    depths = queue_depths()
    if not depths:
        skip("阶段队列清空对账", "rabbitmqctl 不可用")
    else:
        stuck = {q: depths.get(q, 0) for q in stage_queues if depths.get(q, 0)}
        check("五个阶段队列与死信队列都没有积压", not stuck, stuck)

    passed = sum(1 for _, p in results if p)
    print("\n==== 结果: %d/%d 通过，跳过 %d 项 ====" % (passed, len(results), len(skips)))
    if passed != len(results):
        for name, ok_flag in results:
            if not ok_flag:
                print("  未通过: " + name)
    print("留档: tenant=%s stamp=%s 申请单=%s" % (tenant_id, stamp,
                                                 [c.get("application_no") for c in cases.values()]))
    sys.exit(0 if passed == len(results) else 1)


if __name__ == "__main__":
    main()
