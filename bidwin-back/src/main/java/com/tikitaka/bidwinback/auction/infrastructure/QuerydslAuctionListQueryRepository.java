package com.tikitaka.bidwinback.auction.infrastructure;

import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.Expression;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.Predicate;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.core.types.dsl.NumberExpression;
import com.querydsl.core.types.dsl.Param;
import com.querydsl.core.types.dsl.StringExpression;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import com.tikitaka.bidwinback.auction.domain.entity.DownAuction;
import com.tikitaka.bidwinback.auction.domain.entity.QImage;
import com.tikitaka.bidwinback.auction.domain.entity.UpAuction;
import com.tikitaka.bidwinback.auction.domain.enums.AuctionCategory;
import com.tikitaka.bidwinback.auction.domain.enums.AuctionListStatusFilter;
import com.tikitaka.bidwinback.auction.domain.enums.AuctionSort;
import com.tikitaka.bidwinback.auction.domain.enums.AuctionStatus;
import com.tikitaka.bidwinback.auction.domain.enums.AuctionType;
import com.tikitaka.bidwinback.auction.domain.repository.AuctionListQueryRepository;
import com.tikitaka.bidwinback.auction.domain.repository.BidRepository;
import com.tikitaka.bidwinback.auction.domain.repository.dto.AuctionBidSummary;
import com.tikitaka.bidwinback.auction.domain.repository.dto.AuctionListRow;
import com.tikitaka.bidwinback.auction.domain.repository.dto.AuctionListSearchCondition;
import com.tikitaka.bidwinback.auction.domain.repository.dto.AuctionPriceCursor;
import com.tikitaka.bidwinback.auction.domain.repository.dto.AuctionPriceSnapshot;
import com.tikitaka.bidwinback.auction.domain.repository.dto.DownAuctionPriceCandidate;
import jakarta.persistence.EntityManager;
import org.hibernate.jpa.HibernateHints;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.tikitaka.bidwinback.auction.domain.entity.QAuction.auction;
import static com.tikitaka.bidwinback.auction.domain.entity.QBid.bid;
import static com.tikitaka.bidwinback.auction.domain.entity.QDownAuction.downAuction;
import static com.tikitaka.bidwinback.auction.domain.entity.QImage.image;
import static com.tikitaka.bidwinback.member.domain.entity.QMember.member;

@Repository
public class QuerydslAuctionListQueryRepository implements AuctionListQueryRepository {

    private static final Param<LocalDateTime> PRICE_AS_OF = new Param<>(
            LocalDateTime.class,
            "auctionListPriceAsOf"
    );
    // {0}=minimumPrice, {1}=startPrice, {2}=startedAt, {3}=priceAt, {4}=priceDropInterval, {5}=dropPrice
    private static final String DOWN_CURRENT_PRICE_TEMPLATE = """
            greatest(
                {0},
                {1} - floor(
                    greatest(timestampdiff(minute, {2}, {3}), 0) / greatest({4}, 1)
                ) * {5}
            )
            """;
    private static final Comparator<AuctionColumnSortCandidate> LATEST_ORDER =
            Comparator.comparing(AuctionColumnSortCandidate::sortAt)
                    .reversed()
                    .thenComparing(
                            Comparator.comparingLong(AuctionColumnSortCandidate::auctionId)
                                    .reversed()
                    );
    private static final Comparator<AuctionColumnSortCandidate> DEADLINE_ORDER =
            Comparator.comparing(AuctionColumnSortCandidate::sortAt)
                    .thenComparingLong(AuctionColumnSortCandidate::auctionId);
    private static final Comparator<AuctionRecommendedCandidate> RECOMMENDED_ORDER =
            Comparator.comparingLong(AuctionRecommendedCandidate::bidCount)
                    .reversed()
                    .thenComparing(
                            Comparator.comparingLong(AuctionRecommendedCandidate::auctionId)
                                    .reversed()
                    );
    private static final Comparator<DownAuctionPriceCandidateDetails> DOWN_START_PRICE_ORDER =
            Comparator.comparingLong(DownAuctionPriceCandidateDetails::startPrice)
                    .reversed()
                    .thenComparing(
                            Comparator.comparingLong(
                                    DownAuctionPriceCandidateDetails::auctionId
                            ).reversed()
                    );

    private final JPAQueryFactory queryFactory;
    private final BidRepository bidRepository;

    public QuerydslAuctionListQueryRepository(
            EntityManager entityManager,
            BidRepository bidRepository
    ) {
        this.queryFactory = new JPAQueryFactory(entityManager);
        this.bidRepository = bidRepository;
    }

    @Override
    public long count(AuctionListSearchCondition condition) {
        Long count = queryFactory
                .select(auction.count())
                .from(auction)
                .where(searchPredicate(condition))
                .setHint(
                        HibernateHints.HINT_QUERY_DATABASE,
                        "idx_auction_count"
                )
                .fetchOne();
        return count != null ? count : 0L;
    }

