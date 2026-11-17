#!/bin/sh
# Spec 011 (FR-032, FR-033): a wiki edit deploys the documentation site only when its editor has push access.
#
# Input comes from the environment only (the workflow passes event data through env:, never into the script text):
#   EDITOR_LOGIN        the wiki editor (github.event.sender.login of the gollum event)
#   GITHUB_REPOSITORY   owner/repo
#   GH_TOKEN            token for `gh api` (the workflow token is enough: it reads collaborator permissions)
# Exit 0 for admin, maintain or write; exit 1 otherwise, including any API error (fails closed).
set -u

login=${EDITOR_LOGIN:-}
repo=${GITHUB_REPOSITORY:-}

if [ -z "$repo" ]; then
  echo "::error::GITHUB_REPOSITORY is not set; not deploying"
  exit 1
fi
# GitHub logins are letters, digits and single hyphens (apps end in [bot]). Anything else is rejected without
# echoing it, so a crafted value can neither reach the API path nor inject workflow commands into the log.
name=${login%"[bot]"}
case $name in
  ''|-*|*[!A-Za-z0-9-]*)
    echo "::error::the wiki editor's login is missing or malformed; not deploying"
    exit 1
    ;;
esac
if [ ${#name} -gt 39 ]; then
  echo "::error::the wiki editor's login is missing or malformed; not deploying"
  exit 1
fi

if ! permission=$(gh api "repos/$repo/collaborators/$login/permission" --jq .permission 2>/dev/null); then
  echo "::error::wiki edit by $login: could not read the editor's permission (not a collaborator?); not deploying"
  exit 1
fi

case $permission in
  admin|maintain|write)
    echo "wiki edit by $login ($permission): allowed to deploy"
    exit 0
    ;;
esac
echo "::error::wiki edit by $login who has no push access ($permission); not deploying"
exit 1
