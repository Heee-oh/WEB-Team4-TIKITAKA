package com.tikitaka.bidwinback.auction.application.bid.ledger;

import com.tikitaka.bidwinback.global.exception.ErrorCode;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.List;
import java.util.Map;

final class RedisBidStore {
    static final String META = "bid-ledger:meta";
    static final String STREAM = "bid-ledger:events";
    static final String DIRTY = "bid-ledger:dirty";
    private static final DefaultRedisScript<String> SCRIPT = new DefaultRedisScript<>();
    static {
        SCRIPT.setLocation(new ClassPathResource("redis/bid-ledger.lua"));
        SCRIPT.setResultType(String.class);
    }

    private final StringRedisTemplate redis;
    private final BidLedgerProperties properties;

    RedisBidStore(StringRedisTemplate redis, BidLedgerProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    Map<Object, Object> meta() {
        try { return redis.opsForHash().entries(META); }
        catch (RuntimeException failure) { throw unavailable(); }
    }

    Map<Object, Object> activeMeta() {
        Map<Object, Object> value = meta();
        if (!value.keySet().containsAll(List.of("epoch", "gen", "seq", "barriers", "ledger_id"))) throw unavailable();
        return value;
    }

    String execute(String operation, String epoch, String request, String fingerprint, String kind,
                   String payload, long member, long auction, long price, String generation, String data, String record) {
        try {
            String result = redis.execute(SCRIPT,
                    List.of(META, STREAM, request, walletKey(epoch, member), participantKey(epoch, auction, member),
                            auctionKey(epoch, auction), DIRTY),
                    operation, epoch, fingerprint, kind, payload, Long.toString(member), Long.toString(auction),
                    Long.toString(price), Long.toString(properties.cacheTtl().toMillis()),
                    Integer.toString(properties.maxBacklog()), generation, data, record);
            if (result == null || result.startsWith("ERROR_") || result.equals("BUSY")) throw unavailable();
            if (result.equals("BID_REQUEST_CONFLICT")) throw new BidLedgerException(ErrorCode.BID_REQUEST_CONFLICT);
            return result;
        } catch (BidLedgerException known) {
            throw known;
        } catch (RuntimeException failure) {
            throw unavailable();
        }
    }

    MapRecord<String, Object, Object> head() {
        List<MapRecord<String, Object, Object>> records = redis.opsForStream()
                .range(STREAM, Range.unbounded(), Limit.limit().count(1));
        return records == null || records.isEmpty() ? null : records.getFirst();
    }

    String result(String request) {
        try {
            Object value = redis.opsForHash().get(request, "result");
            return value == null ? null : value.toString();
        } catch (RuntimeException failure) { throw unavailable(); }
    }

    String status(String request) {
        try {
            Object value = redis.opsForHash().get(request, "status");
            return value == null ? null : value.toString();
        } catch (RuntimeException failure) { throw unavailable(); }
    }

    static String requestKey(long member, String id) {
        if (member < 0 || id == null || !id.matches("[A-Za-z0-9_-]{1,100}")) {
            throw new BidLedgerException(ErrorCode.INVALID_INPUT_VALUE);
        }
        return "bid-ledger:request:" + member + ":" + id;
    }

    static String walletKey(String epoch, long member) { return "bid-ledger:cache:" + epoch + ":wallet:" + member; }
    static String participantKey(String epoch, long auction, long member) {
        return "bid-ledger:cache:" + epoch + ":participant:" + auction + ":" + member;
    }
    static String auctionKey(String epoch, long auction) { return "bid-ledger:cache:" + epoch + ":auction:" + auction; }
    static BidLedgerException unavailable() { return new BidLedgerException(ErrorCode.BID_LEDGER_UNAVAILABLE); }
}
