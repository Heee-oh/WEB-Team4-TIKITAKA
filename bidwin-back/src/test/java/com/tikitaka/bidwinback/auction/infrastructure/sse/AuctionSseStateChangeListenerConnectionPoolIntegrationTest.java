package com.tikitaka.bidwinback.auction.infrastructure.sse;

import com.tikitaka.bidwinback.auction.application.live.AuctionLiveState;
import com.tikitaka.bidwinback.auction.application.live.AuctionStateChanged;
import com.tikitaka.bidwinback.auction.domain.enums.AuctionStatus;
import com.tikitaka.bidwinback.auction.domain.enums.AuctionType;
import com.tikitaka.bidwinback.global.sse.RedisSseEventBus;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

/**
 * AFTER_COMMIT 리스너가 두 번째 DB 연결을 요구하지 않는지 검증한다.
 *
 * <p>AFTER_COMMIT 시점엔 원 트랜잭션의 연결이 아직 반납되지 않았다. 리스너가 REQUIRES_NEW로 DB를
 * 읽으면 연결을 쥔 채 두 번째 연결을 기다리게 되고, 동시 트랜잭션 수가 풀 크기에 닿으면 풀 전체가
 * 교착한다. 풀에 여유가 있으면 일반 통합 테스트는 이 문제를 잡지 못하므로, 풀을 1개로 줄여 두 번째
 * 연결 요구가 부하 없이도 바로 실패하게 한다.
 */
@SpringBootTest(properties = {
        "app.storage.s3.bucket=test-bucket",
        "spring.datasource.hikari.maximum-pool-size=1",
        "spring.datasource.hikari.connection-timeout=5000"
})
class AuctionSseStateChangeListenerConnectionPoolIntegrationTest {

    private final AuctionLiveState state = new AuctionLiveState(
            1L,
            3L,
            AuctionType.UP,
            AuctionStatus.BID_ONGOING,
            130_000L,
            2L
    );

    @Autowired
    private ApplicationEventPublisher eventPublisher;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private DataSource dataSource;

    @MockitoBean
    private RedisSseEventBus eventBus;

    @Test
    void 커넥션_풀이_1개여도_커밋_후_두번째_연결_없이_경매_상태를_발행한다() throws SQLException {
        assertThat(dataSource.unwrap(HikariDataSource.class).getMaximumPoolSize()).isEqualTo(1);

        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                eventPublisher.publishEvent(new AuctionStateChanged(state))
        );

        verify(eventBus).publish(AuctionSseMessages.state(state));
    }
}
