#!/usr/bin/env python3
"""Initialize or validate the software-design-chain workspace."""

from __future__ import annotations

import argparse
import csv
import json
import re
import sys
from pathlib import Path


STAGES = [
    "00-control",
    "01-requirements",
    "02-business-scenarios",
    "03-domain-boundaries",
    "04-data-model",
    "05-backend-architecture",
    "06-frontend-experience",
    "07-tests-acceptance",
    "08-consistency-review",
]
FOUNDATION_DIR = "00-control/project-foundation"
STAGE_KEYS = [
    "requirements",
    "business_scenarios",
    "project_foundation",
    "domain_boundaries",
    "data_model",
    "backend_architecture",
    "frontend_experience",
    "tests_acceptance",
    "consistency_review",
]
STAGE_REQUIRED_FILES = {
    "requirements": [
        "01-requirements/requirements-baseline.md",
        "01-requirements/source-register.csv",
        "01-requirements/requirement-catalog.csv",
    ],
    "business_scenarios": [
        "02-business-scenarios/business-scenario-model.md",
        "02-business-scenarios/business-rules.csv",
        "02-business-scenarios/state-transitions.csv",
        "02-business-scenarios/permission-matrix.csv",
    ],
    "project_foundation": [
        "00-control/project-foundation/project-convention-profile.md",
        "00-control/project-foundation/architecture-profile.md",
        "00-control/project-foundation/architecture-layer-mapping.csv",
        "00-control/project-foundation/probe-task-register.csv",
        "00-control/project-foundation/project-module-catalog.csv",
        "00-control/project-foundation/project-capability-catalog.csv",
        "00-control/project-foundation/existing-api-catalog.csv",
        "00-control/project-foundation/existing-schema-catalog.csv",
        "00-control/project-foundation/extension-point-catalog.csv",
        "00-control/project-foundation/reuse-decision-catalog.csv",
        "00-control/project-foundation/component-gap-catalog.csv",
    ],
    "domain_boundaries": [
        "03-domain-boundaries/domain-design.md",
        "03-domain-boundaries/context-map.md",
        "03-domain-boundaries/ubiquitous-language.csv",
        "03-domain-boundaries/aggregate-catalog.csv",
        "03-domain-boundaries/domain-events.csv",
    ],
    "data_model": [
        "04-data-model/data-model.md",
        "04-data-model/table-cluster-catalog.csv",
        "04-data-model/table-design-worklist.csv",
        "04-data-model/table-catalog.csv",
        "04-data-model/column-catalog.csv",
        "04-data-model/relationship-catalog.csv",
        "04-data-model/constraint-catalog.csv",
        "04-data-model/index-catalog.csv",
        "04-data-model/query-pattern-catalog.csv",
        "04-data-model/json-field-catalog.csv",
        "04-data-model/reuse-impact-catalog.csv",
        "04-data-model/migration-mapping.csv",
        "04-data-model/data-model-change-log.csv",
        "04-data-model/data-traceability.csv",
    ],
    "backend_architecture": [
        "05-backend-architecture/backend-architecture.md",
        "05-backend-architecture/architecture-decisions.md",
        "05-backend-architecture/use-case-design-worklist.csv",
        "05-backend-architecture/use-case-catalog.csv",
        "05-backend-architecture/api-contract-catalog.csv",
        "05-backend-architecture/transaction-boundary-catalog.csv",
        "05-backend-architecture/authorization-catalog.csv",
        "05-backend-architecture/idempotency-concurrency-catalog.csv",
        "05-backend-architecture/error-catalog.csv",
        "05-backend-architecture/integration-catalog.csv",
        "05-backend-architecture/event-publication-catalog.csv",
        "05-backend-architecture/component-reuse-mapping.csv",
        "05-backend-architecture/code-mapping.csv",
    ],
    "frontend_experience": [
        "06-frontend-experience/frontend-design.md",
        "06-frontend-experience/role-task-matrix.csv",
        "06-frontend-experience/page-design-worklist.csv",
        "06-frontend-experience/page-route-catalog.csv",
        "06-frontend-experience/page-data-source-catalog.csv",
        "06-frontend-experience/page-action-permission.csv",
        "06-frontend-experience/form-field-contract.csv",
        "06-frontend-experience/interaction-state-catalog.csv",
        "06-frontend-experience/api-binding-catalog.csv",
        "06-frontend-experience/frontend-component-reuse.csv",
    ],
    "tests_acceptance": [
        "07-tests-acceptance/test-strategy.md",
        "07-tests-acceptance/integration-migration-test.md",
        "07-tests-acceptance/performance-security-test.md",
        "07-tests-acceptance/uat-plan.md",
        "07-tests-acceptance/risk-catalog.csv",
        "07-tests-acceptance/coverage-obligation-catalog.csv",
        "07-tests-acceptance/test-design-worklist.csv",
        "07-tests-acceptance/acceptance-scenarios.csv",
        "07-tests-acceptance/test-case-catalog.csv",
        "07-tests-acceptance/state-permission-coverage.csv",
        "07-tests-acceptance/test-data-catalog.csv",
        "07-tests-acceptance/test-traceability.csv",
    ],
    "consistency_review": [
        "08-consistency-review/consistency-review.md",
        "08-consistency-review/gate-g7-report.md",
        "08-consistency-review/consistency-check-worklist.csv",
        "08-consistency-review/artifact-consistency-matrix.csv",
        "08-consistency-review/finding-ledger.csv",
        "08-consistency-review/impact-analysis.csv",
    ],
}
STAGE_REQUIRED_DIRS = {
    "data_model": ["04-data-model/tables", "04-data-model/ddl", "04-data-model/migrations"],
    "backend_architecture": ["05-backend-architecture/use-cases", "05-backend-architecture/contracts"],
    "frontend_experience": ["06-frontend-experience/tasks", "06-frontend-experience/pages"],
    "tests_acceptance": ["07-tests-acceptance/tests"],
    "consistency_review": ["08-consistency-review/stage-closure-reports"],
}
GATE_STAGES = {
    "G1": ["requirements"],
    "G2": ["business_scenarios"],
    "GF": ["project_foundation"],
    "G3": ["domain_boundaries"],
    "G4": ["data_model", "backend_architecture"],
    "G5": ["frontend_experience"],
    "G6": ["tests_acceptance"],
    "G7": ["consistency_review"],
}
GATE_EXTRA_FILES = {
    "G4": ["04-data-model/schema.sql", "05-backend-architecture/openapi.yaml"],
}
VALID_STATUSES = {
    "NOT_STARTED",
    "IN_PROGRESS",
    "READY",
    "READY_WITH_ASSUMPTIONS",
    "NEEDS_CONTEXT",
    "BLOCKED",
    "REVISE_UPSTREAM",
    "FAILED_VALIDATION",
}
VALID_GATE_STATUSES = {"NOT_CHECKED", "IN_PROGRESS", "PASSED", "FAILED", "WAIVED"}
VALID_HUMAN_REVIEW_STATUSES = {"NOT_REVIEWED", "HUMAN_COMMENTED", "HUMAN_CONFIRMED"}
VALID_CODEBASE_STATUSES = {"EXISTS", "GREENFIELD", "NOT_PROVIDED"}
VALID_PROBE_STATUSES = {"NOT_STARTED", "IN_PROGRESS", "PASSED", "FAILED"}
VALID_PROBE_MODES = {None, "SUBAGENT", "PARENT_FALLBACK"}
PROBE_SKILL_NAME = "inspect-existing-project-capabilities"
VALID_STOP_REASONS = {None, "REQUESTED_SCOPE_COMPLETE", "G7_PASSED", "NEEDS_HUMAN_INPUT", "UNRECOVERABLE_VALIDATION_FAILURE"}
VALID_GATES = {f"G{index}" for index in range(8)} | {"GF"}
IDENTIFIER_PATTERN = re.compile(r"^[A-Za-z][A-Za-z0-9_-]{1,63}$")
CSV_SCHEMAS = {
    "artifact-registry.csv": ["artifact_id", "type", "path", "version", "status", "owner", "gate", "updated_at"],
    "traceability-ledger.csv": ["from_id", "relation", "to_id", "status", "evidence", "updated_at"],
    "issues.csv": ["id", "severity", "status", "title", "owner", "affected_ids", "decision_needed", "due_date"],
    "decisions.csv": ["id", "status", "title", "decision", "owner", "date", "supersedes"],
    "work-item-registry.csv": ["work_item_id", "stage", "item_type", "candidate_key", "item_ref", "state", "discovery_evidence", "registered_artifact", "confirmation_status", "confirmation_evidence", "priority", "depends_on", "baseline_version", "owner", "source_worklist", "updated_at"],
    "human-input-requests.csv": ["id", "stage", "work_item_id", "missing_fact", "why_ai_cannot_decide", "options", "recommendation", "impact_ids", "default_if_deferred", "scope_blocked", "status", "answered_by", "updated_at"],
    "human-review-feedback.csv": ["id", "stage", "artifact_refs", "feedback", "status", "affected_ids", "issue_refs", "recorded_at"],
}


