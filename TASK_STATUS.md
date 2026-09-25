# ProLes — статус доработок

Последнее обновление: 2026-09-25

## Репозиторий и правила работы

- Репозиторий: `https://github.com/tech01proles-prog/ProLes`.
- Рабочая ветка: `main`.
- `TASKS.txt`: основной файл с начальными требованиями.
- `DataRoutes.kt` разделён на тематические route-файлы.
- Следующий этап: стабилизация SERVER, затем доработка WEB и Android.
- Изменения объединять по 2–4 связанных пакета.
- Не менять бизнес-логику одновременно с крупным структурным переносом.
- Для денежных операций валидировать конечность, знак, валюту и период.
- Для файлов валидировать размер, имя, MIME и выполнять очистку при сбое.
- Этот файл обновлять после каждого завершённого пакета.

## Завершённый рефакторинг маршрутов

| Пакет | Файл | Краткое назначение | Статус |
|---|---|---|---|
| R1 | `DataRouteSupport.kt` | Общие session, UUID и subproject helpers | APPLIED |
| R2 | `FcmRoutes.kt` | Регистрация и удаление FCM-токенов | APPLIED |
| R3 | `TimeEntryRoutes.kt` | Учёт рабочего времени и часы | APPLIED |
| R4 | `ProjectCostRoutes.kt` | Себестоимость и затраты проектов | APPLIED |
| R5 | `ProjectRoutes.kt` | CRUD проектов и подпроектов | APPLIED |
| R6 | `DataRouteDtos.kt` | Общий DTO-файл | CANCELLED: DTO распределены тематически |
| R7 | `ExpenseRoutes.kt` | Расходы, категории и чеки | APPLIED |
| R8 | `IncomeRoutes.kt` | Доходы и подпроекты | APPLIED |
| R9 | `PositionRoutes.kt` | Иерархия должностей | APPLIED |
| R10 | `UserRoutes.kt` | Пользователи и профиль | APPLIED |
| R11 | `AbsenceRoutes.kt` | Отпуска и выходные | APPLIED |
| R12 | `BusinessTripRoutes.kt` | Командировки и суточные | APPLIED |
| R13 | `PayrollRoutes.kt` | Зарплата, компоненты и экспорт | APPLIED |
| R14 | `TicketRoutes.kt` | Билеты, получатели, файлы и чеки | APPLIED |
| R15 | `NotificationRoutes.kt` | Уведомления и прочтение | APPLIED |
| R16 | `PermissionRoutes.kt` | Роли, права и overrides | APPLIED |
| R17 | `VacationRoutes.kt` | Отдельный файл не нужен | MERGED INTO R11 |
| R18 | `ReferenceRoutes.kt` | Отдельный файл пока не нужен | DEFERRED |
| R19 | `DataRoutes.kt` | Чистый агрегатор route-модулей | APPLIED; CLEANUP DONE |
| R20 | `PersonalTimesheetRoutes.kt` | Персональный табель | APPLIED |
| R21 | `NotificationPreferenceRoutes.kt` | Настройки уведомлений | APPLIED |

## Текущий пакет стабилизации

| Пакет | Краткое назначение | Статус |
|---|---|---|
| A3 | Очистить `DataRoutes.kt` до минимального агрегатора | DONE |
| D7A | Валидация payroll-периодов, дат и статусов | DONE |
| D12 | Удалить вложенную transaction в ticket upload | DONE |
| D13 | Ограничить и нормализовать ticket-файлы | DONE |
| D14 | Экранировать пользовательские данные в ticket email | DONE |
| D15 | Компенсационная очистка ticket-файлов при сбое БД | DONE |

## Функциональные задачи SERVER

| Пакет | Краткое назначение | Статус |
|---|---|---|
| D1 | Учёт часов и проверка подпроектов | APPLIED |
| D2 | Проекты и технический отдел | APPLIED |
| D3 | Расходы, scope, категории и чеки | APPLIED;  |
| D4 | Доходы и подпроекты | APPLIED;  |
| D5 | Командировки, даты и суточные | APPLIED;  |
| D6 | `isRemote` и налоговая нагрузка | PARTIAL |
| D7 | Payroll, штрафы, удержания и налоги | PARTIAL |
| D8 | Баланс и идемпотентность | PARTIAL |
| D9 | Билеты и подпроекты | PARTIAL |
| D10 | Удаление файлов ТНПА после удаления проекта | TODO |
| D11 | Удаление файлов чеков после удаления записи | TODO |
| D12 | Устранить вложенные transaction в tickets | DONE |
| D13 | Лимиты, имена и MIME ticket-файлов | DONE |
| D14 | HTML-экранирование ticket email | DONE |
| D15 | Очистка файлов при неуспешной ticket-транзакции | DONE |
| D16 | Перевести ticket upload с Base64 на streaming multipart | TODO |
| D17 | Устранить N+1 запросы в tickets/payroll | TODO |
| D18 | Проверить валютную агрегацию payroll | TODO |
| D19 | Проверить переходы payroll-статусов | TODO |
| D20 | Проверить конкурентный расчёт salary record | TODO |

## Платформа и база данных

