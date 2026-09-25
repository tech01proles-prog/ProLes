# ProLes — статус доработок

Последнее обновление: 2026-09-25

## Репозиторий и правила работы

- Репозиторий: `https://github.com/tech01proles-prog/ProLes`.
- Рабочая ветка: `main`.
- Главная текущая задача: разбить `DataRoutes.kt` на тематические
  route-файлы.
- За один этап переносить 2–4 route-файла, если их блоки видны полностью.
- Не разрывать `route`, HTTP-обработчик, DTO или вспомогательную функцию.
- Границы удаления определять по комментариям и `route(...)`, а не по
  номерам строк.
- При извлечении сначала сохранять бизнес-логику, меняя только импорты,
  область видимости и имена локальных helpers.
- После каждого пакета запускать `compileKotlin`.
- После каждого пакета проверять уникальность URL.
- После каждого push повторно читать актуальную ветку `main`.
- Обновлять этот файл после каждого завершённого пакета.

## Текущее состояние DataRoutes.kt

- Размер до текущего пакета: 70822 байта.
- Зарегистрированы модули:
  - `fcmRoutes`;
  - `timeEntryRoutes`;
  - `projectCostRoutes`;
  - `projectRoutes`;
  - `expenseRoutes`;
  - `incomeRoutes`;
  - `positionRoutes`;
  - `userRoutes`;
  - `absenceRoutes`;
  - `businessTripRoutes`;
  - `notificationRoutes`;
  - `personalTimesheetRoutes`.
- Полностью видны и готовы к извлечению:
  - `/api/v1/notification-preferences`;
  - `/api/v1/rbac`;
  - `/api/v1/tickets`;
  - `/api/v1/payroll`.
- Текущий пакет создаёт четыре route-файла.
- После пакета необходимо повторно определить полный список маршрутов,
  оставшихся в `DataRoutes.kt`.

## Рефакторинг DataRoutes.kt

| Пакет | Файл | Краткое назначение | Статус |
|---|---|---|---|
| R1 | `DataRouteSupport.kt` | Общие session, UUID и subproject helpers | APPLIED |
| R2 | `FcmRoutes.kt` | Регистрация и удаление FCM-токенов | APPLIED |
| R3 | `TimeEntryRoutes.kt` | Учёт рабочего времени и часы | APPLIED |
| R4 | `ProjectCostRoutes.kt` | Себестоимость и затраты проектов | APPLIED |
| R5 | `ProjectRoutes.kt` | CRUD проектов и подпроектов | APPLIED |
| R6 | `DataRouteDtos.kt` | Общий DTO-файл | CANCELLED: DTO переносятся тематически |
| R7 | `ExpenseRoutes.kt` | Расходы, категории и чеки | APPLIED |
| R8 | `IncomeRoutes.kt` | Доходы и привязка к подпроектам | APPLIED |
| R9 | `PositionRoutes.kt` | Иерархия должностей | APPLIED |
| R10 | `UserRoutes.kt` | Сотрудники, профиль и удаление пользователя | APPLIED |
| R11 | `AbsenceRoutes.kt` | Отпуска и выходные | APPLIED |
| R12 | `BusinessTripRoutes.kt` | Командировки, маршруты и суточные | APPLIED |
| R13 | `PayrollRoutes.kt` | Зарплата, компоненты, расчёты и экспорт | READY TO EXTRACT |
| R14 | `TicketRoutes.kt` | Билеты, получатели, файлы и чеки | READY TO EXTRACT |
| R15 | `NotificationRoutes.kt` | Уведомления и отметки о прочтении | APPLIED |
| R16 | `PermissionRoutes.kt` | Роли, права и пользовательские overrides | READY TO EXTRACT |
| R17 | `VacationRoutes.kt` | Отдельный файл не нужен | MERGED INTO R11 |
| R18 | `ReferenceRoutes.kt` | Справочники и небольшие API | TODO |
| R19 | `DataRoutes.kt` | Оставить только регистрацию модулей | TODO |
| R20 | `PersonalTimesheetRoutes.kt` | Персональный табель | APPLIED |
| R21 | `NotificationPreferenceRoutes.kt` | Каналы и настройки уведомлений | READY TO EXTRACT |

## Текущий пакет R13/R14/R16/R21

- Создать `NotificationPreferenceRoutes.kt`.
- Перенести `/api/v1/notification-preferences`.
- Перенести два DTO настроек уведомлений.
- Создать `PermissionRoutes.kt`.
- Перенести `/api/v1/rbac`.
- Перенести DTO обновления ролей и пользовательских overrides.
- Создать `TicketRoutes.kt`.
- Перенести `/api/v1/tickets`.
- Перенести request/response DTO билетов.
- Создать `PayrollRoutes.kt`.
- Перенести `/api/v1/payroll`.
- Перенести DTO расчёта и экспорта зарплаты.
- Добавить четыре регистрации после `personalTimesheetRoutes()`.
- Удалить четыре старых блока из `DataRoutes.kt`.
- Удалить перенесённые DTO из `DataRoutes.kt`.
- Удалить `taxInclusiveCost`, если после переноса он не используется.
- Исключить межфайловые обращения к private session helpers.
- Запустить `compileKotlin`.
- Проверить уникальность URL.
- Выполнить commit и push в `main`.

