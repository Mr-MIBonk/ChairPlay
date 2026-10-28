# ChairPlay project instructions

Read and follow [CLAUDE.md](CLAUDE.md) for the project's development, build, and test rules.

## Updating upstream

- Audit ChairPlay's patches, copied classes, wrappers, and bug workarounds against every upstream update.
- When upstream supplies equivalent behavior, remove the redundant local implementation and use upstream directly.
- Preserve regression coverage for the behavior when removing a workaround. Verify the replacement through the actual desktop integration.
- Keep only desktop-specific behavior or extensions that upstream does not provide. Document the remaining difference and the condition for removing it; do not retain a workaround just because it already exists.
- Do not modify the upstream submodule's sources. Update its pinned revision and adapt this repository's integration.
