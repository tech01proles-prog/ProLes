# ProLes — статус доработок

Последнее обновление: 2026-09-25

## Репозиторий и правила работы

- Репозиторий: `https://github.com/tech01proles-prog/ProLes`.
- Рабочая ветка: `main`.
- Главная текущая задача: разбить большой `DataRoutes.kt` на тематические route-файлы.
- Переносить за один этап 2–3 route-файла, если все блоки видны полностью.
- Не разрывать `route`, обработчик или вспомогательную функцию.
- Границы удаления определять по тематическим комментариям и `route(...)`, а не по номерам строк.
- После каждого пакета запускать компиляцию и проверять уникальность URL.
- После каждого push повторно читать актуальную версию репозитория.
- Этот файл обновлять после каждого завершённого пакета.

## Текущее состояние DataRoutes.kt

- Размер до R9–R11: 144789 байт.
- `IncomeRoutes.kt` создан и зарегистрирован.
- Полностью видны блоки:
  - `/api/v1/positions`;
  - `/api/v1/users`;
  - `/api/v1/vacations`;
  - `/api/v1/dayoffs`.
- Доступное представление обрывается внутри
  `PUT /api/v1/business-trips`.
- Последняя безопасная граница текущего пакета:
  непосредственно перед `BUSINESS TRIPS`.

## Рефакторинг DataRoutes.kt

| Пакет | Файл | Краткое назначение | Статус |
|---|---|---|---|
| R1 | `DataRouteSupport.kt` | Общие session, UUID и subproject helpers | APPLIED |
| R2 | `FcmRoutes.kt` | Регистрация и удаление FCM-токенов | APPLIED |
| R3 | `TimeEntryRoutes.kt` | Учёт рабочего времени и часы | APPLIED |
| R4 | `ProjectCostRoutes.kt` | Себестоимость и затраты проектов | APPLIED |
| R5 | `ProjectRoutes.kt` | CRUD проектов и подпроектов | APPLIED |
| R6 | `DataRouteDtos.kt` | Перенос локальных DTO из агрегатора | DEFERRED |
| R7 | `ExpenseRoutes.kt` | Расходы, категории и чеки | APPLIED |
| R8 | `IncomeRoutes.kt` | Доходы и привязка к подпроектам | APPLIED |
| R9 | `PositionRoutes.kt` | Иерархия должностей | READY TO EXTRACT |
| R10 | `UserRoutes.kt` | Сотрудники, профиль и удаление пользователя | READY TO EXTRACT |
| R11 | `AbsenceRoutes.kt` | Отпуска и выходные | READY TO EXTRACT |
| R12 | `BusinessTripRoutes.kt` | Командировки, маршруты и суточные | BLOCKED: виден не весь PUT |
| R13 | `PayrollRoutes.kt` | Зарплата, начисления, штрафы и экспорт | TODO |
| R14 | `TicketRoutes.kt` | Билеты, вложения и чеки | TODO |
| R15 | `NotificationRoutes.kt` | Уведомления и пользовательские настройки | TODO |
| R16 | `PermissionRoutes.kt` | Права ролей и индивидуальные overrides | TODO |
| R17 | `VacationRoutes.kt` | Не используется отдельно: включён в R11 | MERGED INTO R11 |
| R18 | `ReferenceRoutes.kt` | Справочники и небольшие API | TODO |
| R19 | `DataRoutes.kt` | Оставить только регистрацию модулей | TODO |

## Функциональные задачи сервера

| Пакет | Краткое назначение | Статус |
|---|---|---|
| D1 | Учёт часов и проверка подпроектов | APPLIED |
| D2 | Проекты и технический отдел | APPLIED |
| D3 | Расходы, scope, категории и чеки | APPLIED; TESTS TODO |
| D4 | Доходы и подпроекты | APPLIED; TESTS TODO |
| D5 | Командировки, даты и суточные | APPLIED; TESTS TODO |
| D6 | Поле `isRemote` и расчёт налоговой нагрузки | PARTIAL |
| D7 | Payroll, штрафы, удержания и налоги | TODO |
| D8 | Баланс и идемпотентность операций | PARTIAL |
| D9 | Билеты и привязка к подпроектам | TODO |
| D10 | Удаление файлов ТНПА после удаления проекта | TODO |
| D11 | Удаление файлов чеков после удаления записи | TODO |

## Платформа и база данных

| Пакет | Краткое назначение | Статус |
|---|---|---|
| P1 | `PlatformRoutes.kt` и работа через `SessionManager` | FILE PRESENT; REVIEW TODO |
| A1 | Проверить регистрацию всех routes и таблиц | PARTIAL |
| M1 | PostgreSQL-миграция новой схемы | TODO |
| M2 | Backfill существующих данных | TODO |
| M3 | Проверка миграции на чистой базе | TODO |
| M4 | Проверка миграции на существующей базе | TODO |

