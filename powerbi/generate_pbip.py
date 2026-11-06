"""Generates the AuditIQ Power BI project (PBIP: TMDL semantic model + PBIR report) from one definition.

Run from the repository root:  python powerbi/generate_pbip.py
The output in powerbi/AuditIQ.* is committed; regenerate after changing the analytics views or report layout.

The semantic model imports the PostgreSQL `analytics` views (star schema). Server and database are Power Query
parameters (PgServer, PgDatabase); credentials are never stored in the project – Power BI asks for them on refresh
(use the read-only BI_READER_USERNAME login).
"""

from __future__ import annotations

import json
import shutil
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parent
NAME = "AuditIQ"
NS = uuid.UUID("8f2b1c4e-6a0d-4c7e-9b1a-0a1d17a5e001")


def guid(*parts: str) -> str:
    """Stable identifiers so regenerating the project produces no spurious diffs."""
    return str(uuid.uuid5(NS, "/".join(parts)))


def vid(*parts: str) -> str:
    return guid(*parts).replace("-", "")[:20]


# ---------------------------------------------------------------------------------------------------------------------
# Semantic model definition
# ---------------------------------------------------------------------------------------------------------------------
# column: (name, tmdl dataType, M type, formatString or None, summarizeBy)
INT, TEXT, DEC, DBL, DATE, DT, BOOL = "int64", "string", "decimal", "double", "date", "datetime", "boolean"
M_TYPES = {INT: "Int64.Type", TEXT: "type text", DEC: "Currency.Type", DBL: "type number", DATE: "type date",
           DT: "type datetime", BOOL: "type logical"}
TMDL_TYPES = {INT: "int64", TEXT: "string", DEC: "decimal", DBL: "double", DATE: "dateTime", DT: "dateTime",
              BOOL: "boolean"}
FORMATS = {INT: "0", DEC: "\\R #,0.00", DBL: "0.000", DATE: "yyyy-mm-dd", DT: "yyyy-mm-dd hh:nn"}

