#!/usr/bin/env bash
# Purges expired in-app reports from the private feedback repository: their attachments from the
# whole history of the `feedback-assets` branch, then the issues themselves.
#
# The maintainer runs it by hand (see "Deleting reports" in docs/feedback.md). Without --apply it
# only prints what it would remove. It never touches any other branch.
#
# Usage:
#   scripts/purge-feedback-assets.sh [--repo owner/name] [--months 11] [--before YYYY-MM-DD]
#                                    [--issue N]... [--folder reports/<date>-<id>]... [--apply]
#
#   --months N    reports whose issue was closed more than N months ago expire (default 11)
#   --before D    use this cutoff date instead of --months
#   --issue N     also purge this issue and its attachments now (a deletion request), even if open
#   --folder P    also purge this attachment folder now
#   --apply       rewrite and force-push `feedback-assets`, then delete the issues
#
# Needs: gh (signed in with admin rights on the repository, to delete issues), git, git-filter-repo.
set -euo pipefail
export LC_ALL=C # sort and comm must agree on the order

REPO="${FEEDBACK_REPO:-TeoVader/finalish-feedback}"
BRANCH="feedback-assets"
MONTHS=11 # run monthly: one month under the 12 the privacy policy promises
BEFORE=""
APPLY=0
REQUESTED_ISSUES=()
REQUESTED_FOLDERS=()
FOLDER_RE='reports/[0-9]{4}-[0-9]{2}-[0-9]{2}-[0-9a-f]+'

die() { echo "error: $*" >&2; exit 1; }

while [ $# -gt 0 ]; do
  case "$1" in
    --repo) REPO="$2"; shift 2 ;;
    --months) MONTHS="$2"; shift 2 ;;
    --before) BEFORE="$2"; shift 2 ;;
    --issue) REQUESTED_ISSUES+=("$2"); shift 2 ;;
    --folder) REQUESTED_FOLDERS+=("${2%/}"); shift 2 ;;
    --apply) APPLY=1; shift ;;
    -h|--help) awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "$0"; exit 0 ;;
    *) die "unknown option $1 (see --help)" ;;
  esac
done

[[ "$MONTHS" =~ ^[0-9]+$ ]] || die "--months takes a number"
for n in "${REQUESTED_ISSUES[@]}"; do [[ "$n" =~ ^[0-9]+$ ]] || die "--issue takes a number"; done
for f in "${REQUESTED_FOLDERS[@]}"; do [[ "$f" =~ ^${FOLDER_RE}$ ]] || die "not a report folder: $f"; done
command -v gh >/dev/null || die "gh is not installed"
if [ "$APPLY" = 1 ]; then
  git filter-repo --version >/dev/null 2>&1 || die "git-filter-repo is not installed (e.g. pipx install git-filter-repo)"
fi

if [ -z "$BEFORE" ]; then
  BEFORE="$(date -u -d "-${MONTHS} months" +%F 2>/dev/null || date -u -v-"${MONTHS}"m +%F)"
fi
[[ "$BEFORE" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]] || die "--before takes YYYY-MM-DD"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

echo "Repository: $REPO, branch: $BRANCH"
echo "Cutoff: issues closed before $BEFORE (and unreferenced folders uploaded before it)"

# number <TAB> state <TAB> closedAt <TAB> space-separated folders linked from the body
gh issue list --repo "$REPO" --state all --limit 100000 \
  --json number,state,closedAt,body \
  --jq ".[] | [.number, .state, (.closedAt // \"\"), ([.body // \"\" | scan(\"${FOLDER_RE}\")] | unique | join(\" \"))] | @tsv" \
  > "$WORK/issues.tsv"

# Issues to delete: closed before the cutoff, plus the requested ones.
{
  awk -F'\t' -v cutoff="$BEFORE" '$2 == "CLOSED" && $3 != "" && substr($3, 1, 10) < cutoff { print $1 }' "$WORK/issues.tsv"
  for n in "${REQUESTED_ISSUES[@]}"; do echo "$n"; done
} | sort -nu > "$WORK/expired-issues.txt"
for n in "${REQUESTED_ISSUES[@]}"; do
  grep -q "^${n}"$'\t' "$WORK/issues.tsv" || die "issue #$n not found in $REPO"
done

# Every folder that ever existed on the branch, from its full history (not only the current tree).
gh repo clone "$REPO" "$WORK/assets" -- --quiet --single-branch --branch "$BRANCH" --no-tags \
  || die "could not clone $BRANCH of $REPO"
