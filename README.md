# Report Generator

Генератор HTML-отчётов на базе модели отображения.  
Принимает табличный отчёт (Excel XLSX), конфигурацию с формулами и шаблон — выдаёт красивый HTML.

---

## Архитектура (pipeline)

```
config.json + data.xlsx
        │
        ▼
   ┌─────────────┐
   │  Ingestion  │  Apache POI → читает листы/диапазоны
   └──────┬──────┘
          │  DataModel (таблицы, строки, типы)
          ▼
   ┌──────────────┐
   │ Context      │  SpEL-формулы: sum(col('t','Revenue'))
   │ Engine       │  вычисляет переменные kpi.revenue, kpi.orders…
   └──────┬───────┘
          │  Map<String, Object> context
          ▼
   ┌──────────────────┐
   │ ViewModel Builder│  собирает ReportViewModel по layout-конфигу
   └──────┬───────────┘
          │  ReportViewModel (KPI-карточки, таблицы, заголовки)
          ▼
   ┌──────────────────┐
   │ Template Renderer│  FreeMarker → итоговый HTML
   └──────────────────┘
```

---

## Стек

| Слой | Библиотека | Версия |
|---|---|---|
| Чтение XLSX | Apache POI `poi-ooxml` | 5.2.5 |
| Парсинг конфига | Jackson `jackson-databind` | 2.16.2 |
| Валидация конфига | networknt `json-schema-validator` | 1.3.3 |
| DSL формул | Spring `spring-expression` (SpEL, standalone) | 6.1.6 |
| HTML-шаблоны | FreeMarker | 2.3.32 |
| Логирование | Logback | 1.5.3 |
| Тесты | JUnit 5 + AssertJ | 5.10.2 / 3.25.3 |
| Сборка | Maven Shade Plugin (fat-jar) | 3.5.2 |
| Контейнер | Docker multi-stage + Docker Compose | — |

---

## Структура проекта

```
report-generator1/
├── Dockerfile                          # 2-stage: build (maven) → run (jre-alpine)
├── docker-compose.yml                  # монтирует data/input и data/output
├── pom.xml                             # зависимости + shade fat-jar
├── data/
│   ├── input/                          # сюда кладёшь config.json + data.xlsx
│   └── output/                         # сюда появится report.html
└── src/
    ├── main/
    │   ├── java/com/reportgen/
    │   │   └── Main.java               # ✅ точка входа CLI (заглушка)
    │   └── resources/
    │       └── logback.xml             # ✅ конфигурация логов
    └── test/
        └── java/com/reportgen/
            └── MainTest.java           # ✅ smoke-тест точки входа
```

---

## Что уже сделано

### Инфраструктура
- **`pom.xml`** — все зависимости объявлены, настроен maven-shade-plugin для сборки fat-jar с правильным слиянием SPI-файлов POI/Jackson.
- **`Dockerfile`** — двухэтапная сборка: первый слой (`maven:3.9`) скачивает зависимости и собирает jar, второй (`jre-alpine`) содержит только JRE. Итоговый образ ~180 МБ.
- **`docker-compose.yml`** — единственный сервис, монтирует `./data/input` (read-only) и `./data/output`, передаёт три аргумента CLI.
- **`Main.java`** — валидирует количество аргументов, выводит usage.
- **`MainTest.java`** — JUnit 5 smoke-тест.

---

## Что нужно реализовать

Ниже — полный список классов с описанием ответственности.

### 1. Config layer

**`config/ReportConfig.java`** — POJO верхнего уровня:
```java
public class ReportConfig {
    public String title;
    public String theme;
    public List<SourceConfig> sources;
    public List<TableConfig> tables;
    public Map<String, Object> context;  // вложенный: {"kpi": {"revenue": "sum(...)"}}
    public List<LayoutItem> layout;
}
```

**`config/SourceConfig.java`** — `{id, file}` — указывает на xlsx-файл.

**`config/TableConfig.java`** — `{id, source, sheet, range, headerRow}` — правила извлечения таблицы.

**`config/LayoutItem.java`** — `{type, items, table, columns, title, text}` — одна секция в отчёте.

**`config/ConfigLoader.java`** — загружает `config.json`, валидирует через JSON Schema (`/schema/report-config.schema.json`), десериализует в `ReportConfig`.

**`src/main/resources/schema/report-config.schema.json`** — JSON Schema Draft-07 для валидации конфига.

---

### 2. Ingestion layer

