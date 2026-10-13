package com.galaplat.mfi.ppid.infrastructure.persistence;

import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 并发扣减不超卖。这是 PPID 池唯一的安全防线，必须在真实 MySQL 上跑：它依赖两条数据库语义——InnoDB 行锁
 * 让条件更新串行化，以及单表 UPDATE 的 SET 从左到右求值（所以 status 判断的是扣减之前的余量）。
 * H2 与 mock 都验不到这两条。
 *
 * <p>SQL 直接从 {@code PpIdMapper.deduct} 的注解上取，不在测试里另抄一份，避免测的语句和跑的语句漂移。
 *
 * <p>跑法（本机 MySQL 已在跑、Flyway 已建表）：
 * {@code MFI_IT_DB=1 MFI_DB_USERNAME=root MFI_DB_PASSWORD=root mvn -o -pl mfi-dao -am test}
 */
@EnabledIfEnvironmentVariable(named = "MFI_IT_DB", matches = "1")
class PpIdDeductConcurrencyTest {

    private static final Pattern PLACEHOLDER = Pattern.compile("#\\{(\\w+)}");
    private static final int ATTEMPTS = 50;
    /** 高段合成租户号，不与真实租户重叠；用例结束按它物理清理 */
    private static final long TENANT_ID = 900_000_000L + (System.nanoTime() % 1000);

    private static String url;
    private static String username;
    private static String password;

    @BeforeAll
    static void setUp() {
        url = System.getenv().getOrDefault("MFI_DB_URL", "jdbc:mysql://localhost:3306/apple_mfi_token"
                + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false"
                + "&allowPublicKeyRetrieval=true");
        username = System.getenv("MFI_DB_USERNAME");
        password = System.getenv("MFI_DB_PASSWORD");
        if (username == null || password == null) {
            throw new IllegalStateException("开了 MFI_IT_DB=1 但缺少 MFI_DB_USERNAME / MFI_DB_PASSWORD");
        }
    }

    @AfterAll
    static void tearDown() throws SQLException {
        try (Connection conn = open(); Statement st = conn.createStatement()) {
            st.executeUpdate("delete from t_ppid where tenant_id = " + TENANT_ID);
        }
    }

    /**
     * 40 份额度、50 次并发各扣 1：只允许 40 次成功，余量归 0 且自动停用。
     * 多扣出去的任何一份都是要在苹果侧花钱的超卖。
     */
    @Test
    void concurrentDeductNeverOversells() throws Exception {
        long id = insertQuota(40);

        int granted = deductConcurrently(id, 1, ATTEMPTS);

        assertEquals(40, granted, "扣减成功数必须正好等于余量，多一个就是超卖");
        Map<String, Object> row = readRow(id);
        assertEquals(40, row.get("applied_quantity"));
        assertEquals(0, row.get("remaining_quantity"));
        assertEquals(0, row.get("status"), "余量归零必须由 SQL 自动停用");
        assertEquals(40, ((Number) row.get("applied_quantity")).intValue()
                + ((Number) row.get("remaining_quantity")).intValue(), "总量恒等于已申请 + 剩余");
    }

    /**
     * 余量不足时条件更新必须整条不动，而不是把余量扣成负数。
     */
    @Test
    void deductBeyondRemainingChangesNothing() throws Exception {
        long id = insertQuota(3);

        assertEquals(0, deduct(id, 5));

        Map<String, Object> row = readRow(id);
        assertEquals(3, row.get("remaining_quantity"));
        assertEquals(0, row.get("applied_quantity"));
        assertEquals(1, row.get("status"));
    }

    /**
     * 余量归零后即便把状态重新点亮，也不该再扣到——额度是真的用完了，不是被停用挡住了。
     */
    @Test
    void exhaustedRowTakesNoMoreDeductionEvenIfReenabled() throws Exception {
        long id = insertQuota(1);
        assertEquals(1, deduct(id, 1));

        try (Connection conn = open(); Statement st = conn.createStatement()) {
            st.executeUpdate("update t_ppid set status = 1 where id = " + id);
        }
        assertEquals(0, deduct(id, 1));
    }