git -C "$WORK/assets" log --format= --name-only HEAD -- reports/ \
  | grep -oE "^${FOLDER_RE}" | sort -u > "$WORK/history-folders.txt" || true

# Folders still linked from an issue that stays.
awk -F'\t' 'NR == FNR { gone[$1] = 1; next } !($1 in gone) { n = split($4, f, " "); for (i = 1; i <= n; i++) print f[i] }' \
  "$WORK/expired-issues.txt" "$WORK/issues.tsv" | sort -u > "$WORK/kept-folders.txt"

{
  # Folders of the expiring issues.
  awk -F'\t' 'NR == FNR { gone[$1] = 1; next } ($1 in gone) { n = split($4, f, " "); for (i = 1; i <= n; i++) print f[i] }' \
    "$WORK/expired-issues.txt" "$WORK/issues.tsv"
  # Folders no issue links to (the issue was deleted, or never created), uploaded before the cutoff.
  comm -23 "$WORK/history-folders.txt" "$WORK/kept-folders.txt" \
    | awk -v cutoff="$BEFORE" 'substr($0, 9, 10) < cutoff'
  for f in "${REQUESTED_FOLDERS[@]}"; do echo "$f"; done
} | sort -u | comm -12 - "$WORK/history-folders.txt" > "$WORK/purge-folders.txt"

# A folder still linked from an issue that stays is purged only when asked for with --folder.
for f in "${REQUESTED_FOLDERS[@]}"; do echo "$f"; done | sort -u > "$WORK/requested-folders.txt"
comm -12 "$WORK/purge-folders.txt" "$WORK/kept-folders.txt" | comm -23 - "$WORK/requested-folders.txt" > "$WORK/shared.txt"
if [ -s "$WORK/shared.txt" ]; then
  echo "Keeping folders still linked from an issue that stays:"
  sed 's/^/  /' "$WORK/shared.txt"
  comm -23 "$WORK/purge-folders.txt" "$WORK/shared.txt" > "$WORK/purge.tmp"
  mv "$WORK/purge.tmp" "$WORK/purge-folders.txt"
fi

echo
echo "Issues to delete ($(wc -l < "$WORK/expired-issues.txt")):"
sed 's/^/  #/' "$WORK/expired-issues.txt"
echo "Folders to purge from the history of $BRANCH ($(wc -l < "$WORK/purge-folders.txt")):"
sed 's/^/  /' "$WORK/purge-folders.txt"

if [ "$APPLY" != 1 ]; then
  echo
  echo "Dry run: nothing changed. Re-run with --apply to purge."
  exit 0
fi
if [ ! -s "$WORK/expired-issues.txt" ] && [ ! -s "$WORK/purge-folders.txt" ]; then
  echo "Nothing to purge."
  exit 0
fi

echo
read -r -p "This rewrites $BRANCH on $REPO and deletes the issues above for good. Type purge to continue: " answer
[ "$answer" = "purge" ] || die "cancelled"

if [ -s "$WORK/purge-folders.txt" ]; then
  url="$(git -C "$WORK/assets" remote get-url origin)"
  old="$(git -C "$WORK/assets" rev-parse HEAD)"
  sed 's|$|/|' "$WORK/purge-folders.txt" > "$WORK/paths.txt"
  # filter-repo drops the origin remote; the push below names the URL instead.
  git -C "$WORK/assets" filter-repo --force --invert-paths --paths-from-file "$WORK/paths.txt"
  if git -C "$WORK/assets" rev-parse --verify -q "refs/heads/$BRANCH" >/dev/null; then
    # Refuses if the relay stored a new report since the clone: run the script again.
    git -C "$WORK/assets" push --force-with-lease="refs/heads/$BRANCH:$old" "$url" "refs/heads/$BRANCH:refs/heads/$BRANCH"
  else
    echo "Every commit of $BRANCH was purged: delete the branch by hand (the relay recreates it):"
    echo "  gh api -X DELETE repos/$REPO/git/refs/heads/$BRANCH"
  fi
fi

while read -r n; do
  gh issue delete "$n" --repo "$REPO" --yes
done < "$WORK/expired-issues.txt"

echo
echo "Done. Still to do by hand (docs/feedback.md, \"Deleting reports\"):"
echo "  - re-clone or delete every other clone of $REPO that fetched $BRANCH;"
echo "  - ask GitHub Support to remove cached views and unreferenced commits of $REPO."
