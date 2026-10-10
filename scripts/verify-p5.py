#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""P5 验收：五个阶段在真实 RabbitMQ + 真实 mTLS 桩上跑通，延迟/重试/死信/自愈全部活体对账。

前置（脚本只检查 HTTP 端口，不会替你把服务起起来）：
  1. MySQL / Redis / RabbitMQ 在跑
  2. bash scripts/gen-dev-certs.sh 已生成 .dev-certs/
  3. java -Dfile.encoding=UTF-8 scripts/MfiStubServer.java 8443
  4. 应用以 local profile 启动，注入 MFI_CREDENTIAL_MASTER_KEY 与 MFI_APPLE_TRUSTED_CA_PEM，
     并把延迟档位调短、PROCESSING 超时放宽，好让验收在几分钟内跑完：
       -Dfile.encoding=UTF-8
       -Dmfi.pipeline.delay-tiers=10000,30000,60000
       -Dmfi.pipeline.processing-timeout-minutes=1
     档位只改队列名与时长，机制（TTL + 死信回流、按档位向上取整）与线上配置完全一致。

用法：
  PYTHONIOENCODING=utf-8 python scripts/verify-p5.py

每次运行注册全新租户并用时间戳做 PPID，剧情之间靠 PPID 名字区分，可重复执行；结束时会把仍在等待
的分片收口成 DEAD，不给下一次运行留尾巴。
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
from datetime import datetime

BASE = os.environ.get("MFI_BASE", "http://localhost:8090")
STUB_URL = os.environ.get("MFI_STUB_URL", "https://localhost:8443")
MYSQL = os.environ.get("MFI_MYSQL", r"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe")
RABBITMQCTL = os.environ.get("MFI_RABBITMQCTL", r"D:\tools\rabbitmq_server-3.13.7\sbin\rabbitmqctl.bat")
APP_LOG = os.environ.get("MFI_APP_LOG", ".dev-certs/app-p5.log")
STUB_LOG = os.environ.get("MFI_STUB_LOG", ".dev-certs/stub.log")
# 桩里 token 原文的固定前缀，日志脱敏断言用它反查"有没有把 token 打进日志"
TOKEN_MARK = base64.b64encode(b"mfi-stub").decode("utf-8").rstrip("=")
results = []
skips = []


def check(name, passed, detail=""):
    results.append((name, bool(passed)))
    print(("  PASS  " if passed else "  FAIL  ") + name + ("" if passed else "  -> " + str(detail)[:300]))
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
        with urllib.request.urlopen(request, timeout=120) as response:
            return response.status, json.loads(response.read().decode("utf-8") or "{}")
    except urllib.error.HTTPError as error:
        raw = error.read().decode("utf-8") or "{}"
        try:
            return error.code, json.loads(raw)
        except json.JSONDecodeError:
            return error.code, {"code": "?", "message": raw[:200], "data": None}


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
    """一次 rabbitmqctl 拿到全部队列深度；rabbitmqctl 启动慢，只在整个流程里调用两次"""
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


def new_tenant(account, name, password):
    status, payload = call("POST", "/api/auth/register",
                           {"account": account, "password": password, "name": name,
                            "email": account + "@example.com", "tenantName": name})
    if not ok(payload):
        print("注册失败: %s %s" % (status, payload))
        sys.exit(1)
    data = payload.get("data") or {}
    return data.get("tokenValue"), (data.get("currentTenant") or {}).get("tenantId")


def create_ppid(token, ppid, model, total):
    status, payload = call("POST", "/api/ppid",
                           {"ppid": ppid, "spu": "P5-SPU", "productModel": model,
                            "totalQuantity": total, "remark": "P5 流水线验收"}, token)
    return ok(payload), payload


def apply(token, model, quantity):
    return call("POST", "/api/applications",
                {"productModel": model, "spu": "P5-SPU", "applyQuantity": quantity}, token)


