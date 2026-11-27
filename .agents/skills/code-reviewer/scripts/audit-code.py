#!/usr/bin/env python3
"""
FSP Automated Code Reviewer & Static Analysis Audit Script
Audits the repository (or changed files in git diff) against FSP standards:
- Concurrency & Deadlock safety (synchronized, @Version, external I/O in transactions)
- REST collection standards (pagination, unbounded List endpoints)
- TigerBeetle driver isolation
- Plugin process & schema isolation
- FAPI 2.0 security & secret leakage
- Frontend design tokens & widget standards
"""

import sys
import os
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]

class AuditIssue:
    def __init__(self, severity, category, file_path, line_no, message):
        self.severity = severity  # 'CRITICAL', 'HIGH', 'MEDIUM', 'LOW'
        self.category = category
        self.file_path = file_path
        self.line_no = line_no
        self.message = message

    def __str__(self):
        return f"[{self.severity}] ({self.category}) {self.file_path}:{self.line_no} - {self.message}"

def get_target_files(only_diff=False):
    if only_diff:
        try:
            res = subprocess.run(
                ["git", "diff", "--name-only", "HEAD"],
                cwd=ROOT,
                capture_output=True,
                text=True,
                check=True
            )
            files = [ROOT / f for f in res.stdout.splitlines() if f.strip()]
            return [f for f in files if f.exists() and f.is_file()]
        except Exception as e:
            print(f"Warning: git diff failed ({e}), scanning all files.", file=sys.stderr)
    
    target_files = []
    for ext in (".java", ".ts", ".tsx", ".sql"):
        for p in ROOT.rglob(f"*{ext}"):
            # Skip build dirs, node_modules, generated, etc.
            s = str(p)
            if any(skip in s for skip in ("/build/", "/node_modules/", "/.gradle/", "/dist/", "/docs/generated/")):
                continue
            target_files.append(p)
    return target_files

def check_java_concurrency_and_deadlocks(file_path, content, lines):
    issues = []
    is_test = "/test/" in str(file_path)
    rel_path = file_path.relative_to(ROOT)

    # 1. Detect 'synchronized' keyword in production code
    if not is_test:
        for idx, line in enumerate(lines, 1):
            if re.search(r'\bsynchronized\b', line) and not line.strip().startswith("//"):
                issues.append(AuditIssue(
                    "CRITICAL", "Concurrency", rel_path, idx,
                    "Found 'synchronized' keyword. Shared singleton synchronization causes thread contention and deadlocks. Use fair KeyLocks or DB optimistic locking."
                ))

    # 2. Check for mutable @Entity without @Version
    if "@Entity" in content and not is_test:
        if "@Version" not in content:
            # Check if it has a status column or state machine
            if any(keyword in content for keyword in ("status", "State", "balance", "amount")):
                issues.append(AuditIssue(
                    "HIGH", "Optimistic Locking", rel_path, 1,
                    "Entity with mutable state/status lacks @Version column. Optimistic locking is required to prevent lost updates and race conditions."
                ))

    # 3. Check for external I/O inside @Transactional methods
    if "@Transactional" in content and not is_test:
        in_tx = False
        tx_line = 0
        for idx, line in enumerate(lines, 1):
            if "@Transactional" in line:
                in_tx = True
                tx_line = idx
            if in_tx:
                if re.search(r'\b(tbService\.|restTemplate\.|webClient\.|httpClient\.|Thread\.sleep)\b', line):
                    issues.append(AuditIssue(
                        "CRITICAL", "Deadlock Risk", rel_path, idx,
                        f"External I/O or sleep called within @Transactional boundary (opened at line {tx_line}). Holding DB transactions across network I/O causes connection starvation and deadlocks."
                    ))
                if line.strip().startswith("public ") and idx > tx_line + 2:
                    in_tx = False

    return issues

def check_tigerbeetle_isolation(file_path, content, lines):
    issues = []
    rel_path = str(file_path.relative_to(ROOT))
    
    # Only TbService and TigerBeetleConfig may import com.tigerbeetle.*
    allowed_tb = ("TbService.java", "TigerBeetleConfig.java", "TbResult.java", "TbClient.java")
    if not any(f in rel_path for f in allowed_tb) and "/test/" not in rel_path:
        for idx, line in enumerate(lines, 1):
            if "import com.tigerbeetle" in line:
                issues.append(AuditIssue(
                    "CRITICAL", "TB Driver Isolation", rel_path, idx,
                    "Direct import of 'com.tigerbeetle.*' forbidden. TigerBeetle is accessed exclusively via TbService."
                ))
    return issues

