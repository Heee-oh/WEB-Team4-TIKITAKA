package com.tikitaka.bidwinback.auction.application.bid.ledger;

import com.tikitaka.bidwinback.auction.application.bid.BidService;
import com.tikitaka.bidwinback.auction.application.bid.BidPriceCache;
import com.tikitaka.bidwinback.auction.application.bid.BidTransactionService;
import com.tikitaka.bidwinback.auction.application.buynow.BuyNowService;
import com.tikitaka.bidwinback.auction.application.buynow.BuyNowTransactionService;
import com.tikitaka.bidwinback.auction.application.buynow.BuyNowPriceCalculator;
import com.tikitaka.bidwinback.auction.application.closing.AuctionClosingService;
import com.tikitaka.bidwinback.auction.application.live.AuctionLiveStateService;
import com.tikitaka.bidwinback.auction.application.deposit.DepositSettlementServiceImpl;
import com.tikitaka.bidwinback.auction.application.trade.TradeConfirmationService;
import com.tikitaka.bidwinback.auction.domain.entity.UpAuction;
import com.tikitaka.bidwinback.auction.domain.enums.AuctionStatus;
import com.tikitaka.bidwinback.auction.domain.enums.AuctionCategory;
import com.tikitaka.bidwinback.auction.domain.enums.TradeType;
import com.tikitaka.bidwinback.auction.presentation.AuctionBidController;
import com.tikitaka.bidwinback.auction.presentation.RedisBidRequestController;
import com.tikitaka.bidwinback.global.auth.AuthConstant;
import com.tikitaka.bidwinback.global.auth.AuthMemberFixture;
import com.tikitaka.bidwinback.global.auth.LoginMemberArgumentResolver;
import com.tikitaka.bidwinback.global.auth.AuthExceptionFilter;
import com.tikitaka.bidwinback.global.auth.SessionAuthenticationFilter;
import com.tikitaka.bidwinback.auth.application.SessionAuthService;
import com.tikitaka.bidwinback.global.config.JpaAuditingConfig;
import com.tikitaka.bidwinback.global.config.SchedulingConfig;
import com.tikitaka.bidwinback.global.exception.GlobalExceptionHandler;
import com.tikitaka.bidwinback.member.application.MemberService;
import com.tikitaka.bidwinback.member.domain.entity.Member;
import com.tikitaka.bidwinback.member.domain.enums.MemberStatus;
import com.tikitaka.bidwinback.mypage.presentation.MyPageAccountUpdateController;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.Clock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "bidwin.ledger-api.enabled", matches = "true")
class RedisBidApiIntegrationTest {
    @Container
    static final GenericContainer<?> MYSQL = new GenericContainer<>("mysql:8.4.10")
            .withEnv("MYSQL_ROOT_PASSWORD", "ledger-root").withEnv("MYSQL_DATABASE", "ledger_api")
            .withEnv("MYSQL_USER", "ledger").withEnv("MYSQL_PASSWORD", "ledger-test")
            .withCommand("--default-time-zone=+09:00").withExposedPorts(3306)
            .waitingFor(Wait.forLogMessage(".*mysqld: ready for connections.*port: 3306.*\\n", 1))
            .withStartupTimeout(Duration.ofMinutes(3));
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4")
            .withCommand("--appendonly", "yes", "--appendfsync", "always",
                    "--maxmemory", "128mb", "--maxmemory-policy", "noeviction").withExposedPorts(6379);

    private static final String CHARGE = "/api/v1/mypage/points/charge";
    private static final String CHARGE_BODY = "{\"amount\":1000}";

    private ConfigurableApplicationContext context;
    private JdbcTemplate jdbc;
    private RedisBidRuntime runtime;
    private LettuceConnectionFactory connection;
    private StringRedisTemplate redis;
    private MockMvc mvc;
    private int redisPort;

    static ConfigurableApplicationContext start(String... arguments) {
        return new SpringApplicationBuilder(Configuration.class).web(WebApplicationType.NONE).run(arguments);
    }

    List<String> arguments() {
        return List.of("--spring.datasource.url=jdbc:mysql://" + MYSQL.getHost() + ":" + MYSQL.getMappedPort(3306)
                        + "/ledger_api?connectionTimeZone=%2B09:00&forceConnectionTimeZoneToSession=true",
                "--spring.datasource.username=ledger", "--spring.datasource.password=ledger-test",
                "--spring.datasource.hikari.maximum-pool-size=5", "--spring.data.redis.host=" + REDIS.getHost(),
                "--spring.data.redis.port=" + redisPort, "--spring.data.redis.password=",
                "--app.bid-ledger.enabled=true", "--app.bid-ledger.host=" + REDIS.getHost(),
                "--app.bid-ledger.port=" + redisPort, "--app.bid-ledger.worker-enabled=false",
                "--app.bid-ledger.cache-ttl=500ms", "--app.bid-ledger.wait-timeout=5s",
                "--spring.main.banner-mode=off", "--logging.level.root=WARN");
    }

