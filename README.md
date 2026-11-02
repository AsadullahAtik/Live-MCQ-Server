# Live MCQ Server (Spring Boot)

অনলাইন পরীক্ষা ও মূল্যায়ন সিস্টেমের সার্ভার। একসাথে অনেক ছাত্র পরীক্ষা দিতে পারে (মাল্টি-থ্রেড), REST API + JWT।

## চালানোর নিয়ম
দরকার: **Java 21** এবং **Maven**।

```bash
mvn test                 # মূল্যায়ন/র‍্যাংক/লক এর টেস্ট
mvn spring-boot:run      # সার্ভার চালু (http://localhost:8080)
```
প্রথমবার ডেমো ডেটা তৈরি হয়: অ্যাডমিন `admin` / `admin`, ছাত্র `250112` / `1234` (নবম শ্রেণি, ক)।
ডাটাবেস ডিফল্টে H2 (ফাইল: `./data`)। PostgreSQL এ চালাতে: `mvn spring-boot:run -Dspring-boot.run.profiles=postgres`
(`application-postgres.properties` এ ইউজার/পাসওয়ার্ড মিলিয়ে নিন, ডাটাবেস `livemcq` আগে বানাতে হবে)।

## টেস্ট
`mvn test` চালায়:
- `core/CoreTest` — Grader, Ranker, KeyedLock (২০০ থ্রেড), Shuffler, Scope।
- `service/ServiceFlowTest` — আসল service কোড মেমরির নকল repository দিয়ে (Spring/ডাটাবেস ছাড়া, কয়েক সেকেন্ড):
  shuffle ঘুরে এলে নম্বর ঠিক, চালিয়ে গেলে একই ক্রম, সম্পাদনার নিয়ম, সময়সূচি, sweeper, শিক্ষকের সীমা,
  শিক্ষক ব্যবস্থাপনা, ছাত্র রিপোর্ট, ৫০ বার ডাবল-স্টার্ট, ৪০ জন একসাথে শুরু ও ডাবল-জমা।
- `it/ApiIntegrationTest` — আসল Spring context + মেমরির H2 + MockMvc: লগইন/JWT, ত্রুটির JSON, shuffle-সহ পুরো ছাত্র-যাত্রা, ভূমিকা/শ্রেণি-সীমা, শিক্ষক ব্যবস্থাপনা, শ্রেণি/শাখা/ভর্তি।
- `it/LiveWebSocketTest` — আসল পোর্টে WebSocket: handshake প্রত্যাখ্যান, কে কোন ইভেন্ট পায় (admin/teacher/ছাত্র/অন্য শ্রেণি), sweeper-এর অটো-জমা ইভেন্ট, সংযোগ বন্ধে online কমা।
- দ্রষ্টব্য: `it/*` টেস্টগুলো লেখা ও টাইপ-চেক হয়েছে, কিন্তু যে পরিবেশে লেখা হয়েছে সেখানে Maven চলে না, তাই এখনো চালিয়ে দেখা হয়নি। প্রথম `mvn test`-এ কিছু ভাঙলে লগ পাঠালে ঠিক করে দেওয়া যাবে।

## Docker দিয়ে চালানো (সার্ভার + PostgreSQL একসাথে)
```bash
docker compose up --build
```
প্রথমবার কয়েক মিনিট লাগে। সার্ভার http://localhost:8080 এ চালু, ডেটা `pgdata` ভলিউমে থাকে।
পাসওয়ার্ড ও JWT গোপন কথা বদলাতে `.env` ফাইলে `DB_PASSWORD=...` ও `JWT_SECRET=...` (কমপক্ষে ৩২ অক্ষর) দিন।
সার্ভারের `SPRING_DATASOURCE_*` পরিবেশ চলকগুলো `application-postgres.properties` এর মানকে ছাপিয়ে যায় (Spring-এর নিয়ম)।