    @Override
    public List<AuctionListRow> findPage(
            AuctionListSearchCondition condition,
            long offset,
            int limit
    ) {
        List<AuctionListMetrics> metrics = switch (condition.sort()) {
            case DEADLINE, LATEST -> findPageByColumnSort(condition, offset, limit);
            case RECOMMENDED -> findPageByRecommendedSort(
                    condition,
                    offset,
                    limit
            );
            case PRICE_LOW, PRICE_HIGH ->
                    throw new IllegalArgumentException("가격순은 Top-K 조회를 사용해야 합니다.");
        };
        return findRows(metrics);
    }

    @Override
    public List<AuctionPriceSnapshot> findUpPriceSnapshots(
            AuctionListSearchCondition condition,
            int limit
    ) {
        List<Predicate> statusPredicates = statusBranchPredicates(condition);
        return IntStream.range(0, statusPredicates.size())
                .mapToObj(branchIndex -> findUpPriceSnapshotsInBranch(
                        condition,
                        statusPredicates.get(branchIndex),
                        limit,
                        upPriceIndexHint(condition, branchIndex)
                ))
                .flatMap(List::stream)
                .sorted(priceSnapshotOrder(condition.sort()))
                .limit(limit)
                .toList();
    }

    private List<AuctionPriceSnapshot> findUpPriceSnapshotsInBranch(
            AuctionListSearchCondition condition,
            Predicate statusPredicate,
            long limit,
            String indexHint
    ) {
        return queryFactory
                .select(new QUpAuctionPriceSnapshotDetails(
                        auction.id,
                        auction.currentPrice
                ))
                .from(auction)
                .where(
                        searchPredicate(condition, statusPredicate),
                        auction.instanceOf(UpAuction.class)
                )
                .orderBy(upPriceOrderBy(condition.sort()))
                .limit(limit)
                .setHint(
                        HibernateHints.HINT_QUERY_DATABASE,
                        indexHint
                )
                .fetch()
                .stream()
                .map(UpAuctionPriceSnapshotDetails::toSnapshot)
                .toList();
    }

    @Override
    public List<DownAuctionPriceCandidate> findDownPriceCandidates(
            AuctionListSearchCondition condition,
            AuctionPriceCursor cursor,
            int limit
    ) {
        List<DownAuctionPriceCandidateDetails> details = switch (condition.sort()) {
            case PRICE_LOW -> findDownCandidatesByMinimumPrice(condition, cursor, limit);
            case PRICE_HIGH -> findDownCandidatesByStartPrice(condition, cursor, limit);
            case RECOMMENDED, DEADLINE, LATEST ->
                    throw new IllegalArgumentException("가격 정렬이 아닙니다: " + condition.sort());
        };
        return toDownPriceCandidates(details);
    }

    @Override
    public List<DownAuctionPriceCandidate> findRemainingDownPriceCandidatesAtBound(
            AuctionListSearchCondition condition,
            AuctionPriceCursor cursor
    ) {
        List<DownAuctionPriceCandidateDetails> details = switch (condition.sort()) {
            case PRICE_LOW -> findRemainingDownCandidatesAtMinimumPrice(condition, cursor);
            case PRICE_HIGH -> findRemainingDownCandidatesAtStartPrice(condition, cursor);
            case RECOMMENDED, DEADLINE, LATEST ->
                    throw new IllegalArgumentException("가격 정렬이 아닙니다: " + condition.sort());
        };
        return toDownPriceCandidates(details);
    }

    private List<DownAuctionPriceCandidateDetails> findDownCandidatesByMinimumPrice(
            AuctionListSearchCondition condition,
            AuctionPriceCursor cursor,
            int limit
    ) {
        // 최저가는 하위 테이블에만 있으므로 down_auction의 경계 인덱스부터 읽어야
        // 활성 경매 필터를 위한 auction 조인 전에 LIMIT을 향해 순서대로 스캔할 수 있다.
        List<Long> pageCandidateIds = queryFactory
                .select(downAuction.id)
                .from(downAuction)
                .where(
                        downAuctionSearchPredicate(condition),
                        minimumPriceCursorAfter(cursor)
                )
                .orderBy(
                        downAuction.minimumPrice.asc(),
                        downAuction.id.desc()
                )
                .limit(limit)
                .setHint(HibernateHints.HINT_QUERY_DATABASE,
                        "idx_down_auction_minimum_price_id")
                .fetch();

        return candidateQueryFromDownAuction()
                .where(
                        downAuction.id.in(pageCandidateIds)
                )
                .orderBy(
                        downAuction.minimumPrice.asc(),
                        downAuction.id.desc()
                )
                .fetch();
    }