## Функциональные задачи сервера

| Пакет | Краткое назначение | Статус |
|---|---|---|
| D1 | Учёт часов и проверка подпроектов | APPLIED |
| D2 | Проекты и технический отдел | APPLIED |
| D3 | Расходы, scope, категории и чеки | APPLIED; TESTS TODO |
| D4 | Доходы и подпроекты | APPLIED; TESTS TODO |
| D5 | Командировки, даты и суточные | APPLIED; TESTS TODO |
| D6 | Поле `isRemote` и налоговая нагрузка | PARTIAL |
| D7 | Payroll, штрафы, удержания и налоги | PARTIAL; REVIEW TODO |
| D8 | Баланс и идемпотентность операций | PARTIAL |
| D9 | Билеты и привязка к подпроектам | PARTIAL; REVIEW TODO |
| D10 | Удаление файлов ТНПА после удаления проекта | TODO |
| D11 | Удаление файлов чеков после удаления записи | TODO |
| D12 | Устранить вложенные транзакции в tickets/payroll | TODO |
| D13 | Проверить лимиты и имена загружаемых файлов | TODO |
| D14 | Проверить HTML-экранирование данных в email | TODO |

## Платформа и база данных

| Пакет | Краткое назначение | Статус |
|---|---|---|
| P1 | `PlatformRoutes.kt` и работа через `SessionManager` | FILE PRESENT; REVIEW TODO |
| A1 | Проверить регистрацию всех routes и таблиц | PARTIAL |
| A2 | Проверить private/internal helpers между route-файлами | IN PROGRESS |
| M1 | PostgreSQL-миграция новой схемы | TODO |
| M2 | Backfill существующих данных | TODO |
| M3 | Проверка миграции на чистой базе | TODO |
| M4 | Проверка миграции на существующей базе | TODO |
| M5 | Проверить индексы внешних ключей и частых фильтров | TODO |

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

## Тестирование

| Пакет | Краткое назначение | Статус |
|---|---|---|
| T1 | `compileKotlin` после каждого route-пакета | REQUIRED |
| T2 | Проверка отсутствия дублирующихся URL | REQUIRED |
| T3 | Полный набор SERVER tests | TODO |
| T4 | Smoke test авторизации и сессий | TODO |
| T5 | CRUD проектов и подпроектов | TODO |
| T6 | Расходы и доходы | TODO |
| T7 | Отпуска и выходные | TODO |
| T8 | Командировки | TODO |
| T9 | Payroll и баланс | TODO |
| T10 | Директорские расходы и права | TODO |
| T11 | RBAC roles и overrides | TODO |
| T12 | Tickets: upload, download, view и delete | TODO |
| T13 | Настройки уведомлений и Telegram linking | TODO |
| T14 | WEB production build | TODO |
| T15 | Android build | TODO |
| T16 | Вложения PRO-Chat до 100 MB | TODO |

## Известные замечания

- `TASK_STATUS.md` отставал от фактического кода после нескольких пакетов.
- `DataRouteDtos.kt` не нужен: DTO переносятся в тематические файлы.
- `DataRoutes.kt` всё ещё содержит DTO и общие helpers старого монолита.
- `checkNotificationSession` нельзя вызывать из других файлов, если он
  объявлен `private` в `NotificationRoutes.kt`.
- В tickets есть вложенный `transaction` внутри внешнего `transaction`.
- Ticket upload принимает Base64 в JSON, что увеличивает память и размер
  запроса; позже перейти на streaming multipart.
- Имена загружаемых ticket-файлов требуют нормализации.
- HTML-письмо бухгалтеру включает пользовательский текст без явного
  экранирования.
- Удаление проекта, билета или чека должно удалять связанные файлы только
  после успешной транзакции БД.
- Payroll требует отдельной проверки формул, валют и налоговой нагрузки.
- `PlatformRoutes.kt` требует ревью на дублирование URL с извлечёнными
  модулями.

## Следующая точка продолжения

После применения R13/R14/R16/R21 и push:

- повторно прочитать уменьшенный `DataRoutes.kt`;
- получить полный список оставшихся `route("/api/v1/...")`;
- вынести следующие 2–4 полностью видимых тематических блока;
- перенести оставшиеся DTO в соответствующие файлы;
- после извлечения последнего блока превратить `DataRoutes.kt` в чистый
  агрегатор;
- затем выполнить полный аудит уникальности URL, компиляцию и server tests.