## লোড টেস্ট (একসাথে অনেক ছাত্র)
সার্ভার চালু রেখে অন্য টার্মিনালে:
```bash
java tools/LoadTest.java http://localhost:8080 50
```
৫০ জন ভার্চুয়াল ছাত্র একসাথে লগইন → পরীক্ষা শুরু (ডাবল-ক্লিকসহ) → উত্তর সেভ → জমা (ডাবল-ক্লিকসহ)।
শেষে দেখায়: সবাই সফল কি না, ফলাফল তালিকায় ঠিক ৫০টি সারি কি না, র‍্যাংকের ক্রম ঠিক কি না।

## API সংক্ষেপে
সব `/api/**` এ `Authorization: Bearer <token>` লাগে (শুধু `/api/auth/**` ও `/api/health` ছাড়া)।

| মেথড ও পথ | কাজ |
|---|---|
| POST `/api/auth/login` `{roll,password}` | লগইন → token |
| POST `/api/auth/register` | ছাত্র রেজিস্ট্রেশন |
| GET `/api/auth/classes` | শ্রেণির তালিকা (লগইন ছাড়া) |
| GET `/api/me` | নিজের তথ্য |
| GET `/api/exams` | নিজের শ্রেণির পরীক্ষা (চলমান আগে, পুরনো পরে) |
| POST `/api/exams/{id}/start` | পরীক্ষা শুরু → প্রশ্ন, deadline, serverNow (আবার ডাকলে চলমানটাই ফেরত) |
| PUT `/api/attempts/{id}/answers` `{answers:[0,-1,2..]}` | অটোসেভ (-১ = উত্তর নেই) |
| POST `/api/attempts/{id}/submit` | জমা (বারবার ডাকলেও একবারই গণ্য) |
| GET `/api/results/{examId}` | নিজের বিস্তারিত ফলাফল (প্রকাশের পর) |
| GET `/api/exams/{id}/leaderboard` | লিডারবোর্ড (প্রকাশের পর) |
| GET/POST `/api/admin/classes` | শ্রেণি |
| POST `/api/admin/classes/{id}/sections` `{name}` | শ্রেণিতে নতুন শাখা |
| GET `/api/admin/students` | সব ছাত্রের তালিকা |
| GET/POST `/api/admin/exams`, GET `/api/admin/exams/{id}` | পরীক্ষা তৈরি/তালিকা/বিস্তারিত + প্রশ্নভিত্তিক বিশ্লেষণ |
| POST `/api/admin/exams/{id}/publish` , `/unpublish` | ফলাফল প্রকাশ/প্রত্যাহার |
| GET `/api/admin/exams/{id}/results` | সবার ফলাফল (সঠিক/ভুল/ফাঁকা/সময়সহ) |
| PUT `/api/admin/exams/{id}` | পরীক্ষা সম্পাদনা (কেউ শুরু করার আগে ও প্রকাশের আগে, নইলে ৪০৯) |
| DELETE `/api/admin/exams/{id}` | পরীক্ষা মুছুন |
| GET `/api/admin/students/{id}/report` | একজন ছাত্রের সব পরীক্ষার ফল |
| PUT `/api/admin/students/{id}/enroll` `{classId,section}` | ছাত্রের শ্রেণি/শাখা বদল |

`POST /api/admin/exams` এ ঐচ্ছিক ফিল্ড: `shuffle` (true হলে প্রতি ছাত্রের আলাদা প্রশ্ন/অপশন ক্রম), `startAt`, `endAt` (ISO সময়, যেমন `2026-11-02T04:00:00Z`).

## ভূমিকা (Role): অ্যাডমিন / শিক্ষক / ছাত্র
- **অ্যাডমিন:** সব শ্রেণি; শ্রেণি/শাখা বানানো, ছাত্র শ্রেণি বদল, শিক্ষক ব্যবস্থাপনা শুধু সে-ই পারে।
- **শিক্ষক:** শুধু নিজের দায়িত্বের শ্রেণির পরীক্ষা বানানো/বদলানো/প্রকাশ/মোছা, ফলাফল ও ছাত্র দেখা; অন্য শ্রেণিতে ৪০৩। লাইভ ইভেন্টও শুধু নিজের শ্রেণির।
- সব সিদ্ধান্ত এক জায়গায়: `service/AccessService` + `core/Scope` (টেস্ট করা)।
- শিক্ষকের শ্রেণি বদলালে চলমান WebSocket সংযোগের ইভেন্ট-তালিকা বদলায় না; আবার লগইন করলে নতুন।
- ডেমো শিক্ষক: `t1` / `1234` (নবম শ্রেণির দায়িত্বে; পুরনো ডাটাবেসেও চালুর সময় তৈরি হয়)।

