#!/bin/bash
# Install git hooks

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HOOKS_DIR="$SCRIPT_DIR/.git/hooks"
SOURCE_HOOKS_DIR="$SCRIPT_DIR/.git-hooks"

echo "Installing git hooks..."

# Create hooks directory if it doesn't exist
mkdir -p "$HOOKS_DIR"

# Install commit-msg hook
if [ -f "$SOURCE_HOOKS_DIR/commit-msg" ]; then
    cp "$SOURCE_HOOKS_DIR/commit-msg" "$HOOKS_DIR/commit-msg"
    chmod +x "$HOOKS_DIR/commit-msg"
    echo "✅ Installed commit-msg hook (prevents SEO keywords)"
else
    echo "⚠️  commit-msg hook not found in $SOURCE_HOOKS_DIR"
fi

echo ""
echo "Git hooks installed successfully!"
echo ""
echo "The following checks are now active:"
echo "  - commit-msg: Prevents SEO-related keywords in commit messages"
echo ""
echo "To bypass a hook (not recommended), use: git commit --no-verify"
