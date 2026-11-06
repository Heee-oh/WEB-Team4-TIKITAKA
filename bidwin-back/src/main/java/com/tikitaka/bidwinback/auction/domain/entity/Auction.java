package com.tikitaka.bidwinback.auction.domain.entity;

import com.tikitaka.bidwinback.auction.domain.enums.AuctionCategory;
import com.tikitaka.bidwinback.auction.domain.enums.AuctionStatus;
import com.tikitaka.bidwinback.auction.domain.enums.TradeType;
import com.tikitaka.bidwinback.global.common.entity.BaseTimeEntity;
import com.tikitaka.bidwinback.member.domain.entity.Member;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.SourceType;

import java.time.LocalDateTime;

import static lombok.AccessLevel.PROTECTED;

@Getter
@Entity
@Table(
        name = "Auction",
        indexes = {
                @Index(
                        name = "idx_auction_status_ended_at",
                        columnList = "status, ended_at"
                ),
                @Index(
                        name = "idx_auction_start_price_id",
                        columnList = "auction_type, start_price DESC, id DESC"
                ),
                @Index(
                        name = "idx_auction_current_price_asc_id_desc",
                        columnList = "auction_type, current_price ASC, id DESC"
                ),
                @Index(
                        name = "idx_auction_current_price_desc_id_desc",
                        columnList = "auction_type, current_price DESC, id DESC"
                ),
                @Index(
                        name = "idx_auction_snapshot_price",
                        columnList = "auction_type, completed_at, start_price DESC, "
                                + "id DESC, started_at, ended_at"
                )
        }
)
@Inheritance(strategy = InheritanceType.JOINED)
@DiscriminatorColumn(name = "auction_type", length = 4)
@NoArgsConstructor(access = PROTECTED)
public abstract class Auction extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "seller_id", nullable = false)
    private Member seller;

    @Column(nullable = false, length = 30)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AuctionStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AuctionCategory category;

    @Column(name = "start_price", nullable = false)
    private long startPrice;

    @Column(name = "current_price", nullable = false)
    private long currentPrice;

    @Column(name = "current_bidder_id")
    private Long currentBidderId;

    @Column(name = "sealed_top_price")
    private Long sealedTopPrice;

    @Column(name = "sealed_top_bidder_id")
    private Long sealedTopBidderId;

    // 진행 중에는 공개 입찰 수를, 밀봉입찰 공개 후에는 공개·밀봉 총입찰 수를 누적한다.
    @Column(name = "bid_count", nullable = false)
    private long bidCount;

    // 공개 전 밀봉 입찰 수가 추천순 bid_count에 섞여 노출되지 않도록 별도로 누적한다.
    @Column(name = "sealed_bid_count", nullable = false)
    private long sealedBidCount;

    @Column(name = "ended_at", nullable = false)
    private LocalDateTime endedAt;

    @CreationTimestamp(source = SourceType.DB)
    @Column(
            name = "started_at",
            nullable = false,
            updatable = false,
            columnDefinition = "datetime(6) default current_timestamp(6)"
    )
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    /**
     * 화면에 노출되는 경매 상태가 바뀔 때 같은 트랜잭션에서 증가한다.
     * 클라이언트는 이 값으로 중복·역순 SSE를 버린다.
     */
    @Column(nullable = false)
    private long revision;

    @Enumerated(EnumType.STRING)
    @Column(name = "trade_type", nullable = false)
    private TradeType tradeType;

    @Column(nullable = false, length = 100)
    private String contact;

    protected Auction(
            Member seller,
            String title,
            String description,
            AuctionStatus status,
            AuctionCategory category,
            long startPrice,
            LocalDateTime endedAt,
            TradeType tradeType,
            String contact
    ) {
        this.seller = seller;
        this.title = title;
        this.description = description;
        this.status = status == null ? AuctionStatus.OPEN : status;
        this.category = category;
        this.startPrice = startPrice;
        this.currentPrice = startPrice;
        this.endedAt = endedAt;
        this.tradeType = tradeType;
        this.contact = contact;
    }

    public long getCurrentPrice() {
        return currentPrice;
    }

    public boolean isSealedBidRevealed() {
        return status == AuctionStatus.WINNER_DETERMINING
                || status == AuctionStatus.COMPLETED
                || status == AuctionStatus.UNSOLD;
    }

    public void complete(long finalPrice, LocalDateTime completedAt) {
        complete(finalPrice, completedAt, 0L);
    }

    public void complete(
            long finalPrice,
            LocalDateTime completedAt,
            long additionalBidCount
    ) {
        if (status != AuctionStatus.OPEN && status != AuctionStatus.BID_ONGOING) {
            throw new IllegalStateException("진행 중인 경매만 낙찰 처리할 수 있습니다.");
        }
        if (finalPrice <= 0) {
            throw new IllegalArgumentException("낙찰가는 0보다 커야 합니다.");
        }

        addBidCount(additionalBidCount);
        this.currentPrice = finalPrice;
        this.status = AuctionStatus.COMPLETED;
        this.completedAt = completedAt;
        this.revision++;
    }

    public void markUnsold(LocalDateTime completedAt) {
        markUnsold(completedAt, 0L);
    }

    public void markUnsold(LocalDateTime completedAt, long additionalBidCount) {
        if (status != AuctionStatus.OPEN && status != AuctionStatus.BID_ONGOING) {
            throw new IllegalStateException("진행 중인 경매만 유찰 처리할 수 있습니다.");
        }

        addBidCount(additionalBidCount);
        this.status = AuctionStatus.UNSOLD;
        this.completedAt = completedAt;
        this.revision++;
    }

    private void addBidCount(long additionalBidCount) {
        if (additionalBidCount < 0) {
            throw new IllegalArgumentException("추가 입찰 수는 음수일 수 없습니다.");
        }
        this.bidCount = Math.addExact(this.bidCount, additionalBidCount);
    }
}