| মেথড ও পথ (শুধু অ্যাডমিন) | কাজ |
|---|---|
| GET/POST `/api/admin/teachers` | শিক্ষকের তালিকা / নতুন `{roll,name,password,classIds}` |
| PUT `/api/admin/teachers/{id}/classes` `{classIds}` | দায়িত্বের শ্রেণি বদল |
| DELETE `/api/admin/teachers/{id}` | শিক্ষক মোছা |

## লাইভ ইভেন্ট (WebSocket)
`ws://localhost:8080/ws` — JWT দিতে হয় (`Authorization: Bearer ...` হেডার অথবা `?token=...`)।
ইভেন্ট সব JSON, `type` দিয়ে চেনা যায়:

| type | কে পায় | মানে |
|---|---|---|
| `exam_created`, `exam_updated`, `exam_deleted` | অ্যাডমিন + ওই শ্রেণির শিক্ষক ও ছাত্র | পরীক্ষা বদলেছে |
| `published`, `unpublished` | অ্যাডমিন + ওই শ্রেণির শিক্ষক ও ছাত্র | ফলাফল প্রকাশ/প্রত্যাহার |
| `attempt_started`, `attempt_submitted`, `attempt_auto_submitted` | অ্যাডমিন + ওই শ্রেণির শিক্ষক | কে শুরু/জমা দিল (নামসহ) |

ইভেন্টে শুধু "কী বদলেছে" থাকে; ক্লায়েন্ট দরকার হলে REST থেকে নতুন ডেটা আনে।

## প্রশ্ন/অপশন এলোমেলো (shuffle)
- প্রতি attempt-এ একটা `shuffleSeed` সংরক্ষিত হয়; seed থেকে ক্রম আবার বানানো যায় (`core/Shuffler`), তাই চালিয়ে গেলেও একই ক্রম।
- ডাটাবেসে উত্তর সবসময় **আসল ক্রমে** থাকে, তাই মূল্যায়ন/বিশ্লেষণ/রিভিউ বদলায়নি। পর্দার ক্রম ↔ আসল ক্রমের রূপান্তর শুধু `Shuffler.Plan`-এ।
- ফলাফলের রিভিউ আসল ক্রমে দেখানো হয়।

## পরীক্ষার সময়সূচি
`startAt` এর আগে শুরু করা যায় না (ছাত্রের তালিকায় `upcoming`)। `endAt` এর পর নতুন কেউ শুরু করতে পারে না, আর চলমান পরীক্ষার deadline `endAt` পর্যন্ত ছেঁটে যায়।

## নিয়ম (ওয়েব ডেমোর মতোই)
- নম্বর = সঠিক × প্রতি প্রশ্ন − ভুল × কাটা নম্বর (০ এর নিচে নয়)।
- র‍্যাংক: নম্বর বেশি আগে, সমান হলে কম সময় আগে; নম্বর ও সময় সমান হলে একই র‍্যাংক, পরেরটি বাদ।
- ফলাফল ছাত্ররা দেখে শুধু অ্যাডমিন প্রকাশ করার পর।

## থ্রেডিং কীভাবে নিরাপদ
- Tomcat প্রতিটি রিকোয়েস্টে আলাদা থ্রেড দেয়।
- `core/KeyedLock`: একই ছাত্র+পরীক্ষা (বা একই attempt) এর কাজ একটার পর একটা; আলাদা ছাত্র সমান্তরালে।
- ডাটাবেসে unique `(exam_id, user_id)`: শেষ ভরসা।
- সময় সার্ভারের ঘড়িতে। `AttemptService.sweep()` প্রতি ৫ সেকেন্ডে সময়-শেষ attempt স্বয়ংক্রিয় জমা করে (সার্ভার রিস্টার্টের পরও কাজ করে)।

