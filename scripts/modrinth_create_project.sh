#!/usr/bin/env bash
# Bootstraps the Seedscout Nav project on Modrinth: a single multipart
# POST to https://api.modrinth.com/v2/project carrying the project
# metadata (as a JSON "data" part) and the mod icon (as an "icon" file
# part), matching Modrinth's project-creation API.
#
# This is a ONE-TIME step. Modrinth projects cannot be created through the
# ongoing publish workflow (.github/workflows/publish.yml); mc-publish only
# uploads new versions to a project that already exists. Run this script
# once, by hand, then record the returned project id/slug as the
# MODRINTH_PROJECT_ID repository variable (see the Publishing section of
# README.md for the full human runbook).
#
# Requires: curl, jq.
#
# Usage:
#   MODRINTH_TOKEN=<token> ./scripts/modrinth_create_project.sh
#   ./scripts/modrinth_create_project.sh --dry-run
#
# The token needs the PROJECT_CREATE scope at minimum; PROJECT_WRITE is
# also useful on the same token for any follow-up edits made by hand. Not
# required for --dry-run, which makes no network call.

set -euo pipefail

DRY_RUN=0
for arg in "$@"; do
  case "$arg" in
    --dry-run)
      DRY_RUN=1
      ;;
    -h|--help)
      sed -n '2,26p' "$0"
      exit 0
      ;;
    *)
      echo "Unknown argument: $arg" >&2
      echo "Usage: $0 [--dry-run]" >&2
      exit 1
      ;;
  esac
done

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

SUMMARY_FILE="$REPO_ROOT/docs/listing/summary.txt"
BODY_FILE="$REPO_ROOT/docs/listing/description.md"
ICON_FILE="$REPO_ROOT/src/main/resources/assets/seedscout-nav/icon.png"

for f in "$SUMMARY_FILE" "$BODY_FILE" "$ICON_FILE"; do
  if [ ! -f "$f" ]; then
    echo "Missing required file: $f" >&2
    exit 1
  fi
done

command -v jq >/dev/null 2>&1 || { echo "jq is required but not installed." >&2; exit 1; }
command -v curl >/dev/null 2>&1 || { echo "curl is required but not installed." >&2; exit 1; }

# Modrinth's API guidelines ask for a User-Agent that identifies the
# project and gives a way to reach the maintainer. This uses the public
# repository URL, deliberately not a personal email address, so nothing
# personally identifying is sent to a third-party server.
USER_AGENT="Seedscout-app/seedscout-nav (https://github.com/Seedscout-app/seedscout-nav)"

SLUG="seedscout-nav"
TITLE="Seedscout Nav"
DESCRIPTION="$(cat "$SUMMARY_FILE")"
BODY="$(cat "$BODY_FILE")"

PAYLOAD="$(jq -n \
  --arg project_type "mod" \
  --arg slug "$SLUG" \
  --arg title "$TITLE" \
  --arg description "$DESCRIPTION" \
  --arg body "$BODY" \
  --arg client_side "required" \
  --arg server_side "unsupported" \
  --arg license_id "Apache-2.0" \
  --arg issues_url "https://github.com/Seedscout-app/seedscout-nav/issues" \
  --arg source_url "https://github.com/Seedscout-app/seedscout-nav" \
  --argjson categories '["utility", "adventure"]' \
  '{
    project_type: $project_type,
    slug: $slug,
    title: $title,
    description: $description,
    body: $body,
    categories: $categories,
    client_side: $client_side,
    server_side: $server_side,
    license_id: $license_id,
    issues_url: $issues_url,
    source_url: $source_url
  }')"

if [ "$DRY_RUN" -eq 1 ]; then
  echo "--- Dry run: no network call made ---"
  echo "POST https://api.modrinth.com/v2/project"
  echo "User-Agent: $USER_AGENT"
  echo "Icon file: $ICON_FILE"
  echo "data part:"
  echo "$PAYLOAD" | jq .
  exit 0
fi

if [ -z "${MODRINTH_TOKEN:-}" ]; then
  echo "MODRINTH_TOKEN is not set. Export a Modrinth PAT with the PROJECT_CREATE scope, or pass --dry-run to preview the request without one." >&2
  exit 1
fi

RESPONSE="$(curl -sS -w '\n%{http_code}' \
  -X POST "https://api.modrinth.com/v2/project" \
  -H "Authorization: ${MODRINTH_TOKEN}" \
  -H "User-Agent: ${USER_AGENT}" \
  -F "data=${PAYLOAD};type=application/json" \
  -F "icon=@${ICON_FILE};type=image/png")"

HTTP_STATUS="$(echo "$RESPONSE" | tail -n1)"
BODY_RESPONSE="$(echo "$RESPONSE" | sed '$d')"

echo "$BODY_RESPONSE" | jq . 2>/dev/null || echo "$BODY_RESPONSE"

if [ "$HTTP_STATUS" -lt 200 ] || [ "$HTTP_STATUS" -ge 300 ]; then
  echo "Modrinth API request failed (HTTP $HTTP_STATUS)." >&2
  exit 1
fi

echo "" >&2
echo "Project created. Record the \"id\" field above as the MODRINTH_PROJECT_ID repository variable." >&2