    private static int deductConcurrently(long id, int quantity, int attempts) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < attempts; i++) {
                // 每个任务独占一个连接：连接不是线程安全的，共用只会把并发变成串行排队
                futures.add(pool.submit(() -> {
                    try (Connection conn = open()) {
                        return deduct(conn, id, quantity);
                    }
                }));
            }
            int granted = 0;
            for (Future<Integer> future : futures) {
                granted += future.get();
            }
            return granted;
        } finally {
            pool.shutdown();
            pool.awaitTermination(60, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    private static int deduct(long id, int quantity) throws SQLException {
        try (Connection conn = open()) {
            return deduct(conn, id, quantity);
        }
    }

    private static int deduct(Connection conn, long id, int quantity) throws SQLException {
        Map<String, Object> params = Map.of("tenantId", TENANT_ID, "id", id, "quantity", quantity);
        try (PreparedStatement ps = bind(conn, deductSql(), params)) {
            return ps.executeUpdate();
        }
    }

    private static long insertQuota(int total) throws SQLException {
        String marker = Long.toHexString(System.nanoTime());
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement("""
                     insert into t_ppid (tenant_id, ppid, spu, product_model, total_quantity,
                                         applied_quantity, remaining_quantity, status)
                     values (?, ?, ?, ?, ?, 0, ?, 1)
                     """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, TENANT_ID);
            ps.setString(2, "IT-CAS-" + marker);
            ps.setString(3, "IT-SPU");
            ps.setString(4, "IT-MODEL");
            ps.setInt(5, total);
            ps.setInt(6, total);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private static Map<String, Object> readRow(long id) throws SQLException {
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(
                     "select total_quantity, applied_quantity, remaining_quantity, status"
                             + " from t_ppid where tenant_id = ? and id = ?")) {
            ps.setLong(1, TENANT_ID);
            ps.setLong(2, id);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("total_quantity", rs.getInt("total_quantity"));
                row.put("applied_quantity", rs.getInt("applied_quantity"));
                row.put("remaining_quantity", rs.getInt("remaining_quantity"));
                row.put("status", rs.getInt("status"));
                return row;
            }
        }
    }

    /** 取 mapper 注解上的原始 SQL，测试跑的就是生产语句 */
    private static String deductSql() {
        try {
            Update update = PpIdMapper.class
                    .getMethod("deduct", long.class, long.class, int.class)
                    .getAnnotation(Update.class);
            return String.join(" ", update.value());
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("PpIdMapper.deduct 的签名变了，并发扣减测试需要一起调整", e);
        }
    }

    /**
     * 把 MyBatis 的 {@code #{name}} 按出现顺序换成 {@code ?} 并绑定同名参数。顺序由正则扫描得出，
     * 所以占位符顺序变化不需要改测试代码。
     */
    private static PreparedStatement bind(Connection conn, String sql, Map<String, Object> params)
            throws SQLException {
        Matcher matcher = PLACEHOLDER.matcher(sql);
        StringBuilder jdbc = new StringBuilder();
        List<Object> values = new ArrayList<>();
        while (matcher.find()) {
            String name = matcher.group(1);
            if (!params.containsKey(name)) {
                throw new IllegalStateException("注解 SQL 里的占位符 " + name + " 没有对应参数");
            }
            matcher.appendReplacement(jdbc, "?");
            values.add(params.get(name));
        }
        matcher.appendTail(jdbc);
        PreparedStatement ps = conn.prepareStatement(jdbc.toString());
        for (int i = 0; i < values.size(); i++) {
            ps.setObject(i + 1, values.get(i));
        }
        return ps;
    }

    private static Connection open() throws SQLException {
        return DriverManager.getConnection(url, username, password);
    }

}
