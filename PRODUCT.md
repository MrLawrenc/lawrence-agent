# Agent Monitor

<!-- impeccable:product-schema 1 -->

## Platform

JavaFX desktop, macOS / Windows / Linux. This is a native desktop workbench, not a website or mobile app.

## Users and purpose

Java developers investigating a local application's performance. The working path is selecting an explicit JVM, choosing capture scope, reproducing work, inspecting calls and diagnostics, and stopping with confirmed bytecode restoration.

## Confirmed direction

The user requested a complete UI review and redesign, including layout and interaction. Priority: one coherent workbench, fewer separate windows, discoverable navigation. Implement directly and verify the runnable JavaFX UI.

## Capabilities and constraints

Preserve the existing core/model capture, scope, lifecycle and evidence contracts. The desktop is an adapter, not a second implementation of capture logic. Existing uncommitted repository work must be preserved. Diagnostic data and historical sessions stay distinguishable from live capture. Stopping and restoration remain explicit.

## Evidence

README.md and docs/ describe the product. Production controls live in monitor-app. scripts/ui-preview uses labeled illustrative data and never attaches to a real JVM. Visual validation must cover desktop sizes, navigation, empty states and asynchronous updates.
