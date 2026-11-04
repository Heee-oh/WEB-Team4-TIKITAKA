package com.tikitaka.bidwinback.auction.application.bid.ledger;

import com.tikitaka.bidwinback.auction.application.bid.BidService;
import com.tikitaka.bidwinback.auction.application.buynow.BuyNowService;
import com.tikitaka.bidwinback.auction.application.buynow.BuyNowTransactionService;
import com.tikitaka.bidwinback.auction.application.closing.AuctionClosingService;
import com.tikitaka.bidwinback.auction.application.deposit.DepositSettlementServiceImpl;
import com.tikitaka.bidwinback.auction.application.trade.TradeConfirmationService;
import com.tikitaka.bidwinback.member.application.MemberService;
import org.springframework.context.ApplicationContext;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** 네트워크 입력으로 클래스/메서드를 선택하지 않는다. 등록된 기존 금액 처리 진입점만 직렬화한다. */
final class RedisCommandCatalog {
    static final ThreadLocal<Boolean> IN_WORKER = ThreadLocal.withInitial(() -> false);
    private static final Map<Class<?>, Set<String>> TARGETS = Map.of(
            BidService.class, Set.of("place"),
            BuyNowService.class, Set.of("buyUpAuction", "buyDownAuction"),
            BuyNowTransactionService.class, Set.of("buy"),
            MemberService.class, Set.of("chargePoint"),
            TradeConfirmationService.class, Set.of("confirmBuyer", "confirmSeller"),
            AuctionClosingService.class, Set.of("closeBatch", "closeOne"),
            DepositSettlementServiceImpl.class, Set.of("topUpToFinalPrice", "refund", "refundLosingDeposits",
                    "transferToSeller", "forfeit"));
    /**
     * 같은 요청을 다시 보내면 별개 명령으로 또 적용되는, 도메인 멱등 장치가 없는 명령.
     * 대기 시간이 지나 503이 나가도 명령은 장부에 남아 실행되므로 클라이언트가 보낸 키로만 재전송을 식별할 수 있다.
     */
    private static final Set<String> CLIENT_KEY_REQUIRED = Set.of("MemberService.chargePoint");
    private static final Map<String, Method> METHODS = new HashMap<>();
    static {
        TARGETS.forEach((type, names) -> {
            for (Method method : type.getDeclaredMethods()) {
                if (names.contains(method.getName())) METHODS.put(key(type, method), method);
            }
        });
    }

    static boolean matches(Class<?> type, Method method) {
        return TARGETS.getOrDefault(type, Set.of()).contains(method.getName());
    }

    static boolean requiresClientKey(String name) { return CLIENT_KEY_REQUIRED.contains(name); }

    static String key(Class<?> type, Method method) { return type.getSimpleName() + "." + method.getName(); }

    static Method method(String key) {
        Method method = METHODS.get(key);
        if (method == null) throw new IllegalStateException("등록되지 않은 금액 처리 명령");
        return method;
    }

    static Object invoke(ApplicationContext context, ObjectMapper mapper, String name, String json) {
        Method method = method(name);
        var args = mapper.readTree(json);
        if (!args.isArray() || args.size() != method.getParameterCount()) throw new IllegalStateException("명령 인자 불일치");
        Object[] values = new Object[args.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = mapper.convertValue(args.get(i), mapper.getTypeFactory().constructType(method.getGenericParameterTypes()[i]));
        }
        try {
            return method.invoke(context.getBean(method.getDeclaringClass()), values);
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException(failure.getCause());
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
