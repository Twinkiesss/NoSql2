# Library Events Service 
##  Содержание
- [Технологии](#технологии)
- [Запуск](#запуск)
- [Учётные записи и роли](#учётные-записи-и-роли)
- [Права пользователей](#права-пользователей)
- [Жизненный цикл заявки](#жизненный-цикл-заявки)
- [Заявки: фильтрация, сортировка и страницы](#заявки-фильтрация-сортировка-и-страницы)
- [Статистика](#статистика)
- [Каталог книг](#каталог-книг)
- [Основные API](#основные-api)
- [Хранилища](#хранилища)
- [Лабораторная работа №3 — ScyllaDB (wide-column)](#лабораторная-работа-3--scylladb-wide-column)
    - [1. Кластер из трёх узлов](#1-кластер-из-трёх-узлов)
    - [2. Модель данных и обоснование](#2-модель-данных-и-обоснование)
    - [3. API журнала](#3-api-журнала)
    - [4. Эксперимент QUORUM и ALL](#4-quorum-и-all)
    - [5. Эксперимент Отказ узла и hinted handoff](#5-отказ-узла-и-hinted-handoff)
    - [6. Эксперимент SSTable: структура и содержимое](#6-sstable-структура-и-содержимое)
    - [7. Нагрузочный эксперимент](#7-нагрузочный-эксперимент)

## Технологии

- Java 17, Spring Boot 3.2, Maven
- Spring Security, HTTP Basic Auth
- Spring Data JPA, PostgreSQL 16
- Etcd 3.5 для временных заявок
- Spring Cache для событий и настроек пользователя
- JUnit 5, Testcontainers

## Запуск

   ```powershell
   docker compose up -d
mvn clean package    
mvn spring-boot:run
   ```

5. Откройте `http://localhost:8081`.

PostgreSQL доступен на `localhost:5432`, Etcd — на `localhost:2379`.

## Учётные записи и роли

При первом запуске автоматически создаётся администратор:

- User ID: `admin`
- Пароль: `admin123`
- Роль: `ADMIN`

Обычная регистрация создаёт пользователя с ролью `USER` (читатель). Перед использованием в реальной среде обязательно измените пароль администратора. Это можно сделать переменными окружения до запуска приложения:

```powershell
$env:APP_ADMIN_USER="admin"
$env:APP_ADMIN_PASSWORD="ваш_сложный_пароль"
mvn spring-boot:run
```

Если администратор с таким ID уже существует в БД, приложение повышает его роль до `ADMIN`, но не перезаписывает его пароль.

## Права пользователей

| Действие | Администратор | Читатель |
|---|---:|---:|
| Просматривать каталог | Да | Да |
| Добавлять / удалять книги | Да | Нет |
| Создавать заявку | Да | Да |
| Просматривать заявки | Все | Только свои |
| Выдавать книгу (`PENDING → CONFIRMED`) | Да | Нет |
| Отменять заявку `PENDING` | Любую | Только свою |
| Принимать возврат (`CONFIRMED → COMPLETED`) | Да | Нет |
| Смотреть статистику заявок | Да | Нет |

Права проверяются на сервере, а не только скрытием кнопок в интерфейсе.

## Жизненный цикл заявки

1. Администратор или читатель создаёт заявку. Она всегда создаётся со статусом `PENDING`.
2. При создании заявки один доступный экземпляр резервируется атомарным SQL-обновлением. Поэтому параллельные заявки не могут зарезервировать один и тот же последний экземпляр.
3. Администратор выдаёт книгу: `PENDING → CONFIRMED`.
4. Администратор принимает книгу обратно: `CONFIRMED → COMPLETED`, экземпляр снова становится доступным.
5. Читатель может отменить только собственную заявку в статусе `PENDING`; администратор может отменить любую заявку `PENDING`. При отмене резерв снимается.
6. Отменённые заявки не удаляются — они остаются в истории и статистике.

## Заявки: фильтрация, сортировка и страницы

`GET /api/orders/page` принимает параметры:

- `status` — `PENDING`, `CONFIRMED`, `COMPLETED`, `CANCELLED` (необязательный);
- `page` — номер страницы с нуля;
- `size` — размер страницы (1–100);
- `sort` — `createdAt`, `updatedAt`, `status`, `userId`, `eventId`;
- `direction` — `asc` или `desc`.

Для читателя сервер автоматически ограничивает результат его собственными заявками. Администратор получает заявки всех пользователей.

## Статистика

Администратор видит раскрываемый блок статистики. Запрос к БД выполняется только при первом раскрытии. Результат кэшируется в памяти приложения на 30 секунд. Кнопка «Обновить из БД» принудительно пересчитывает статистику.

Endpoint: `GET /api/statistics/orders?refresh=false|true` (только ADMIN).

## Каталог книг

У книги есть рейтинг от 0 до 5, список жанров, `schemaVersion` и `lastChangedAt`. Жанры передаются массивом строк, в форме интерфейса вводятся через запятую.

## Основные API

- `POST /api/auth/register` — регистрация читателя
- `POST /api/auth/login` — проверка учётных данных
- `GET /api/auth/me` — текущий пользователь и роль
- `GET /api/events` — каталог
- `POST /api/events` — создать книгу (ADMIN)
- `GET /api/orders` — заявки текущего пользователя или все заявки администратора
- `GET /api/orders/page` — фильтрация, сортировка, пагинация
- `POST /api/orders` — создать заявку (ADMIN и USER)
- `PUT /api/orders/{id}/status?status=CONFIRMED|CANCELLED` — выдать или отменить (ADMIN); читателю разрешена только отмена своей PENDING-заявки
- `POST /api/orders/{id}/return` — принять возврат (ADMIN)
- `GET /api/statistics/orders` — статистика (ADMIN)
- `/api/temp-requests` — временные заявки в Etcd

## Хранилища

- PostgreSQL: книги, пользователи, настройки, заявки и жанры.
- Etcd: временные заявки с автоматическим истечением lease.
- Кэш Spring: часто запрашиваемые книги и настройки; статистика заявок имеет TTL 30 секунд.


---

# Лабораторная работа №3 — ScyllaDB (wide-column)

В третьей лабораторной добавлен журнал действий пользователей в ScyllaDB. Существующие PostgreSQL, Etcd, CouchDB, кэш и CRUD API не заменяются. Запись действия по-прежнему сохраняется в CouchDB, а затем дополнительно отправляется в ScyllaDB. Если ScyllaDB временно недоступна, ошибка журналирования фиксируется в логе и не ломает CRUD-запросы первых лабораторных.

## 1. Кластер из трёх узлов

Требуется Docker с доступной памятью. Кластер использует replication factor 3 и NetworkTopologyStrategy для `datacenter1`.

```powershell
docker compose up -d postgres etcd couchdb1 couchdb2 scylla1 scylla2 scylla3
docker compose ps
```

Дождитесь готовности узлов. Проверка:

```powershell
docker exec library-scylla1 nodetool status
docker exec library-scylla1 cqlsh -e "DESCRIBE KEYSPACES"
```

В выводе `nodetool status` должны отображаться три узла со статусом `UN`. Если узел ещё присоединяется, подождите и повторите проверку. Запуск приложения:

```powershell
mvn clean package
mvn spring-boot:run
```

Настройки подключения задаются через `SCYLLA_CONTACT_POINT`, `SCYLLA_PORT`, `SCYLLA_DATACENTER`, `SCYLLA_KEYSPACE`, `SCYLLA_BUCKET_COUNT` и `SCYLLA_CONSISTENCY`.

## 2. Модель данных и обоснование

Все таблицы ориентированы на конкретные запросы; `ALLOW FILTERING` не используется. Время хранится в UTC.

* `action_by_object_bucket` — обязательный запрос: история действий над объектом за интервал времени.
* `action_by_user_bucket` — история действий пользователя за интервал.
* `action_by_type_bucket` — поиск действий определённого типа за интервал.

В каждой таблице partition key включает дату бакета и хеш идентификатора (а также исходный идентификатор для защиты от коллизий хеша). Размер бакета по числу хеш-корзин по умолчанию равен 16 (`SCYLLA_BUCKET_COUNT`). В таблице объекта ключ партиции: `(bucket_date, object_hash, object_id)`. Clustering columns: `event_time DESC, event_id ASC`; это обеспечивает чтение новых событий первыми и детерминированный порядок при совпадении времени. В таблицах пользователя и типа действия используются аналогичные составные ключи.

Денормализация нужна потому, что ScyllaDB эффективно читает данные по известному partition key. Отдельные таблицы под три шаблона чтения позволяют не фильтровать всю таблицу и не применять `ALLOW FILTERING`. Запись одного действия выполняет три INSERT — по одному в каждую проекцию.

## 3. API журнала

Все endpoints доступны администратору:

* `GET /api/action-history/object/{objectId}?from=2026-11-01T00:00:00Z&to=2026-11-08T23:59:59Z&page=0&size=50` — обязательная история объекта за период, от новых событий к старым.
* `GET /api/action-history/user/{userId}?from=2026-11-01&to=2026-11-08&page=0&size=50`
* `GET /api/action-history/type/VIEW?from=2026-11-01&to=2026-11-08&page=0&size=50`
* `GET /api/action-history/health` — состояние подключения ScyllaDB.

Поддерживаемые автоматически записываемые действия: `CREATE`, `UPDATE`, `DELETE`, `VIEW`. Параметры `page` и `size` задают постраничное чтение. Сервер ограничивает `size` диапазоном 1–200.

## 4. QUORUM и ALL
Все скрипты написаны под виндус, линуксоиды сорян 

возможно понадобится выполнить команду в powershell
```powershell
wsl -d docker-desktop sysctl -w fs.aio-max-nr=1048576
```
Сборка и запуск:
```powershell
mvn clean package -DskipTests
docker compose down
docker compose up -d --build
```
### Провдение эксперимента
1) запуск скрипта:
```powershell
.\scylla-consistency.ps1
```
2) когда скрипт остановится В docker-compose.yml заменить значние SCYLLA_CONSISTENCY на ALL


3) Перезапустить только app:
```powershell
docker compose up -d app
```
4) в окне со скриптом нажать enter

5) можно потом вернуть SCYLLA_CONSISTENCY: QUORUM

### Результаты:
Write: N = 200, Read: N = 100 на каждый прогон.

| Режим | Запись, оп/с | Чтение, оп/с | Ср. задержка, мс | Доступность, % |
|---|---:|---:|---:|---:|
| QUORUM | 363,11 | 10,09 | 99,03 | 100,00 |
| ALL    | 274,19 |  9,71 | 103,08 | 100,00 |
### Выводы
- **Запись**: `ALL` медленнее `QUORUM` (~274 vs ~363 оп/с, −25%) — координатор ждёт подтверждения от 3 реплик вместо 2. Разница в latency записи ожидаемо в пользу QUORUM.
- **Чтение**: скорость почти одинаковая (~10 оп/с у обоих) — узкое место не Scylla, а HTTP-слой (Basic Auth, Spring MVC, JSON, пагинация в памяти). По этой метрике CL не показателен; для чистого read-бенчмарка нужен `cqlsh` или прямой драйвер.
- **Доступность**: 100% у обоих, потому что все 3 узла живы. Разница в доступности проявится только при отказе узла — это **Эксперимент B** (failover + hinted handoff), где `ALL` при 1 down даёт `UnavailableException` (availability → 0%), а `QUORUM` продолжает работать.


## 5. Отказ узла и hinted handoff
### Запуск

```powershell
# обычный прогон: 1000 записей, без очистки
.\scylla-failover.ps1

# с очисткой таблиц (чистые цифры 0 -> 1000)
.\scylla-failover.ps1 -TruncateBefore

# строгая проверка hints через Scylla REST API (sync point)
.\scylla-failover.ps1 -TruncateBefore -StrictHintCheck
```

Скрипт требует `SCYLLA_CONSISTENCY=QUORUM` в `docker-compose.yml` (иначе падает) и 3 × `UN` в `nodetool status`.

### Что делает

1. Снимает baseline `COUNT(*)` на всех трёх узлах + `nodetool netstats`.
2. `docker stop library-scylla3`, ждёт `DN`.
3. Пишет 1000 событий через `POST /api/action-history/benchmark` с `QUORUM` — запись должна пройти (2 из 3 живы).
4. Фиксирует hints (`system.hints`, `netstats`, `tpstats`) и, при `-StrictHintCheck`, создаёт sync point для `scylla3`.
5. `docker start library-scylla3`, ждёт `UN` и готовность CQL.
6. Ждёт дренаж hints, при `-StrictHintCheck` — финальный статус sync point.
7. Снимает финальный `COUNT(*)` на всех узлах, сравнивает, пишет тайминги.

### Результаты

Прогон с `-TruncateBefore` (1000 событий, scylla3 down):

| Момент | scylla1 | scylla2 | scylla3 | nodetool status |
|---|---|---|---|---|
| До отказа | 0 | 0 | 0 | 3 × UN |
| Во время (scylla3 down) | 0 | 0 | DN | 2 UN + 1 DN |
| Запись 1000 (QUORUM) | 1000 | 1000 | DN | 2 UN + 1 DN |
| После возврата scylla3 | 1000 | 1000 | **1000** | 3 × UN |

- Запись при 1 down: `written=1000, throughput=343/s` — QUORUM прошёл без ошибок.
- `system.hints = 0` на живых узлах — ожидаемо: ScyllaDB 5.4 хранит hints в файлах на диске, а не в CQL-таблице; нулевое значение не означает отсутствие hints.
- Строгая проверка через sync point (`-StrictHintCheck`): `SYNC_POINT_CREATE → IN_PROGRESS → DONE` — hints для `scylla3` были и доставлены.
- Финальный `COUNT(*)` на `scylla3` совпал с `scylla1/2` (1000) без `nodetool repair`.
- Время восстановления узла и дренажа hints — в `failover-timings.txt`.

### Выводы

- При отказе одной из трёх реплик (RF=3) запись с `QUORUM` продолжается: 2 из 3 живы, координатор возвращает OK, для мёртвой реплики создаётся hint.
- Hinted handoff доставил накопленные мутации на `scylla3` после его возврата: `COUNT(*)` догнал остальные реплики **без `nodetool repair`**.
- `system.hints` в Scylla 5.4 не отражает реальное состояние hints — для проверки использовать `nodetool netstats` / sync point REST API.
- Ограничение: hinted handoff работает в пределах `max_hint_window_in_ms` (по умолчанию 3 часа). При более длительном отказе hints теряются, и требуется `nodetool repair`.
- `tpstats` (`Large messages Completed`) — накопительный счётчик межузлового трафика, не метрика hints; в отчёт как hints не включать.



## 6. SSTable: структура и содержимое

### Компоненты SSTable

После `nodetool flush` в каталоге таблицы лежит набор файлов с одним префиксом:

- `Data.db` — сами данные (partition key, clustering, значения ячеек);
- `Index.db` — индекс партиций (где партиция лежит в Data.db);
- `Summary.db` — сводный индекс для быстрого поиска;
- `Filter.db` — Bloom filter («есть ли партиция в этом файле?»);
- `Statistics.db` — метаданные (min/max timestamp, размер, tombstone stats);
- `CompressionInfo.db` — параметры сжатия (LZ4);
- `TOC.txt` — список компонентов;
- `Scylla.db` — Scylla-специфичные метаданные.

### Содержимое (scylla sstable dump-data)

Partition key: `2026-11-20:14:failover-object-001` — это `(bucket_date, object_hash, object_id)`.
Clustering: `2026-11-20T12:05:25.282Z:b800fc80-...` — это `(event_time, event_id)`.
Ячейки: `action = "VIEW"`, `user_id = "bench-user"`.
Строки идут по убыванию времени (`12:05:25.282 → 12:05:25.280 → …`) — как задано в `CLUSTERING ORDER BY (event_time DESC, event_id ASC)`.

### Типы ключей (scylla sstable dump-statistics)

- partition key: `(date, int, text)` — совпадает со схемой;
- clustering: `(timestamp DESC, text ASC)` — совпадает со схемой;
- обычные колонки: `action`, `user_id`.

### Статистика (nodetool tablestats)

1 SSTable, 1 partition, сжатие ~32% (ratio 0.675), Bloom filter без ложных срабатываний, tombstone'ов нет.

### Вывод

SSTable — неизменяемый файл: каждый flush/compaction создаёт новый `Data.db`, старый не меняется. Обновления и удаления — это новые SSTable + tombstone-маркеры, физически они удаляются при compaction. Partition key, clustering и порядок строк в `dump-data` совпадают со схемой таблицы.

# 7-нагрузочный-эксперимент
### Методика

Нагрузка — end-to-end через HTTP: `POST /api/events/{ObjectId}/views`.
Скрипт: `scylla-benchmark.ps1`. Параметры: `-Requests 500 -Warmup 20`.

- **Warm-up** (20 запросов) не учитывается — исключает JIT, connection pool, прогрев кэша.
- **Consistency** берётся из `docker exec library-app printenv SCYLLA_CONSISTENCY` и пишется в CSV.
- **Метрики**: throughput (успешные / длительность), average latency, p95, availability.

> Важно: эндпоинт `/views` пишет в **CouchDB + Scylla** (`eventRepository.saveWithRevision` + `actionService.log`),
> поэтому метрики — **end-to-end по приложению**, а не «чистая Scylla». Узкое место — CouchDB и HTTP-слой.

### Результаты
| Запросов | Throughput, оп/с | Ср. задержка, мс | P95, мс | Доступность, % |
|---:|---:|---:|---:|---:|
| 10  | 6,433 | 155,407 | 170,756 | 100 |
| 100 | 6,355 | 157,340 | 203,603 | 100 |
| 500 | 6,571 | 152,182 | 186,721 | 100 |


### Выводы

- При трёх живых узлах доступность 100% у обоих CL — отказ узла не моделировался (это эксперимент B).
- **ALL** ожидаемо медленнее **QUORUM** по throughput: координатор ждёт 3 подтверждения вместо 2.
- Метрики **end-to-end** (HTTP + CouchDB + Scylla), поэтому абсолютные значения throughput ниже, чем у чистой Scylla; для сравнения CL важна **относительная** разница.
- P95 на 500 запросах стабилен; на 100 — шумный (учтено warm-up и объёмом).