TABLES: dict[str, dict] = {
    "dim_date": {"columns": [
        ("date_key", INT), ("calendar_date", DATE), ("year", INT), ("quarter", TEXT), ("month_number", INT),
        ("month_name", TEXT), ("month_label", TEXT), ("year_month", TEXT), ("iso_week", INT), ("day_of_week", INT),
        ("day_name", TEXT), ("is_weekend", BOOL)],
        "sortBy": {"month_name": "month_number", "month_label": "year_month", "day_name": "day_of_week"}},
    "dim_account": {"columns": [
        ("account_id", TEXT), ("customer_id", TEXT), ("account_type", TEXT), ("account_status", TEXT),
        ("branch", TEXT), ("opened_date", DATE)]},
    "dim_merchant": {"columns": [
        ("merchant_id", TEXT), ("merchant_name", TEXT), ("merchant_category", TEXT), ("city", TEXT),
        ("country", TEXT), ("is_vendor", BOOL)]},
    "dim_detector": {"columns": [
        ("detector_key", TEXT), ("detector_name", TEXT), ("detector_type", TEXT), ("risk_category", TEXT),
        ("default_severity", TEXT), ("enabled", BOOL)]},
    "fact_transactions": {"columns": [
        ("transaction_id", TEXT), ("date_key", INT), ("transaction_date", DATE), ("local_hour", INT),
        ("account_id", TEXT), ("customer_id", TEXT), ("merchant_id", TEXT), ("amount", DEC),
        ("transaction_type", TEXT), ("transaction_channel", TEXT), ("merchant_category", TEXT), ("location", TEXT),
        ("account_type", TEXT), ("transaction_status", TEXT), ("approval_status", TEXT), ("approval_threshold", DEC),
        ("rule_findings", INT), ("model_findings", INT), ("is_flagged", BOOL), ("anomaly_score", DBL),
        ("anomaly_priority", TEXT), ("pipeline_run_id", INT)],
        "measures": [
            ("Total Transactions", "COUNTROWS(fact_transactions)", "#,0"),
            ("Total Transaction Value", "SUM(fact_transactions[amount])", "\\R #,0"),
            ("Flagged Transactions",
             "CALCULATE(COUNTROWS(fact_transactions), fact_transactions[is_flagged] = TRUE())", "#,0"),
            ("Exception Rate %", "DIVIDE([Flagged Transactions], [Total Transactions])", "0.00%"),
        ]},
    "fact_exceptions": {"columns": [
        ("exception_id", INT), ("exception_ref", TEXT), ("detector_key", TEXT), ("detection_source", TEXT),
        ("transaction_id", TEXT), ("account_id", TEXT), ("merchant_id", TEXT), ("transaction_date_key", INT),
        ("detected_date_key", INT), ("transaction_date", DATE), ("severity", TEXT), ("severity_rank", INT),
        ("is_high_priority", BOOL), ("risk_category", TEXT), ("exception_status", TEXT), ("amount", DEC),
        ("transaction_type", TEXT), ("transaction_channel", TEXT), ("anomaly_score", DBL),
        ("investigation_id", INT), ("investigation_ref", TEXT), ("investigation_status", TEXT), ("outcome", TEXT)],
        "sortBy": {"severity": "severity_rank"},
        "measures": [
            ("Potential Exceptions", "COUNTROWS(fact_exceptions)", "#,0"),
            ("High Priority Findings",
             "CALCULATE(COUNTROWS(fact_exceptions), fact_exceptions[is_high_priority] = TRUE())", "#,0"),
            ("Rule Findings", "CALCULATE(COUNTROWS(fact_exceptions), fact_exceptions[detection_source] = \"RULE\")",
             "#,0"),
            ("Model Findings", "CALCULATE(COUNTROWS(fact_exceptions), fact_exceptions[detection_source] = \"ML\")",
             "#,0"),
            ("Findings Awaiting Review",
             "CALCULATE(COUNTROWS(fact_exceptions), fact_exceptions[exception_status] = \"OPEN\")", "#,0"),
            ("Flagged Value", "SUM(fact_exceptions[amount])", "\\R #,0"),
            ("Max Anomaly Score", "MAX(fact_exceptions[anomaly_score])", "0.000"),
        ]},
    "fact_investigations": {"columns": [
        ("investigation_id", INT), ("investigation_ref", TEXT), ("exception_id", INT), ("exception_ref", TEXT),
        ("detector_key", TEXT), ("account_id", TEXT), ("risk_category", TEXT), ("status", TEXT), ("is_open", BOOL),
        ("priority", TEXT), ("outcome", TEXT), ("assigned_to", TEXT), ("created_by", TEXT), ("created_at", DT),
        ("created_date_key", INT), ("resolved_at", DT), ("resolved_date_key", INT), ("resolution_hours", DBL),
        ("age_hours", DBL), ("notes", INT), ("status_changes", INT)],
        "measures": [
            ("Investigations", "COUNTROWS(fact_investigations)", "#,0"),
            ("Open Investigations",
             "CALCULATE(COUNTROWS(fact_investigations), fact_investigations[is_open] = TRUE())", "#,0"),
            ("Investigations In Progress",
             "CALCULATE(COUNTROWS(fact_investigations), fact_investigations[status] IN "
             "{\"UNDER_REVIEW\", \"AWAITING_EVIDENCE\", \"ESCALATED\"})", "#,0"),
            ("Resolved Investigations",
             "CALCULATE(COUNTROWS(fact_investigations), fact_investigations[status] = \"RESOLVED\")", "#,0"),
            ("Average Investigation Duration (h)", "AVERAGE(fact_investigations[resolution_hours])", "#,0.0"),
        ]},
    "fact_pipeline_runs": {"columns": [
        ("run_id", INT), ("run_ref", TEXT), ("status", TEXT), ("trigger_type", TEXT), ("triggered_by", TEXT),
        ("started_at", DT), ("start_date_key", INT), ("duration_seconds", DBL), ("total_records", INT),
        ("valid_records", INT), ("rejected_records", INT), ("duplicate_records", INT), ("missing_value_total", INT),
        ("valid_rate_percent", DBL)],
        "measures": [
            ("Pipeline Runs", "COUNTROWS(fact_pipeline_runs)", "#,0"),
            ("Pipeline Success Rate %",
             "DIVIDE(CALCULATE(COUNTROWS(fact_pipeline_runs), fact_pipeline_runs[status] = \"SUCCESS\"), "
             "COUNTROWS(fact_pipeline_runs))", "0.0%"),
            ("Records Received", "SUM(fact_pipeline_runs[total_records])", "#,0"),
            ("Valid Record Rate %",
             "DIVIDE(SUM(fact_pipeline_runs[valid_records]), SUM(fact_pipeline_runs[total_records]))", "0.00%"),
        ]},
    "fact_model_versions": {"columns": [
        ("version", TEXT), ("algorithm", TEXT), ("trained_at", DT), ("trained_by", TEXT), ("training_rows", INT),
        ("is_active", BOOL), ("n_estimators", INT), ("precision", DBL), ("recall", DBL), ("f1", DBL),
        ("roc_auc", DBL), ("average_precision", DBL), ("flagged", INT)]},
}