**`ingestion/SpreadsheetLoader.java`** — Apache POI:
- открывает XLSX по пути из `SourceConfig.file`
- для каждого `TableConfig` читает лист + диапазон
- первая строка (headerRow) → имена колонок
- остальные строки → `List<Map<String, Object>>` с типами: `Double`, `String`, `Boolean`, `Date`
- пропускает полностью пустые строки
- возвращает `DataModel`

**`model/DataTable.java`** — record: `{id, headers, rows}` + метод `column(name)`.

**`model/DataModel.java`** — record: `{Map<String, DataTable> tables}` + метод `table(id)`.

---

### 3. Context Engine (формульный DSL)

**`context/FormulaContext.java`** — корневой объект для SpEL.  
Все DSL-функции — методы этого класса, SpEL вызывает их напрямую без `#`:

| Метод | Формула в конфиге | Результат |
|---|---|---|
| `col(tableId, colName)` | `col('t', 'Revenue')` | `List<Object>` |
| `sum(values)` | `sum(col('t', 'Revenue'))` | `Double` |
| `count(values)` | `count(col('t', 'OrderId'))` | `Long` |
| `avg / min / max` | аналогично | `Double` |
| `cell(ref)` | `cell('Sales!B2')` | TODO |
| `filter(tableId, cond)` | `filter('t', 'Region==MSK')` | TODO |

**`context/VarMapPropertyAccessor.java`** — кастомный `PropertyAccessor` для SpEL.  
Позволяет `kpi.revenue` в формуле находить `resolvedVars["kpi"]["revenue"]` без `#`.

**`context/FormulaEvaluator.java`** — один вызов SpEL:
```java
Object evaluate(String formula, DataModel dataModel, Map<String, Object> resolvedVars)
```
Регистрирует `VarMapPropertyAccessor` + `MapAccessor` + `ReflectivePropertyAccessor`.

**`context/ContextEngine.java`** — оркестрирует вычисление всех переменных контекста:
1. Рекурсивно сглаживает вложенный `context`-конфиг: `kpi.revenue → "sum(...)"`.
2. Итеративно вычисляет формулы (до N проходов), пока не разрешатся все зависимости — так `kpi.avg_check = kpi.revenue / kpi.orders` вычисляется после `kpi.revenue` и `kpi.orders`.
3. Собирает вложенный `Map<String, Object>` результатов.

---

### 4. ViewModel Builder

**`viewmodel/ReportViewModel.java`**:
```java
public class ReportViewModel {
    public String title;
    public String generatedAt;
    public List<SectionViewModel> sections;
}
```

**`viewmodel/SectionViewModel.java`** — плоский класс с полем `type` и nullable-полями для каждого типа секции.

**`viewmodel/KpiCardViewModel.java`** — `{label, formattedValue}`:
- `label` — из последнего сегмента пути: `kpi.avg_check` → `"Avg Check"`
- `formattedValue` — `Double` → `"12 345"`, `Long` → `"100"`

**`viewmodel/ViewModelBuilder.java`** — итерирует `layout`, для каждого `LayoutItem` создаёт нужную `SectionViewModel`.

---

### 5. Template Renderer

**`renderer/HtmlRenderer.java`** — FreeMarker:
```java
String render(ReportViewModel vm)
```
- конфигурирует `freemarker.template.Configuration` с путём `/templates`
- рендерит `report.ftlh` → строка HTML

**`src/main/resources/templates/report.ftlh`** — FreeMarker-шаблон:
- `<#if section.type == "kpiRow">` → ряд KPI-карточек
- `<#elseif section.type == "table">` → HTML-таблица с `<#list>`
- `<#elseif section.type == "heading">` → заголовок секции

---

### 6. Orchestration

**`ReportGenerator.java`** — связывает все слои:
```java
void generate(String configPath, String xlsxPath, String outputPath)
```
Вызывает: `ConfigLoader → SpreadsheetLoader → ContextEngine → ViewModelBuilder → HtmlRenderer → FileWriter`.

---

## Формат конфига (config.json)

