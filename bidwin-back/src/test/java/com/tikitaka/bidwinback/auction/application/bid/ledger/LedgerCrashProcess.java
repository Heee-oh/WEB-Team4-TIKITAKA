package com.tikitaka.bidwinback.auction.application.bid.ledger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class LedgerCrashProcess {
    public static void main(String[] arguments) throws Exception {
        try (var context = RedisBidApiIntegrationTest.start(arguments)) {
            var environment = context.getEnvironment();
            var runtime = context.getBean(RedisBidRuntime.class);
            if (environment.getRequiredProperty("crash.action").equals("produce")) {
                runtime.submitOpen(environment.getRequiredProperty("crash.member", Long.class),
                        environment.getRequiredProperty("crash.auction", Long.class), 1_000_000,
                        environment.getRequiredProperty("crash.request"));
            } else {
                runtime.projector.apply(runtime.store.head());
            }
            Files.writeString(Path.of(environment.getRequiredProperty("crash.marker")), "ready");
            new CountDownLatch(1).await(60, TimeUnit.SECONDS);
            throw new IllegalStateException("부모가 프로세스를 종료하지 못했습니다.");
        }
    }
}