# (from table.column, to table.column, active)
RELATIONSHIPS = [
    ("fact_transactions", "date_key", "dim_date", "date_key", True),
    ("fact_transactions", "account_id", "dim_account", "account_id", True),
    ("fact_transactions", "merchant_id", "dim_merchant", "merchant_id", True),
    ("fact_exceptions", "transaction_date_key", "dim_date", "date_key", True),
    ("fact_exceptions", "account_id", "dim_account", "account_id", True),
    ("fact_exceptions", "detector_key", "dim_detector", "detector_key", True),
    ("fact_investigations", "created_date_key", "dim_date", "date_key", True),
    ("fact_investigations", "account_id", "dim_account", "account_id", True),
    ("fact_investigations", "detector_key", "dim_detector", "detector_key", True),
    ("fact_pipeline_runs", "start_date_key", "dim_date", "date_key", True),
]
KEY_COLUMNS = {(t, c) for _, _, t, c, _ in RELATIONSHIPS}


def tmdl_table(name: str, spec: dict) -> str:
    lines = [f"table {name}", f"\tlineageTag: {guid('table', name)}", ""]
    for measure, expression, fmt in spec.get("measures", []):
        lines += [f"\tmeasure '{measure}' = {expression}", f"\t\tformatString: {fmt}",
                  f"\t\tlineageTag: {guid('measure', name, measure)}", ""]
    sort_by = spec.get("sortBy", {})
    for column, kind in spec["columns"]:
        lines.append(f"\tcolumn {column}")
        lines.append(f"\t\tdataType: {TMDL_TYPES[kind]}")
        if kind in FORMATS and not column.endswith("_key") and not column.endswith("_id"):
            lines.append(f"\t\tformatString: {FORMATS[kind]}")
        if (name, column) in KEY_COLUMNS or column.endswith("_key"):
            lines.append("\t\tisKey" if (name, column) in KEY_COLUMNS else "\t\tisHidden")
        lines.append(f"\t\tlineageTag: {guid('column', name, column)}")
        summarize = "sum" if kind in (DEC,) and column == "amount" else "none"
        lines.append(f"\t\tsummarizeBy: {summarize}")
        lines.append(f"\t\tsourceColumn: {column}")
        if column in sort_by:
            lines.append(f"\t\tsortByColumn: {sort_by[column]}")
        lines.append("")
    types = ", ".join(f'{{"{c}", {M_TYPES[k]}}}' for c, k in spec["columns"])
    names = ", ".join(f'"{c}"' for c, _ in spec["columns"])
    lines += [
        f"\tpartition {name} = m",
        "\t\tmode: import",
        "\t\tsource =",
        "\t\t\t\tlet",
        "\t\t\t\t    Source = PostgreSQL.Database(PgServer, PgDatabase),",
        f"\t\t\t\t    View = Source{{[Schema = \"analytics\", Item = \"{name}\"]}}[Data],",
        f"\t\t\t\t    Selected = Table.SelectColumns(View, {{{names}}}),",
        f"\t\t\t\t    Typed = Table.TransformColumnTypes(Selected, {{{types}}})",
        "\t\t\t\tin",
        "\t\t\t\t    Typed",
        "",
    ]
    return "\n".join(lines)