def design_root(project_root: Path) -> Path:
    return project_root / "docs" / "software-design"


def write_csv_if_missing(path: Path, headers: list[str]) -> None:
    if path.exists():
        return
    with path.open("w", encoding="utf-8", newline="") as handle:
        csv.writer(handle).writerow(headers)


def init_workspace(project_root: Path, project_code: str, slice_id: str) -> int:
    for label, value in (("project code", project_code), ("slice id", slice_id)):
        if not IDENTIFIER_PATTERN.fullmatch(value):
            print(f"ERROR: invalid {label}: {value!r}")
            return 2
    project_code = project_code.upper()
    root = design_root(project_root)
    manifest_path = root / "00-control" / "design-chain-manifest.json"
    if manifest_path.exists():
        try:
            existing = json.loads(manifest_path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as exc:
            print(f"ERROR: existing manifest is invalid: {exc}")
            return 1
        if not isinstance(existing, dict):
            print("ERROR: existing manifest must be an object")
            return 1
        if existing.get("project_code") != project_code or existing.get("slice_id") != slice_id:
            print("ERROR: existing workspace project_code/slice_id does not match requested scope")
            return 2

    for stage in STAGES:
        (root / stage).mkdir(parents=True, exist_ok=True)
    (root / FOUNDATION_DIR).mkdir(parents=True, exist_ok=True)

    if not manifest_path.exists():
        template = Path(__file__).resolve().parent.parent / "assets" / "design-chain-manifest.json"
        data = json.loads(template.read_text(encoding="utf-8"))
        data["project_code"] = project_code
        data["slice_id"] = slice_id
        manifest_path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    control = root / "00-control"
    for file_name, headers in CSV_SCHEMAS.items():
        write_csv_if_missing(control / file_name, headers)
    print(manifest_path)
    return 0


def check_workspace(project_root: Path) -> int:
    root = design_root(project_root).resolve()
    errors: list[str] = []
    data: dict = {}
    probe_required = False
    required_stage_paths: set[str] = set()
    for stage in STAGES:
        if not (root / stage).is_dir():
            errors.append(f"missing directory: {stage}")
    if not (root / FOUNDATION_DIR).is_dir():
        errors.append(f"missing directory: {FOUNDATION_DIR}")

    manifest_path = root / "00-control" / "design-chain-manifest.json"
    if not manifest_path.exists():
        errors.append("missing manifest")
    else:
        try:
            data = json.loads(manifest_path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as exc:
            errors.append(f"invalid manifest: {exc}")
            data = {}
        if not isinstance(data, dict):
            errors.append("manifest root must be an object")
            data = {}
        for key in ("schema_version", "project_code", "slice_id", "baseline_version", "code_version", "codebase_status", "current_stage", "current_gate", "gate_status", "active_work_item", "iron_law", "project_probe", "continuation_required", "next_work_item_id", "next_stage", "stop_reason", "human_review", "human_input_requests", "gate_components", "stage_status", "artifacts", "open_blockers", "approvals", "gate_records", "route_history"):
            if key not in data:
                errors.append(f"manifest missing key: {key}")
        if data.get("schema_version") != 6:
            errors.append(f"unsupported schema_version: {data.get('schema_version')!r}; expected 6")
        iron_law = data.get("iron_law")
        if not isinstance(iron_law, dict):
            errors.append("iron_law must be an object")
        elif iron_law.get("name") != "discover-register-confirm" or iron_law.get("status") != "ENFORCED" or iron_law.get("continuous_execution") is not True:
            errors.append("iron_law must enforce discover-register-confirm with continuous execution")
        if data.get("codebase_status") not in VALID_CODEBASE_STATUSES:
            errors.append(f"invalid codebase_status: {data.get('codebase_status')!r}")
        project_probe = data.get("project_probe")
        if not isinstance(project_probe, dict):
            errors.append("project_probe must be an object")
            project_probe = {}
        else:
            required_probe_keys = {
                "subagent_available",
                "execution_mode",
                "skill_name",
                "agent_task_ref",
                "status",
                "fallback_reason",
                "code_version",
                "artifact_refs",
            }
            if set(project_probe) != required_probe_keys:
                errors.append("project_probe must contain exactly the required execution fields")
        subagent_available = project_probe.get("subagent_available")
        probe_mode = project_probe.get("execution_mode")
        probe_status = project_probe.get("status")
        if subagent_available is not None and not isinstance(subagent_available, bool):
            errors.append("project_probe.subagent_available must be boolean or null")
        if probe_mode not in VALID_PROBE_MODES:
            errors.append(f"invalid project_probe.execution_mode: {probe_mode!r}")
        if project_probe.get("skill_name") != PROBE_SKILL_NAME:
            errors.append(f"project_probe.skill_name must be {PROBE_SKILL_NAME}")
        if probe_status not in VALID_PROBE_STATUSES:
            errors.append(f"invalid project_probe.status: {probe_status!r}")
        probe_artifact_refs = project_probe.get("artifact_refs")
        if not isinstance(probe_artifact_refs, list) or any(not isinstance(ref, str) or not ref for ref in probe_artifact_refs):
            errors.append("project_probe.artifact_refs must be an array of non-empty strings")
            probe_artifact_refs = []
        if probe_mode == "SUBAGENT":
            if subagent_available is not True:
                errors.append("SUBAGENT project probe requires subagent_available=true")
            if not isinstance(project_probe.get("agent_task_ref"), str) or not project_probe.get("agent_task_ref"):
                errors.append("SUBAGENT project probe requires agent_task_ref")
        if probe_mode == "PARENT_FALLBACK":
            if subagent_available is not False:
                errors.append("PARENT_FALLBACK requires subagent_available=false")
            if not isinstance(project_probe.get("fallback_reason"), str) or not project_probe.get("fallback_reason"):
                errors.append("PARENT_FALLBACK requires fallback_reason")
        if probe_status == "PASSED":
            if probe_mode not in {"SUBAGENT", "PARENT_FALLBACK"}:
                errors.append("PASSED project probe requires an explicit execution_mode")
            if not isinstance(project_probe.get("code_version"), str) or not project_probe.get("code_version"):
                errors.append("PASSED project probe requires code_version")
            if not probe_artifact_refs:
                errors.append("PASSED project probe requires artifact_refs")
        if not isinstance(data.get("continuation_required"), bool):
            errors.append("continuation_required must be boolean")
        if data.get("stop_reason") not in VALID_STOP_REASONS:
            errors.append(f"invalid stop_reason: {data.get('stop_reason')!r}")
        if data.get("continuation_required") is False and data.get("stop_reason") is None:
            errors.append("stopping requires an allowed stop_reason")
        if data.get("continuation_required") is True and data.get("stop_reason") is not None:
            errors.append("continuing execution cannot have a stop_reason")
        if data.get("continuation_required") is True and not data.get("next_work_item_id") and not data.get("next_stage"):
            errors.append("continuing execution requires next_work_item_id or next_stage")
        if data.get("codebase_status") == "EXISTS" and (not isinstance(data.get("code_version"), str) or not data.get("code_version")):
            errors.append("existing codebase requires code_version")
        human_review = data.get("human_review")
        if not isinstance(human_review, dict):
            errors.append("human_review must be an object")
        elif human_review.get("status") not in VALID_HUMAN_REVIEW_STATUSES:
            errors.append(f"invalid human review status: {human_review.get('status')!r}")
        if not isinstance(data.get("human_input_requests"), list):
            errors.append("human_input_requests must be an array")
        for label in ("project_code", "slice_id"):
            value = data.get(label)
            if not isinstance(value, str) or not IDENTIFIER_PATTERN.fullmatch(value):
                errors.append(f"invalid {label}: {value!r}")
        if not isinstance(data.get("current_stage"), str) or data.get("current_stage") not in STAGE_KEYS:
            errors.append(f"invalid current stage: {data.get('current_stage')}")
        if not isinstance(data.get("current_gate"), str) or data.get("current_gate") not in VALID_GATES:
            errors.append(f"invalid current gate: {data.get('current_gate')}")
        if not isinstance(data.get("gate_status"), str) or data.get("gate_status") not in VALID_GATE_STATUSES:
            errors.append(f"invalid gate status: {data.get('gate_status')}")
        probe_required = data.get("codebase_status") == "EXISTS" and (
            data.get("current_gate") in {"G3", "G4", "G5", "G6", "G7"}
            or (data.get("current_gate") == "GF" and data.get("gate_status") == "PASSED")
        )
        gate_components = data.get("gate_components")
        if not isinstance(gate_components, dict):
            errors.append("gate_components must be an object")
        else:
            if set(gate_components) != {"data", "backend"}:
                errors.append("gate_components must contain exactly data and backend")
            for component, status in gate_components.items():
                if not isinstance(status, str) or status not in VALID_GATE_STATUSES:
                    errors.append(f"invalid gate component status for {component}: {status}")
        stage_status = data.get("stage_status", {})
        if not isinstance(stage_status, dict):
            errors.append("stage_status must be an object")
        else:
            for stage in STAGE_KEYS:
                if stage not in stage_status:
                    errors.append(f"manifest missing stage status: {stage}")
            for stage, status in stage_status.items():
                if stage not in STAGE_KEYS:
                    errors.append(f"manifest has unknown stage: {stage}")
                if not isinstance(status, str) or status not in VALID_STATUSES:
                    errors.append(f"invalid status for {stage}: {status}")
            if "BLOCKED" in stage_status.values() and not data.get("open_blockers"):
                errors.append("BLOCKED stage requires open_blockers evidence")
            stages_to_validate = {
                stage for stage, status in stage_status.items() if stage in STAGE_REQUIRED_FILES and status == "READY"
            }
            if data.get("gate_status") == "PASSED":
                stages_to_validate.update(GATE_STAGES.get(data.get("current_gate"), []))
            if probe_required:
                stages_to_validate.add("project_foundation")
            for stage in sorted(stages_to_validate):
                for rel in STAGE_REQUIRED_FILES.get(stage, []):
                    required_stage_paths.add(rel)
                    path = root / rel
                    if not path.is_file():
                        errors.append(f"missing required stage artifact for {stage}: {rel}")
                        continue
                    try:
                        if path.suffix.lower() == ".csv":
                            with path.open(encoding="utf-8", newline="") as handle:
                                rows = list(csv.reader(handle))
                            if len(rows) < 2 or not any(any(cell.strip() for cell in row) for row in rows[1:]):
                                errors.append(f"required stage CSV has no data row for {stage}: {rel}")
                        elif not path.read_text(encoding="utf-8").strip():
                            errors.append(f"required stage artifact is empty for {stage}: {rel}")
                    except (OSError, UnicodeError, csv.Error) as exc:
                        errors.append(f"unreadable required stage artifact for {stage}: {rel}: {exc}")
                for rel in STAGE_REQUIRED_DIRS.get(stage, []):
                    if not (root / rel).is_dir():
                        errors.append(f"missing required stage directory for {stage}: {rel}")
            if data.get("gate_status") == "PASSED":
                for rel in GATE_EXTRA_FILES.get(data.get("current_gate"), []):
                    required_stage_paths.add(rel)
                    path = root / rel
                    if not path.is_file():
                        errors.append(f"missing required gate artifact: {rel}")
                    else:
                        try:
                            if not path.read_text(encoding="utf-8").strip():
                                errors.append(f"required gate artifact is empty: {rel}")
                        except (OSError, UnicodeError) as exc:
                            errors.append(f"unreadable required gate artifact: {rel}: {exc}")
        artifacts = data.get("artifacts", [])
        if not isinstance(artifacts, list):
            errors.append("artifacts must be an array")
            artifacts = []
        for artifact in artifacts:
            rel = artifact.get("path") if isinstance(artifact, dict) else None
            if not rel:
                errors.append("artifact without path")
                continue
            if not isinstance(rel, str):
                errors.append(f"artifact path must be a string: {rel!r}")
                continue
            rel_path = Path(rel)
            if rel_path.is_absolute() or ".." in rel_path.parts:
                errors.append(f"artifact path must be relative and contained: {rel}")
                continue
            candidate = (root / rel_path).resolve()
            if candidate != root and root not in candidate.parents:
                errors.append(f"artifact path escapes design root: {rel}")
            elif not candidate.exists():
                errors.append(f"missing artifact: {rel}")
        artifact_paths = {
            artifact.get("path")
            for artifact in artifacts
            if isinstance(artifact, dict) and isinstance(artifact.get("path"), str)
        }
        for ref in probe_artifact_refs:
            if ref not in artifact_paths:
                errors.append(f"project probe artifact is not registered: {ref}")
        gate_records = data.get("gate_records", [])
        if not isinstance(gate_records, list):
            errors.append("gate_records must be an array")
            gate_records = []
        valid_current_records = []
        for index, record in enumerate(gate_records):
            if not isinstance(record, dict):
                errors.append(f"gate record {index} must be an object")
                continue
            required = ("gate", "status", "checked_at", "input_versions", "evidence", "approved_by")
            missing = [key for key in required if key not in record]
            if missing:
                errors.append(f"gate record {index} missing: {','.join(missing)}")
                continue
            if (
                not isinstance(record.get("gate"), str)
                or record.get("gate") not in VALID_GATES
                or not isinstance(record.get("status"), str)
                or record.get("status") not in {"PASSED", "FAILED", "WAIVED"}
            ):
                errors.append(f"gate record {index} has invalid gate/status")
            if not isinstance(record.get("checked_at"), str) or not record.get("checked_at"):
                errors.append(f"gate record {index} has invalid checked_at")
            for field in ("input_versions", "evidence"):
                if not isinstance(record.get(field), list) or not record.get(field):
                    errors.append(f"gate record {index} has invalid {field}")
            if not isinstance(record.get("approved_by"), str) or not record.get("approved_by"):
                errors.append(f"gate record {index} has invalid approved_by")
            if record.get("gate") == data.get("current_gate") and record.get("status") == data.get("gate_status"):
                valid_current_records.append(record)
        if isinstance(data.get("gate_status"), str) and data.get("gate_status") in {"PASSED", "WAIVED"} and not valid_current_records:
            errors.append("passed or waived gate requires a complete matching gate record")
        probe_required = data.get("codebase_status") == "EXISTS" and (
            data.get("current_gate") in {"G3", "G4", "G5", "G6", "G7"}
            or (data.get("current_gate") == "GF" and data.get("gate_status") == "PASSED")
        )
        if probe_required:
            if probe_status != "PASSED":
                errors.append("existing codebase cannot pass GF or enter G3+ without project_probe.status=PASSED")
            if project_probe.get("code_version") != data.get("code_version"):
                errors.append("project_probe.code_version must match manifest code_version")
            if subagent_available is True and probe_mode != "SUBAGENT":
                errors.append("available subagent capability requires SUBAGENT project probe execution")
            if subagent_available is False and probe_mode != "PARENT_FALLBACK":
                errors.append("unavailable subagent capability requires explicit PARENT_FALLBACK")
            if subagent_available is None:
                errors.append("project probe must record whether subagent capability is available")
        if data.get("codebase_status") == "EXISTS" and data.get("current_gate") in {"G3", "G4", "G5", "G6", "G7"}:
            gf_records = [
                record
                for record in gate_records
                if isinstance(record, dict)
                and record.get("gate") == "GF"
                and record.get("status") == "PASSED"
                and data.get("code_version") in record.get("input_versions", [])
            ]
            if not gf_records:
                errors.append("existing codebase cannot enter G3+ without a PASSED GF record for code_version")
            foundation_artifacts = [
                artifact
                for artifact in artifacts
                if isinstance(artifact, dict)
                and isinstance(artifact.get("path"), str)
                and artifact["path"].startswith("00-control/project-foundation/")
            ]
            if not foundation_artifacts:
                errors.append("existing codebase cannot enter G3+ without registered project-foundation artifacts")
        if data.get("current_gate") == "G4" and data.get("gate_status") == "PASSED":
            if not isinstance(gate_components, dict) or any(gate_components.get(key) != "PASSED" for key in ("data", "backend")):
                errors.append("G4 PASSED requires data and backend components PASSED")
            if not isinstance(stage_status, dict) or any(stage_status.get(key) != "READY" for key in ("data_model", "backend_architecture")):
                errors.append("G4 PASSED requires data_model and backend_architecture READY")
        for key in ("open_blockers", "approvals", "route_history"):
            if not isinstance(data.get(key, []), list):
                errors.append(f"{key} must be an array")

    control = root / "00-control"
    work_item_rows: list[dict[str, str]] = []
    work_item_ids: set[str] = set()
    artifact_registry_paths: set[str] = set()
    for file_name, expected_headers in CSV_SCHEMAS.items():
        path = control / file_name
        if not path.is_file():
            errors.append(f"missing control ledger: {file_name}")
            continue
        try:
            with path.open(encoding="utf-8", newline="") as handle:
                reader = csv.reader(handle)
                headers = next(reader, [])
                rows = list(reader)
        except (OSError, UnicodeError) as exc:
            errors.append(f"unreadable control ledger {file_name}: {exc}")
            continue
        if headers != expected_headers:
            errors.append(f"invalid headers for {file_name}")
            continue
        if file_name == "artifact-registry.csv":
            for row_number, values in enumerate(rows, start=2):
                if len(values) != len(expected_headers):
                    errors.append(f"artifact-registry.csv row {row_number} has invalid column count")
                    continue
                row = {key: value.strip() for key, value in zip(expected_headers, values)}
                if row.get("path"):
                    artifact_registry_paths.add(row["path"])
            continue
        if file_name != "work-item-registry.csv":
            continue
        for row_number, values in enumerate(rows, start=2):
            if len(values) != len(expected_headers):
                errors.append(f"work-item-registry.csv row {row_number} has invalid column count")
                continue
            row = {key: value.strip() for key, value in zip(expected_headers, values)}
            if not any(row.values()):
                errors.append(f"work-item-registry.csv row {row_number} is empty")
                continue
            required_registration_fields = (
                "work_item_id",
                "stage",
                "item_type",
                "candidate_key",
                "state",
                "discovery_evidence",
                "registered_artifact",
                "confirmation_status",
                "baseline_version",
                "owner",
                "source_worklist",
                "updated_at",
            )
            missing_fields = [field for field in required_registration_fields if not row[field]]
            if missing_fields:
                errors.append(
                    f"work-item-registry.csv row {row_number} is not a registration; missing {','.join(missing_fields)}"
                )
            work_item_id = row["work_item_id"]
            if work_item_id in work_item_ids:
                errors.append(f"duplicate registered work_item_id: {work_item_id}")
            elif work_item_id:
                work_item_ids.add(work_item_id)
            for field in ("registered_artifact", "source_worklist"):
                rel = row[field]
                if not rel:
                    continue
                rel_path = Path(rel)
                if rel_path.suffix.lower() != ".csv" or rel_path.is_absolute() or ".." in rel_path.parts:
                    errors.append(f"work-item-registry.csv row {row_number} has invalid {field}: {rel}")
                    continue
                candidate = (root / rel_path).resolve()
                if candidate != root and root not in candidate.parents:
                    errors.append(f"work-item-registry.csv row {row_number} {field} escapes design root: {rel}")
                elif not candidate.is_file():
                    errors.append(f"work-item-registry.csv row {row_number} references missing CSV: {rel}")
            if row["confirmation_status"] in {"CONFIRMED", "BASELINED", "CONSISTENCY_CHECKED", "VERIFIED", "CLOSED"} and not row["confirmation_evidence"]:
                errors.append(
                    f"work-item-registry.csv row {row_number} confirmation requires confirmation_evidence"
                )
            work_item_rows.append(row)

    active_work_item = data.get("active_work_item")
    if active_work_item is not None and active_work_item not in work_item_ids:
        errors.append("active_work_item must reference a real work-item-registry.csv data row")
    if data.get("gate_status") in {"PASSED", "WAIVED"} and data.get("current_gate") != "G0" and not work_item_rows:
        errors.append("a passed design gate requires real work-item-registry.csv data rows")
    if probe_required:
        probe_rows = [
            row
            for row in work_item_rows
            if row.get("registered_artifact") == "00-control/project-foundation/probe-task-register.csv"
            or row.get("source_worklist") == "00-control/project-foundation/probe-task-register.csv"
        ]
        if not probe_rows:
            errors.append("project probe must be registered in work-item-registry.csv and probe-task-register.csv")
    for rel in sorted(required_stage_paths):
        if rel not in artifact_registry_paths:
            errors.append(f"required stage artifact is not registered in artifact-registry.csv: {rel}")

    if errors:
        for error in errors:
            print(f"ERROR: {error}")
        return 1
    print("design chain workspace structure: OK (gate not evaluated)")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="command", required=True)
    init_parser = sub.add_parser("init")
    init_parser.add_argument("project_root", type=Path)
    init_parser.add_argument("--project-code", required=True)
    init_parser.add_argument("--slice-id", required=True)
    for command in ("check-structure", "check"):
        check_parser = sub.add_parser(command)
        check_parser.add_argument("project_root", type=Path)
    args = parser.parse_args()
    if args.command == "init":
        return init_workspace(args.project_root.resolve(), args.project_code, args.slice_id)
    return check_workspace(args.project_root.resolve())


if __name__ == "__main__":
    sys.exit(main())
