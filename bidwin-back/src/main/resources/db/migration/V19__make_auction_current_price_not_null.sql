-- 생성 코드는 항상 시작가를 넣지만, 예전 데이터에 NULL이 남아 있어도 배포가 실패하지 않게 먼저 보정한다.
-- 상향 경매는 V4와 같은 기준(Bid 최고가, 없으면 시작가)을 쓴다.
UPDATE auction AS a
SET a.current_price = COALESCE(
        (SELECT MAX(b.price) FROM bid AS b WHERE b.auction_id = a.id),
        a.start_price
    )
WHERE a.auction_type = 'UP'
  AND a.current_price IS NULL;

-- 하향 경매의 현재가는 시간으로 계산하므로, 저장값은 생성 코드와 같은 시작가로 채운다.
UPDATE auction
SET current_price = start_price
WHERE current_price IS NULL;

ALTER TABLE auction
    MODIFY COLUMN current_price BIGINT NOT NULL;