def write_semantic_model(base: Path) -> None:
    sm = base / f"{NAME}.SemanticModel"
    definition = sm / "definition"
    (definition / "tables").mkdir(parents=True)
    write_json(sm / "definition.pbism", {
        "$schema": "https://developer.microsoft.com/json-schemas/fabric/item/semanticModel/definitionProperties/"
                   "1.0.0/schema.json",
        "version": "4.0",
        "settings": {},
    })
    write_text(definition / "database.tmdl", "database\n\tcompatibilityLevel: 1567\n")
    model = ["model Model", "\tculture: en-US", "\tdefaultPowerBIDataSourceVersion: powerBI_V3",
             "\tsourceQueryCulture: en-US", "\tdataAccessOptions", "\t\tlegacyRedirects", "\t\treturnErrorValuesAsNull",
             "", "annotation __PBI_TimeIntelligenceEnabled = 0", ""]
    model += [f"ref table {t}" for t in TABLES]
    model += ["", "ref cultureInfo en-US", ""]
    write_text(definition / "model.tmdl", "\n".join(model))
    write_text(definition / "cultures" / "en-US.tmdl", "cultureInfo en-US\n")
    write_text(definition / "expressions.tmdl", "\n".join([
        'expression PgServer = "localhost:5433" meta [IsParameterQuery=true, Type="Text", '
        'IsParameterQueryRequired=true]',
        f"\tlineageTag: {guid('expr', 'PgServer')}",
        "",
        "\tannotation PBI_ResultType = Text",
        "",
        'expression PgDatabase = "auditiq" meta [IsParameterQuery=true, Type="Text", IsParameterQueryRequired=true]',
        f"\tlineageTag: {guid('expr', 'PgDatabase')}",
        "",
        "\tannotation PBI_ResultType = Text",
        "",
    ]))
    rel = []
    for f_table, f_col, t_table, t_col, active in RELATIONSHIPS:
        rel += [f"relationship {guid('rel', f_table, f_col, t_table)}"]
        if not active:
            rel.append("\tisActive: false")
        rel += [f"\tfromColumn: {f_table}.{f_col}", f"\ttoColumn: {t_table}.{t_col}", ""]
    write_text(definition / "relationships.tmdl", "\n".join(rel))
    for name, spec in TABLES.items():
        write_text(definition / "tables" / f"{name}.tmdl", tmdl_table(name, spec))


# ---------------------------------------------------------------------------------------------------------------------
# Report definition (PBIR)
# ---------------------------------------------------------------------------------------------------------------------
SCHEMA = "https://developer.microsoft.com/json-schemas/fabric/item/report/definition"
NAVY, GREEN = "#0B1F3A", "#1E9E6A"


def measure(table: str, name: str) -> dict:
    return {"field": {"Measure": {"Expression": {"SourceRef": {"Entity": table}}, "Property": name}},
            "queryRef": f"{table}.{name}", "nativeQueryRef": name}


def column(table: str, name: str) -> dict:
    return {"field": {"Column": {"Expression": {"SourceRef": {"Entity": table}}, "Property": name}},
            "queryRef": f"{table}.{name}", "nativeQueryRef": name}


def text_literal(text: str) -> dict:
    return {"expr": {"Literal": {"Value": "'" + text.replace("'", "''") + "'"}}}


def visual(page: str, key: str, vtype: str, x: int, y: int, w: int, h: int, roles: dict, title: str | None = None,
           sort: tuple[dict, str] | None = None) -> tuple[str, dict]:
    name = vid(page, key)
    body: dict = {"visualType": vtype, "query": {"queryState": {
        role: {"projections": projections} for role, projections in roles.items()}}}
    if sort is not None:
        field, direction = sort
        body["query"]["sortDefinition"] = {"sort": [{"field": field["field"], "direction": direction}],
                                           "isDefaultSort": True}
    if title:
        body["visualContainerObjects"] = {"title": [{"properties": {
            "show": {"expr": {"Literal": {"Value": "true"}}}, "text": text_literal(title)}}]}
    return name, {"$schema": f"{SCHEMA}/visualContainer/1.0.0/schema.json", "name": name,
                  "position": {"x": x, "y": y, "z": 0, "width": w, "height": h}, "visual": body}


