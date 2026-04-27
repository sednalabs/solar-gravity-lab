/**
 * @name Privileged job uses input-driven checkout ref
 * @description Jobs with write-scoped GITHUB_TOKEN permissions should not check out refs controlled by workflow inputs or event payload fields.
 * @kind problem
 * @problem.severity error
 * @precision high
 * @id actions/solar/privileged-job-input-driven-ref
 * @tags security
 *       external/cwe/cwe-829
 */

import actions

predicate writePermissionName(string permission) {
  permission = "actions" or
  permission = "attestations" or
  permission = "checks" or
  permission = "contents" or
  permission = "deployments" or
  permission = "discussions" or
  permission = "id-token" or
  permission = "issues" or
  permission = "packages" or
  permission = "pages" or
  permission = "pull-requests" or
  permission = "security-events" or
  permission = "statuses"
}

predicate jobHasWritePermission(Job job) {
  exists(Permissions permissions, string permission |
    permissions = job.getPermissions() and
    writePermissionName(permission) and
    (
      permissions.getPermission(permission) = "write" or
      permissions.toString().regexpMatch("(?is).*write-all.*")
    )
  )
}

from UsesStep step, string ref
where
  step.getCallee() = "actions/checkout" and
  ref = step.getArgument("ref") and
  (
    ref.regexpMatch("(?s).*\\$\\{\\{[^}]*inputs\\..*") or
    ref.regexpMatch("(?s).*\\$\\{\\{[^}]*github\\.event\\..*") or
    ref.regexpMatch("(?s).*\\$\\{\\{[^}]*github\\.head_ref.*") or
    ref.regexpMatch("(?s).*\\$\\{\\{[^}]*github\\.ref_name.*")
  ) and
  jobHasWritePermission(step.getEnclosingJob())
select step,
  "This write-privileged job checks out an input- or event-controlled ref. Split checkout/build from publishing, or validate the ref before granting write permissions."