## PRO-Chat

| Пакет | Краткое назначение | Статус |
|---|---|---|
| C1 | AES-GCM шифрование сообщений и файлов | TODO |
| C2 | REST API и cursor pagination | TODO |
| C3 | WebSocket-доставка сообщений | TODO |
| C4 | Вложения размером до 100 MB | TODO |
| C5 | Проверка прав и доступа к диалогам | TODO |

## WEB

| Пакет | Краткое назначение | Статус |
|---|---|---|
| W1 | API-клиент и TypeScript-типы | TODO |
| W2 | Страница календаря часов | TODO |
| W3 | Интерфейс payroll | TODO |
| W4 | Управление и файлы ТНПА | TODO |
| W5 | CRUD подпроектов | TODO |
| W6 | Интерфейс расходов и чеков | TODO |
| W7 | Доходы и командировки | TODO |
| W8 | Себестоимость проектов | TODO |
| W9 | Интерфейс PRO-Chat | TODO |
| W10 | Пагинация и оптимизация запросов | TODO |
| W11 | Production build WEB | TODO |

## Android

| Пакет | Краткое назначение | Статус |
|---|---|---|
| AN1 | Найти весь pipeline обработки фотографий | TODO |
| AN2 | Удалить повторное Bitmap/JPEG-сжатие | TODO |
| AN3 | Загружать исходные байты файла | TODO |
| AN4 | Сохранять MIME type и исходное имя | TODO |
| AN5 | Ограничить потребление памяти при загрузке | TODO |
| AN6 | Выполнить Android build и smoke test | TODO |

## Текущий пакет R9–R11

- Создать `PositionRoutes.kt`.
- Перенести полный `/api/v1/positions`.
- Создать `UserRoutes.kt`.
- Перенести полный `/api/v1/users`.
- Исправить двойную запись email при создании пользователя.
- Создать `AbsenceRoutes.kt`.
- Перенести полный `/api/v1/vacations`.
- Перенести полный `/api/v1/dayoffs`.
- Добавить `positionRoutes()` после `incomeRoutes()`.
- Добавить `userRoutes()` после `positionRoutes()`.
- Добавить `absenceRoutes()` после `userRoutes()`.
- Удалить старые четыре route-блока из `DataRoutes.kt`.
- Удалить неиспользуемые expense-only helpers из `DataRoutes.kt`.
- Не изменять неполный блок `/api/v1/business-trips`.
- Запустить `compileKotlin`.
- Проверить уникальность URL.
- Выполнить commit и push в `main`.

## Тестирование

| Пакет | Краткое назначение | Статус |
|---|---|---|
| T1 | `compileKotlin` после каждого route-пакета | TODO |
| T2 | Проверка отсутствия дублирующихся URL | TODO |
| T3 | Полный набор SERVER tests | TODO |
| T4 | Smoke test авторизации и сессий | TODO |
| T5 | Тесты CRUD проектов и подпроектов | TODO |
| T6 | Тесты расходов и доходов | TODO |
| T7 | Тесты отпусков и выходных | TODO |
| T8 | Тесты командировок | TODO |
| T9 | Тесты payroll и баланса | TODO |
| T10 | Проверка директорских расходов и прав | TODO |
| T11 | WEB build | TODO |
| T12 | Android build | TODO |
| T13 | Проверка вложений PRO-Chat до 100 MB | TODO |

## Известные замечания

- `TASK_STATUS.md` до этого обновления отставал от кода.
- `DataRouteDtos.kt` пока не создан; DTO всё ещё частично находятся в
  `DataRoutes.kt`.
- В `DataRoutes.kt` остались expense-only helpers после извлечения R7.
- `CrudResult` нельзя удалять до проверки оставшихся маршрутов.
- `checkSession`, `json` и UUID helpers пока используются ниже.
- В `POST /users` email записывается дважды; вторая запись отменяет
  fallback `login@proles.local`.
- Удаление пользователя физически удаляет большой набор связанных данных.
- Удаление проекта и чеков требует отдельной очистки файлов.
- Нельзя переносить `BusinessTripRoutes.kt`, пока полностью не виден PUT
  и конец тематического блока.

## Следующая точка продолжения

После применения R9–R11 и push повторно прочитать уменьшенный
`DataRoutes.kt`.

Если `/api/v1/business-trips` виден полностью, включить его в следующий
пакет. В тот же пакет добавить ещё один или два полностью видимых
тематических route-файла, предпочтительно payroll и tickets.