    private List<DownAuctionPriceCandidateDetails> findRemainingDownCandidatesAtMinimumPrice(
            AuctionListSearchCondition condition,
            AuctionPriceCursor cursor
    ) {
        return candidateQueryFromDownAuction()
                .where(
                        downAuctionSearchPredicate(condition),
                        downAuction.minimumPrice.eq(cursor.priceBound()),
                        downAuction.id.lt(cursor.auctionId())
                )
                .orderBy(downAuction.id.desc())
                .fetch();
    }

    // down_auction만 읽으므로 최저가 인덱스가 스캔을 주도한다.
    private JPAQuery<DownAuctionPriceCandidateDetails> candidateQueryFromDownAuction() {
        return queryFactory
                .select(new QDownAuctionPriceCandidateDetails(
                        downAuction.id,
                        downAuction.startPrice,
                        downAuction.minimumPrice,
                        downAuction.startedAt,
                        downAuction.endedAt,
                        downAuction.dropPrice,
                        downAuction.priceDropInterval,
                        downAuction.status,
                        downAuction.completedAt,
                        downAuction.currentPrice
                ))
                .from(downAuction);
    }

    private List<DownAuctionPriceCandidateDetails> findDownCandidatesByStartPrice(
            AuctionListSearchCondition condition,
            AuctionPriceCursor cursor,
            int limit
    ) {
        return mergeAcrossStatusBranches(
                condition,
                limit,
                (statusPredicate, branchLimit) -> findDownStartPriceCandidatesInBranch(
                        condition,
                        cursor,
                        branchLimit,
                        statusPredicate
                ),
                DOWN_START_PRICE_ORDER
        );
    }

    private List<DownAuctionPriceCandidateDetails> findDownStartPriceCandidatesInBranch(
            AuctionListSearchCondition condition,
            AuctionPriceCursor cursor,
            long branchLimit,
            Predicate statusPredicate
    ) {
        return candidateQueryFromAuction(
                searchPredicate(condition, statusPredicate)
        )
                .where(
                        startPriceCursorAfter(cursor)
                )
                .orderBy(
                        auction.startPrice.desc(),
                        auction.id.desc()
                )
                .limit(branchLimit)
                .fetch();
    }

    private List<DownAuctionPriceCandidateDetails> findRemainingDownCandidatesAtStartPrice(
            AuctionListSearchCondition condition,
            AuctionPriceCursor cursor
    ) {
        return candidateQueryFromAuction(searchPredicate(condition))
                .where(
                        auction.startPrice.eq(cursor.priceBound()),
                        auction.id.lt(cursor.auctionId())
                )
                .orderBy(auction.id.desc())
                .fetch();
    }

    // auction을 읽고 down_auction을 조인하므로 시작가 인덱스가 스캔을 주도한다.
    private JPAQuery<DownAuctionPriceCandidateDetails> candidateQueryFromAuction(
            Predicate searchPredicate
    ) {
        return queryFactory
                .select(new QDownAuctionPriceCandidateDetails(
                        auction.id,
                        auction.startPrice,
                        downAuction.minimumPrice,
                        auction.startedAt,
                        auction.endedAt,
                        downAuction.dropPrice,
                        downAuction.priceDropInterval,
                        auction.status,
                        auction.completedAt,
                        auction.currentPrice
                ))
                .from(auction)
                .innerJoin(downAuction).on(downAuction.id.eq(auction.id))
                .where(
                        searchPredicate,
                        auction.instanceOf(DownAuction.class)
                );
    }

    private List<DownAuctionPriceCandidate> toDownPriceCandidates(
            List<DownAuctionPriceCandidateDetails> details
    ) {
        return details.stream()
                .map(DownAuctionPriceCandidateDetails::toCandidate)
                .toList();
    }

    @Override
    public List<AuctionListRow> findRowsByPriceSnapshots(
            List<AuctionPriceSnapshot> snapshots,
            LocalDateTime asOf
    ) {
        if (snapshots.isEmpty()) {
            return List.of();
        }
        List<Long> auctionIds = snapshots.stream()
                .map(AuctionPriceSnapshot::auctionId)
                .toList();
        Map<Long, AuctionBidSummary> bidSummaries = findBidSummariesByAuctionId(
                auctionIds,
                asOf
        );
        List<AuctionListMetrics> metrics = snapshots.stream()
                .map(snapshot -> {
                    AuctionBidSummary bidSummary = bidSummaries.get(snapshot.auctionId());
                    return new AuctionListMetrics(
                            snapshot.auctionId(),
                            snapshot.displayPrice(),
                            bidSummary != null ? bidSummary.bidCount() : 0L
                    );
                })
                .toList();
        return findRows(metrics);
    }

    @Override
    public List<AuctionListRow> findDownRowsByPriceSnapshots(
            List<AuctionPriceSnapshot> snapshots
    ) {
        if (snapshots.isEmpty()) {
            return List.of();
        }
        List<AuctionListMetrics> metrics = snapshots.stream()
                .map(snapshot -> new AuctionListMetrics(
                        snapshot.auctionId(),
                        snapshot.displayPrice(),
                        0L
                ))
                .toList();
        return findRows(metrics);
    }