```json
{
  "report": {
    "title": "Ежемесячный отчёт продаж",
    "theme": "default",
    "sources": [
      { "id": "sales", "file": "/app/input/data.xlsx" }
    ],
    "tables": [
      {
        "id": "sales_table",
        "source": "sales",
        "sheet": "Sales",
        "range": "A1:G200",
        "headerRow": 1
      }
    ],
    "context": {
      "kpi": {
        "revenue":   "sum(col('sales_table', 'Revenue'))",
        "orders":    "count(col('sales_table', 'OrderId'))",
        "avg_check": "kpi.revenue / kpi.orders"
      }
    },
    "layout": [
      { "type": "kpiRow", "items": ["kpi.revenue", "kpi.orders", "kpi.avg_check"] },
      { "type": "heading", "text": "Детализация" },
      { "type": "table", "table": "sales_table", "columns": ["Region", "Manager", "Revenue"] }
    ]
  }
}
```

---

## Как запустить

### Локально (нужен Java 17 + Maven)

```bash
# Сборка
mvn package -DskipTests

# Запуск
java -jar target/report-generator-0.1.0-SNAPSHOT.jar \
  data/input/config.json \
  data/input/data.xlsx \
  data/output/report.html
```

### Через Docker Compose

```bash
# Положить файлы
cp path/to/config.json data/input/config.json
cp path/to/data.xlsx   data/input/data.xlsx

# Сборка образа и запуск
docker compose up --build

# Результат
open data/output/report.html
```

### Пересобрать образ без кеша

```bash
docker compose build --no-cache
docker compose up
```

---

## Как тестировать

### Запустить тесты

```bash
mvn test
```

### Что тестируется сейчас

| Тест | Что проверяет |
|---|---|
| `MainTest#mainPrintsUsageWhenNoArgs` | Точка входа не падает без аргументов |

### Что нужно написать (по мере реализации)

| Тест-класс | Сценарии |
|---|---|
| `ingestion/SpreadsheetLoaderTest` | Создать XLSX в памяти (POI), загрузить, проверить заголовки и строки; пустые строки пропускаются; неизвестный лист → исключение |
| `context/FormulaEvaluatorTest` | `sum(col(...))` на тестовом DataModel; `count`, `avg`; кросс-ссылка `kpi.revenue / kpi.orders`; неизвестная колонка → исключение |
| `context/ContextEngineTest` | Вложенный контекст с зависимостями; циклическая зависимость → исключение |
| `config/ConfigLoaderTest` | Валидный JSON загружается; отсутствует поле `title` → исключение валидации |
| `viewmodel/ViewModelBuilderTest` | `kpiRow` → правильный label и formattedValue; `table` → нужные колонки |
| `renderer/HtmlRendererTest` | Render простой ViewModel, проверить `contains("<title>")`, KPI-значение, данные таблицы |

### Пример unit-теста для SpreadsheetLoader

```java
@Test
void loadsHeadersAndRows() throws Exception {
    // создаём xlsx в памяти
    XSSFWorkbook wb = new XSSFWorkbook();
    Sheet sheet = wb.createSheet("Sales");
    Row header = sheet.createRow(0);
    header.createCell(0).setCellValue("Region");
    header.createCell(1).setCellValue("Revenue");
    Row row1 = sheet.createRow(1);
    row1.createCell(0).setCellValue("MSK");
    row1.createCell(1).setCellValue(100_000.0);

    Path tmp = Files.createTempFile("test", ".xlsx");
    try (FileOutputStream fos = new FileOutputStream(tmp.toFile())) { wb.write(fos); }

    // конфигурируем загрузчик
    TableConfig tc = new TableConfig();
    tc.id = "sales"; tc.sheet = "Sales"; tc.source = "s";

    ReportConfig cfg = new ReportConfig();
    cfg.tables = List.of(tc);
    cfg.sources = List.of(/* ... */);

    DataModel model = new SpreadsheetLoader().load(tmp, cfg);

    assertThat(model.table("sales").headers()).containsExactly("Region", "Revenue");
    assertThat(model.table("sales").rows()).hasSize(1);
    assertThat(model.table("sales").rows().get(0).get("Revenue")).isEqualTo(100_000.0);
}
```

---

## TODO / Ограничения MVP

- [ ] `cell('Sheet!A1')` — требует хранения сырого листа после Ingestion
- [ ] `filter('table', 'condition')` — парсинг условия как SpEL-предиката на строку
- [ ] ODS-формат (Apache ODF Toolkit)
- [ ] Темы оформления (несколько CSS-тем)
- [ ] Admin UI — веб-форма редактирования конфига
- [ ] Sandboxing SpEL — ограничить `StandardEvaluationContext` до whitelist методов
- [ ] Поддержка нескольких источников (сейчас используется первый файл)
- [ ] Экспорт в PDF (Playwright headless или Flying Saucer)
