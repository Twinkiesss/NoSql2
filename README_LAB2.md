# Лабораторная работа №2 — CouchDB

Вариант: **3489**

Основной объект — `Event`. В ЛР1 он находился в PostgreSQL; в ЛР2 он перенесён в CouchDB. PostgreSQL и Etcd сохранены для остальных частей приложения.

## Что реализовано

- CouchDB 3.3 как постоянное хранилище событий.
- Два CouchDB-узла: `couchdb1:5984` и `couchdb2:5984`.
- `_rev` при изменении документов и обработка `409 Conflict` с повторной попыткой.
- Вложенная `stateHistory`.
- `lastChange` и `schemaVersion=2`.
- Связанная коллекция `relatedObject`.
- Mango-индексы `event-updated-at` и `event-type-updated-at`.
- Поиск по типу и диапазону даты изменения.
- Сортировка и пагинация.
- Поиск по вложенному `stateHistory.state`.
- `_explain` для исследования Mango-запроса.
- MapReduce view `analytics/user_actions` для статистики действий пользователей.
- `validate_doc_update` в design document.
- Повторяемая миграция документов старой схемы.
- Репликация CouchDB между двумя узлами.
- Etcd сохранён для временных заявок с TTL.
- Etcd используется как TTL-кэш результатов запросов к CouchDB. Инвалидация выполняется через generation key после изменения данных.
- PostgreSQL продолжает хранить пользователей, заказы и настройки.

## Запуск

```powershell
docker compose up -d
mvn clean package
java -jar target/library-events-service-2.0.0.jar
```

Приложение: `http://localhost:8081`

- PostgreSQL: `localhost:5433`
- Etcd: `localhost:2379`
- CouchDB 1: `http://localhost:5984`
- CouchDB 2: `http://localhost:5985`

Логин CouchDB: `admin`
Пароль CouchDB: `admin`

При старте приложения автоматически создаются база `events`, design document, Mango-индексы и выполняется миграция.

## Основные запросы ЛР2

### Поиск по типу и дате

```text
GET /api/events/search?type=book&from=2026-01-01T00:00:00&to=2026-12-31T23:59:59&page=0&size=10&sort=updatedAt&desc=true
```

### Поиск по вложенному полю

```text
GET /api/events/search?state=UPDATED&page=0&size=10
```

### `_explain`

```text
GET /api/events/search/explain?type=book&from=2026-01-01T00:00:00
```

В ответе CouchDB должен показать использованный Mango-индекс.

### Связанные объекты

```text
GET /api/events/{eventId}/related
POST /api/events/{eventId}/related
```

Пример POST:

```json
{
  "title": "Авторская информация",
  "value": "Дополнительные сведения"
}
```

### MapReduce

```text
GET /api/events/analytics/actions
```

### Миграция

```text
POST /api/events/migrate
```

Миграция идемпотентна: уже обновлённые документы повторно не изменяются.

### Репликация

```text
POST /api/events/replicate
```

Запускается непрерывная репликация `couchdb1 -> couchdb2`.

## Проверка CouchDB напрямую

```powershell
curl.exe -u admin:admin http://localhost:5984/events/_design/analytics
curl.exe -u admin:admin http://localhost:5984/events/_index
curl.exe -u admin:admin "http://localhost:5984/events/_design/analytics/_view/user_actions?group=true"
```

## Проверка `_explain`

```powershell
curl.exe -u admin:admin -X POST http://localhost:5984/events/_explain `
  -H "Content-Type: application/json" `
  -d '{"selector":{"type":"event","eventType":"book","updatedAt":{"$gte":"2026-01-01T00:00:00"}},"sort":[{"updatedAt":"asc"}]}'
```

## Конфликт `_rev`

1. Получить один документ два раза.
2. Изменить его первым клиентом и сохранить.
3. Попытаться сохранить старую копию со старым `_rev`.
4. CouchDB вернёт `409 Conflict`.
5. Сервис повторно читает актуальную ревизию и выполняет изменение заново.

Для счётчика просмотров и изменения количества доступных экземпляров также используется optimistic concurrency с повторными попытками.

## Структура документа Event

```json
{
  "_id": "uuid",
  "_rev": "2-...",
  "type": "event",
  "eventType": "book",
  "schemaVersion": 2,
  "title": "Война и мир",
  "description": "Роман-эпопея",
  "author": "Лев Толстой",
  "category": "Классическая литература",
  "availableCopies": 5,
  "viewCount": 0,
  "createdAt": "2026-10-05T13:00:00",
  "updatedAt": "2026-10-05T13:00:00",
  "lastChange": {
    "action": "CREATE",
    "userId": "system",
    "changedAt": "2026-10-05T13:00:00"
  },
  "stateHistory": [
    {
      "state": "CREATED",
      "action": "CREATE",
      "userId": "system",
      "changedAt": "2026-10-05T13:00:00"
    }
  ]
}
```
