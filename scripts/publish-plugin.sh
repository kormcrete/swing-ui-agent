#!/bin/sh
# Publishes the Claude Code plugin (skills/, agents/, .claude-plugin/plugin.json) into a checkout of the kormcrete
# marketplace repository (github.com/kormcrete/claude-plugins) as plugins/swing-ui-agent, then commits and tags it
# there. Pushing the marketplace is left to you.
#
# The plugin's version is the agent jar's: pom.xml's version 0.1.0 publishes plugin 0.1.0, so the skills a project
# installs match the jar it runs. A SNAPSHOT pom publishes previews 0.1.0-SNAPSHOT.1, .2, ... (consumers only get files
# whose version changed). Commit first: the release is built from HEAD.
#
#   scripts/publish-plugin.sh [marketplace checkout, default ../claude-plugins]
set -eu

here=$(cd "$(dirname "$0")/.." && pwd)
market=$(cd "${1:-$here/../claude-plugins}" && pwd)
plugin=swing-ui-agent
dest=$market/plugins/$plugin

jar=$(git -C "$here" show HEAD:pom.xml \
  | awk '/<parent>/{p=1} /<\/parent>/{p=0; next} !p && /<version>/{gsub(/.*<version>|<\/version>.*/, ""); print; exit}')
[ -n "$jar" ] || { echo "no project version in pom.xml" >&2; exit 1; }
case "$jar" in
  *-SNAPSHOT)
    n=$(git -C "$market" tag -l "$plugin--v$jar.*" | wc -l | tr -d ' ')
    version="$jar.$((n + 1))" ;;
  *) version=$jar ;;
esac
tag="$plugin--v$version"

[ -z "$(git -C "$here" status --porcelain -- skills agents .claude-plugin NOTICE LICENSE pom.xml)" ] \
  || { echo "commit skills/, agents/, .claude-plugin/ and pom.xml first: the release is built from HEAD" >&2; exit 1; }
[ -z "$(git -C "$market" status --porcelain)" ] || { echo "$market has uncommitted changes" >&2; exit 1; }
if git -C "$market" rev-parse -q --verify "refs/tags/$tag" >/dev/null; then
  echo "$tag is already published: a release version is published once" >&2; exit 1
fi
grep -q "\"./plugins/$plugin\"" "$market/.claude-plugin/marketplace.json" \
  || { echo "$market/.claude-plugin/marketplace.json lists no ./plugins/$plugin" >&2; exit 1; }

rm -rf "$dest"
mkdir -p "$dest"
# Committed files only, so nothing local (an untracked note, a build output) can slip into the release.
git -C "$here" archive HEAD .claude-plugin/plugin.json skills agents LICENSE NOTICE | tar -x -C "$dest"
# The version goes in right after the name; the source manifest carries none, so --plugin-dir checkouts stay unversioned.
sed -i.bak "s/^\(  \"name\": \"$plugin\",\)\$/\1\\
  \"version\": \"$version\",/" "$dest/.claude-plugin/plugin.json"
rm "$dest/.claude-plugin/plugin.json.bak"
grep -q "\"version\": \"$version\"" "$dest/.claude-plugin/plugin.json" \
  || { echo "could not write the version into plugin.json" >&2; exit 1; }

if command -v claude >/dev/null; then claude plugin validate "$market"; fi

commit=$(git -C "$here" rev-parse --short HEAD)
git -C "$market" add -A "plugins/$plugin"
git -C "$market" commit -q -m "$plugin $version, for agent jar $jar (from kormcrete/swing-ui-agent $commit)"
git -C "$market" tag -a "$tag" -m "$plugin $version, for agent jar $jar"
echo "published $tag in $market; push with: git -C $market push origin main $tag"
