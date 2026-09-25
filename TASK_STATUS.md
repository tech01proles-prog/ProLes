# ProLes — статус доработок

Последнее обновление: 2026-09-25

## Репозиторий и правила

- Репозиторий: `https://github.com/tech01proles-prog/ProLes`
- Ветка: `main`.
- `DataRoutes.kt`: 4639 строк, 206 KB.
- Доступное представление заканчивается внутри
  `DELETE /api/v1/expenses/receipt`, после начала запроса
  `ExpenseReceiptsTable.selectAll()`.
- Последний полностью доступный тематический блок:
  `route("/api/v1/projects")`.
- Для удаления используются маркеры комментариев и route, а не номера строк.
- В конце каждого ответа публикуется полный актуальный `TASK_STATUS.md`.

## Рефакторинг маршрутов

| Пакет | Файл | Содержание | Статус |
|---|---|---|---|
| R1 | `DataRouteSupport.kt` | Сессия, UUID, подпроекты, mapper часов | APPLIED |
| R2 | `FcmRoutes.kt` | `/api/v1/fcm` | APPLIED |
| R3 | `TimeEntryRoutes.kt` | `/api/v1/entries` | APPLIED |
| R4 | `ProjectCostRoutes.kt` | `/api/v1/project-costs` | APPLIED |
| R5 | `ProjectRoutes.kt` | `/api/v1/projects` | CODE PROVIDED |
| R6 | `DataRouteDtos.kt` | Локальные DTO маршрутов | CODE PROVIDED |
| R7 | `ExpenseRoutes.kt` | Расходы и чеки | TODO после следующего push |
| R8 | `IncomeRoutes.kt` | Доходы | TODO |
| R9 | `BusinessTripRoutes.kt` | Командировки | TODO |
| R10 | `UserRoutes.kt` | Пользователи и сотрудники | TODO |
| R11 | `PayrollRoutes.kt` | Payroll и компоненты | TODO |
| R12 | `TicketRoutes.kt` | Билеты и чеки | TODO |
| R13 | `NotificationRoutes.kt` | Уведомления | TODO |
| R14 | `PermissionRoutes.kt` | Права ролей и пользователей | TODO |
| R15 | `VacationRoutes.kt` | Отпуска и отгулы | TODO |
| R16 | `ReferenceRoutes.kt` | Справочники и малые API | TODO |
| R17 | `DataRoutes.kt` | Только агрегатор | TODO |

## Функциональные пакеты

| Пакет | Содержание | Статус |
|---|---|---|
| D1 | Часы и подпроекты | APPLIED |
| D2 | Проекты и технический отдел | APPLIED |
| D3 | Расходы, категории, scope, чеки | APPLIED; compile не подтверждён |
| D4 | Доходы и подпроекты | APPLIED; compile не подтверждён |
| D5 | Командировки | APPLIED; compile не подтверждён |
| D6 | `isRemote` | PARTIAL |
| D7 | Payroll, штрафы, удержания, налоги | TODO |
| D8 | Баланс и идемпотентность | PARTIAL |
| D9 | Билеты и подпроекты | TODO |

## Серверные пакеты

| Пакет | Содержание | Статус |
|---|---|---|
| P1 | `PlatformRoutes.kt` под `SessionManager` | Код выдан ранее; проверить применение |
| A1 | Регистрация маршрутов и таблиц | Код выдан ранее; проверить применение |
| M1 | PostgreSQL-миграция и backfill | TODO |
| C1 | AES-GCM PRO-Chat | TODO |
| C2 | REST и cursor pagination чата | TODO |
| C3 | WebSocket чата | TODO |
| C4 | Вложения чата до 100 MB | TODO |
| S1 | Очистка файлов ТНПА | TODO |
| S2 | Очистка файлов чеков | TODO |

## WEB-пакеты

| Пакет | Содержание | Статус |
|---|---|---|
| W1 | API-клиент и типы | TODO |
| W2 | `/hours-calendar` | TODO |
| W3 | `/payroll` | TODO |
| W4 | `/management` и ТНПА | TODO |
| W5 | CRUD подпроектов | TODO |
| W6 | Расходы | TODO |
| W7 | Доходы и командировки | TODO |
| W8 | Себестоимость | TODO |
| W9 | PRO-Chat UI | TODO |
| W10 | Пагинация и оптимизация | TODO |

## Android-пакеты

| Пакет | Содержание | Статус |
|---|---|---|
| AN1 | Найти обработку фото | TODO |
| AN2 | Удалить Bitmap/JPEG-сжатие | TODO |
| AN3 | Исходные байты | TODO |
| AN4 | MIME и имя файла | TODO |
| AN5 | Проверка памяти | TODO |

## Проверки текущего пакета

- Создать `ProjectRoutes.kt`.
- Создать `DataRouteDtos.kt`.
- Добавить `projectRoutes()` после `projectCostRoutes()`.
- Удалить старый блок `/api/v1/projects`.
- Удалить перенесённые DTO из `DataRoutes.kt`.
- Заменить вызов удалённого `validateSubprojectForProject` на
  `validateActiveSubproject`.
- Выполнить `compileKotlin`.
- Проверить отсутствие двойных URL.

## Тестирование

| Пакет | Содержание | Статус |
|---|---|---|
| T1 | Компиляция после R5–R6 | TODO |
| T2 | Уникальность routes | TODO |
| T3 | SERVER tests | TODO |
| T4 | Миграция чистой БД | TODO |
| T5 | Миграция существующей БД | TODO |
| T6 | Payroll и баланс | TODO |
| T7 | Права директорских расходов | TODO |
| T8 | WEB build | TODO |
| T9 | Android build | TODO |
| T10 | PRO-Chat 100 MB | TODO |

## Критические замечания

- Не переносить `ExpenseRoutes.kt` сейчас: доступное содержимое обрывается
  внутри удаления чека.
- Удаление проекта сейчас физически удаляет связанные записи и метаданные
  ТНПА; очистку файлов ТНПА нужно выполнять отдельно после commit.
- `DataRoutes.kt` должен остаться агрегатором тематических модулей.
- После применения R5–R6 выполнить push для следующего чтения.

## Следующая точка продолжения

После push повторно прочитать уменьшенный `DataRoutes.kt`. Вынести полный
`/api/v1/expenses` в `ExpenseRoutes.kt`, связанные helpers и `ExpenseTypes`.
Если следующий блок доходов будет виден целиком, одновременно создать
`IncomeRoutes.kt`.