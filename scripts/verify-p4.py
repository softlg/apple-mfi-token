#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""P4 验收：申请同步段——校验顺序、贪心拆分扣减、幂等、并发抢额度、消息只在提交后离开进程。

前置：
  1. 应用以 local profile 启动（MySQL/Redis 在跑），RabbitMQ 也在跑（要看队列深度）
  2. bash scripts/gen-dev-certs.sh 已生成 .dev-certs/

用法：
  PYTHONIOENCODING=utf-8 python scripts/verify-p4.py

每次运行注册两个全新租户，维度带时间戳，因此可重复执行；不动别人的数据。
"""
import argparse
import json
import os
import re
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request

BASE = os.environ.get("MFI_BASE", "http://localhost:8090")
MYSQL = os.environ.get("MFI_MYSQL", r"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe")
RABBITMQCTL = os.environ.get("MFI_RABBITMQCTL", r"D:\tools\rabbitmq_server-3.13.7\sbin\rabbitmqctl.bat")
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


def mysql_value(sql):
    """账号密码走一次性 defaults 文件，不出现在命令行与进程列表里"""
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
            return None
        return out
    finally:
        os.unlink(handle.name)


def queue_depth():
    """mfi.apply.queue 的堆积数。P4 没有消费者，投出去的消息应当原样躺在这里"""
    if not os.path.exists(RABBITMQCTL):
        return None
    env = dict(os.environ)
    # 免安装 RabbitMQ 的 ctl 脚本要自己找到 Erlang runtime（§10 的第三个坑），否则非零退出
    erlang = os.environ.get("MFI_ERLANG_HOME", r"D:\tools\erlang")
    env["ERLANG_HOME"] = env.get("ERLANG_HOME", erlang)
    env["PATH"] = env.get("PATH", "") + os.pathsep + os.path.join(erlang, "bin") \
        + os.pathsep + os.path.join(erlang, "erts-14.2.5.4", "bin")
    try:
        proc = subprocess.run([RABBITMQCTL, "list_queues", "name", "messages", "--quiet"],
                              capture_output=True, timeout=90, env=env)
    except Exception as error:
        print("  rabbitmqctl 调用异常: " + str(error)[:200])
        return None
    if proc.returncode != 0:
        print("  rabbitmqctl 非零退出: " + proc.stderr.decode("utf-8", "replace")[:200])
        return None
    text = proc.stdout.decode("utf-8", "replace")
    for line in text.splitlines():
        parts = line.split()
        if parts and parts[0] == "mfi.apply.queue":
            return int(parts[1]) if len(parts) > 1 and parts[1].isdigit() else 0
    return 0


def new_tenant(account, name, password):
    status, payload = call("POST", "/api/auth/register",
                           {"account": account, "password": password, "name": name,
                            "email": account + "@example.com", "tenantName": name})
    if not ok(payload):
        print("注册失败: %s %s" % (status, payload))
        sys.exit(1)
    data = payload.get("data") or {}
    tenant = (data.get("currentTenant") or {}).get("tenantId")
    return data.get("tokenValue"), tenant


def create_ppid(token, ppid, spu, model, total, remark=""):
    status, payload = call("POST", "/api/ppid",
                           {"ppid": ppid, "spu": spu, "productModel": model,
                            "totalQuantity": total, "remark": remark}, token)
    return ok(payload), payload


def apply(token, model, spu, quantity, request_id=None):
    body = {"productModel": model, "spu": spu, "applyQuantity": quantity}
    if request_id:
        body["requestId"] = request_id
    return call("POST", "/api/applications", body, token)


def ppid_row(token, ppid, spu):
    _, payload = call("GET", "/api/ppid/page?ppid=%s&spu=%s" % (ppid, spu), token=token)
    rows = (payload.get("data") or {}).get("records") or []
    return rows[0] if rows else None


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--password", default="Passw0rd123")
    parser.add_argument("--certs", default=".dev-certs")
    args = parser.parse_args()
    stamp = time.strftime("%H%M%S")
    model = "P4-M-" + stamp
    spu = "P4-SPU-" + stamp
    race_model = "P4-RACE-" + stamp
    certs = args.certs

    token, tenant_id = new_tenant("p4own_%s" % stamp, "P4 验收租户 " + stamp, args.password)
    print("租户 tenantId=%s，维度 %s / %s" % (tenant_id, model, spu))

    print("\n[1] 申请前置：额度已备好，证书还没配")
    ok_big, _ = create_ppid(token, "P4-BIG-" + stamp, spu, model, 100)
    ok_small, _ = create_ppid(token, "P4-SMALL-" + stamp, spu, model, 30)
    check("两份额度录入成功", ok_big and ok_small, None)
    status, payload = apply(token, model, spu, 10)
    check("未配证书时拒绝申请（B0404）", payload.get("code") == "B0404", payload.get("message"))
    applied = mysql_value("select ifnull(sum(applied_quantity),0) from t_ppid "
                          "where tenant_id=%s and product_model='%s'" % (tenant_id, model))
    check("快速失败没有消耗额度", applied == "0", "applied=" + str(applied))

    with open(os.path.join(certs, "tenant-b-chain.crt"), "r", encoding="utf-8") as handle:
        # 只取叶子证书那一段：链里带了 CA，凭证接口要的是"一张证书 + 一把私钥"
        cert_pem = re.search(r"-----BEGIN CERTIFICATE-----.*?-----END CERTIFICATE-----", handle.read(), re.S).group(0)
    with open(os.path.join(certs, "tenant-b.pk1.key"), "r", encoding="utf-8") as handle:
        key_pem = re.search(r"-----BEGIN (?:RSA )?PRIVATE KEY-----.*?-----END (?:RSA )?PRIVATE KEY-----",
                            handle.read(), re.S).group(0)
    status, payload = call("PUT", "/api/tenant/credential", {"certPem": cert_pem, "keyPem": key_pem}, token)
    check("配上租户证书后才能受理", ok(payload), payload.get("message"))

    print("\n[2] 额度不足：先拒再扣，不留半张单")
    status, payload = apply(token, model, spu, 136)
    check("超过池子总量被拒", payload.get("code") == "A0422", payload.get("message"))
    check("拒绝文案给出缺口", "还差 6" in (payload.get("message") or ""), payload.get("message"))
    rows = mysql_value("select count(*) from t_application where tenant_id=%s" % tenant_id)
    check("额度不足时库里没有申请单", rows == "0", "count=" + str(rows))

    print("\n[3] 正常受理：120 拆成 100 + 20")
    depth_before = queue_depth()
    status, payload = apply(token, model, spu, 120)
    submitted = payload.get("data") or {}
    check("受理成功", ok(payload), payload.get("message"))
    check("拆出两笔分片", submitted.get("ppidCount") == 2, submitted)
    check("分片数量为 100 与 20",
          [s.get("allocateCount") for s in (submitted.get("shards") or [])] == [100, 20], submitted.get("shards"))
    check("分片落在 APPLY/PENDING",
          all(s.get("stage") == "APPLY" and s.get("stageStatus") == "PENDING"
              for s in (submitted.get("shards") or [])), submitted.get("shards"))
    application_no = submitted.get("applicationNo")
    check("单号可读且不是纯数字", bool(application_no) and application_no[0] == "A", application_no)

    ledger = mysql_value("select concat(ppid,'|',applied_quantity,'|',remaining_quantity,'|',status) "
                         "from t_ppid where tenant_id=%s order by total_quantity desc" % tenant_id)
    lines = (ledger or "").splitlines()
    check("大额那份额度扣满并自动停用", len(lines) > 0 and lines[0].endswith("|100|0|0"), lines)
    check("零头那份额度扣 20 留 10", len(lines) > 1 and lines[1].endswith("|20|10|1"), lines)
    shard_db = mysql_value("select count(*), sum(allocate_count) from t_application_ppid "
                           "where tenant_id=%s" % tenant_id)
    parts = (shard_db or "").split()
    check("库里分片 2 行、合计 120", len(parts) > 1 and parts[0] == "2" and parts[1] == "120", shard_db)
    app_row = mysql_value("select concat(status,'|',apply_quantity,'|',ppid_count,'|',apple_technology) "
                          "from t_application where tenant_id=%s" % tenant_id)
    check("申请单快照完整（状态/量/分片数/technology）", (app_row or "").startswith("PENDING|120|2|"), app_row)
    oplog = mysql_value("select concat(action,'|',ifnull(quantity,-1)) from t_operation_log "
                        "where tenant_id=%s order by id desc limit 1" % tenant_id)
    check("受理动作写了留痕且只记数量", oplog == "APPLY|120", oplog)
    leaked = mysql_value("select count(*) from t_operation_log where tenant_id=%s and detail like '%%P4-BIG-%%'"
                         % tenant_id)
    check("留痕不抄 PPID 清单", leaked == "0", "count=" + str(leaked))

    print("\n[4] 消息只在事务提交后离开进程")
    depth_after = queue_depth()
    if depth_before is None or depth_after is None:
        skip("队列深度对账", "rabbitmqctl 不可用（%s）" % RABBITMQCTL)
    else:
        check("受理 2 个分片，队列正好多出 2 条", depth_after - depth_before == 2,
              "before=%s after=%s" % (depth_before, depth_after))

    print("\n[5] 幂等：同一个 requestId 只扣一份额度")
    idem_model = "P4-IDEM-" + stamp
    create_ppid(token, "P4-IDEM-A-" + stamp, spu, idem_model, 20)
    status, first = apply(token, idem_model, spu, 5, "p4-req-1")
    status, again = apply(token, idem_model, spu, 5, "p4-req-1")
    first_data, again_data = first.get("data") or {}, again.get("data") or {}
    check("首次提交受理", ok(first), first.get("message"))
    check("重复提交不再报错，而是给回同一张单", ok(again) and again_data.get("duplicated") is True, again)
    check("两次返回同一单号", first_data.get("applicationNo") == again_data.get("applicationNo"),
          [first_data.get("applicationNo"), again_data.get("applicationNo")])
    row = ppid_row(token, "P4-IDEM-A-" + stamp, spu)
    check("重复提交只扣走一份 5", row and row.get("appliedQuantity") == 5 and row.get("remainingQuantity") == 15, row)
    count = mysql_value("select count(*) from t_application where tenant_id=%s and product_model='%s'"
                        % (tenant_id, idem_model))
    check("库里只有一张单", count == "1", "count=" + str(count))

    status, bare_one = apply(token, idem_model, spu, 1)
    status, bare_two = apply(token, idem_model, spu, 1)
    no_one = (bare_one.get("data") or {}).get("applicationNo")
    no_two = (bare_two.get("data") or {}).get("applicationNo")
    check("不带 requestId 的两次提交是两个不同的单（唯一索引不约束 NULL）",
          no_one and no_two and no_one != no_two, [no_one, no_two])

    print("\n[6] 租户级上限在扣减之前生效")
    status, payload = call("PUT", "/api/tenant/config", {"values": {"APPLY_MAX_QUANTITY": "3"}}, token)
    check("owner 可设单次上限", ok(payload), payload.get("message"))
    status, payload = apply(token, idem_model, spu, 4)
    check("超上限被拒且文案给出上限值", "上限 3" in (payload.get("message") or ""), payload.get("message"))
    row = ppid_row(token, "P4-IDEM-A-" + stamp, spu)
    check("被上限挡掉时额度分文未动", row.get("appliedQuantity") == 7, row)
    status, payload = apply(token, idem_model, spu, 3)
    check("上限之内的量正常受理", ok(payload), payload.get("message"))
    call("PUT", "/api/tenant/config", {"values": {"APPLY_MAX_QUANTITY": ""}}, token)
    status, payload = apply(token, idem_model, spu, 10)
    check("清除上限后大额可过", ok(payload), payload.get("message"))

    print("\n[7] 并发抢最后份额度：只有一个赢家，输家不留单也不扣量")
    create_ppid(token, "P4-RACE-A-" + stamp, spu, race_model, 1)
    before = mysql_value("select count(*) from t_application where tenant_id=%s and product_model='%s'"
                         % (tenant_id, race_model))
    outcomes = []
    barrier = threading.Barrier(2)

    def racer(index):
        barrier.wait()
        status, payload = apply(token, race_model, spu, 1, "p4-race-%d" % index)
        outcomes.append((status, payload))

    threads = [threading.Thread(target=racer, args=(i,)) for i in (1, 2)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()
    wins = [o for o in outcomes if ok(o[1])]
    check("两个并发只成一个", len(wins) == 1, outcomes)
    after = mysql_value("select count(*) from t_application where tenant_id=%s and product_model='%s'"
                        % (tenant_id, race_model))
    check("输的那条没有留下申请单", after == str(int(before) + 1), "before=%s after=%s" % (before, after))
    race_row = mysql_value("select concat(applied_quantity,'|',remaining_quantity,'|',status) from t_ppid "
                           "where tenant_id=%s and ppid='P4-RACE-A-%s'" % (tenant_id, stamp))
    check("额度只被扣走 1 并自动停用", race_row == "1|0|0", race_row)
    shard_left = mysql_value("select count(*) from t_application_ppid where tenant_id=%s and ppid_id in "
                             "(select id from t_ppid where tenant_id=%s and ppid='P4-RACE-A-%s')"
                             % (tenant_id, tenant_id, stamp))
    check("赢家留下 1 个分片", shard_left == "1", shard_left)

    print("\n[8] 跨租户：申请单不跨租户可见")
    token_b, tenant_b = new_tenant("p4own_b_%s" % stamp, "P4 隔离租户 " + stamp, args.password)
    status, page_b = call("GET", "/api/applications/page?pageSize=50", token=token_b)
    check("B 租户的申请列表为空", ((page_b.get("data") or {}).get("total") == 0), page_b.get("data"))
    status, peek = call("GET", "/api/applications/page?applicationNo=%s" % application_no, token=token_b)
    check("B 按 A 的单号查不到", ((peek.get("data") or {}).get("total") == 0), peek.get("data"))
    status, blind = apply(token_b, model, spu, 1)
    check("B 没配证书就在碰到 A 的池子之前被拒（B0404）", blind.get("code") == "B0404", blind.get("message"))
    b_rows = mysql_value("select count(*) from t_application where tenant_id=%s" % tenant_b)
    check("B 的失败尝试没有落任何单", b_rows == "0", "count=" + str(b_rows))

    print("\n[9] 列表筛选与接口可见性")
    status, page_a = call("GET", "/api/applications/page?pageSize=50&status=PENDING&productModel=%s" % model,
                          token=token)
    data = page_a.get("data") or {}
    check("按状态与型号筛选命中刚受理的单", data.get("total") == 1 and
          (data.get("records") or [{}])[0].get("applicationNo") == application_no, data)
    status, bad = call("GET", "/api/applications/page?status=NOT_A_STATUS", token=token)
    check("非法状态给参数错误而不是 500", bad.get("code") == "A0400", bad.get("message"))
    status, docs = call("GET", "/v3/api-docs")
    text = json.dumps(docs, ensure_ascii=False)
    check("openapi 暴露 /api/applications", "/api/applications" in text, None)
    check("openapi 不泄漏库列名 idempotency_key", "idempotency_key" not in text, None)

    passed = sum(1 for _, p in results if p)
    print("\n==== 结果: %d/%d 通过，跳过 %d 项 ====" % (passed, len(results), len(skips)))
    if passed != len(results):
        for name, ok_flag in results:
            if not ok_flag:
                print("  未通过: " + name)
    print("留档: tenant=%s applicationNo=%s model=%s" % (tenant_id, application_no, model))
    sys.exit(0 if passed == len(results) else 1)



if __name__ == "__main__":
    main()

