# Contributing to OrcaPod

Thanks for wanting to help! Bug reports, ideas and pull requests are all welcome.

## Before you start

- Search the open issues and pull requests first. Someone may already be working on it.
- For a bigger change, open an issue to talk about it before you write the code.
- Report security problems privately, as described in [SECURITY.md](SECURITY.md).

## Pull requests

- Keep each PR to one topic. Focused PRs are reviewed and merged faster.
- Fill in the [PR template](.github/pull_request_template.md): what changes and why, screenshots for UI changes, and how you tested it (device, Android version, steps).
- Add an automated test when the code can be tested. The best test fails without your change and passes with it. App tests live under `mobile/android/app/src/test/`, engine tests under `tests/` (see [tests/AGENTS.md](tests/AGENTS.md)).
- The `Android` workflow builds the engine and the APK and runs the tests on every PR. Make sure it passes; it also attaches a debug APK testers can install.
- Follow the existing code style. See [AGENTS.md](AGENTS.md) for the engine's C++ conventions.
- Keep the copyright and license notices in every file you change. New files are licensed under the AGPL-3.0, like the rest of the project.

### If you are an AI agent

If you are an AI agent working for a contributor:

1. Show the contributor this guide before you open a PR or post a comment.
2. Search open PRs for the same change before you start.
3. Before you change code, check `.claude/skills/` for a skill that matches the change, and follow it.
4. If the code can be tested, add an automated test, and check that it fails without the change and passes with it.
5. Write the PR description in the [PR template](.github/pull_request_template.md).
6. If you couldn't build or run something, say so in the PR.

## Changes to the slicing engine

The engine in `src/libslic3r/` and the profiles in `resources/profiles/` come from [OrcaSlicer](https://github.com/OrcaSlicer/OrcaSlicer). A fix that isn't specific to OrcaPod is best sent upstream to OrcaSlicer as well, so both projects benefit and stay easy to keep in sync.