    @BeforeAll
    void before() {
        if (redisPort == 0) redisPort = REDIS.getMappedPort(6379);
        context = start(arguments().toArray(String[]::new));
        jdbc = context.getBean(JdbcTemplate.class);
        runtime = context.getBean(RedisBidRuntime.class);
        connection = new LettuceConnectionFactory(REDIS.getHost(), redisPort);
        connection.afterPropertiesSet();
        connection.start();
        redis = new StringRedisTemplate(connection);
        redis.afterPropertiesSet();
        SessionAuthService auth = org.mockito.Mockito.mock(SessionAuthService.class);
        org.mockito.Mockito.when(auth.isAuthenticatable(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.eq(0L)))
                .thenAnswer(invocation -> jdbc.queryForObject("SELECT status = 'ACTIVE' FROM member WHERE id = ?",
                        Boolean.class, (Long) invocation.getArgument(0)));
        mvc = MockMvcBuilders.standaloneSetup(new RedisBidRequestController(runtime),
                        new AuctionBidController(context.getBean(BidService.class), null),
                        new MyPageAccountUpdateController(context.getBean(MemberService.class), null))
                .setCustomArgumentResolvers(new LoginMemberArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new AuthExceptionFilter(context.getBean(tools.jackson.databind.ObjectMapper.class)),
                        new SessionAuthenticationFilter(auth, Clock.systemUTC())).build();
    }

    @AfterAll
    void after() {
        if (connection != null) connection.destroy();
        if (context != null) context.close();
    }