def textbox(page: str, key: str, text: str, x: int, y: int, w: int, h: int, size: str = "20pt") -> tuple[str, dict]:
    name = vid(page, key)
    paragraphs = [{"textRuns": [{"value": text, "textStyle": {"fontSize": size, "fontWeight": "bold",
                                                              "color": NAVY}}]}]
    return name, {"$schema": f"{SCHEMA}/visualContainer/1.0.0/schema.json", "name": name,
                  "position": {"x": x, "y": y, "z": 0, "width": w, "height": h},
                  "visual": {"visualType": "textbox", "objects": {"general": [{"properties": {
                      "paragraphs": paragraphs}}]}}}


def cards(page: str, items: list[tuple[str, str, str]], y: int) -> list[tuple[str, dict]]:
    width = (1240 - 10 * (len(items) - 1)) // len(items)
    return [visual(page, f"card{i}", "card", 20 + i * (width + 10), y, width, 110,
                   {"Values": [measure(table, m)]}, title)
            for i, (table, m, title) in enumerate(items)]


def date_slicer(page: str) -> tuple[str, dict]:
    return visual(page, "date-slicer", "slicer", 900, 10, 360, 60, {"Values": [column("dim_date", "calendar_date")]})


PAGES: list[tuple[str, str, list[tuple[str, dict]]]] = []