## ফোল্ডার
```
src/main/java/com/livemcq/
  core/        Grader, Ranker, KeyedLock, Answers, Shuffler   (Spring ছাড়া, সহজে টেস্টযোগ্য)
  domain/      JPA entity
  repo/        Spring Data repository
  service/     Auth, Jwt, Exam, Attempt, Result
  live/        LiveHub (সংযোগ ও ইভেন্ট), WebSocket handler/config/handshake
  controller/  Auth, Admin, Student
  web/         DTO, Interceptor (JWT), Error handler, CORS
tools/LoadTest.java
```

## পরের ধাপ
refresh token, প্রশ্ন bank (বিষয়/অধ্যায়)।


## ডাটাবেস migration (Flyway)
- PostgreSQL প্রোফাইলে স্কিমা আসে `src/main/resources/db/migration/V1__init.sql` থেকে; Hibernate `ddl-auto=validate` — entity বদলালে নতুন `V2__...sql` লিখতে হবে, নাহলে সার্ভার চালুই হবে না (ইচ্ছাকৃত)।
- খালি ডাটাবেসে V1 চলে। আগে `ddl-auto=update` দিয়ে বানানো ডাটাবেসে `baseline-on-migrate` V1 বাদ দিয়ে শুরু করে।
- H2 (ডিফল্ট ও টেস্ট) আগের মতোই `ddl-auto=update`, Flyway বন্ধ।
- V1 entity দেখে হাতে লেখা, আসল Postgres-এ এখনো চালানো হয়নি। প্রথম `docker compose up`-এ validate-এর ত্রুটি এলে লগ পাঠাও।


## আসল ব্যবহারের সেটিং (নিরাপত্তা)
| environment | কাজ |
|---|---|
| `APP_SEED_DEMO=false` | ডেমো ছাত্র/শিক্ষক/পরীক্ষা বসে না (docker-compose-এ ডিফল্ট false) |
| `ADMIN_PASSWORD` | ফাঁকা ডাটাবেসে বসানো `admin`-এর পাসওয়ার্ড (কমপক্ষে ৬ অক্ষর; demo=false হলে বাধ্যতামূলক) |
| `REGISTRATION_CODE` | দিলে ছাত্র-নিবন্ধনে এই কোড লাগে; ফাঁকা = সবাই নিবন্ধন করতে পারে |
| `JWT_SECRET`, `DB_PASSWORD` | অবশ্যই বদলান |

`docker compose` চালানোর আগে `.env.example` কপি করে `.env` বানিয়ে মান বসান।
আগে থেকে চলা ডাটাবেসে `admin/admin` থাকলে অ্যাপের "পাসওয়ার্ড বদল" দিয়ে সাথে সাথে বদলে ফেলুন।

### পাসওয়ার্ড API
- `POST /api/me/password` `{oldPassword, newPassword}` — যে কেউ নিজের (পুরনোটি মিলতে হবে)
- `PUT /api/admin/users/{id}/password` `{newPassword}` — শুধু অ্যাডমিন (ছাত্র/শিক্ষক ভুলে গেলে)
- নিবন্ধন: `POST /api/auth/register`-এ `code` ফিল্ড (কোড চালু থাকলে)


## Windows-এ এক কমান্ডে সব পরীক্ষা
`tools\windows\run-all-tests.bat` ডাবল-ক্লিক করুন (JDK 17+ ও Maven লাগবে)। এটি `mvn test` চালায়, মেমরির ডাটাবেসে সার্ভার চালু করে Student/Teacher/Admin-এর পুরো যাত্রা আসল HTTP-তে পরীক্ষা করে, সার্ভার বন্ধ করে প্রজেক্ট ফোল্ডারে `report.txt` লেখে। ফাইলটি Claude-কে পাঠালে ব্যর্থ অংশ ঠিক করে দেওয়া হবে।
