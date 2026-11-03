CREATE TABLE redis_bid_runtime (
    id INT NOT NULL PRIMARY KEY,
    ledger_id CHAR(36) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    last_sequence BIGINT NOT NULL DEFAULT 0
) ENGINE=InnoDB;

INSERT INTO redis_bid_runtime(id, ledger_id) VALUES (1, UUID());

CREATE TABLE redis_bid_command (
    sequence_no BIGINT NOT NULL PRIMARY KEY,
    request_key VARCHAR(180) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
    fingerprint TEXT NOT NULL,
    result_json JSON,
    error_code VARCHAR(64),
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB;