def build_pages() -> None:
    p = "executive"
    PAGES.append((p, "Executive Overview", [
        textbox(p, "title", "Executive Overview", 20, 10, 700, 50),
        date_slicer(p),
        *cards(p, [("fact_transactions", "Total Transactions", "Transactions processed"),
                   ("fact_exceptions", "Potential Exceptions", "Potential exceptions"),
                   ("fact_exceptions", "High Priority Findings", "High-priority findings"),
                   ("fact_transactions", "Total Transaction Value", "Total transaction value"),
                   ("fact_transactions", "Exception Rate %", "Exception rate"),
                   ("fact_pipeline_runs", "Pipeline Success Rate %", "Pipeline health (success rate)")], 80),
        visual(p, "trend", "lineChart", 20, 205, 800, 495,
               {"Category": [column("dim_date", "year_month")],
                "Y": [measure("fact_transactions", "Total Transactions")]}, "Transaction volume by month"),
        visual(p, "severity", "clusteredBarChart", 830, 205, 430, 240,
               {"Category": [column("fact_exceptions", "severity")],
                "Y": [measure("fact_exceptions", "Potential Exceptions")]}, "Potential exceptions by severity"),
        visual(p, "category", "clusteredBarChart", 830, 455, 430, 245,
               {"Category": [column("fact_exceptions", "risk_category")],
                "Y": [measure("fact_exceptions", "Potential Exceptions")]}, "Potential exceptions by risk category"),
    ]))
    p = "transactions"
    PAGES.append((p, "Transaction Analysis", [
        textbox(p, "title", "Transaction Analysis", 20, 10, 700, 50),
        date_slicer(p),
        visual(p, "volume", "columnChart", 20, 80, 820, 300,
               {"Category": [column("dim_date", "year_month")],
                "Y": [measure("fact_transactions", "Total Transactions")]}, "Transaction volume over time"),
        visual(p, "channel", "donutChart", 850, 80, 410, 300,
               {"Category": [column("fact_transactions", "transaction_channel")],
                "Y": [measure("fact_transactions", "Total Transactions")]}, "Transaction channels"),
        visual(p, "category", "clusteredBarChart", 20, 390, 410, 310,
               {"Category": [column("fact_transactions", "merchant_category")],
                "Y": [measure("fact_transactions", "Total Transaction Value")]}, "Transaction value by category"),
        visual(p, "location", "clusteredBarChart", 440, 390, 400, 310,
               {"Category": [column("fact_transactions", "location")],
                "Y": [measure("fact_transactions", "Total Transactions")]}, "Geographic distribution"),
        visual(p, "daily", "lineChart", 850, 390, 410, 310,
               {"Category": [column("dim_date", "calendar_date")],
                "Y": [measure("fact_transactions", "Total Transactions")]}, "Daily transaction trend"),
    ]))
    p = "risk"
    top_sort = measure("fact_exceptions", "High Priority Findings")
    PAGES.append((p, "Risk Analysis", [
        textbox(p, "title", "Risk Analysis", 20, 10, 700, 50),
        date_slicer(p),
        visual(p, "category", "clusteredBarChart", 20, 80, 410, 300,
               {"Category": [column("fact_exceptions", "risk_category")],
                "Y": [measure("fact_exceptions", "Potential Exceptions")]}, "Exceptions by risk category"),
        visual(p, "severity", "columnChart", 440, 80, 400, 300,
               {"Category": [column("fact_exceptions", "severity")],
                "Y": [measure("fact_exceptions", "Potential Exceptions")]}, "Exceptions by severity"),
        visual(p, "detector", "clusteredBarChart", 850, 80, 410, 300,
               {"Category": [column("dim_detector", "detector_name")],
                "Y": [measure("fact_exceptions", "Potential Exceptions")]}, "Rule-based versus model findings"),
        visual(p, "accounts", "tableEx", 20, 390, 820, 310,
               {"Values": [column("dim_account", "account_id"), column("dim_account", "account_type"),
                           column("dim_account", "branch"), measure("fact_exceptions", "Potential Exceptions"),
                           top_sort, measure("fact_exceptions", "Rule Findings"),
                           measure("fact_exceptions", "Model Findings"), measure("fact_exceptions", "Flagged Value"),
                           measure("fact_exceptions", "Max Anomaly Score")]},
               "Top accounts requiring review", sort=(top_sort, "Descending")),
        visual(p, "trend", "lineChart", 850, 390, 410, 310,
               {"Category": [column("dim_date", "year_month")],
                "Y": [measure("fact_transactions", "Exception Rate %")]}, "Unusual transaction trend (flagged rate)"),
    ]))
    p = "investigations"
    PAGES.append((p, "Investigation Monitoring", [
        textbox(p, "title", "Investigation Monitoring", 20, 10, 700, 50),
        *cards(p, [("fact_investigations", "Open Investigations", "Open investigations"),
                   ("fact_investigations", "Investigations In Progress", "In progress"),
                   ("fact_investigations", "Resolved Investigations", "Resolved"),
                   ("fact_investigations", "Average Investigation Duration (h)", "Average duration (hours)"),
                   ("fact_exceptions", "Findings Awaiting Review", "Findings awaiting review")], 80),
        visual(p, "status", "clusteredBarChart", 20, 205, 410, 495,
               {"Category": [column("fact_investigations", "status")],
                "Y": [measure("fact_investigations", "Investigations")]}, "Investigations by status"),
        visual(p, "outcome", "clusteredBarChart", 440, 205, 400, 240,
               {"Category": [column("fact_investigations", "outcome")],
                "Y": [measure("fact_investigations", "Investigations")]}, "Recorded outcomes"),
        visual(p, "assignee", "clusteredBarChart", 440, 455, 400, 245,
               {"Category": [column("fact_investigations", "assigned_to")],
                "Y": [measure("fact_investigations", "Open Investigations")]}, "Open cases by assignee"),
        visual(p, "list", "tableEx", 850, 205, 410, 495,
               {"Values": [column("fact_investigations", "investigation_ref"),
                           column("fact_investigations", "status"), column("fact_investigations", "priority"),
                           column("fact_investigations", "assigned_to"),
                           column("fact_investigations", "age_hours")]}, "Investigations"),
    ]))