| Пакет | Краткое назначение | Статус |
|---|---|---|
| P1 | `PlatformRoutes.kt` через `SessionManager` | FILE PRESENT; REVIEW TODO |
| P2 | Разделить `PlatformRoutes.kt` на тематические файлы | TODO |
| A1 | Проверить регистрацию всех routes и таблиц | PARTIAL |
| A2 | Проверить private/internal helpers между файлами | IN PROGRESS |
| A3 | Удалить мёртвые imports/helpers из агрегатора | DONE |
| A4 | Проверить дублирование URL | TODO |
| M1 | PostgreSQL-миграция новой схемы | TODO |
| M2 | Backfill существующих данных | TODO |
| M3 | Проверка миграции на чистой базе | TODO |
| M4 | Проверка миграции на существующей базе | TODO |
| M5 | Индексы внешних ключей и частых фильтров | TODO |
| M6 | Уникальный индекс idempotency key | TODO |
| M7 | Проверить каскады и порядок удаления зависимостей | TODO |

## PRO-Chat

| Пакет | Краткое назначение | Статус |
|---|---|---|
| C1 | AES-GCM шифрование сообщений и файлов | TODO |
| C2 | REST API и cursor pagination | TODO |
| C3 | WebSocket-доставка сообщений | TODO |
| C4 | Вложения до 100 MB | TODO |
| C5 | Проверка доступа к диалогам | TODO |
| C6 | Ограничение частоты и размера сообщений | TODO |
| C7 | Тестирование восстановления соединения | TODO |

## WEB

| Пакет | Краткое назначение | Статус |
|---|---|---|
| W1 | API-клиент и TypeScript-типы | TODO |
| W2 | Календарь часов | TODO |
| W3 | Интерфейс payroll | TODO |
| W4 | Управление и ТНПА | TODO |
| W5 | CRUD подпроектов | TODO |
| W6 | Расходы и чеки | TODO |
| W7 | Доходы и командировки | TODO |
| W8 | Себестоимость проектов | TODO |
| W9 | PRO-Chat UI | TODO |
| W10 | Пагинация и оптимизация | TODO |
| W11 | Production build WEB | TODO |
| W12 | Обработка API-ошибок и истечения сессии | TODO |
| W13 | Проверка загрузки больших файлов | TODO |

## Android

| Пакет | Краткое назначение | Статус |
|---|---|---|
| AN1 | Найти pipeline обработки фотографий | TODO |
| AN2 | Удалить повторное Bitmap/JPEG-сжатие | TODO |
| AN3 | Загружать исходные байты | TODO |
| AN4 | Сохранять MIME и исходное имя | TODO |
| AN5 | Ограничить потребление памяти | TODO |
| AN6 | Android build и smoke test | TODO |
| AN7 | Обработка ошибок загрузки и retry | TODO |
| AN8 | Проверка истечения сессии | TODO |

## Тестирование (НЕ ДЕЛАЕМ ПОКА НЕ ПОПРОСЯТ)

| Пакет | Краткое назначение                     | Статус   |
|---|----------------------------------------|----------|
| T1 |                                        | DONE     |
| T2 | Проверка уникальности URL              | REQUIRED |
| T3 | Полный набор SERVER tests              | TODO     |
| T4 | Авторизация и сессии                   | TODO     |
| T5 | Проекты и подпроекты                   | TODO     |
| T6 | Расходы и доходы                       | TODO     |
| T7 | Отпуска и выходные                     | TODO     |
| T8 | Командировки                           | TODO     |
| T9 | Payroll и баланс                       | TODO     |
| T10 | Директорские расходы и права           | TODO     |
| T11 | RBAC roles и overrides                 | TODO     |
| T12 | Tickets: upload, download, view и delete | TODO     |
| T13 | Настройки уведомлений и Telegram       | TODO     |
| T14 | WEB production build                   | TODO     |
| T15 | Android build                          | TODO     |
| T16 | PRO-Chat attachments до 100 MB         | TODO     |
| T17 | Ticket size, filename и rollback tests | TODO     |
| T18 | Payroll validation и status tests      | TODO     |

## Известные дефекты и риски

- Ticket upload записывает файл до полной валидации запроса.
- Основное имя ticket-файла не нормализуется.
- Ticket upload не ограничивает размер декодированного файла.
- Ticket upload открывает вложенную Exposed transaction.
- Некорректный Base64 чека игнорируется.
- Пользовательский текст вставляется в HTML email без экранирования.
- Файлы могут остаться на диске при сбое транзакции БД.
- Ticket upload через Base64 расходует значительно больше памяти.
- Payroll принимает произвольный статус salary record.
- Payroll экспорт суммирует расходы разных валют в одно число.
- `PlatformRoutes.kt` необходимо проверить на пересечение URL.
- Удаление ТНПА и чеков требует согласованной очистки файлов.
- Отсутствует подтверждённый полный набор server integration tests.

## Следующая точка продолжения

После применения A3, D7A и D12–D15:

- выполнить аудит URL и регистрации `platformRoutes()`;
- исправить физическое удаление ТНПА и чеков;
- затем перейти к миграциям и WEB-клиенту.