    private List<AuctionListRow> findRows(List<AuctionListMetrics> metrics) {
        if (metrics.isEmpty()) {
            return List.of();
        }

        List<Long> auctionIds = metrics.stream()
                .map(AuctionListMetrics::auctionId)
                .toList();
        Map<Long, AuctionListDetails> detailsByAuctionId = findDetailsByAuctionId(auctionIds);
        Map<Long, String> thumbnailKeysByAuctionId = findThumbnailKeysByAuctionId(auctionIds);

        return metrics.stream()
                .filter(metric -> detailsByAuctionId.containsKey(metric.auctionId()))
                .map(metric -> toRow(
                        detailsByAuctionId.get(metric.auctionId()),
                        metric,
                        thumbnailKeysByAuctionId.get(metric.auctionId())
                ))
                .toList();
    }

    // 최저가 오름차순이므로 커서 다음 페이지는 더 큰 최저가 쪽이다.
    private BooleanExpression minimumPriceCursorAfter(AuctionPriceCursor cursor) {
        if (cursor == null) {
            return null;
        }
        return downAuction.minimumPrice.gt(cursor.priceBound())
                .or(downAuction.minimumPrice.eq(cursor.priceBound())
                        .and(downAuction.id.lt(cursor.auctionId())));
    }

    // 시작가 내림차순이므로 커서 다음 페이지는 더 작은 시작가 쪽이다.
    private BooleanExpression startPriceCursorAfter(AuctionPriceCursor cursor) {
        if (cursor == null) {
            return null;
        }
        return auction.startPrice.lt(cursor.priceBound())
                .or(auction.startPrice.eq(cursor.priceBound())
                        .and(auction.id.lt(cursor.auctionId())));
    }

    private OrderSpecifier<?>[] upPriceOrderBy(AuctionSort sort) {
        return switch (sort) {
            case PRICE_LOW -> new OrderSpecifier<?>[]{
                    auction.currentPrice.asc(),
                    auction.id.desc()
            };
            case PRICE_HIGH -> new OrderSpecifier<?>[]{
                    auction.currentPrice.desc(),
                    auction.id.desc()
            };
            case RECOMMENDED, DEADLINE, LATEST ->
                    throw new IllegalArgumentException("가격 정렬이 아닙니다: " + sort);
        };
    }

    private Comparator<AuctionPriceSnapshot> priceSnapshotOrder(AuctionSort sort) {
        Comparator<AuctionPriceSnapshot> idDescending = Comparator
                .comparingLong(AuctionPriceSnapshot::auctionId)
                .reversed();
        return switch (sort) {
            case PRICE_LOW -> Comparator
                    .comparingLong(AuctionPriceSnapshot::sortPrice)
                    .thenComparing(idDescending);
            case PRICE_HIGH -> Comparator
                    .comparingLong(AuctionPriceSnapshot::sortPrice)
                    .reversed()
                    .thenComparing(idDescending);
            case RECOMMENDED, DEADLINE, LATEST ->
                    throw new IllegalArgumentException("가격 정렬이 아닙니다: " + sort);
        };
    }

    private String upPriceIndexHint(
            AuctionListSearchCondition condition,
            int branchIndex
    ) {
        AuctionListStatusFilter status = condition.status() != null
                ? condition.status()
                : AuctionListStatusFilter.ACTIVE;
        if (branchIndex > 0) {
            return "idx_auction_count";
        }
        if (status == AuctionListStatusFilter.ACTIVE
                && condition.sort() == AuctionSort.PRICE_HIGH) {
            return "idx_auction_active_current_price_desc_id_desc";
        }
        return switch (condition.sort()) {
            case PRICE_LOW -> "idx_auction_current_price_asc_id_desc";
            case PRICE_HIGH -> "idx_auction_current_price_desc_id_desc";
            case RECOMMENDED, DEADLINE, LATEST ->
                    throw new IllegalArgumentException("가격 정렬이 아닙니다: " + condition.sort());
        };
    }

    private Map<Long, AuctionBidSummary> findBidSummariesByAuctionId(
            List<Long> auctionIds,
            LocalDateTime asOf
    ) {
        return bidRepository.summarizeByAuctionIds(auctionIds, asOf)
                .stream()
                .collect(Collectors.toMap(
                        AuctionBidSummary::auctionId,
                        Function.identity()
                ));
    }

    private List<AuctionListMetrics> findPageByColumnSort(
            AuctionListSearchCondition condition,
            long offset,
            int limit
    ) {
        ColumnSortSpec sortSpec = columnSortSpec(condition.sort());
        List<AuctionColumnSortCandidate> candidates = condition.auctionType() == null
                ? mergeAcrossAuctionTypes(
                        condition,
                        offset,
                        limit,
                        (typedCondition, branchLimit) -> findColumnSortCandidates(
                                typedCondition,
                                0,
                                branchLimit,
                                sortSpec
                        ),
                        sortSpec.comparator()
                )
                : findColumnSortCandidates(condition, offset, limit, sortSpec);
        List<Long> auctionIds = candidates.stream()
                .map(AuctionColumnSortCandidate::auctionId)
                .toList();
        return findMetricsInPageOrder(auctionIds, condition.asOf());
    }