def write_report(base: Path) -> None:
    rep = base / f"{NAME}.Report"
    definition = rep / "definition"
    write_json(rep / "definition.pbir", {
        "$schema": "https://developer.microsoft.com/json-schemas/fabric/item/report/definitionProperties/"
                   "1.0.0/schema.json",
        "version": "4.0",
        "datasetReference": {"byPath": {"path": f"../{NAME}.SemanticModel"}},
    })
    write_json(definition / "version.json", {"$schema": f"{SCHEMA}/versionMetadata/1.0.0/schema.json",
                                             "version": "2.0.0"})
    write_json(definition / "report.json", {
        "$schema": f"{SCHEMA}/report/1.0.0/schema.json",
        "themeCollection": {"baseTheme": {"name": "CY24SU10", "reportVersionAtImport": "5.59",
                                          "type": "SharedResources"}},
        "layoutOptimization": "None",
    })
    write_json(rep / "StaticResources" / "SharedResources" / "BaseThemes" / "CY24SU10.json", {
        "name": "CY24SU10",
        "dataColors": ["#2A78D6", "#1E9E6A", "#C98A00", "#C0392B", "#6B4FA3", "#0B1F3A", "#2E9CB8", "#8A6D3B"],
        "foreground": NAVY, "background": "#FFFFFF", "tableAccent": GREEN,
    })
    build_pages()
    write_json(definition / "pages" / "pages.json", {
        "$schema": f"{SCHEMA}/pagesMetadata/1.0.0/schema.json",
        "pageOrder": [vid("page", p) for p, _, _ in PAGES],
        "activePageName": vid("page", PAGES[0][0]),
    })
    for key, display, visuals in PAGES:
        page_name = vid("page", key)
        folder = definition / "pages" / page_name
        write_json(folder / "page.json", {"$schema": f"{SCHEMA}/page/1.0.0/schema.json", "name": page_name,
                                          "displayName": display, "displayOption": "FitToPage",
                                          "height": 720, "width": 1280})
        for name, body in visuals:
            write_json(folder / "visuals" / name / "visual.json", body)


# ---------------------------------------------------------------------------------------------------------------------
def write_json(path: Path, data: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8", newline="\n")


def write_text(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8", newline="\n")


def check_references() -> None:
    """Every field a visual uses must exist in the semantic model (the schemas cannot check this)."""
    known = {(t, c) for t, spec in TABLES.items() for c, _ in spec["columns"]}
    known |= {(t, m) for t, spec in TABLES.items() for m, _, _ in spec.get("measures", [])}
    measures = {(t, m) for t, spec in TABLES.items() for m, _, _ in spec.get("measures", [])}
    problems = []
    for _, display, visuals in PAGES:
        for _, body in visuals:
            for role in body["visual"].get("query", {}).get("queryState", {}).values():
                for projection in role["projections"]:
                    kind, ref = next(iter(projection["field"].items()))
                    key = (ref["Expression"]["SourceRef"]["Entity"], ref["Property"])
                    if key not in known or (kind == "Measure") != (key in measures):
                        problems.append(f"{display}: {kind} {key}")
    for f_table, f_col, t_table, t_col, _ in RELATIONSHIPS:
        for key in ((f_table, f_col), (t_table, t_col)):
            if key not in known:
                problems.append(f"relationship column {key}")
    if problems:
        raise SystemExit("Unknown model references: " + "; ".join(problems))


def main() -> None:
    for item in (f"{NAME}.SemanticModel", f"{NAME}.Report"):
        shutil.rmtree(ROOT / item, ignore_errors=True)
    write_json(ROOT / f"{NAME}.pbip", {
        "$schema": "https://developer.microsoft.com/json-schemas/fabric/pbip/pbipProperties/1.0.0/schema.json",
        "version": "1.0",
        "artifacts": [{"report": {"path": f"{NAME}.Report"}}],
        "settings": {"enableAutoRecovery": True},
    })
    write_semantic_model(ROOT)
    write_report(ROOT)
    check_references()
    print(f"Generated {NAME}.pbip with {len(TABLES)} tables, {len(RELATIONSHIPS)} relationships and "
          f"{sum(len(v) for _, _, v in PAGES)} visuals on {len(PAGES)} pages")


if __name__ == "__main__":
    main()
