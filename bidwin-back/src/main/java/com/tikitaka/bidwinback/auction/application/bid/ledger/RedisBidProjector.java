package com.tikitaka.bidwinback.auction.application.bid.ledger;

import com.tikitaka.bidwinback.auction.application.bid.BidResult;
import com.tikitaka.bidwinback.auction.application.live.AuctionBidCreated;
import com.tikitaka.bidwinback.auction.application.live.AuctionLiveStateService;
import com.tikitaka.bidwinback.auction.application.live.AuctionStateChanged;
import com.tikitaka.bidwinback.auction.domain.enums.BidStatus;
import com.tikitaka.bidwinback.auction.presentation.dto.response.BidHistoryItemResponse;
import com.tikitaka.bidwinback.global.exception.BusinessException;
import com.tikitaka.bidwinback.member.domain.entity.Member;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;

final class RedisBidProjector {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ObjectMapper mapper;
    private final ApplicationContext context;
    private final LedgerSseNotifier notifier;

    /** notifier 가 null 이면(소비자를 끈 경우) 기존처럼 커밋 후 리스너가 호출 스레드에서 동기로 발행한다. */
    RedisBidProjector(JdbcTemplate jdbc, TransactionTemplate transaction, ObjectMapper mapper,
                      ApplicationContext context, LedgerSseNotifier notifier) {
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.mapper = mapper;
        this.context = context;
        this.notifier = notifier;
    }

    String apply(MapRecord<String, Object, Object> record) {
        Map<Object, Object> event = record.getValue();
        try {
            return transaction.execute(status -> commit(event, null));
        } catch (RuntimeException rejection) {
            if (!event.get("kind").equals("CALL")) throw rejection;
            // 도메인 트랜잭션 전체 롤백 후, 거절 결과와 커서를 같은 별도 트랜잭션에 남긴다.
            String error = rejection instanceof BusinessException business
                    ? business.getErrorCode().name() : "INTERNAL_SERVER_ERROR";
            return transaction.execute(status -> commit(event, error));
        }
    }

    private String commit(Map<Object, Object> event, String error) {
        long sequence = Long.parseLong(event.get("sequence").toString());
        long previous = jdbc.queryForObject("SELECT last_sequence FROM redis_bid_runtime WHERE id = 1 FOR UPDATE", Long.class);
        String request = event.get("request").toString();
        String fingerprint = event.get("fingerprint").toString();
        if (sequence <= previous) {
            Map<String, Object> saved = jdbc.queryForMap(
                    "SELECT request_key, fingerprint, result_json FROM redis_bid_command WHERE sequence_no = ?", sequence);
            if (!saved.get("request_key").equals(request) || !saved.get("fingerprint").equals(fingerprint)) {
                throw new IllegalStateException("입찰 로그 처리 이력 불일치");
            }
            return saved.get("result_json").toString();
        }
        if (sequence != previous + 1) throw new IllegalStateException("입찰 로그 순서 공백");
        Object value = null;
        if (error == null) {
            if (event.get("kind").equals("OPEN")) {
                value = projectOpen(event);
            } else {
                var payload = mapper.readTree(event.get("payload").toString());
                RedisCommandCatalog.IN_WORKER.set(true);
                try {
                    value = RedisCommandCatalog.invoke(context, mapper, payload.get("method").asText(),
                            payload.get("args").toString());
                    if (value instanceof Member member) value = Map.of("memberId", member.getId());
                } finally {
                    RedisCommandCatalog.IN_WORKER.remove();
                }
            }
        }
        String result = mapper.writeValueAsString(new Completion(error, value));
        jdbc.update("""
                INSERT INTO redis_bid_command(sequence_no, request_key, fingerprint, result_json, error_code)
                VALUES (?, ?, ?, ?, ?)
                """, sequence, request, fingerprint, result, error);
        jdbc.update("UPDATE redis_bid_runtime SET last_sequence = ? WHERE id = 1", sequence);
        return result;
    }

    private BidResult projectOpen(Map<Object, Object> event) {
        var payload = mapper.readTree(event.get("payload").toString());
        long member = payload.get("member").asLong();
        long auction = payload.get("auction").asLong();
        long price = payload.get("price").asLong();
        long reserve = Long.parseLong(event.get("reserve").toString());
        long version = Long.parseLong(event.get("version").toString());
        if (reserve > 0) {
            int changed = jdbc.update("""
                    UPDATE member SET total_point = total_point - ?, locked_point = locked_point + ?,
                        last_modified_at = SYSDATE(6)
                    WHERE id = ? AND status = 'ACTIVE' AND total_point >= ?
                    """, reserve, reserve, member, reserve);
            if (changed != 1) throw new IllegalStateException("수락한 예약 금액과 DB 잔액 불일치");
            jdbc.update("""
                    INSERT INTO auction_deposit(member_id, auction_id, reserved_amount, status, created_at, last_modified_at)
                    VALUES (?, ?, ?, 'HELD', SYSDATE(6), SYSDATE(6))
                    """, member, auction, reserve);
        } else if (reserve != 0 || jdbc.queryForObject("""
                SELECT COUNT(*) FROM auction_deposit WHERE auction_id = ? AND member_id = ? AND status = 'HELD'
                """, Long.class, auction, member) != 1L) {
            throw new IllegalStateException("수락한 입찰의 보증금 내역 불일치");
        }
        int changed = jdbc.update("""
                UPDATE auction SET current_price = ?, current_bidder_id = ?, bid_count = bid_count + 1,
                    revision = revision + 1, status = 'BID_ONGOING', last_modified_at = SYSDATE(6)
                WHERE id = ? AND revision = ? AND status IN ('OPEN', 'BID_ONGOING') AND completed_at IS NULL
                    AND current_price <= ? - 1000
                """, price, member, auction, version - 1, price);
        if (changed != 1) throw new IllegalStateException("수락한 입찰의 경매 버전 불일치");
        LocalDateTime acceptedAt = LocalDateTime.ofInstant(
                Instant.ofEpochMilli(Long.parseLong(event.get("accepted_ms").toString())), ZoneId.of("Asia/Seoul"));
        var keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("""
                    INSERT INTO bid(auction_id, bidder_id, price, status, created_at, last_modified_at)
                    VALUES (?, ?, ?, 'UP', ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, auction);
            statement.setLong(2, member);
            statement.setLong(3, price);
            statement.setTimestamp(4, Timestamp.valueOf(acceptedAt));
            statement.setTimestamp(5, Timestamp.valueOf(acceptedAt));
            return statement;
        }, keys);
        long bid = keys.getKey().longValue();
        String nickname = jdbc.queryForObject("SELECT nickname FROM member WHERE id = ?", String.class, member);
        var created = new AuctionBidCreated(auction, bid,
                BidHistoryItemResponse.of("BID:" + bid, nickname, price, acceptedAt));
        if (notifier == null) {
            // 커밋 뒤 리스너가 DB를 읽지 않도록 snapshot을 이 트랜잭션에서 만들어 싣는다.
            context.publishEvent(new AuctionStateChanged(
                    context.getBean(AuctionLiveStateService.class).snapshot(auction)
            ));
            context.publishEvent(created);
        } else {
            // 커밋이 확정된 뒤에만 알리고, 발행(DB 재조회·Redis)은 소비자 스레드 밖에서 한다.
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    notifier.committed(auction, created);
                }
            });
        }
        return new BidResult(bid, auction, member, price, BidStatus.UP, acceptedAt);
    }

    record Completion(String error, Object value) { }
}