    private List<AuctionColumnSortCandidate> findColumnSortCandidates(
            AuctionListSearchCondition condition,
            long offset,
            long limit,
            ColumnSortSpec sortSpec
    ) {
        // 정렬 인덱스에서 페이지 후보만 먼저 고르고, 집계 쿼리는 선택된 ID에만 수행한다.
        return mergeAcrossStatusBranches(
                condition,
                offset,
                limit,
                (statusPredicate, branchLimit) -> findColumnSortCandidatesInBranch(
                        condition,
                        statusPredicate,
                        branchLimit,
                        sortSpec
                ),
                sortSpec.comparator()
        );
    }

    private List<AuctionColumnSortCandidate> findColumnSortCandidatesInBranch(
            AuctionListSearchCondition condition,
            Predicate statusPredicate,
            long branchLimit,
            ColumnSortSpec sortSpec
    ) {
        return queryFactory
                .select(new QAuctionColumnSortCandidate(
                        auction.id,
                        sortSpec.sortExpression()
                ))
                .from(auction)
                .where(searchPredicate(condition, statusPredicate))
                .orderBy(sortSpec.sortOrder(), sortSpec.idOrder())
                .limit(branchLimit)
                // completed_at 분기별로 정렬에 맞는 복합 인덱스를 사용한다.
                .setHint(
                        HibernateHints.HINT_QUERY_DATABASE,
                        sortSpec.indexHint()
                )
                .fetch();
    }

    private <T> List<T> mergeAcrossStatusBranches(
            AuctionListSearchCondition condition,
            long limit,
            BiFunction<Predicate, Long, List<T>> branchQuery,
            Comparator<T> comparator
    ) {
        return mergeAcrossStatusBranches(condition, 0, limit, branchQuery, comparator);
    }

    private <T> List<T> mergeAcrossStatusBranches(
            AuctionListSearchCondition condition,
            long offset,
            long limit,
            BiFunction<Predicate, Long, List<T>> branchQuery,
            Comparator<T> comparator
    ) {
        // 상태 조건의 OR을 인덱스로 처리할 수 있도록 서로 겹치지 않는 분기로 나눈다.
        // QueryDSL JPA가 UNION ALL을 지원하지 않으므로 각 분기의 후보만 읽고 병합한다.
        long branchLimit = Math.addExact(offset, limit);
        return statusBranchPredicates(condition).stream()
                .flatMap(predicate -> branchQuery.apply(predicate, branchLimit).stream())
                .sorted(comparator)
                .skip(offset)
                .limit(limit)
                .toList();
    }

    private <T> List<T> mergeAcrossAuctionTypes(
            AuctionListSearchCondition condition,
            long offset,
            long limit,
            BiFunction<AuctionListSearchCondition, Long, List<T>> typeQuery,
            Comparator<T> comparator
    ) {
        long branchLimit = Math.addExact(offset, limit);
        return List.of(AuctionType.UP, AuctionType.DOWN).stream()
                .map(auctionType -> conditionWithType(condition, auctionType))
                .flatMap(typedCondition -> typeQuery.apply(typedCondition, branchLimit).stream())
                .sorted(comparator)
                .skip(offset)
                .limit(limit)
                .toList();
    }

    private AuctionListSearchCondition conditionWithType(
            AuctionListSearchCondition condition,
            AuctionType auctionType
    ) {
        return new AuctionListSearchCondition(
                auctionType,
                condition.sort(),
                condition.keyword(),
                condition.status(),
                condition.category(),
                condition.asOf()
        );
    }

    private List<AuctionListMetrics> findMetricsInPageOrder(
            List<Long> auctionIds,
            LocalDateTime asOf
    ) {
        if (auctionIds.isEmpty()) {
            return List.of();
        }

        Map<Long, AuctionListMetrics> metricsByAuctionId = findMetricsByAuctionId(
                auctionIds,
                asOf
        );

        return metricsInOrder(auctionIds, metricsByAuctionId);
    }

    private List<AuctionListMetrics> metricsInOrder(
            List<Long> auctionIds,
            Map<Long, AuctionListMetrics> metricsByAuctionId
    ) {

        return auctionIds.stream()
                .map(auctionId -> {
                    AuctionListMetrics metrics = metricsByAuctionId.get(auctionId);
                    if (metrics == null) {
                        throw new IllegalStateException(
                                "페이지 경매 집계 정보를 조회하지 못했습니다: " + auctionId
                        );
                    }
                    return metrics;
                })
                .toList();
    }

