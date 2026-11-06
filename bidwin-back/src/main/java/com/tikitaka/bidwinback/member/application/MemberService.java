package com.tikitaka.bidwinback.member.application;

import com.tikitaka.bidwinback.member.domain.entity.Member;
import com.tikitaka.bidwinback.member.domain.enums.MemberStatus;
import com.tikitaka.bidwinback.member.domain.exception.MemberException;
import com.tikitaka.bidwinback.member.domain.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

import static com.tikitaka.bidwinback.global.exception.ErrorCode.DUPLICATE_EMAIL;
import static com.tikitaka.bidwinback.global.exception.ErrorCode.DUPLICATE_NICKNAME;
import static com.tikitaka.bidwinback.global.exception.ErrorCode.EMAIL_VERIFICATION_PENDING;
import static com.tikitaka.bidwinback.global.exception.ErrorCode.INVALID_POINT_CHARGE_AMOUNT;
import static com.tikitaka.bidwinback.global.exception.ErrorCode.MEMBER_NOT_ACTIVE;
import static com.tikitaka.bidwinback.global.exception.ErrorCode.MEMBER_NOT_FOUND;
import static com.tikitaka.bidwinback.global.exception.ErrorCode.POINT_CHARGE_AMOUNT_EXCEEDED;
import static com.tikitaka.bidwinback.member.domain.entity.Member.EMAIL_UNIQUE_CONSTRAINT;
import static com.tikitaka.bidwinback.member.domain.entity.Member.NICKNAME_UNIQUE_CONSTRAINT;

@Service
@RequiredArgsConstructor
public class MemberService {

    private static final long POINT_UNIT = 1_000L;
    // 테스트 목적의 무상 충전 기능이라 반복 호출로 잔액이 한없이 불어나는 것까지 막지는
    // 않되, 한 번의 요청이 만들 수 있는 피해 규모는 이 상한으로 제한한다.
    private static final long MAX_POINT_CHARGE_AMOUNT = 100_000_000L;

    private final MemberRepository memberRepository;

    @Transactional(readOnly = true)
    public void validateEmailAvailable(String email) {
        memberRepository.findByEmail(email).ifPresent(member -> {
            if (member.getStatus() == MemberStatus.PENDING) {
                throw new MemberException(EMAIL_VERIFICATION_PENDING);
            }
            throw new MemberException(DUPLICATE_EMAIL);
        });
    }

    @Transactional(readOnly = true)
    public void validateNicknameAvailable(String nickname) {
        if (memberRepository.existsByNickname(nickname)) {
            throw new MemberException(DUPLICATE_NICKNAME);
        }
    }

    @Transactional
    public Member create(
            String email,
            String encodedPassword,
            String name,
            String phoneNumber,
            String nickname
    ) {
        Member member = Member.builder()
                .email(email)
                .password(encodedPassword)
                .name(name)
                .phoneNumber(phoneNumber)
                .nickname(nickname)
                .build();

        try {
            // 트랜잭션 커밋 전에 UNIQUE 제약 위반을 감지하기 위해 즉시 flush한다.
            return memberRepository.saveAndFlush(member);
        } catch (DataIntegrityViolationException exception) {
            throw translateDataIntegrityViolation(exception);
        }
    }

    @Transactional
    public String changeNickname(Long memberId, String nickname) {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new MemberException(MEMBER_NOT_FOUND));

        if (member.getNickname().equals(nickname)) {
            return nickname;
        }

        member.changeNickname(nickname);
        try {
            // UPDATE의 UNIQUE 제약 위반도 서비스 호출 안에서 응답으로 변환한다.
            memberRepository.flush();
        } catch (DataIntegrityViolationException exception) {
            throw translateDataIntegrityViolation(exception);
        }
        return member.getNickname();
    }

    // 알려진 UNIQUE 제약 위반만 회원 중복 예외로 변환한다.
    private RuntimeException translateDataIntegrityViolation(
            DataIntegrityViolationException exception
    ) {
        String constraintName = extractConstraintName(exception);
        if (matchesConstraint(constraintName, EMAIL_UNIQUE_CONSTRAINT)) {
            return new MemberException(DUPLICATE_EMAIL);
        }
        if (matchesConstraint(constraintName, NICKNAME_UNIQUE_CONSTRAINT)) {
            return new MemberException(DUPLICATE_NICKNAME);
        }
        return exception;
    }

    private boolean matchesConstraint(String actualName, String expectedName) {
        if (actualName == null) {
            return false;
        }

        int separatorIndex = actualName.lastIndexOf('.');
        String unqualifiedName = actualName.substring(separatorIndex + 1);
        return expectedName.equalsIgnoreCase(unqualifiedName);
    }

    // 중첩된 DB 예외에서 Hibernate가 추출한 제약조건 이름을 찾는다.
    private String extractConstraintName(Throwable exception) {
        Throwable cause = exception;
        while (cause != null) {
            if (cause instanceof ConstraintViolationException constraintViolation) {
                return constraintViolation.getConstraintName();
            }
            cause = cause.getCause();
        }
        return null;
    }

    @Transactional(readOnly = true)
    public Optional<Member> findByEmail(String email) {
        return memberRepository.findByEmail(email);
    }

    public boolean isActiveWithAuthVersion(Long memberId, long authVersion) {
        return memberRepository.existsByIdAndStatusAndAuthVersion(
                memberId,
                MemberStatus.ACTIVE,
                authVersion
        );
    }

    // 실제 결제 연동 없이 테스트를 위해 잔액을 바로 채워주는 기능이다.
    @Transactional
    public Member chargePoint(Long memberId, long amount) {
        validateChargeAmount(amount);

        int updatedRows = memberRepository.chargePointIfActive(memberId, amount);
        if (updatedRows != 1) {
            // 이 시점엔 이미 로그인 세션으로 존재가 확인된 회원이라, 갱신 실패는 사실상
            // 활성 상태가 아니라는 뜻이다. 그래도 행 자체가 없는 극단적 경우까지 구분해둔다.
            if (memberRepository.findById(memberId).isEmpty()) {
                throw new MemberException(MEMBER_NOT_FOUND);
            }
            throw new MemberException(MEMBER_NOT_ACTIVE, "활성 상태의 회원만 포인트를 충전할 수 있습니다.");
        }

        return memberRepository.findById(memberId)
                .orElseThrow(() -> new MemberException(MEMBER_NOT_FOUND));
    }

    private void validateChargeAmount(long amount) {
        if (amount <= 0 || amount % POINT_UNIT != 0) {
            throw new MemberException(INVALID_POINT_CHARGE_AMOUNT);
        }
        if (amount > MAX_POINT_CHARGE_AMOUNT) {
            throw new MemberException(POINT_CHARGE_AMOUNT_EXCEEDED);
        }
    }
}
