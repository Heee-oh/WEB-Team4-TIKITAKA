package com.tikitaka.bidwinback.auction.application.bid.ledger;

import com.tikitaka.bidwinback.auction.application.bid.BidResult;
import com.tikitaka.bidwinback.global.exception.ErrorCode;
import com.tikitaka.bidwinback.member.domain.entity.Member;
import com.tikitaka.bidwinback.member.domain.repository.MemberRepository;
import com.tikitaka.bidwinback.mypage.presentation.dto.response.DepositResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
public final class RedisBidRuntime implements AutoCloseable {
    private final BidLedgerProperties properties;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ObjectMapper mapper;
    private final ApplicationContext context;
    private final LettuceConnectionFactory connection;
    final RedisBidStore store;
    final RedisBidProjector projector;
    private long retryAfter;
    private long retryDelayMillis = 100;

    RedisBidRuntime(BidLedgerProperties properties, JdbcTemplate jdbc, TransactionTemplate transaction,
                    ObjectMapper mapper, ApplicationContext context) {
        this.properties = properties;
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.mapper = mapper;
        this.context = context;
        var standalone = new RedisStandaloneConfiguration(properties.host(), properties.port());
        if (!properties.password().isEmpty()) standalone.setPassword(RedisPassword.of(properties.password()));
        connection = new LettuceConnectionFactory(standalone, LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofSeconds(1)).shutdownTimeout(Duration.ofMillis(100)).build());
        connection.afterPropertiesSet();
        connection.start();
        var redis = new StringRedisTemplate(connection);
        redis.afterPropertiesSet();
        store = new RedisBidStore(redis, properties);
        projector = new RedisBidProjector(jdbc, transaction, mapper, context);
    }

    public void initialize() {
        var redis = new StringRedisTemplate(connection);
        var config = redis.execute((RedisCallback<java.util.Properties>) c -> {
            var values = c.serverCommands().getConfig("append*");
            values.putAll(c.serverCommands().getConfig("maxmemory-policy"));
            return values;
        });
        if (config == null || !"yes".equals(config.getProperty("appendonly"))
                || !"always".equals(config.getProperty("appendfsync"))
                || !"noeviction".equals(config.getProperty("maxmemory-policy"))) {
            throw new IllegalStateException("입찰 전용 Redis는 AOF always·noeviction 설정이 필요합니다.");
        }
        transaction.executeWithoutResult(status -> {
            Map<String, Object> row = jdbc.queryForMap("SELECT * FROM redis_bid_runtime WHERE id = 1 FOR UPDATE");
            Map<Object, Object> meta = store.meta();
            String ledger = row.get("ledger_id").toString();
            if (meta.isEmpty()) {
                if (((Boolean) row.get("enabled"))) throw new IllegalStateException("활성 입찰 로그가 유실되었습니다. DB로 자동 전환할 수 없습니다.");
                redis.opsForHash().putAll(RedisBidStore.META,
                        Map.of("ledger_id", ledger, "seq", "0", "epoch", "0", "gen", "0", "barriers", "0"));
            } else if (!ledger.equals(meta.get("ledger_id"))
                    || Long.parseLong(meta.get("seq").toString()) < ((Number) row.get("last_sequence")).longValue()) {
                throw new IllegalStateException("DB와 Redis 입찰 로그 식별자·처리 위치가 다릅니다.");
            }
            jdbc.update("UPDATE redis_bid_runtime SET enabled = TRUE WHERE id = 1");
        });
    }

    public Receipt submitOpen(long member, long auction, long price, String id) {
        if (member < 1 || auction < 1) throw new BidLedgerException(ErrorCode.INVALID_INPUT_VALUE);
        String request = RedisBidStore.requestKey(member, id);
        String payload = mapper.writeValueAsString(new OpenPayload(member, auction, price));
        String outcome = null;
        for (int retry = 0; retry < 8; retry++) {
            Map<Object, Object> meta = store.activeMeta();
            String epoch = meta.get("epoch").toString();
            outcome = store.execute("OPEN", epoch, request, "OPEN:" + payload, "OPEN", payload,
                    member, auction, price, "", "", "");
            if (!"CACHE_MISS".equals(outcome)) break;
            load(member, auction, epoch, meta.get("gen").toString(), request);
        }
        if (!"ACCEPTED".equals(outcome) && !"DB_DONE".equals(outcome)) throw rejection(outcome);
        return new Receipt(id, "ACCEPTED", "DB_DONE".equals(outcome),
                "/api/v1/auctions/bid-requests/" + id);
    }

    private void load(long member, long auction, String epoch, String generation, String request) {
        Map<String, Object> row;
        try {
            row = jdbc.queryForMap("""
                SELECT m.total_point, m.locked_point, m.status AS member_status,
                    COALESCE(a.current_price, (SELECT MAX(b.price) FROM bid b WHERE b.auction_id = a.id), a.start_price)
                        AS current_price,
                    a.revision, a.seller_id, a.status, a.ended_at, a.start_price, a.completed_at,
                    u.auction_id AS up_id, d.status AS deposit_status, d.reserved_amount
                FROM auction a LEFT JOIN up_auction u ON u.auction_id = a.id
                    JOIN member m ON m.id = ?
                    LEFT JOIN auction_deposit d ON d.auction_id = a.id AND d.member_id = m.id
                WHERE a.id = ?
                """, member, auction);
        } catch (EmptyResultDataAccessException missing) {
            throw new BidLedgerException(ErrorCode.AUCTION_NOT_FOUND);
        }
        if (row.get("up_id") == null) throw new BidLedgerException(ErrorCode.NOT_UP_AUCTION);
        Map<String, String> snapshot = new LinkedHashMap<>();
        snapshot.put("available", row.get("total_point").toString());
        snapshot.put("locked", row.get("locked_point").toString());
        snapshot.put("memberStatus", row.get("member_status").toString());
        snapshot.put("auctionType", row.get("up_id") == null ? "DOWN" : "UP");
        snapshot.put("amount", row.get("deposit_status") == null ? "0"
                : row.get("deposit_status").equals("HELD") ? row.get("reserved_amount").toString() : "-1");
        snapshot.put("price", row.get("current_price").toString());
        snapshot.put("version", row.get("revision").toString());
        snapshot.put("seller", row.get("seller_id").toString());
        snapshot.put("status", row.get("completed_at") == null ? row.get("status").toString() : "COMPLETED");
        snapshot.put("reserve", Long.toString(((Number) row.get("start_price")).longValue() / 10 * 3));
        Object ended = row.get("ended_at");
        LocalDateTime endedAt = ended instanceof LocalDateTime time ? time : ((java.sql.Timestamp) ended).toLocalDateTime();
        snapshot.put("ended", Long.toString(endedAt
                .atZone(ZoneId.of("Asia/Seoul")).toInstant().toEpochMilli()));
        String result = store.execute("LOAD", epoch, request, "", "", "", member, auction, 0,
                generation, mapper.writeValueAsString(snapshot), "");
        if (!result.equals("READY") && !result.equals("RETRY")) throw rejection(result);
    }

    Object call(String name, Method method, Object[] arguments, long owner, String id) {
        String request = RedisBidStore.requestKey(owner, id);
        String payload = mapper.writeValueAsString(new CallPayload(name, arguments));
        String epoch = store.activeMeta().get("epoch").toString();
        store.execute("CALL", epoch, request, "CALL:" + payload, "CALL", payload, owner, 0, 0, "", "", "");
        var value = completed(request).get("value");
        if (method.getReturnType() == void.class) return null;
        if (method.getReturnType() == Member.class) {
            return context.getBean(MemberRepository.class).findById(value.get("memberId").asLong()).orElseThrow();
        }
        return mapper.convertValue(value, mapper.getTypeFactory().constructType(method.getGenericReturnType()));
    }

    public BidResult awaitBid(long member, String id) {
        return mapper.convertValue(completed(RedisBidStore.requestKey(member, id)).get("value"), BidResult.class);
    }

    public Object lookup(long member, String id) {
        String request = RedisBidStore.requestKey(member, id);
        String status = store.status(request);
        if (status == null) throw new BidLedgerException(ErrorCode.ENTITY_NOT_FOUND);
        String result = store.result(request);
        return result == null ? new Receipt(id, status, false, "/api/v1/auctions/bid-requests/" + id)
                : mapper.readTree(result);
    }

    private tools.jackson.databind.JsonNode completed(String request) {
        long deadline = System.nanoTime() + properties.waitTimeout().toNanos();
        while (System.nanoTime() < deadline) {
            String result = store.result(request);
            if (result != null) {
                var node = mapper.readTree(result);
                if (!node.get("error").isNull()) throw rejection(node.get("error").asText());
                return node;
            }
            try {
                TimeUnit.MILLISECONDS.sleep(10);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw RedisBidStore.unavailable();
            }
        }
        throw RedisBidStore.unavailable();
    }

    @Scheduled(fixedDelayString = "${app.bid-ledger.poll-delay-ms:20}")
    public void consume() {
        if (!properties.workerEnabled() || System.nanoTime() < retryAfter) return;
        try {
            for (int i = 0; i < 32 && processHead(); i++) { }
            retryDelayMillis = 100;
        } catch (RuntimeException failure) {
            retryAfter = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(retryDelayMillis);
            retryDelayMillis = Math.min(retryDelayMillis * 2, 5000);
            log.atError().addKeyValue("event", "bid_ledger_consumer_failed")
                    .addKeyValue("failureType", failure.getClass().getSimpleName())
                    .log("입찰 로그 처리 중단: 기록을 보존하고 다음 주기에 재시도합니다.");
        }
    }

    public boolean processHead() {
        var head = store.head();
        if (head == null) return false;
        var e = head.getValue();
        String result = projector.apply(head);
        String ack = store.execute("ACK", e.get("epoch").toString(), e.get("request").toString(), "",
                e.get("kind").toString(), "", Long.parseLong(e.get("member").toString()),
                Long.parseLong(e.get("auction").toString()), 0, "", result, head.getId().getValue());
        if (!ack.equals("ACKED")) throw RedisBidStore.unavailable();
        return true;
    }

    boolean hasPendingEvents() { return store.head() != null; }

    public DepositResponse cachedBalance(long member, DepositResponse fallback) {
        try {
            var redis = new StringRedisTemplate(connection);
            String epoch = store.activeMeta().get("epoch").toString();
            String key = RedisBidStore.walletKey(epoch, member);
            Map<Object, Object> balance = redis.opsForHash().entries(key);
            if (balance.isEmpty()) {
                if (redis.opsForHash().hasKey(RedisBidStore.DIRTY, key)) throw RedisBidStore.unavailable();
                return fallback;
            }
            return new DepositResponse(Long.parseLong(balance.get("available").toString()),
                    Long.parseLong(balance.get("locked").toString()));
        } catch (RuntimeException failure) {
            throw RedisBidStore.unavailable();
        }
    }

    private static BidLedgerException rejection(String name) {
        try { return new BidLedgerException(ErrorCode.valueOf(name)); }
        catch (IllegalArgumentException | NullPointerException unknown) { return RedisBidStore.unavailable(); }
    }

    @Override
    public void close() { connection.destroy(); }

    public record Receipt(String requestId, String status, boolean persisted, String resultUrl) { }
    private record OpenPayload(long member, long auction, long price) { }
    private record CallPayload(String method, Object[] args) { }
}
