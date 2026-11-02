-- মূল স্কিমা (entity অনুযায়ী). Postgres প্রোফাইলে Flyway চালায়, Hibernate শুধু validate করে.
CREATE TABLE school_class (
    id        BIGSERIAL PRIMARY KEY,
    name      VARCHAR(255) NOT NULL,
    sections  VARCHAR(255)
);

CREATE TABLE app_user (
    id             BIGSERIAL PRIMARY KEY,
    roll           VARCHAR(255) NOT NULL UNIQUE,
    name           VARCHAR(255) NOT NULL,
    password_hash  VARCHAR(255) NOT NULL,
    role           VARCHAR(255) NOT NULL,
    class_id       BIGINT,
    section        VARCHAR(255),
    phone          VARCHAR(255)
);

CREATE TABLE exam (
    id            BIGSERIAL PRIMARY KEY,
    title         VARCHAR(255) NOT NULL,
    class_id      BIGINT NOT NULL,
    duration_min  INTEGER NOT NULL,
    per_q         DOUBLE PRECISION NOT NULL,
    neg           DOUBLE PRECISION NOT NULL,
    published     BOOLEAN NOT NULL,
    shuffle       BOOLEAN,
    start_at      TIMESTAMP(6) WITH TIME ZONE,
    end_at        TIMESTAMP(6) WITH TIME ZONE,
    created_at    TIMESTAMP(6) WITH TIME ZONE
);
CREATE INDEX idx_exam_class ON exam (class_id);

CREATE TABLE question (
    id        BIGSERIAL PRIMARY KEY,
    exam_id   BIGINT NOT NULL,
    sort_no   INTEGER NOT NULL,
    text      VARCHAR(1000) NOT NULL,
    opt0      VARCHAR(400),
    opt1      VARCHAR(400),
    opt2      VARCHAR(400),
    opt3      VARCHAR(400),
    correct   INTEGER NOT NULL
);
CREATE INDEX idx_question_exam ON question (exam_id);

CREATE TABLE attempt (
    id            BIGSERIAL PRIMARY KEY,
    exam_id       BIGINT NOT NULL,
    user_id       BIGINT NOT NULL,
    started_at    TIMESTAMP(6) WITH TIME ZONE,
    deadline      TIMESTAMP(6) WITH TIME ZONE,
    submitted_at  TIMESTAMP(6) WITH TIME ZONE,
    status        VARCHAR(255) NOT NULL,
    answers       VARCHAR(2000),
    correct       INTEGER NOT NULL,
    wrong         INTEGER NOT NULL,
    blank         INTEGER NOT NULL,
    marks         DOUBLE PRECISION NOT NULL,
    time_sec      INTEGER NOT NULL,
    shuffle_seed  BIGINT,
    CONSTRAINT uq_attempt_exam_user UNIQUE (exam_id, user_id)
);
CREATE INDEX idx_attempt_status_deadline ON attempt (status, deadline);

CREATE TABLE teacher_class (
    id        BIGSERIAL PRIMARY KEY,
    user_id   BIGINT NOT NULL,
    class_id  BIGINT NOT NULL,
    CONSTRAINT uq_teacher_class UNIQUE (user_id, class_id)
);