    private Map<Long, AuctionListMetrics> findMetricsByAuctionId(
            List<Long> auctionIds,
            LocalDateTime asOf
    ) {
        AuctionListMetricExpressions expressions = metricExpressions();
        List<AuctionListMetrics> metrics = baseMetricQuery(expressions, asOf)
                .where(auction.id.in(auctionIds))
                .groupBy(expressions.groupByKeys())
                .fetch();

        return metrics.stream()
                .collect(Collectors.toMap(
                        AuctionListMetrics::auctionId,
                        Function.identity()
                ));
    }

    private List<AuctionListMetrics> findPageByRecommendedSort(
            AuctionListSearchCondition condition,
            long offset,
            int limit
    ) {
        List<AuctionRecommendedCandidate> candidates = condition.auctionType() == null
                ? mergeAcrossAuctionTypes(
                        condition,
                        offset,
                        limit,
                        (typedCondition, branchLimit) -> findRecommendedCandidates(
                                typedCondition,
                                0,
                                branchLimit
                        ),
                        RECOMMENDED_ORDER
                )
                : findRecommendedCandidates(condition, offset, limit);
        List<Long> auctionIds = candidates.stream()
                .map(AuctionRecommendedCandidate::auctionId)
                .toList();
        if (auctionIds.isEmpty()) {
            return List.of();
        }

        Map<Long, AuctionListMetrics> metricsByAuctionId =
                findRecommendedMetricsByAuctionId(auctionIds, condition.asOf());
        return metricsInOrder(auctionIds, metricsByAuctionId);
    }

    private List<AuctionRecommendedCandidate> findRecommendedCandidates(
            AuctionListSearchCondition condition,
            long offset,
            long limit
    ) {
        List<Predicate> statusPredicates = statusBranchPredicates(condition);
        if (statusPredicates.size() == 1) {
            return findRecommendedCandidatesInBranch(
                    condition,
                    statusPredicates.getFirst(),
                    offset,
                    limit
            );
        }
        return mergeAcrossStatusBranches(
                condition,
                offset,
                limit,
                (statusPredicate, branchLimit) -> findRecommendedCandidatesInBranch(
                        condition,
                        statusPredicate,
                        0,
                        branchLimit
                ),
                RECOMMENDED_ORDER
        );
    }

    private List<AuctionRecommendedCandidate> findRecommendedCandidatesInBranch(
            AuctionListSearchCondition condition,
            Predicate statusPredicate,
            long offset,
            long limit
    ) {
        return queryFactory
                .select(new QAuctionRecommendedCandidate(
                        auction.id,
                        auction.bidCount
                ))
                .from(auction)
                .where(searchPredicate(condition, statusPredicate))
                .orderBy(auction.bidCount.desc(), auction.id.desc())
                .offset(offset)
                .limit(limit)
                .setHint(
                        HibernateHints.HINT_QUERY_DATABASE,
                        "idx_auction_recommended"
                )
                .fetch();
    }

    private Map<Long, AuctionListMetrics> findRecommendedMetricsByAuctionId(
            List<Long> auctionIds,
            LocalDateTime asOf
    ) {
        NumberExpression<Long> downCurrentPrice = downCurrentPriceExpression();
        NumberExpression<Long> currentPrice = Expressions.cases()
                .when(auction.status.eq(AuctionStatus.COMPLETED))
                .then(auction.currentPrice)
                .when(auction.instanceOf(UpAuction.class))
                .then(auction.currentPrice)
                .otherwise(downCurrentPrice);

        return queryFactory
                .select(new QAuctionListMetrics(
                        auction.id,
                        currentPrice,
                        auction.bidCount
                ))
                .from(auction)
                .leftJoin(downAuction).on(downAuction.id.eq(auction.id))
                .where(auction.id.in(auctionIds))
                .set(PRICE_AS_OF, asOf)
                .fetch()
                .stream()
                .collect(Collectors.toMap(
                        AuctionListMetrics::auctionId,
                        Function.identity()
                ));
    }

    private NumberExpression<Long> downCurrentPriceExpression() {
        Expression<LocalDateTime> priceAt = Expressions.cases()
                .when(auction.endedAt.lt(PRICE_AS_OF))
                .then(auction.endedAt)
                .otherwise(PRICE_AS_OF);
        return Expressions.numberTemplate(
                Long.class,
                DOWN_CURRENT_PRICE_TEMPLATE,
                downAuction.minimumPrice,
                auction.startPrice,
                auction.startedAt,
                priceAt,
                downAuction.priceDropInterval,
                downAuction.dropPrice
        );
    }

    private JPAQuery<AuctionListMetrics> baseMetricQuery(
            AuctionListMetricExpressions expressions,
            LocalDateTime asOf
    ) {
        return queryFactory
                .select(new QAuctionListMetrics(
                        auction.id,
                        expressions.currentPrice(),
                        expressions.bidCount()
                ))
                .from(auction)
                .leftJoin(downAuction).on(downAuction.id.eq(auction.id))
                .leftJoin(bid).on(
                        bid.auction.id.eq(auction.id)
                                .and(bid.createdAt.loe(asOf))
                )
                // LocalDateTime constant는 numberTemplate 안에서 Hibernate 7이 VARCHAR로 해석한다.
                .set(PRICE_AS_OF, asOf);
    }

