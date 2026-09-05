# Recreates .claude/skills/<name> as a junction to .agents/skills/<name>.
#
# Why this exists: two tools, one copy. Claude Code reads project skills from
# .claude/skills/; the cross-tool convention (Codex, Cursor) is .agents/skills/.
# The real files live in .agents/skills/ and are the only copy in git. Before
# 2026-09-05 both paths were tracked and git held two byte-identical copies of
# the same 226 files.
#
# Run once after cloning:  powershell -ExecutionPolicy Bypass -File scripts/link-skills.ps1
# Junctions (not symlinks) are used deliberately: they need no Developer Mode
# and no administrator rights, and this repo has core.symlinks=false.

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$source = Join-Path $root '.agents/skills'
$target = Join-Path $root '.claude/skills'

if (-not (Test-Path $source)) { throw "not found: $source" }
if (-not (Test-Path $target)) { New-Item -ItemType Directory -Path $target | Out-Null }

foreach ($skill in Get-ChildItem -Path $source -Directory) {
    $link = Join-Path $target $skill.Name
    if (Test-Path $link) {
        Write-Output ("ok      " + $skill.Name)
        continue
    }
    New-Item -ItemType Junction -Path $link -Value $skill.FullName | Out-Null
    Write-Output ("linked  " + $skill.Name)
}