    @Test
    void 실제_컨트롤러는_202수락과_201영속화를_구분하고_재전송과_소유권을_검증한다() throws Exception {
        Fixture f = fixture(2);
        long member = f.members.getFirst();
        String id = UUID.randomUUID().toString();
        String endpoint = "/api/v1/auctions/up/" + f.auction + "/bid-requests";
        var body = "{\"price\":101000,\"bidType\":\"OPEN\"}";
        mvc.perform(post(endpoint).with(loggedIn(member))
                        .header("Idempotency-Key", id).contentType("application/json").content(body))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.data.persisted").value(false));
        assertThat(number("SELECT COUNT(*) FROM bid WHERE auction_id = ?", f.auction)).isZero();
        mvc.perform(post(endpoint).with(loggedIn(member))
                        .header("Idempotency-Key", id).contentType("application/json").content(body))
                .andExpect(status().isAccepted());
        mvc.perform(post(endpoint).with(loggedIn(member))
                        .header("Idempotency-Key", id).contentType("application/json")
                        .content("{\"price\":102000,\"bidType\":\"OPEN\"}"))
                .andExpect(status().isConflict());
        mvc.perform(get("/api/v1/auctions/bid-requests/" + id)
                        .with(loggedIn(f.members.getLast())))
                .andExpect(status().isNotFound());
        mvc.perform(post(endpoint).header("Idempotency-Key", "no-auth").contentType("application/json").content(body))
                .andExpect(status().isUnauthorized());
        assertThat(runtime.processHead()).isTrue();
        mvc.perform(get("/api/v1/auctions/bid-requests/" + id)
                        .with(loggedIn(member)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.value.bidId").isNumber());
        assertBalance(member, 1_970_000, 30_000);

        whileDraining(() -> {
            mvc.perform(post("/api/v1/auctions/up/" + f.auction + "/bids")
                            .with(loggedIn(member))
                            .header("Idempotency-Key", "sync-" + id).contentType("application/json")
                            .content("{\"price\":102000,\"bidType\":\"OPEN\"}"))
                    .andExpect(status().isCreated()).andExpect(jsonPath("$.data.bidId").isNumber());
            return null;
        });
        assertThat(number("SELECT COUNT(*) FROM bid WHERE auction_id = ?", f.auction)).isEqualTo(2);
        assertBalance(member, 1_970_000, 30_000);
    }

    @Test
    void 충전은_앞선_입찰을_반영한_뒤_처리하고_캐시를_갱신한다() throws Exception {
        Fixture f = fixture(1);
        long member = f.members.getFirst();
        runtime.submitOpen(member, f.auction, 101_000, UUID.randomUUID().toString());
        try (var client = Executors.newSingleThreadExecutor()) {
            var charge = client.submit(() -> context.getBean(MemberService.class).chargePoint(member, 1_000));
            awaitBarrier();
            assertThatThrownBy(() -> runtime.submitOpen(member, f.auction, 102_000, "blocked"))
                    .isInstanceOf(BidLedgerException.class);
            assertThat(runtime.processHead()).isTrue();
            assertBalance(member, 1_970_000, 30_000);
            assertThat(runtime.processHead()).isTrue();
            assertThat(charge.get(5, TimeUnit.SECONDS).getTotalPoint()).isEqualTo(1_971_000);
        }
        runtime.submitOpen(member, f.auction, 102_000, UUID.randomUUID().toString());
        assertThat(runtime.processHead()).isTrue();
        assertBalance(member, 1_971_000, 30_000);
    }

    @Test
    void 다른경매_즉시구매는_Redis미반영_보증금을_중복사용하지_못한다() throws Exception {
        Fixture f = fixture(1);
        Fixture purchase = fixture(0);
        long member = f.members.getFirst();
        commit("UPDATE member SET total_point = 320000 WHERE id = ?", member);
        runtime.submitOpen(member, f.auction, 101_000, UUID.randomUUID().toString());
        assertThatThrownBy(() -> whileDraining(() -> context.getBean(BuyNowService.class)
                .buyUpAuction(member, purchase.auction, UUID.randomUUID().toString())))
                .hasRootCauseInstanceOf(BidLedgerException.class);
        assertBalance(member, 290_000, 30_000);
        assertThat(number("SELECT COUNT(*) FROM auction_trade WHERE auction_id = ?", purchase.auction)).isZero();
        assertThat(redis.opsForHash().get(RedisBidStore.META, "barriers")).isEqualTo("0");
    }

    @Test
    void 앞선_수락을_반영한_후_마감하고_패자환불과_양측확정을_처리한다() throws Exception {
        Fixture f = fixture(2);
        long loser = f.members.getFirst();
        long winner = f.members.getLast();
        runtime.submitOpen(loser, f.auction, 101_000, UUID.randomUUID().toString());
        runtime.submitOpen(winner, f.auction, 102_000, UUID.randomUUID().toString());
        // 수락 후 시간이 경과한 조건을 DB 마감 시각으로 통제한다. 이미 수락된 이벤트를 재판정하지 않는다.
        commit("UPDATE auction SET ended_at = SYSDATE(6) - INTERVAL 1 SECOND WHERE id = ?", f.auction);
        int closed = whileDraining(() -> context.getBean(AuctionClosingService.class).closeBatch(AuctionStatus.BID_ONGOING, 10));
        assertThat(closed).isEqualTo(1);
        assertBalance(loser, 2_000_000, 0);
        assertBalance(winner, 1_970_000, 30_000);
        long trade = number("SELECT id FROM auction_trade WHERE auction_id = ?", f.auction);
        whileDraining(() -> context.getBean(TradeConfirmationService.class).confirmBuyer(winner, trade));
        assertBalance(winner, 1_898_000, 102_000);
        whileDraining(() -> context.getBean(TradeConfirmationService.class).confirmSeller(f.seller, trade));
        assertBalance(winner, 1_898_000, 0);
        assertBalance(f.seller, 2_102_000, 0);
    }

    @Test
    void 밀봉입찰은_기존_가격비공개_응답을_유지한다() throws Exception {
        Fixture f = fixture(1);
        commit("UPDATE auction SET ended_at = SYSDATE(6) + INTERVAL 4 MINUTE WHERE id = ?", f.auction);
        whileDraining(() -> {
            mvc.perform(post("/api/v1/auctions/up/" + f.auction + "/bids")
                            .with(loggedIn(f.members.getFirst()))
                            .contentType("application/json").content("{\"price\":101000,\"bidType\":\"SEALED\"}"))
                    .andExpect(status().isCreated()).andExpect(jsonPath("$.data.price").doesNotExist());
            return null;
        });
        assertBalance(f.members.getFirst(), 1_970_000, 30_000);
        assertThat(number("SELECT COUNT(*) FROM sealed_bid WHERE auction_id = ?", f.auction)).isEqualTo(1);
    }

    @Test
    void DB커밋후_ACK전_재소비와_TTL은_중복차감을_만들지_않는다() throws Exception {
        Fixture f = fixture(1);
        long member = f.members.getFirst();
        String id = UUID.randomUUID().toString();
        String epoch = runtime.store.meta().get("epoch").toString();
        runtime.submitOpen(member, f.auction, 101_000, id);
        Thread.sleep(600);
        assertThat(redis.getExpire(RedisBidStore.walletKey(epoch, member))).isEqualTo(-1);
        var event = runtime.store.head();
        runtime.projector.apply(event); // DB 커밋 후 ACK 전 종료 지점. 다음 처리에서 재소비한다.
        assertBalance(member, 1_970_000, 30_000);
        runtime.processHead();
        assertBalance(member, 1_970_000, 30_000);
        assertThat(number("SELECT COUNT(*) FROM bid WHERE auction_id = ?", f.auction)).isEqualTo(1);
        Thread.sleep(600);
        assertThat(redis.hasKey(RedisBidStore.walletKey(epoch, member))).isFalse();
        runtime.submitOpen(member, f.auction, 102_000, UUID.randomUUID().toString());
        runtime.processHead();
        assertBalance(member, 1_970_000, 30_000);
    }

    @Test
    void 겹친_두소비자가_같은_HEAD를_반영해도_입찰과_금액은_한번만_변경된다() throws Exception {
        Fixture f = fixture(1);
        runtime.submitOpen(f.members.getFirst(), f.auction, 101_000, UUID.randomUUID().toString());
        var event = runtime.store.head();
        try (var clients = Executors.newFixedThreadPool(2)) {
            var a = clients.submit(() -> runtime.projector.apply(event));
            var b = clients.submit(() -> runtime.projector.apply(event));
            var mapper = context.getBean(tools.jackson.databind.ObjectMapper.class);
            assertThat(mapper.readTree(a.get())).isEqualTo(mapper.readTree(b.get()));
        }
        runtime.processHead();
        assertBalance(f.members.getFirst(), 1_970_000, 30_000);
        assertThat(number("SELECT COUNT(*) FROM bid WHERE auction_id = ?", f.auction)).isEqualTo(1);
    }

    private <T> T whileDraining(Callable<T> operation) throws Exception {
        try (var client = Executors.newSingleThreadExecutor()) {
            var result = client.submit(operation);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!result.isDone() && System.nanoTime() < deadline) {
                runtime.processHead();
                Thread.sleep(5);
            }
            return result.get(1, TimeUnit.SECONDS);
        }
    }

    @Test
    void 잘못된_헤더와_가격과_자기입찰과_단계는_금액변경없이_거절한다() throws Exception {
        Fixture f = fixture(1);
        long member = f.members.getFirst();
        String endpoint = "/api/v1/auctions/up/" + f.auction + "/bid-requests";
        mvc.perform(post(endpoint).with(loggedIn(member)).contentType("application/json")
                        .content("{\"price\":101000,\"bidType\":\"OPEN\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(endpoint).with(loggedIn(member)).header("Idempotency-Key", "../invalid")
                        .contentType("application/json").content("{\"price\":101000,\"bidType\":\"OPEN\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(endpoint).with(loggedIn(f.seller)).header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json").content("{\"price\":101000,\"bidType\":\"OPEN\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post(endpoint).with(loggedIn(member)).header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json").content("{\"price\":100500,\"bidType\":\"OPEN\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(endpoint).with(loggedIn(member)).header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json").content("{\"price\":100000,\"bidType\":\"OPEN\"}"))
                .andExpect(status().isUnprocessableEntity());
        Fixture late = fixture(0);
        commit("UPDATE auction SET ended_at = SYSDATE(6) + INTERVAL 4 MINUTE WHERE id = ?", late.auction);
        mvc.perform(post("/api/v1/auctions/up/" + late.auction + "/bid-requests").with(loggedIn(member))
                        .header("Idempotency-Key", UUID.randomUUID().toString()).contentType("application/json")
                        .content("{\"price\":101000,\"bidType\":\"OPEN\"}"))
                .andExpect(status().isConflict());
        assertBalance(member, 2_000_000, 0);
        assertBalance(f.seller, 2_000_000, 0);
        assertThat(runtime.store.head()).isNull();
    }

    @Test
    void 여러_제어명령이_대기해도_순서대로_반영하고_장벽을_해제한다() throws Exception {
        Fixture f = fixture(1);
        long member = f.members.getFirst();
        try (var clients = Executors.newFixedThreadPool(2)) {
            var a = clients.submit(() -> context.getBean(MemberService.class).chargePoint(member, 1_000));
            var b = clients.submit(() -> context.getBean(MemberService.class).chargePoint(member, 2_000));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!"2".equals(runtime.store.meta().get("barriers")) && System.nanoTime() < deadline) Thread.sleep(5);
            assertThat(runtime.store.meta().get("barriers")).isEqualTo("2");
            runtime.processHead();
            assertThat(runtime.store.meta().get("barriers")).isEqualTo("1");
            runtime.processHead();
            a.get(3, TimeUnit.SECONDS);
            b.get(3, TimeUnit.SECONDS);
            assertThat(runtime.store.meta().get("barriers")).isEqualTo("0");
        }
        assertBalance(member, 2_003_000, 0);
    }

    @Test
    void 같은회원의_여러경매_동시첫입찰도_초과예약하지_못한다() throws Exception {
        Fixture a = fixture(1);
        Fixture b = fixture(0);
        long member = a.members.getFirst();
        commit("UPDATE member SET total_point = 30000 WHERE id = ?", member);
        try (var clients = Executors.newFixedThreadPool(2)) {
            var results = clients.invokeAll(List.<Callable<Boolean>>of(
                    () -> acceptOrReject(member, a.auction), () -> acceptOrReject(member, b.auction)));
            assertThat(results.stream().filter(f -> {
                try { return f.get(); } catch (Exception failure) { throw new IllegalStateException(failure); }
            }).count()).isEqualTo(1);
        }
        runtime.processHead();
        assertBalance(member, 0, 30_000);
        assertThat(number("SELECT COUNT(*) FROM auction_deposit WHERE member_id = ?", member)).isEqualTo(1);
    }

    private boolean acceptOrReject(long member, long auction) {
        try {
            runtime.submitOpen(member, auction, 101_000, UUID.randomUUID().toString());
            return true;
        } catch (BidLedgerException failure) {
            assertThat(failure.getErrorCode()).isEqualTo(com.tikitaka.bidwinback.global.exception.ErrorCode.INSUFFICIENT_DEPOSIT);
            return false;
        }
    }

    @Test
    void 앱JVM_SIGKILL후_수락과_커밋후_ACK전_기록을_실제코드로_복구한다() throws Exception {
        Fixture accepted = fixture(1);
        String acceptedId = killChild(accepted, "produce");
        runtime.submitOpen(accepted.members.getFirst(), accepted.auction, 1_000_000, acceptedId);
        assertBalance(accepted.members.getFirst(), 2_000_000, 0);
        runtime.processHead();
        assertBalance(accepted.members.getFirst(), 1_970_000, 30_000);
        assertThat(number("SELECT current_price FROM auction WHERE id = ?", accepted.auction)).isEqualTo(1_000_000);

        Fixture committed = fixture(1);
        runtime.submitOpen(committed.members.getFirst(), committed.auction, 101_000, UUID.randomUUID().toString());
        killChild(committed, "commit");
        assertBalance(committed.members.getFirst(), 1_970_000, 30_000);
        runtime.processHead();
        assertBalance(committed.members.getFirst(), 1_970_000, 30_000);
        assertThat(number("SELECT COUNT(*) FROM bid WHERE auction_id = ?", committed.auction)).isEqualTo(1);
    }

    private String killChild(Fixture fixture, String action) throws Exception {
        Path directory = Path.of("build/reports/bid-ledger-api");
        Files.createDirectories(directory);
        String id = UUID.randomUUID().toString();
        Path marker = directory.resolve(id + ".marker").toAbsolutePath();
        Path output = directory.resolve(id + ".log").toAbsolutePath();
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Duser.timezone=Asia/Seoul", "-cp", System.getProperty("bidwin.ledger-api.classpath"),
                LedgerCrashProcess.class.getName()));
        command.addAll(arguments());
        command.addAll(List.of("--crash.auction=" + fixture.auction, "--crash.member=" + fixture.members.getFirst(),
                "--crash.request=" + id, "--crash.action=" + action, "--crash.marker=" + marker));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
            while (process.isAlive() && !Files.exists(marker) && System.nanoTime() < deadline) Thread.sleep(20);
            assertThat(Files.exists(marker)).withFailMessage("자식 프로세스 실패: %s", Files.readString(output)).isTrue();
            process.destroyForcibly();
            assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
            }
        }
        return id;
    }

    @Test
    void 로그가_유실되면_과거DB로_초기화하거나_기존모드로_시작하지_못한다() {
        Map<Object, Object> meta = runtime.store.meta();
        redis.delete(RedisBidStore.META);
        try {
            assertThatThrownBy(runtime::initialize).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("유실");
            assertThatThrownBy(() -> new BidLedgerConfiguration().preventUnsafeBidFallback(jdbc,
                    new BidLedgerProperties(false, null, 0, null, null, null, 0, false))
                    .run(new org.springframework.boot.DefaultApplicationArguments()))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("전환할 수 없습니다");
        } finally {
            redis.opsForHash().putAll(RedisBidStore.META, meta);
        }
    }

    @Test
    void Redis_SIGKILL후_AOF와_DB커서로_재시작해_미반영_입찰을_복구한다() throws Exception {
        Fixture f = fixture(1);
        long member = f.members.getFirst();
        String id = UUID.randomUUID().toString();
        runtime.submitOpen(member, f.auction, 101_000, id);
        context.close();
        connection.destroy();
        var docker = org.testcontainers.DockerClientFactory.instance().client();
        docker.killContainerCmd(REDIS.getContainerId()).withSignal("KILL").exec();
        docker.startContainerCmd(REDIS.getContainerId()).exec();
        redisPort = Integer.parseInt(docker.inspectContainerCmd(REDIS.getContainerId()).exec().getNetworkSettings()
                .getPorts().getBindings().get(com.github.dockerjava.api.model.ExposedPort.tcp(6379))[0].getHostPortSpec());
        // 새 실제 연결에서 Redis 준비를 기다린 뒤 Spring 컨텍스트를 다시 시작한다.
        var probe = new LettuceConnectionFactory(REDIS.getHost(), redisPort);
        probe.afterPropertiesSet();
        probe.start();
        try {
            var template = new StringRedisTemplate(probe);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            boolean ready = false;
            while (!ready && System.nanoTime() < deadline) {
                try { ready = Boolean.TRUE.equals(template.hasKey(RedisBidStore.META)); }
                catch (RuntimeException starting) { Thread.sleep(25); }
            }
            assertThat(ready).isTrue();
        } finally { probe.destroy(); }
        before();
        runtime.submitOpen(member, f.auction, 101_000, id);
        runtime.processHead();
        assertBalance(member, 1_970_000, 30_000);
        assertThat(number("SELECT COUNT(*) FROM bid WHERE auction_id = ?", f.auction)).isEqualTo(1);
    }

    @Test
    void 실제_Spring스케줄러가_202입찰을_자동으로_DB에_반영한다() throws Exception {
        Fixture f = fixture(1);
        List<String> enabled = new ArrayList<>(arguments());
        enabled.remove("--app.bid-ledger.worker-enabled=false");
        enabled.add("--app.bid-ledger.worker-enabled=true");
        try (var workerContext = start(enabled.toArray(String[]::new))) {
            String id = UUID.randomUUID().toString();
            mvc.perform(post("/api/v1/auctions/up/" + f.auction + "/bid-requests").with(loggedIn(f.members.getFirst()))
                            .header("Idempotency-Key", id).contentType("application/json")
                            .content("{\"price\":101000,\"bidType\":\"OPEN\"}"))
                    .andExpect(status().isAccepted());
            assertThat(runtime.awaitBid(f.members.getFirst(), id).price()).isEqualTo(101_000L);
            assertBalance(f.members.getFirst(), 1_970_000, 30_000);
        }
    }

    @Test
    void 적체상한은_추가_가격변경과_예약전에_입찰을_거절한다() {
        Fixture f = fixture(1);
        var properties = new BidLedgerProperties(true, REDIS.getHost(), redisPort, "", Duration.ofMillis(500),
                Duration.ofSeconds(1), 1, false);
        try (var limited = new RedisBidRuntime(properties, jdbc,
                new org.springframework.transaction.support.TransactionTemplate(
                        context.getBean(org.springframework.transaction.PlatformTransactionManager.class)),
                context.getBean(tools.jackson.databind.ObjectMapper.class), context)) {
            limited.initialize();
            limited.submitOpen(f.members.getFirst(), f.auction, 101_000, UUID.randomUUID().toString());
            assertThatThrownBy(() -> limited.submitOpen(f.members.getFirst(), f.auction, 102_000, "overloaded"))
                    .isInstanceOf(BidLedgerException.class);
            String epoch = runtime.store.meta().get("epoch").toString();
            assertThat(redis.opsForHash().get(RedisBidStore.auctionKey(epoch, f.auction), "price")).isEqualTo("101000");
            assertThat(redis.opsForHash().get(RedisBidStore.walletKey(epoch, f.members.getFirst()), "available"))
                    .isEqualTo("1970000");
            runtime.processHead();
            assertBalance(f.members.getFirst(), 1_970_000, 30_000);
        }
    }

    @Test
    void 손상된_카운터는_수락과_금액변경전에_거절한다() {
        Fixture f = fixture(1);
        long member = f.members.getFirst();
        runtime.submitOpen(member, f.auction, 101_000, UUID.randomUUID().toString());
        String epoch = runtime.store.meta().get("epoch").toString();
        String wallet = RedisBidStore.walletKey(epoch, member);
        redis.opsForHash().put(wallet, "pending", "0.5");
        redis.opsForHash().put(RedisBidStore.DIRTY, wallet, "0.5");
        try {
            assertThatThrownBy(() -> runtime.submitOpen(member, f.auction, 102_000, UUID.randomUUID().toString()))
                    .isInstanceOf(BidLedgerException.class);
            assertThat(redis.opsForHash().get(wallet, "available")).isEqualTo("1970000");
            assertThat(redis.opsForHash().get(RedisBidStore.auctionKey(epoch, f.auction), "price")).isEqualTo("101000");
            assertThat(redis.opsForStream().size(RedisBidStore.STREAM)).isEqualTo(1);
        } finally {
            redis.opsForHash().put(wallet, "pending", "1");
            redis.opsForHash().put(RedisBidStore.DIRTY, wallet, "1");
        }
        runtime.processHead();
        assertBalance(member, 1_970_000, 30_000);
    }

    @Test
    void 이전_데이터의_NULL현재가도_기존_호가기준으로_처리한다() {
        Fixture f = fixture(1);
        commit("UPDATE auction SET current_price = NULL WHERE id = ?", f.auction);
        runtime.submitOpen(f.members.getFirst(), f.auction, 101_000, UUID.randomUUID().toString());
        runtime.processHead();
        assertThat(number("SELECT current_price FROM auction WHERE id = ?", f.auction)).isEqualTo(101_000);
        assertBalance(f.members.getFirst(), 1_970_000, 30_000);
    }

    @Test
    void 마감대상이_없고_미반영로그도_없으면_장벽과_명령을_만들지_않는다() {
        String sequence = runtime.store.meta().get("seq").toString();
        assertThat(context.getBean(AuctionClosingService.class).closeBatch(AuctionStatus.BID_ONGOING, 10)).isZero();
        assertThat(runtime.store.meta().get("seq")).isEqualTo(sequence);
        assertThat(runtime.store.head()).isNull();
    }

    @Test
    void DB반영오류는_잔액과_처리이력을_함께_롤백하고_로그를_보존한다() {
        Fixture f = fixture(1);
        long member = f.members.getFirst();
        long previous = jdbc.queryForObject("SELECT last_sequence FROM redis_bid_runtime WHERE id = 1", Long.class);
        runtime.submitOpen(member, f.auction, 101_000, UUID.randomUUID().toString());
        commit("UPDATE auction SET revision = 1 WHERE id = ?", f.auction);
        assertThatThrownBy(runtime::processHead).isInstanceOf(IllegalStateException.class);
        assertBalance(member, 2_000_000, 0);
        assertThat(number("SELECT COUNT(*) FROM auction_deposit WHERE auction_id = ?", f.auction)).isZero();
        assertThat(jdbc.queryForObject("SELECT last_sequence FROM redis_bid_runtime WHERE id = 1", Long.class)).isEqualTo(previous);
        assertThat(runtime.store.head()).isNotNull();
        commit("UPDATE auction SET revision = 0 WHERE id = ?", f.auction);
        runtime.processHead();
        assertBalance(member, 1_970_000, 30_000);
        assertThat(number("SELECT COUNT(*) FROM bid WHERE auction_id = ?", f.auction)).isEqualTo(1);
    }

    @Test
    void 응답이_503인_충전을_키없이_다시_보내도_두번_반영되지_않는다() throws Exception {
        Fixture f = fixture(1);
        long member = f.members.getFirst();
        // 소비자가 멈춘 상태: 명령은 장부에 남고 waitTimeout 뒤 503이 나간다. 현재 프런트는 Idempotency-Key 없이 다시 보낸다.
        var first = mvc.perform(post(CHARGE).with(loggedIn(member)).contentType("application/json").content(CHARGE_BODY))
                .andReturn().getResponse();
        var retry = mvc.perform(post(CHARGE).with(loggedIn(member)).contentType("application/json").content(CHARGE_BODY))
                .andReturn().getResponse();
        while (runtime.processHead()) { }
        assertThat(number("SELECT total_point FROM member WHERE id = ?", member))
                .as("첫 응답=%d, 재시도 응답=%d: 한 번의 충전 의도가 두 번 반영되면 안 된다", first.getStatus(), retry.getStatus())
                .isLessThanOrEqualTo(2_001_000);
    }

    @Test
    void 응답이_503인_충전도_응답의_키로_다시_보내면_한번만_반영된다() throws Exception {
        Fixture f = fixture(1);
        long member = f.members.getFirst();
        String key = UUID.randomUUID().toString();
        var first = mvc.perform(post(CHARGE).with(loggedIn(member)).header("Idempotency-Key", key)
                        .contentType("application/json").content(CHARGE_BODY))
                .andExpect(status().isServiceUnavailable()).andReturn().getResponse();
        assertThat(first.getHeader("Idempotency-Key")).isEqualTo(key);
        try (var client = Executors.newSingleThreadExecutor()) {
            var retry = client.submit(() -> mvc.perform(post(CHARGE).with(loggedIn(member))
                    .header("Idempotency-Key", key).contentType("application/json").content(CHARGE_BODY))
                    .andReturn().getResponse().getStatus());
            assertThat(runtime.processHead()).isTrue();
            assertThat(retry.get(5, TimeUnit.SECONDS)).isEqualTo(200);
        }
        assertThat(runtime.processHead()).isFalse();
        assertThat(number("SELECT total_point FROM member WHERE id = ?", member)).isEqualTo(2_001_000);
    }

    @Test
    void 충전은_키가_없으면_장부에_넣지_않고_같은_키의_다른_금액은_거절한다() throws Exception {
        Fixture f = fixture(1);
        long member = f.members.getFirst();
        String sequence = runtime.store.meta().get("seq").toString();
        mvc.perform(post(CHARGE).with(loggedIn(member)).contentType("application/json").content(CHARGE_BODY))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("BID_400_3"));
        assertThat(runtime.store.meta().get("seq")).isEqualTo(sequence);
        assertThat(runtime.store.head()).isNull();
        String key = UUID.randomUUID().toString();
        try (var client = Executors.newSingleThreadExecutor()) {
            var charge = client.submit(() -> mvc.perform(post(CHARGE).with(loggedIn(member)).header("Idempotency-Key", key)
                    .contentType("application/json").content(CHARGE_BODY)).andReturn().getResponse().getStatus());
            awaitBarrier();
            mvc.perform(post(CHARGE).with(loggedIn(member)).header("Idempotency-Key", key)
                            .contentType("application/json").content("{\"amount\":2000}"))
                    .andExpect(status().isConflict());
            assertThat(runtime.processHead()).isTrue();
            assertThat(charge.get(5, TimeUnit.SECONDS)).isEqualTo(200);
        }
        assertThat(number("SELECT total_point FROM member WHERE id = ?", member)).isEqualTo(2_001_000);
    }

    private void awaitBarrier() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while ("0".equals(runtime.store.meta().get("barriers")) && System.nanoTime() < deadline) Thread.sleep(5);
        assertThat(runtime.store.meta().get("barriers")).isNotEqualTo("0");
    }

    // autocommit이 꺼져 있으므로 테스트 준비용 쓰기도 트랜잭션 안에서 커밋한다.
    private void commit(String sql, Object... args) {
        new TransactionTemplate(context.getBean(PlatformTransactionManager.class))
                .executeWithoutResult(status -> jdbc.update(sql, args));
    }

    private Fixture fixture(int count) {
        try (var em = context.getBean(EntityManagerFactory.class).createEntityManager()) {
            em.getTransaction().begin();
            Member seller = member(em);
            var auction = UpAuction.builder().seller(seller).title("실제 API 검증").description("전용 테스트 DB")
                    .status(AuctionStatus.OPEN).category(AuctionCategory.HOUSEHOLD).startPrice(100_000L)
                    .endedAt(LocalDateTime.now().plusHours(1)).tradeType(TradeType.DELIVERY)
                    .contact("01012345678").buyNowPrice(300_000L).build();
            em.persist(auction);
            List<Long> members = new ArrayList<>();
            for (int i = 0; i < count; i++) members.add(member(em).getId());
            em.getTransaction().commit();
            return new Fixture(auction.getId(), seller.getId(), members);
        }
    }

    private Member member(jakarta.persistence.EntityManager em) {
        String name = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        var member = Member.builder().email(name + "@example.com").nickname(name).name("API검증")
                .phoneNumber("01012345678").password("test-only").status(MemberStatus.ACTIVE).build();
        em.persist(member);
        em.flush();
        return member;
    }

    private long number(String query, long id) { return jdbc.queryForObject(query, Long.class, id); }
    private RequestPostProcessor loggedIn(long member) {
        return request -> {
            var session = new MockHttpSession();
            session.setAttribute(AuthConstant.SESSION_KEY, AuthMemberFixture.of(member));
            request.setSession(session);
            return request;
        };
    }
    private void assertBalance(long member, long available, long locked) {
        assertThat(number("SELECT total_point FROM member WHERE id = ?", member)).isEqualTo(available);
        assertThat(number("SELECT locked_point FROM member WHERE id = ?", member)).isEqualTo(locked);
    }
    record Fixture(long auction, long seller, List<Long> members) { }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @AutoConfigurationPackage(basePackages = "com.tikitaka.bidwinback")
    @Import({BidService.class, BidPriceCache.class, BidTransactionService.class, AuctionLiveStateService.class, JpaAuditingConfig.class,
            BidLedgerConfiguration.class, DepositSettlementServiceImpl.class, BuyNowService.class,
            BuyNowTransactionService.class, BuyNowPriceCalculator.class, MemberService.class,
            TradeConfirmationService.class, AuctionClosingService.class, SchedulingConfig.class})
    static class Configuration { }
}
