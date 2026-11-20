CREATE TABLE story_requests (
 id VARCHAR(36) PRIMARY KEY, created_at DATETIME(6) NOT NULL, expires_at DATETIME(6) NOT NULL,
 provider VARCHAR(16) NOT NULL, model VARCHAR(255) NOT NULL, status VARCHAR(32) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE INDEX idx_story_requests_expiry ON story_requests(expires_at);
CREATE TABLE story_photos (
 id VARCHAR(36) PRIMARY KEY, story_request_id VARCHAR(36) NOT NULL,
 photo_id VARCHAR(32) NOT NULL, upload_order INTEGER NOT NULL, original_image LONGBLOB NOT NULL,
 sha256 VARCHAR(64) NOT NULL, media_type VARCHAR(32) NOT NULL,
 original_width INTEGER NOT NULL, original_height INTEGER NOT NULL,
 oriented_width INTEGER NOT NULL, oriented_height INTEGER NOT NULL, created_at DATETIME(6) NOT NULL,
 CONSTRAINT uq_story_photo_id UNIQUE(story_request_id, photo_id),
 CONSTRAINT uq_story_photo_order UNIQUE(story_request_id, upload_order),
 CONSTRAINT fk_story_photo_request FOREIGN KEY(story_request_id) REFERENCES story_requests(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE story_recommendations (
 id VARCHAR(36) PRIMARY KEY, story_request_id VARCHAR(36) NOT NULL,
 title VARCHAR(512) NOT NULL, summary_json LONGTEXT NOT NULL, render_plan_json LONGTEXT NOT NULL,
 created_at DATETIME(6) NOT NULL, expires_at DATETIME(6) NOT NULL,
 CONSTRAINT fk_story_recommendation_request FOREIGN KEY(story_request_id) REFERENCES story_requests(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE INDEX idx_story_recommendations_request ON story_recommendations(story_request_id);
CREATE INDEX idx_story_recommendations_expiry ON story_recommendations(expires_at);