    private Predicate searchPredicate(AuctionListSearchCondition condition) {
        return searchPredicate(condition, statusPredicate(condition));
    }

    private Predicate searchPredicate(
            AuctionListSearchCondition condition,
            Predicate statusPredicate
    ) {
        return new BooleanBuilder()
                .and(auction.startedAt.loe(condition.asOf()))
                .and(statusPredicate)
                .and(auctionTypeEq(condition.auctionType()))
                .and(categoryEq(condition.category()))
                .and(titleContains(auction.title, condition.keyword()));
    }

    private Predicate downAuctionSearchPredicate(AuctionListSearchCondition condition) {
        return new BooleanBuilder()
                .and(downAuction.startedAt.loe(condition.asOf()))
                .and(downAuctionStatusPredicate(condition))
                .and(condition.category() != null
                        ? downAuction.category.eq(condition.category())
                        : null)
                .and(titleContains(downAuction.title, condition.keyword()));
    }

    private Predicate statusPredicate(AuctionListSearchCondition condition) {
        AuctionListStatusFilter status = condition.status() != null
                ? condition.status()
                : AuctionListStatusFilter.ACTIVE;
        if (condition.sort() == AuctionSort.RECOMMENDED) {
            return status == AuctionListStatusFilter.ENDED
                    ? auction.completedAt.isNotNull()
                            .or(auction.endedAt.loe(condition.asOf()))
                    : auction.completedAt.isNull()
                            .and(auction.endedAt.gt(condition.asOf()));
        }
        return status == AuctionListStatusFilter.ENDED
                ? auction.completedAt.loe(condition.asOf())
                        .or(auction.endedAt.loe(condition.asOf()))
                : auction.completedAt.isNull()
                        .or(auction.completedAt.gt(condition.asOf()))
                        .and(auction.endedAt.gt(condition.asOf()));
    }

    private Predicate downAuctionStatusPredicate(AuctionListSearchCondition condition) {
        AuctionListStatusFilter status = condition.status() != null
                ? condition.status()
                : AuctionListStatusFilter.ACTIVE;
        if (condition.sort() == AuctionSort.RECOMMENDED) {
            return status == AuctionListStatusFilter.ENDED
                    ? downAuction.completedAt.isNotNull()
                            .or(downAuction.endedAt.loe(condition.asOf()))
                    : downAuction.completedAt.isNull()
                            .and(downAuction.endedAt.gt(condition.asOf()));
        }
        return status == AuctionListStatusFilter.ENDED
                ? downAuction.completedAt.loe(condition.asOf())
                        .or(downAuction.endedAt.loe(condition.asOf()))
                : downAuction.completedAt.isNull()
                        .or(downAuction.completedAt.gt(condition.asOf()))
                        .and(downAuction.endedAt.gt(condition.asOf()));
    }

    private List<Predicate> statusBranchPredicates(AuctionListSearchCondition condition) {
        AuctionListStatusFilter status = condition.status() != null
                ? condition.status()
                : AuctionListStatusFilter.ACTIVE;
        if (condition.sort() == AuctionSort.RECOMMENDED) {
            return status == AuctionListStatusFilter.ENDED
                    ? List.of(
                            auction.completedAt.isNotNull(),
                            auction.completedAt.isNull()
                                    .and(auction.endedAt.loe(condition.asOf()))
                    )
                    : List.of(
                            auction.completedAt.isNull()
                                    .and(auction.endedAt.gt(condition.asOf()))
                    );
        }
        return status == AuctionListStatusFilter.ENDED
                ? List.of(
                        auction.completedAt.loe(condition.asOf()),
                        auction.completedAt.isNull()
                                .and(auction.endedAt.loe(condition.asOf())),
                        auction.completedAt.gt(condition.asOf())
                                .and(auction.endedAt.loe(condition.asOf()))
                )
                : List.of(
                        auction.completedAt.isNull()
                                .and(auction.endedAt.gt(condition.asOf())),
                        auction.completedAt.gt(condition.asOf())
                                .and(auction.endedAt.gt(condition.asOf()))
                );
    }

    private BooleanExpression titleContains(StringExpression title, String keyword) {
        // 제목 검색 조건이 없으면 BooleanBuilder.and(null)에서 무시되도록 null을 반환한다.
        if (keyword == null) {
            return null;
        }
        // 컬럼 collation이 utf8mb4_0900_ai_ci(대소문자 무시)라 LOWER() 없이도
        // 대소문자 구분 없이 매칭된다.
        String pattern = "%" + AuctionListKeywordEscaper.escape(keyword) + "%";
        return title.like(pattern, AuctionListKeywordEscaper.LIKE_ESCAPE);
    }

