package com.tikitaka.bidwinback.auction.application.bid.ledger;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("app.bid-ledger")
public record BidLedgerProperties(boolean enabled, String host, int port, String password,
                                  Duration cacheTtl, Duration waitTimeout, int maxBacklog, Boolean workerEnabled) {
    public BidLedgerProperties {
        host = host == null ? "localhost" : host;
        port = port == 0 ? 6380 : port;
        password = password == null ? "" : password;
        cacheTtl = cacheTtl == null ? Duration.ofMinutes(5) : cacheTtl;
        waitTimeout = waitTimeout == null ? Duration.ofSeconds(5) : waitTimeout;
        maxBacklog = maxBacklog == 0 ? 5000 : maxBacklog;
        workerEnabled = workerEnabled == null ? true : workerEnabled;
        if (cacheTtl.toMillis() < 1 || cacheTtl.compareTo(Duration.ofDays(1)) > 0
                || waitTimeout.toMillis() < 1 || waitTimeout.compareTo(Duration.ofSeconds(30)) > 0
                || maxBacklog < 1 || maxBacklog > 100_000) {
            throw new IllegalArgumentException("입찰 로그 TTL·대기 시간·적체 상한 설정이 올바르지 않습니다.");
        }
    }
}
