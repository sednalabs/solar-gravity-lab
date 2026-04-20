# Hosted AI Development

Solar Gravity Lab uses GitHub-hosted workflows as the primary heavy-compute
surface for Android proof, packaging, and bounded interactive investigation.
That posture is useful for ordinary development, and it is especially useful
for AI-assisted development because it gives both humans and models a shared,
reproducible execution surface instead of one machine's local toolchain state.

This document describes the current implementation in publication-safe terms.
It does not describe local-only operator setup, private credentials, or
org-specific capacity policy.

The current public recommendation is GitHub-hosted first. Older self-hosted
Android runner material remains in the repository as deferred evaluation and
operator background, not as the primary development path on `main`.

## Why this exists

For this repository, the important questions are often:

- does the canonical Rust platform still prove cleanly
- does the Android shell still build and launch on a known hosted surface
- can we package a reusable Android artifact for later device or session proof
- can we keep one bounded hosted emulator session alive long enough to inspect
  a real UI or interaction seam without paying full startup cost every loop

Those questions are expensive enough that GitHub-hosted runners are a better
default measurement surface than repeatedly rebuilding everything on a local
machine.

For AI-assisted development, the benefits are even more direct:

- the proof surface is remote, clean, and shareable
- heavyweight Android toolchain and emulator work can run away from the
  operator's laptop
- reusable artifacts make it easier to separate "build the app" from "inspect
  or drive the app"
- bounded live sessions give a model or operator a real Android state to check
  without turning every investigation into a full rebuild-and-boot cycle

## Workflow roles

The current hosted workflow model has four distinct roles.

### 1. Canonical remote validation

[`validation-lab`](validation-lab.md) is the main remote proof surface for
code changes. It validates the canonical Rust platform and, when appropriate,
the Android shell under [`clients/android`](../clients/android/README.md).

Use it when the question is:

- does this branch still prove cleanly
- what is the next blocker family
- did we preserve the canonical build and test contract

### 2. Installable packaging

`.github/workflows/prerelease-apk.yml` packages installable Android preview
artifacts from the same promoted head that passed remote proof.

Use it when the question is:

- can we produce an internal installable build
- do we have a preview artifact suitable for device testing or proof

### 3. Reusable interactive build artifacts

`.github/workflows/interactive-android-build.yml` produces a reusable Android
artifact bundle for hosted interactive work.

Use it when the question is:

- can we build one Android artifact once and reuse it in a live session
- can we separate build production from interactive inspection or relaunch work

### 4. Bounded live Android sessions

`.github/workflows/interactive-android-session.yml` runs a bounded hosted
Android emulator session for live inspection and debugging.

Use it when the question is:

- what does the app actually look like on a hosted emulator right now
- can we verify a UI or interaction seam against a real running session
- can we keep one remote Android environment alive while we relaunch or swap
  newer builds into it

These sessions are intentionally bounded and operator-started. They are a
development and proof surface, not a general-purpose public service.

## Why this helps AI-assisted development

AI-assisted development gets more useful when the model has:

- a reproducible proof lane
- clear artifact boundaries
- a real running environment for targeted inspection
- enough structure that tool-driven investigation is more reliable than
  vague narrative guessing

This repository's hosted workflow model supports that by combining:

- exact-head remote validation
- reusable Android artifacts
- bounded live emulator sessions
- structured Android interaction surfaces where available

That combination lets a model participate in real investigation and proof work
without depending on one laptop's local state or on purely hypothetical UI
reasoning.

## What this document is not claiming

This documentation does not claim that:

- GitHub Actions replaces real device testing
- every development task should be routed through a hosted session
- the repository exposes a public multi-tenant Android harness service
- the current hosted workflow model is finished or permanent

The current implementation should instead be read as:

- remote-first for heavy proof
- reusable artifacts when build churn matters
- bounded live sessions when interactive Android inspection is the real need
- local development still valuable for fast editing and small feedback loops

## Where to read next

- [README.md](../README.md)
- [Validation Lab](validation-lab.md)
- [Interactive Android Session](interactive-android-session.md)
- [CI Cache Rollout](ci-cache-rollout.md)