    private BooleanExpression auctionTypeEq(AuctionType auctionType) {
        // 경매 유형 조건이 없으면 BooleanBuilder.and(null)에서 무시되도록 null을 반환한다.
        if (auctionType == null) {
            return null;
        }
        return switch (auctionType) {
            case UP -> auction.instanceOf(UpAuction.class);
            case DOWN -> auction.instanceOf(DownAuction.class);
        };
    }

    private BooleanExpression categoryEq(AuctionCategory category) {
        return category != null ? auction.category.eq(category) : null;
    }

    private ColumnSortSpec columnSortSpec(AuctionSort sort) {
        return switch (sort) {
            case DEADLINE -> new ColumnSortSpec(
                    auction.endedAt,
                    auction.endedAt.asc(),
                    auction.id.asc(),
                    DEADLINE_ORDER,
                    "idx_auction_snapshot_deadline"
            );
            case LATEST -> new ColumnSortSpec(
                    auction.createdAt,
                    auction.createdAt.desc(),
                    auction.id.desc(),
                    LATEST_ORDER,
                    "idx_auction_snapshot_latest"
            );
            case RECOMMENDED, PRICE_LOW, PRICE_HIGH ->
                    throw new IllegalArgumentException("컬럼 정렬이 아닙니다: " + sort);
        };
    }

    private AuctionListMetricExpressions metricExpressions() {
        NumberExpression<Long> bidCount = bid.id.count();
        NumberExpression<Long> upCurrentPrice = bid.price.max().coalesce(auction.startPrice);
        NumberExpression<Long> downCurrentPrice = downCurrentPriceExpression();
        NumberExpression<Long> currentPrice = Expressions.cases()
                .when(auction.status.eq(AuctionStatus.COMPLETED))
                .then(auction.currentPrice)
                .when(auction.instanceOf(UpAuction.class))
                .then(upCurrentPrice)
                .otherwise(downCurrentPrice);
        return new AuctionListMetricExpressions(
                currentPrice,
                bidCount,
                new Expression<?>[]{
                        auction.id,
                        auction.startPrice,
                        auction.startedAt,
                        auction.endedAt,
                        downAuction.minimumPrice,
                        downAuction.dropPrice,
                        downAuction.priceDropInterval
                }
        );
    }

    private Map<Long, AuctionListDetails> findDetailsByAuctionId(List<Long> auctionIds) {
        return queryFactory
                .select(new QAuctionListDetails(
                        auction.id,
                        downAuction.id,
                        auction.title,
                        member.nickname,
                        auction.category,
                        auction.startPrice,
                        auction.endedAt,
                        auction.createdAt,
                        auction.status,
                        auction.currentPrice,
                        auction.revision,
                        auction.startedAt,
                        downAuction.minimumPrice,
                        downAuction.dropPrice,
                        downAuction.priceDropInterval
                ))
                .from(auction)
                .join(auction.seller, member)
                .leftJoin(downAuction).on(downAuction.id.eq(auction.id))
                .where(auction.id.in(auctionIds))
                .fetch()
                .stream()
                .collect(Collectors.toMap(AuctionListDetails::auctionId, Function.identity()));
    }

    private Map<Long, String> findThumbnailKeysByAuctionId(List<Long> auctionIds) {
        QImage firstImage = new QImage("firstImage");
        return queryFactory
                .select(new QAuctionThumbnailDetails(
                        image.auction.id,
                        image.objectKey
                ))
                .from(image)
                .where(
                        image.auction.id.in(auctionIds),
                        image.id.eq(
                                JPAExpressions
                                        .select(firstImage.id.min())
                                        .from(firstImage)
                                        .where(firstImage.auction.id.eq(image.auction.id))
                        )
                )
                .fetch()
                .stream()
                .collect(Collectors.toMap(
                        AuctionThumbnailDetails::auctionId,
                        AuctionThumbnailDetails::objectKey
                ));
    }

    private AuctionListRow toRow(
            AuctionListDetails details,
            AuctionListMetrics metrics,
            String thumbnailObjectKey
    ) {
        if (details == null) {
            throw new IllegalStateException("페이지 경매 정보를 조회하지 못했습니다: " + metrics.auctionId());
        }
        return details.toRow(metrics, thumbnailObjectKey);
    }

    private record AuctionListMetricExpressions(
            NumberExpression<Long> currentPrice,
            NumberExpression<Long> bidCount,
            Expression<?>[] groupByKeys
    ) {
    }

    // DB의 분기별 정렬과 애플리케이션의 병합 정렬은 같은 키와 방향을 사용해야 한다.
    private record ColumnSortSpec(
            Expression<LocalDateTime> sortExpression,
            OrderSpecifier<?> sortOrder,
            OrderSpecifier<?> idOrder,
            Comparator<AuctionColumnSortCandidate> comparator,
            String indexHint
    ) {
    }

}
