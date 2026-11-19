#!/usr/bin/env bash
# Renders every ```mermaid block of the repo's Markdown with a pinned mermaid-cli image and fails on a parse error.
# Output goes to a temp directory, never into the working tree. Run from the repository root.
set -euo pipefail

IMAGE="ghcr.io/mermaid-js/mermaid-cli/mermaid-cli:12.0.0"
ROOT="$(pwd)"
OUT="$(mktemp -d)"
chmod 777 "$OUT"  # the container user is not the runner user
trap 'rm -rf "$OUT"' EXIT

# The image ships /usr/bin/chromium but not the Chrome that Puppeteer downloads.
echo '{"executablePath":"/usr/bin/chromium","args":["--no-sandbox"]}' > "$OUT/puppeteer.json"
chmod 644 "$OUT/puppeteer.json"

failed=0
checked=0
while IFS= read -r file; do
  grep -q '^```mermaid' "$file" || continue
  checked=$((checked + 1))
  echo "== $file"
  if ! docker run --rm -v "$ROOT":/data:ro -v "$OUT":/out "$IMAGE" \
      -p /out/puppeteer.json -i "/data/$file" -o "/out/$(echo "$file" | tr / _)"; then
    echo "::error file=$file::invalid Mermaid block"
    failed=1
  fi
done < <(git ls-files 'README*.md' 'docs/*.md')

echo "Checked $checked Markdown files with Mermaid blocks."
exit "$failed"
