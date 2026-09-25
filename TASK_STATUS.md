# ProLes — статус доработок

Последнее обновление: 2026-09-25

## Репозиторий и правила

- Репозиторий: `https://github.com/tech01proles-prog/ProLes`
- Ветка: `main`.
- `DataRoutes.kt`: 4422 строки, около 193 KB.
- Доступное представление заканчивается внутри
  `route("/api/v1/incomes")`.
- Последний полностью доступный тематический блок:
  `route("/api/v1/expenses")`.
- Для удаления используются маркеры комментариев и route, а не номера строк.
- Нельзя переносить блок, если его конец не виден.
- В конце каждого ответа публикуется полный актуальный `TASK_STATUS.md`.

## Рефакторинг маршрутов

| Пакет | Файл | Содержание | Статус |
|---|---|---|---|
| R1 | `DataRouteSupport.kt` | Сессия, UUID, подпроекты, mapper часов | APPLIED |
| R2 | `FcmRoutes.kt` | `/api/v1/fcm` | APPLIED |
| R3 | `TimeEntryRoutes.kt` | `/api/v1/entries` | APPLIED |
| R4 | `ProjectCostRoutes.kt` | `/api/v1/project-costs` | APPLIED |
| R5 | `ProjectRoutes.kt` | `/api/v1/projects` | FILE APPLIED; REGISTRATION BROKEN |
| R6 | `DataRouteDtos.kt` | Локальные DTO маршрутов | NOT APPLIED |
| R7 | `ExpenseRoutes.kt` | Расходы и чеки | READY TO EXTRACT |
| R8 | `IncomeRoutes.kt` | Доходы | BLOCKED: блок виден не полностью |
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
| P1 | `PlatformRoutes.kt` под `SessionManager` | Файл присутствует; функциональная проверка TODO |
| A1 | Регистрация маршрутов и таблиц | PARTIAL |
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

- Исправить повторный `projectCostRoutes()` на `projectRoutes()`.
- Заменить `validateSubprojectForProject` на `validateActiveSubproject`.
- Создать `ExpenseRoutes.kt`.
- Добавить `expenseRoutes()` после `projectRoutes()`.
- Удалить старый блок `/api/v1/expenses`.
- Перенести expense-only helpers и DTO.
- Оставить общие helpers доступными доходам.
- Выполнить `compileKotlin`.
- Проверить уникальность routes.
- Сделать push в `main`.

## Тестирование

| Пакет | Содержание | Статус |
|---|---|---|
| T1 | Компиляция после R5–R7 | TODO |
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

- В `dataRoutes()` ошибочно дважды вызывается `projectCostRoutes()`.
- `projectRoutes()` сейчас не вызывается.
- `DataRouteDtos.kt` отсутствует.
- `ExpenseRoutes.kt` можно безопасно вынести: блок расходов виден целиком.
- `IncomeRoutes.kt` пока не переносить: доступное представление обрывается
  внутри блока доходов.
- Не дублировать общие helpers между расходами и доходами.
- После удаления расхода запись помечается удалённой, но файлы чеков
  автоматически не очищаются.
- После применения R7 выполнить push для повторного чтения.

## Следующая точка продолжения

После push повторно прочитать уменьшенный `DataRoutes.kt`. Если
`route("/api/v1/incomes")` виден полностью, вынести его в
`IncomeRoutes.kt`. Затем проверить видимость полного блока командировок.