/**
 * @name Workflow-level write permissions
 * @description Write-scoped GITHUB_TOKEN permissions should be granted to the narrow job that needs them, not at workflow scope.
 * @kind problem
 * @problem.severity error
 * @precision high
 * @id actions/solar/workflow-level-write-permissions
 * @tags security
 *       external/cwe/cwe-275
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

from Workflow workflow, Permissions permissions, string permission
where
  permissions = workflow.getPermissions() and
  writePermissionName(permission) and
  (
    permissions.getPermission(permission) = "write" or
    permissions.toString().regexpMatch("(?is).*write-all.*")
  )
select permissions,
  "Workflow-level `" + permission + ": write` grants write access to every job. Move this permission to the specific publishing or analysis job that needs it."
