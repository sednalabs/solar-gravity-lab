/**
 * @name Interactive session summary is missing native tool schema contract
 * @description The interactive summary helper and its tests should preserve native Android tool names, dynamic-tool specs, observe proof validation, outcome taxonomy, and fail-closed status semantics.
 * @kind problem
 * @problem.severity warning
 * @precision high
 * @id py/solar/interactive-summary-missing-native-tool-schema-contract
 * @tags maintainability
 *       product-invariants
 *       android
 *       computer-use
 */

import python

predicate summaryFile(File file) {
  file.getRelativePath() = ".github/scripts/write_interactive_session_summary.py"
}

predicate summaryTestFile(File file) {
  file.getRelativePath() = ".github/scripts/test_interactive_session_summary.py"
}

predicate fileHasLiteral(File file, string text) {
  exists(StringLiteral literal |
    literal.getEnclosingModule().getFile() = file and
    literal.getText() = text
  )
}

predicate summaryPreservesNativeToolSchema(File file) {
  summaryFile(file) and
  fileHasLiteral(file, "codex_bridge") and
  fileHasLiteral(file, "codex_dynamic_tool_proof_validation") and
  fileHasLiteral(file, "codex_provider_manifest_validation") and
  fileHasLiteral(file, "dynamic_tool_specs_status") and
  fileHasLiteral(file, "dynamic_tool_proof_status") and
  fileHasLiteral(file, "dynamic_tool_outcome_contract_proven") and
  fileHasLiteral(file, "dynamic_tool_outcome_success") and
  fileHasLiteral(file, "tool_names") and
  fileHasLiteral(file, "android_observe") and
  fileHasLiteral(file, "android_step") and
  fileHasLiteral(file, "response_success") and
  fileHasLiteral(file, "outcome_status") and
  fileHasLiteral(file, "outcome_retryability") and
  fileHasLiteral(file, "taxonomy_source")
}

predicate testsNativeToolSchemaFailureModes(File file) {
  summaryTestFile(file) and
  fileHasLiteral(file, "dynamic_tool_specs_status") and
  fileHasLiteral(file, "dynamic_tool_proof_status") and
  fileHasLiteral(file, "dynamic_tool_outcome_contract_proven") and
  fileHasLiteral(file, "dynamic_tool_outcome_success") and
  fileHasLiteral(file, "android_observe") and
  fileHasLiteral(file, "android_step") and
  fileHasLiteral(file, "response_success") and
  fileHasLiteral(file, "provider_unavailable") and
  fileHasLiteral(file, "operator_required") and
  fileHasLiteral(file, "action_required") and
  fileHasLiteral(file, "- error details: `see uploaded proof-validation artifact`")
}

from File file
where
  summaryFile(file) and
  not (
    summaryPreservesNativeToolSchema(file) and
    exists(File testFile | testsNativeToolSchemaFailureModes(testFile))
  )
select file,
  "Interactive session summaries should preserve native Android tool schema fields and test fail-closed behavior for invalid or unsuccessful dynamic-tool proof."
