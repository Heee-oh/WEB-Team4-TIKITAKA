package com.tikitaka.bidwinback.auction.application.bid.ledger;

import com.tikitaka.bidwinback.auction.application.bid.BidService;
import com.tikitaka.bidwinback.auction.application.closing.AuctionClosingService;
import com.tikitaka.bidwinback.auction.domain.enums.AuctionStatus;
import com.tikitaka.bidwinback.auction.domain.enums.BidType;
import com.tikitaka.bidwinback.global.auth.AuthConstant;
import com.tikitaka.bidwinback.global.auth.AuthMember;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.StaticMethodMatcherPointcut;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;
import org.springframework.core.Ordered;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Method;
import java.util.UUID;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(BidLedgerProperties.class)
public class BidLedgerConfiguration {

    @Bean(initMethod = "initialize", destroyMethod = "close")
    @DependsOnDatabaseInitialization
    @ConditionalOnProperty(name = "app.bid-ledger.enabled", havingValue = "true")
    RedisBidRuntime redisBidRuntime(BidLedgerProperties properties, JdbcTemplate jdbc,
                                   PlatformTransactionManager manager, ObjectMapper mapper, ApplicationContext context) {
        return new RedisBidRuntime(properties, jdbc, new TransactionTemplate(manager), mapper, context);
    }

    @Bean
    ApplicationRunner preventUnsafeBidFallback(JdbcTemplate jdbc, BidLedgerProperties properties) {
        return args -> {
            if (!properties.enabled() && Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT enabled FROM redis_bid_runtime WHERE id = 1", Boolean.class))) {
                throw new IllegalStateException("활성 Redis 입찰 로그가 있습니다. 모든 앱을 정지하고 로그를 소진·검증하기 전 DB 모드로 전환할 수 없습니다.");
            }
        };
    }

    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    @ConditionalOnProperty(name = "app.bid-ledger.enabled", havingValue = "true")
    DefaultPointcutAdvisor redisFinancialBoundary(ObjectProvider<RedisBidRuntime> runtime) {
        var pointcut = new StaticMethodMatcherPointcut() {
            @Override
            public boolean matches(Method method, Class<?> targetClass) {
                return RedisCommandCatalog.matches(targetClass, method);
            }
        };
        MethodInterceptor interceptor = invocation -> {
            if (RedisCommandCatalog.IN_WORKER.get()) return invocation.proceed();
            // 트랜잭션보다 바깥에서 큐에 넣어야 소비자를 기다리며 DB 락/연결을 점유하지 않는다.
            if (TransactionSynchronizationManager.isActualTransactionActive()) throw RedisBidStore.unavailable();
            Class<?> target = invocation.getThis().getClass();
            String name = RedisCommandCatalog.key(target, invocation.getMethod());
            Method method = RedisCommandCatalog.method(name);
            Object[] arguments = invocation.getArguments();
            var ledger = runtime.getObject();
            // 마감 대상은 시각으로 정해지므로(ended_at <= NOW) 대기 중인 입찰이 대상 유무를 바꿀 수 없다.
            // OPEN·BID_ONGOING 어느 쪽에도 마감 시각이 지난 경매가 없으면 명령·장벽을 만들지 않는다.
            // 하나라도 있으면 앞선 수락 이벤트 뒤에서 순서대로 실행해야 하므로 기존처럼 큐에 넣는다.
            if (target == AuctionClosingService.class && method.getName().equals("closeBatch")) {
                var closing = (AuctionClosingService) invocation.getThis();
                if (closing.findClosingCandidateIds(AuctionStatus.OPEN, 1).isEmpty()
                        && closing.findClosingCandidateIds(AuctionStatus.BID_ONGOING, 1).isEmpty()) {
                    return 0;
                }
            }
            String id = requestId();
            if (target == BidService.class && arguments[3] == BidType.OPEN) {
                long member = (Long) arguments[0];
                ledger.submitOpen(member, (Long) arguments[1], (Long) arguments[2], id);
                return ledger.awaitBid(member, id);
            }
            return ledger.call(name, method, arguments, owner(), id);
        };
        var advisor = new DefaultPointcutAdvisor(pointcut, interceptor);
        advisor.setOrder(Ordered.HIGHEST_PRECEDENCE + 100);
        return advisor;
    }

    static String requestId() {
        String id = null;
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes request) {
            id = request.getRequest().getHeader("Idempotency-Key");
            if (id == null) id = UUID.randomUUID().toString();
            if (request.getResponse() != null) request.getResponse().setHeader("Idempotency-Key", id);
        }
        return id == null ? UUID.randomUUID().toString() : id;
    }

    private static long owner() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes request
                && request.getRequest().getAttribute(AuthConstant.REQUEST_ATTRIBUTE_KEY) instanceof AuthMember auth) {
            return auth.memberId();
        }
        return 0;
    }
}
