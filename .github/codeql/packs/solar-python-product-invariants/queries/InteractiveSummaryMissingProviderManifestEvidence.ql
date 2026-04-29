/**
 * @name Interactive session summary is missing provider-manifest evidence
 * @description Hosted Android visual proof should summarize provider-manifest availability so reviewers know which native Android capability surface was active.
 * @kind problem
 * @problem.severity warning
 * @precision high
 * @id py/solar/interactive-summary-missing-provider-manifest-evidence
 * @tags maintainability
 *       product-invariants
 *       android
 */

import python

predicate interactiveSummaryFile(File file) {
  file.getRelativePath() = ".github/scripts/write_interactive_session_summary.py" or
  file.getRelativePath() = ".github/scripts/test_interactive_session_summary.py"
}

predicate fileMentions(File file, string pattern) {
  exists(StringLiteral literal |
    literal.getEnclosingModule().getFile() = file and
    literal.getText().regexpMatch(pattern)
  )
}

from File file
where
  interactiveSummaryFile(file) and
  not (
    fileMentions(file, "(?s).*provider_manifest.*") and
    fileMentions(file, "(?s).*taxonomy_source.*")
  )
select file,
  "Interactive Android session summaries should preserve provider-manifest and taxonomy-source evidence for visual acceptance review."
