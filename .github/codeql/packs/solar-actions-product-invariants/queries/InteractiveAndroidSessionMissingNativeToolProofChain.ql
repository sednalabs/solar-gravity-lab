/**
 * @name Interactive Android session is missing native tool proof chain
 * @description The hosted interactive session should build the Android provider, run the live session, summarize native tool schema/proof evidence, upload artifacts, and fail closed on action-required summaries.
 * @kind problem
 * @problem.severity warning
 * @precision high
 * @id actions/solar/interactive-android-session-missing-native-tool-proof-chain
 * @tags maintainability
 *       product-invariants
 *       android
 *       computer-use
 */

import actions

predicate interactiveSessionWorkflow(Workflow workflow) {
  workflow.toString() = ".github/workflows/interactive-android-session.yml"
}

predicate checksOutAndroidProvider(Workflow workflow) {
  exists(UsesStep step, string repository |
    step.getEnclosingWorkflow() = workflow and
    step.getCallee().regexpMatch("actions/checkout(@.*)?") and
    repository = step.getArgument("repository") and
    repository.regexpMatch("(?s).*sednalabs/android-emulator-mcp.*")
  )
}

predicate checksOutToolkitWorkspace(Workflow workflow) {
  exists(UsesStep step, string repository |
    step.getEnclosingWorkflow() = workflow and
    step.getCallee().regexpMatch("actions/checkout(@.*)?") and
    repository = step.getArgument("repository") and
    repository.regexpMatch("(?s).*GraciousGazelles/toolkits-mcp-toolkit-rs.*")
  )
}

predicate buildsProviderBinary(Workflow workflow) {
  exists(Run run |
    run.getEnclosingWorkflow() = workflow and
    run.getScript().getRawScript().regexpMatch("(?s).*cargo build --release --locked.*")
  )
}

predicate runsHostedSessionScript(Workflow workflow) {
  exists(UsesStep step, string script |
    step.getEnclosingWorkflow() = workflow and
    step.getCallee().regexpMatch("reactivecircus/android-emulator-runner(@.*)?") and
    script = step.getArgument("script") and
    script.regexpMatch("(?s).*run_interactive_android_session\\.sh.*")
  )
}

predicate writesInteractiveSummary(Workflow workflow) {
  exists(Run run |
    run.getEnclosingWorkflow() = workflow and
    run.getScript().getRawScript().regexpMatch(
      "(?s).*write_interactive_session_summary\\.py.*--artifacts-dir.*INTERACTIVE_SESSION_ROOT.*--output-json.*interactive-session-summary\\.json.*--output-md.*interactive-session-summary\\.md.*"
    )
  )
}

predicate uploadsInteractiveArtifacts(Workflow workflow) {
  exists(UsesStep step, string path |
    step.getEnclosingWorkflow() = workflow and
    step.getCallee().regexpMatch("actions/upload-artifact(@.*)?") and
    path = step.getArgument("path") and
    path.regexpMatch("(?s).*dist/interactive-session/\\*\\*.*") and
    path.regexpMatch("(?s).*dist/interactive-session-summary/\\*\\*.*")
  )
}

predicate gatesOnActionRequiredSummary(Workflow workflow) {
  exists(Run run |
    run.getEnclosingWorkflow() = workflow and
    run.getScript().getRawScript().regexpMatch(
      "(?s).*interactive-session-summary\\.json.*summary\\.get\\(\"summary\", \\{\\}\\).*status != \"success\".*"
    )
  )
}

from Workflow workflow
where
  interactiveSessionWorkflow(workflow) and
  not (
    checksOutAndroidProvider(workflow) and
    checksOutToolkitWorkspace(workflow) and
    buildsProviderBinary(workflow) and
    runsHostedSessionScript(workflow) and
    writesInteractiveSummary(workflow) and
    uploadsInteractiveArtifacts(workflow) and
    gatesOnActionRequiredSummary(workflow)
  )
select workflow,
  "interactive-android-session should preserve the native Android tool proof chain: provider checkout/build, hosted session execution, schema/proof summary, uploaded evidence artifacts, and fail-closed summary gating."