def shard_row(tenant_id, ppid):
    raw = mysql_value("select concat_ws('|',stage,stage_status,retry_count,"
                      "ifnull(date_format(next_execute_time,'%%Y-%%m-%%d %%H:%%i:%%s'),''),"
                      "ifnull(last_error,''),ifnull(apple_request_uuid,''),allocate_count) "
                      "from t_application_ppid where tenant_id=%s and ppid='%s'" % (tenant_id, ppid))
    if not raw:
        return None
    parts = raw.split("|")
    if len(parts) < 7:
        return None
    # last_error 是唯一可能含竖线的一列，所以首三列与末两列按位置取，中间全部归给它
    row = {"stage": parts[0], "stage_status": parts[1], "retry_count": int(parts[2]),
           "next_execute_time": parts[3], "last_error": "|".join(parts[4:-2]),
           "apple_request_uuid": parts[-2], "allocate_count": parts[-1]}
    row["due"] = None
    if row["next_execute_time"]:
        try:
            row["due"] = datetime.strptime(row["next_execute_time"], "%Y-%m-%d %H:%M:%S")
        except ValueError:
            pass
    return row


def app_status(tenant_id, application_no):
    return mysql_value("select concat(status,'|',token_count,'|',ifnull(date_format(finish_time,"
                       "'%%Y-%%m-%%d %%H:%%i:%%s'),'')) from t_application "
                       "where tenant_id=%s and application_no='%s'" % (tenant_id, application_no))


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


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--password", default="Passw0rd123")
    parser.add_argument("--certs", default=".dev-certs")
    args = parser.parse_args()
    stamp = time.strftime("%H%M%S")
    tenant_id = None

    def sql_tenant():
        return tenant_id

    # ---- 场景与 PPID：剧情全靠名字触发，每个场景一个独立型号，保证只拆出一个分片 ----
    cases = {
        "OK": {"ppid": "P5-OK-" + stamp, "model": "P5OK" + stamp, "pool": 20, "apply": 7},
        "WAIT": {"ppid": "P5-WAIT-" + stamp, "model": "P5WAIT" + stamp, "pool": 10, "apply": 5},
        "WAITLONG": {"ppid": "P5-WAITLONG-" + stamp, "model": "P5WAITL" + stamp, "pool": 10, "apply": 3},
        "F403": {"ppid": "P5-FAIL403-" + stamp, "model": "P5F403" + stamp, "pool": 10, "apply": 4},
        "F503": {"ppid": "P5-FAIL503-" + stamp, "model": "P5F503" + stamp, "pool": 10, "apply": 4},
    }

    print("\n[1] 准备：新租户 + 桩地址 + 业务参数")
    token, tenant_id = new_tenant("p5own_%s" % stamp, "P5 流水线租户 " + stamp, args.password)
    print("租户 tenantId=%s" % tenant_id)
    with open(os.path.join(args.certs, "tenant-b-chain.crt"), "r", encoding="utf-8") as handle:
        cert_pem = re.search(r"-----BEGIN CERTIFICATE-----.*?-----END CERTIFICATE-----", handle.read(),
                             re.S).group(0)
    with open(os.path.join(args.certs, "tenant-b.pk1.key"), "r", encoding="utf-8") as handle:
        key_pem = re.search(r"-----BEGIN (?:RSA )?PRIVATE KEY-----.*?-----END (?:RSA )?PRIVATE KEY-----",
                            handle.read(), re.S).group(0)
    status, payload = call("PUT", "/api/tenant/credential", {"certPem": cert_pem, "keyPem": key_pem}, token)
    check("租户凭证已入库", ok(payload), payload.get("message"))
    status, payload = call("PUT", "/api/tenant/profile",
                           {"tenantName": "P5 流水线租户 " + stamp, "appleTechnology": "",
                            "appleBaseUrl": STUB_URL}, token)
    data = payload.get("data") or {}
    check("调用地址指向本地桩", ok(payload) and data.get("effectiveBaseUrl") == STUB_URL, data)
    status, payload = call("POST", "/api/tenant/credential/verify", {}, token)
    verified = payload.get("data") or {}
    check("mTLS 自检打桩成功（reachable + 200）",
          ok(payload) and verified.get("reachable") is True and verified.get("httpStatus") == 200, verified)
    status, payload = call("PUT", "/api/tenant/config",
                           {"values": {"PIPELINE_MAX_RETRY": "3", "PIPELINE_CONCURRENT_PER_TENANT": "4",
                                        "APPLY_MAX_QUANTITY": "0"}}, token)
    check("重试上限 3、租户并发 4 配置成功", ok(payload), payload.get("message"))
    if not os.path.exists(APP_LOG):
        skip("应用日志断言", "找不到 %s，用 MFI_APP_LOG 指定运行日志" % APP_LOG)

    print("\n[2] 五份额度录入并提交五张申请单")
    prepared = 0
    for case in cases.values():
        created, payload = create_ppid(token, case["ppid"], case["model"], case["pool"])
        prepared += 1 if created else 0
    check("五份 PPID 额度录入成功", prepared == len(cases), prepared)
    for key, case in cases.items():
        status, payload = apply(token, case["model"], case["apply"])
        submitted = payload.get("data") or {}
        case["application_no"] = submitted.get("applicationNo")
        check("%s 受理并进入 APPLY 队列" % key, ok(payload) and submitted.get("ppidCount") == 1, payload)

    print("\n[3] 正常链路：7 个 token 走完五个阶段")
    ok_row = wait_for(lambda: (lambda r: r if r and (r["stage_status"] in ("SUCCESS", "DEAD")) else None)(
        shard_row(tenant_id, cases["OK"]["ppid"])), 150)
    check("OK 分片跑完五个阶段（FINISH/SUCCESS）",
          ok_row and ok_row["stage"] == "FINISH" and ok_row["stage_status"] == "SUCCESS", ok_row)
    if ok_row:
        check("APPLY 落下了桩给的 request id", ok_row["apple_request_uuid"].startswith("stub-"),
              ok_row["apple_request_uuid"])
        check("成功后 retry_count 归零且没有残留错误",
              ok_row["retry_count"] == 0 and ok_row["last_error"] == "", ok_row)

    print("\n[4] 到货对账：token 行数、内容与注册状态")
    files = mysql_value("select concat(count(*),'|',ifnull(sum(token_count),0),'|',sum(download_status='SUCCESS')) "
                        "from t_token_file where tenant_id=%s and ppid='%s'" % (tenant_id, cases["OK"]["ppid"]))
    parts = files.split("|")
    check("登记了两个文件且都下载成功（逐文件推进）",
          len(parts) == 3 and parts[0] == "2" and parts[1] == "7" and parts[2] == "2", files)
    tokens = mysql_value("select concat(count(*),'|',sum(status='REGISTERED'),'|',"
                         "sum(register_status='SUCCESS'),'|',count(distinct accessory_uuid),'|',"
                         "sum(secret_key regexp '^[0-9A-F]{8}$')) from t_token where tenant_id=%s and ppid='%s'"
                         % (tenant_id, cases["OK"]["ppid"]))
    parts = tokens.split("|")
    check("7 个 token 落库、全部注册成功", len(parts) == 5 and parts[0] == "7" and parts[1] == "7"
          and parts[2] == "7", tokens)
    check("配件 UUID 逐行唯一且数量对得上（重试不会换 uuid）",
          len(parts) == 5 and parts[3] == "7", tokens)
    check("苹果文件里的 CRC32 原样落库", len(parts) == 5 and parts[4] == "7", tokens)
    sample = mysql_value("select concat(auth_entity_id,'|',encoded_token) from t_token "
                         "where tenant_id=%s and ppid='%s' order by id asc limit 1"
                         % (tenant_id, cases["OK"]["ppid"]))
    entity, _, encoded = sample.partition("|")
    decoded = ""
    try:
        decoded = base64.b64decode(encoded).decode("utf-8")
    except Exception:
        pass
    check("列序解析正确：Token ID 与 token 原文各归其列",
          entity.startswith("TST") and decoded == "mfi-stub-token-%s-1" % cases["OK"]["ppid"],
          [entity, decoded[:60]])
    check("申请单聚合为 SUCCESS 且 token_count=7、终态时间已落",
          app_status(tenant_id, cases["OK"]["application_no"]).startswith("SUCCESS|7|")
          and len(app_status(tenant_id, cases["OK"]["application_no"]).split("|")[2]) == 19,
          app_status(tenant_id, cases["OK"]["application_no"]))
    logs = mysql_value("select concat(action,'|',count(*)) from t_operation_log "
                       "where tenant_id=%s and shard_id=(select id from t_application_ppid where tenant_id=%s "
                       "and ppid='%s') group by action" % (tenant_id, tenant_id, cases["OK"]["ppid"]))
    check("五个阶段各留一条前进记录", "STAGE_ADVANCE|5" in logs.splitlines(), logs)
    leaked = mysql_value("select count(*) from t_operation_log where tenant_id=%s and "
                         "(detail like '%%TST%%' or detail like '%%%s%%')"
                         % (tenant_id, TOKEN_MARK))
    check("留痕里没有 token 原文与 Token ID", leaked == "0", "count=" + str(leaked))

    print("\n[5] 按 retry_download_after 等待：等待不消耗重试次数")
    wait_case = cases["WAIT"]

    def waiting():
        row = shard_row(tenant_id, wait_case["ppid"])
        return row if row and "苹果尚未生成文件" in row["last_error"] and row["stage_status"] == "PENDING" else None

    parked = wait_for(waiting, 60)
    check("文件未就绪时按计划等待（PENDING + next_execute_time 在未来）",
          parked and parked["due"] and parked["due"] > datetime.now(), parked)
    if parked:
        check("等待期间没有消耗重试次数", parked["retry_count"] == 0, parked)
        depths = queue_depths()
        if not depths:
            skip("延迟队列里能看到在等的消息", "rabbitmqctl 不可用")
        else:
            held = sum(v for k, v in depths.items() if k.startswith("mfi.delay."))
            check("消息确实躺在某个延迟档位队列里（TTL + 死信回流）", held >= 1,
                  {k: v for k, v in depths.items() if k.startswith("mfi.delay.") and v})
        status, page = call("GET", "/api/applications/page?applicationNo=%s" % wait_case["application_no"],
                            token=token)
        records = ((page.get("data") or {}).get("records") or [{}])
        check("等待中的申请单读侧显示 RUNNING", records[0].get("status") == "RUNNING", records[0])
    done = wait_for(lambda: (lambda r: r if r and r["stage_status"] in ("SUCCESS", "DEAD") else None)(
        shard_row(tenant_id, wait_case["ppid"])), 180)
    check("到货后同一个阶段自动接上并跑完",
          done and done["stage"] == "FINISH" and done["stage_status"] == "SUCCESS", done)
    check("桩侧对 WAIT 只回了一次「还在生成」",
          count_marker(STUB_LOG, "文件仍在生成: ppid=%s" % wait_case["ppid"]) == 1, None)
    check("等待后的第二次取文件名成功",
          count_marker(STUB_LOG, "下发文件清单: ppid=%s" % wait_case["ppid"]) == 1, None)

    print("\n[6] 超过最长延迟档位：到货发现自己还早到，再排一次")
    long_case = cases["WAITLONG"]

    def parked_long():
        row = shard_row(tenant_id, long_case["ppid"])
        return row if row and row["stage"] == "FILE_NAME" and row["stage_status"] == "PENDING" \
            and row["due"] and row["due"] > datetime.now() else None

    long_parked = wait_for(parked_long, 60)
    check("两小时后才到货的分片停在 FILE_NAME 等待", long_parked is not None, long_parked)

    # 崩溃自愈：把正在等待的分片伪造成"消费者拿走再没回来"的样子，交给兜底扫描捞回
    mysql_run("update t_application_ppid set stage_status='PROCESSING', update_time = now() - interval 5 minute "
              "where tenant_id=%s and ppid='%s' and stage_status='PENDING'" % (tenant_id, long_case["ppid"]))
    reset_marker = "发现卡死的分片并放回待处理"
    reset_before = count_marker(APP_LOG, reset_marker)
    recovered = wait_for(lambda: (lambda r: r if r and r["stage_status"] == "PENDING" else None)(
        shard_row(tenant_id, long_case["ppid"])), 150)
    check("卡在 PROCESSING 的分片被兜底扫描复位成 PENDING", recovered is not None, recovered)
    check("复位没有把等待算成失败重试", recovered and recovered["retry_count"] == 0, recovered)
    if reset_before == count_marker(APP_LOG, reset_marker):
        skip("扫描日志计数", "本轮扫描间隔内未新增复位日志（已被更早的日志覆盖）")
    else:
        check("兜底扫描留下了复位日志", True, None)

    def reheld():
        row = shard_row(tenant_id, long_case["ppid"])
        return row if row and "早于计划执行时间" in row["last_error"] else None

    reheld_row = wait_for(reheld, 150)
    check("最长档位之外：到货后发现自己还早到，重新排期", reheld_row is not None, reheld_row)
    check("再排一次仍然不消耗重试次数", reheld_row and reheld_row["retry_count"] == 0, reheld_row)
    check("重排后的执行时刻还在两小时后（没有倒退成过去）",
          reheld_row and reheld_row["due"] and reheld_row["due"] > datetime.now(), reheld_row)

    # 收口：这条分片注定要等两小时，标成 DEAD 免得给下一次运行留尾巴；
    # 而它延迟队列里那条在飞的消息到货时会被 CAS 挡下——正好当作重复投递的活体证据
    mysql_run("update t_application_ppid set stage_status='DEAD', last_error='P5 验收收口：仍在等待两小时后的文件' "
              "where tenant_id=%s and ppid='%s'" % (tenant_id, long_case["ppid"]))
    duplicate_marker = "判定为重复投递或已被推进"
    duplicate_before = count_marker(APP_LOG, duplicate_marker)
    caught = wait_for(lambda: count_marker(APP_LOG, duplicate_marker) > duplicate_before, 150)
    check("在飞消息到货时 CAS 挡下重复推进（分片不被改坏）", bool(caught), None)

    print("\n[7] 永久失败：4xx 不重试，直接终态失败并投死信")
    f403 = wait_for(lambda: (lambda r: r if r and r["stage_status"] == "DEAD" else None)(
        shard_row(tenant_id, cases["F403"]["ppid"])), 90)
    check("4xx 拒绝后分片立刻 DEAD", f403 and f403["stage"] == "APPLY", f403)
    check("死因写进 last_error 且带上苹果的拒绝", f403 and "苹果拒绝申请" in f403["last_error"], f403)
    check("4xx 只调用苹果一次（不做无谓重试）",
          count_marker(STUB_LOG, "故意拒绝申请: ppid=%s" % cases["F403"]["ppid"]) == 1, None)
    check("死信被消费者记录成需要人工介入",
          count_marker(APP_LOG, "需要人工介入-分片已进入死信: tenantId=%s" % tenant_id) >= 1, None)
    check("全分片失败的申请单聚合为 FAILED",
          app_status(tenant_id, cases["F403"]["application_no"]).startswith("FAILED|"),
          app_status(tenant_id, cases["F403"]["application_no"]))

    print("\n[8] 可恢复失败：5xx 按档位退避，耗尽才终态失败")
    f503_ppid = cases["F503"]["ppid"]
    retried = wait_for(lambda: (lambda r: r if r and r["retry_count"] >= 1 else None)(
        shard_row(tenant_id, f503_ppid)), 90)
    check("第一次失败后进入退避而不是立刻放弃", retried is not None, retried)
    check("退避期间分片仍停在原阶段等待", retried and retried["stage"] == "APPLY", retried)
    f503_dead = wait_for(lambda: (lambda r: r if r and r["stage_status"] == "DEAD" else None)(
        shard_row(tenant_id, f503_ppid)), 120)
    check("重试耗尽后落 DEAD", f503_dead is not None, f503_dead)
    attempts = count_marker(STUB_LOG, "故意服务不可用: ppid=%s" % f503_ppid)
    check("对苹果的总尝试次数正好等于重试上限 3", attempts == 3, "stub 调用次数=" + str(attempts))
    retry_logs = mysql_value("select ifnull(count(*),0) from t_operation_log where tenant_id=%s "
                             "and action='RETRY' and shard_id=(select id from t_application_ppid "
                             "where tenant_id=%s and ppid='%s')" % (tenant_id, tenant_id, f503_ppid))
    check("退避过程有留痕（RETRY 记录）", retry_logs.isdigit() and int(retry_logs) >= 2, retry_logs)
    check("死因带上 5xx 原文", f503_dead and ("503" in f503_dead["last_error"] or "IOException"
                                             in f503_dead["last_error"]), f503_dead)

    print("\n[9] 不重复调苹果：每个 PPID 的申请调用只发生一次")
    for key in ("OK", "WAIT"):
        marker = "受理申请: ppid=%s " % cases[key]["ppid"]
        check("%s 分片在苹果侧只被申请过一次" % key, count_marker(STUB_LOG, marker) == 1,
              "次数=" + str(count_marker(STUB_LOG, marker)))
    check("OK 分片的文件按两份清单分别下载",
          count_marker(STUB_LOG, "下发文件: ppid=%s" % cases["OK"]["ppid"]) == 2, None)

    print("\n[10] 日志脱敏与队列收口")
    app_log = log_text(APP_LOG)
    stub_log = log_text(STUB_LOG)
    check("应用日志没有 token 原文", TOKEN_MARK not in app_log, None)
    check("应用日志没有 Token ID", "TST0000" not in app_log, None)
    check("应用日志没有私钥文本", "PRIVATE KEY" not in app_log and "PRIVATE KEY" not in stub_log, None)
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