def check_rest_collection_standards(file_path, content, lines):
    issues = []
    if not str(file_path).endswith("Controller.java"):
        return issues
    rel_path = file_path.relative_to(ROOT)

    for idx, line in enumerate(lines, 1):
        # Look for GET methods returning List<T> instead of Page<T>
        if "@GetMapping" in line or "@RequestMapping" in line:
            # Check following lines for signature
            window = "".join(lines[idx:min(idx+5, len(lines))])
            if re.search(r'public\s+(?:ResponseEntity<)?List<', window):
                if not re.search(r'Pageable|@RequestParam.*(?:page|size)', window):
                    issues.append(AuditIssue(
                        "HIGH", "REST Standard", rel_path, idx,
                        "Collection endpoint returns unbounded List<T>. Must support server-side pagination (Page<T> or Pageable) by default."
                    ))
    return issues

def check_plugin_isolation(file_path, content, lines):
    issues = []
    rel_path = str(file_path.relative_to(ROOT))
    if "/backend/plugins/" in rel_path:
        for idx, line in enumerate(lines, 1):
            if re.search(r'import\s+com\.fsp\.core\.(tenant|ledger|journal|approval|rbac)\.(domain|repository|entity)', line):
                issues.append(AuditIssue(
                    "CRITICAL", "Plugin Isolation", rel_path, idx,
                    "Plugin directly imports internal Core domain/repository. Plugins must be isolated processes with their own DB and talk via Gateway REST APIs."
                ))
    return issues

def check_fapi_and_secret_leakage(file_path, content, lines):
    issues = []
    rel_path = file_path.relative_to(ROOT)
    is_test = "/test/" in str(file_path)

    for idx, line in enumerate(lines, 1):
        # Check for logging passwords or tokens
        if not is_test and re.search(r'\blog\.(info|debug|warn|error)\(.*(password|token|dpop|credential|secret).*\)', line, re.IGNORECASE):
            if "masked" not in line.lower() and "hash" not in line.lower() and "status" not in line.lower():
                issues.append(AuditIssue(
                    "HIGH", "FAPI2 / Security", rel_path, idx,
                    "Possible credential, token, or secret logging. Full tokens, passwords, and DPoP proofs must never be logged."
                ))
        if not is_test and re.search(r'System\.(out|err)\.println|printStackTrace\(', line):
            issues.append(AuditIssue(
                "MEDIUM", "Code Quality", rel_path, idx,
                "Avoid System.out.println or printStackTrace(). Use Lombok @Slf4j structured incident logging."
            ))
    return issues

def check_frontend_standards(file_path, content, lines):
    issues = []
    if not (str(file_path).endswith(".ts") or str(file_path).endswith(".tsx")):
        return issues
    rel_path = str(file_path.relative_to(ROOT))
    if "/test/" in rel_path or "tests/" in rel_path:
        return issues

    for idx, line in enumerate(lines, 1):
        if line.strip().startswith("//") or line.strip().startswith("/*"):
            continue
        # Raw hex color in components outside tokens
        if ("src/portals/" in rel_path or "src/components/" in rel_path) and "design-system" not in rel_path:
            if re.search(r'["\']#[0-9a-fA-F]{3,8}["\']', line) and "portal-config.ts" not in rel_path:
                issues.append(AuditIssue(
                    "MEDIUM", "UI Tokens", rel_path, idx,
                    "Hardcoded hex color found. UI Standard mandates semantic design tokens only."
                ))
    return issues

def run_audit(target_files):
    all_issues = []
    for f in target_files:
        try:
            content = f.read_text(encoding="utf-8", errors="ignore")
            lines = content.splitlines()
        except Exception:
            continue

        if f.suffix == ".java":
            all_issues.extend(check_java_concurrency_and_deadlocks(f, content, lines))
            all_issues.extend(check_tigerbeetle_isolation(f, content, lines))
            all_issues.extend(check_rest_collection_standards(f, content, lines))
            all_issues.extend(check_plugin_isolation(f, content, lines))
            all_issues.extend(check_fapi_and_secret_leakage(f, content, lines))
        elif f.suffix in (".ts", ".tsx"):
            all_issues.extend(check_frontend_standards(f, content, lines))

    return all_issues

def main():
    only_diff = "--diff" in sys.argv
    files = get_target_files(only_diff=only_diff)
    mode_str = "git diff changes" if only_diff else "codebase"
    print(f"=== FSP Code Reviewer & Concurrency Audit (Scanning {len(files)} files in {mode_str}) ===")
    
    issues = run_audit(files)
    
    criticals = [i for i in issues if i.severity == "CRITICAL"]
    highs = [i for i in issues if i.severity == "HIGH"]
    mediums = [i for i in issues if i.severity == "MEDIUM"]

    for issue in issues:
        print(issue)

    print("\n--- Audit Summary ---")
    print(f"Critical: {len(criticals)} | High: {len(highs)} | Medium: {len(mediums)} | Total: {len(issues)}")
    
    if criticals:
        print("FAILED: Critical concurrency, deadlock, or architectural boundary violations detected.")
        sys.exit(1)
    elif highs:
        print("WARNING: High-priority review issues found.")
        sys.exit(0)
    else:
        print("PASSED: No critical defects found.")
        sys.exit(0)

if __name__ == "__main__":
    main()
