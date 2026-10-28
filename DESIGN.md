---
name: Agent Monitor
description: Native JavaFX desktop workbench for local JVM performance investigation.
colors:
  accent: "#176B60"
  accent-soft: "#E8F3EF"
  accent-hover: "#10594F"
  accent-pressed: "#0C493F"
  accent-focus: "#104B42"
  canvas: "#F5F6F7"
  surface: "#FFFFFF"
  subtle: "#F4F6F6"
  border: "#DAE0E0"
  ink: "#202B30"
  muted: "#59686D"
  navigation-surface: "#EDF1F0"
  navigation-hover: "#E1E9E6"
  navigation-ink: "#485A5B"
  hover-border: "#A6B4C8"
  pressed-surface: "#E8EDF5"
  positive: "#17663F"
  positive-soft: "#EAF7EF"
  warning: "#875713"
  warning-soft: "#FFF2DB"
  danger: "#A92E35"
  danger-soft: "#FFF2F2"
  code-surface: "#202B3D"
  code-text: "#DCE6F5"
typography:
  display:
    fontFamily: "System"
    fontSize: "26px"
    fontWeight: 700
  headline:
    fontFamily: "System"
    fontSize: "24px"
    fontWeight: 700
  metric:
    fontFamily: "System"
    fontSize: "30px"
    fontWeight: 700
  brand:
    fontFamily: "System"
    fontSize: "16px"
    fontWeight: 700
  title:
    fontFamily: "System"
    fontSize: "14px"
    fontWeight: 700
  body:
    fontFamily: "System"
    fontSize: "13px"
    fontWeight: 400
  label:
    fontFamily: "System"
    fontSize: "12px"
    fontWeight: 400
  caption:
    fontFamily: "System"
    fontSize: "11px"
    fontWeight: 400
  code:
    fontFamily: "Menlo"
    fontSize: "12px"
    fontWeight: 400
rounded:
  flat: "0px"
  checkbox: "4px"
  control: "5px"
  status: "6px"
  panel: "8px"
  loading: "12px"
spacing:
  tight: "4px"
  compact: "8px"
  control: "12px"
  section: "16px"
  heading: "20px"
  page: "24px"
  selector: "32px"
components:
  button-primary:
    backgroundColor: "{colors.accent}"
    textColor: "{colors.surface}"
    typography: "{typography.label}"
    rounded: "{rounded.control}"
    padding: "9px 16px"
  button-primary-hover:
    backgroundColor: "{colors.accent-hover}"
  button-primary-active:
    backgroundColor: "{colors.accent-pressed}"
  button-secondary:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink}"
    typography: "{typography.label}"
    rounded: "{rounded.control}"
    padding: "7px 12px"
  button-stop:
    backgroundColor: "{colors.danger-soft}"
    textColor: "{colors.danger}"
    typography: "{typography.label}"
    rounded: "{rounded.control}"
    padding: "9px 16px"
  input:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink}"
    typography: "{typography.body}"
    rounded: "{rounded.control}"
    padding: "8px 10px"
  navigation-item:
    backgroundColor: "transparent"
    textColor: "{colors.navigation-ink}"
    typography: "{typography.body}"
    rounded: "{rounded.control}"
    padding: "12px 14px"
  navigation-item-selected:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.accent}"
  status-connected:
    backgroundColor: "{colors.positive-soft}"
    textColor: "{colors.positive}"
    typography: "{typography.label}"
    rounded: "{rounded.status}"
    padding: "5px 9px"
  process-card:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink}"
    rounded: "{rounded.panel}"
    padding: "12px"
  code-editor:
    backgroundColor: "{colors.code-surface}"
    textColor: "{colors.code-text}"
    typography: "{typography.code}"
    rounded: "{rounded.panel}"
    padding: "14px"
  data-row:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink}"
    typography: "{typography.body}"
    height: "38px"
    padding: "8px"
---

# Design System: Agent Monitor

## Overview

**Creative North Star: "Desktop investigation workbench"**

Agent Monitor uses a continuous white evidence workspace, a pale green-gray navigation rail, and deep teal for actions and selection. System typography supports Chinese labels alongside JVM identifiers. The visual character is quiet, precise, and compact enough for sustained investigation.

The workbench keeps navigation, target identity, and capture controls stable while the evidence changes. Flat surfaces and restrained separators provide hierarchy; imagery and decorative animation are absent. This records the implemented native desktop system, not a web or mobile theme.

**Key Characteristics:**

- Persistent task navigation with retained page state.
- Neutral evidence surfaces with teal interaction states.
- Native controls, visible keyboard focus, and compact data typography.
- Inline evidence, editors, and results within the main workspace.

This is a scan of the current implementation. The YAML frontmatter carries portable tokens; production truth remains [main.css](monitor-app/src/main/resources/com/agentmonitor/app/main.css), [WorkbenchPane](monitor-app/src/main/java/com/agentmonitor/app/ui/WorkbenchPane.java), [ClassSelectionPane](monitor-app/src/main/java/com/agentmonitor/app/ui/ClassSelectionPane.java), and [DiagnosticsPane](monitor-app/src/main/java/com/agentmonitor/app/ui/DiagnosticsPane.java). JavaFX looked-up colors are not browser CSS custom properties. The sidecar's HTML/CSS specimens translate the observed appearance for documentation only.

## Colors

The palette combines cool neutral surfaces, a green-gray rail, and a single deep teal interaction accent.

### Primary

- **Deep teal** (`accent`): primary capture/apply actions, active navigation, focused controls, and selected checkboxes.
- **Soft teal** (`accent-soft`): selected package/process backgrounds and secondary action surfaces. Hover, pressed, and focus variants are separate observed tokens.

### Neutral

- **White workspace** (`surface`): main content, table bodies, inputs, and selected navigation.
- **Cool canvas** (`canvas`) and **subtle gray** (`subtle`): application surround, package tree, summary regions, and inspector backing.
- **Green-gray rail** (`navigation-surface`): persistent left navigation; its hover surface and text have their own tokens.
- **Dark ink** (`ink`), **muted ink** (`muted`), and **quiet border** (`border`): primary information, supporting labels, and dividers.
- **Code surface** and **code text**: the intentionally dark YAML/log area within the light application. This is not a second application theme.

### Semantic states

Positive green marks connected/active states; amber marks waiting or warning states; red marks stop/error meaning. Each uses a pale companion surface. Duration cells have their own observed green-to-amber-to-red thresholds; preserve their measurement semantics rather than applying them as decoration.

**The State Color Rule.** Use teal for actions and selection; use green, amber, and red for runtime meaning. Keep a text label with every status.

## Typography

**Display and body font:** JavaFX `System`, resolved by the operating system, including its Chinese fallback. **Code font:** `Menlo`; raw call details and diagnostics explicitly allow `Consolas`/monospace fallback.

The system uses a compact functional hierarchy, not a display typeface pairing. The frontmatter records observed sizes, not a mathematically scaled type system. Line height and letter spacing remain native defaults.

- **Display:** process-selection heading.
- **Headline:** workbench page title; the largest normal heading within a page.
- **Metric:** prominent values on JVM overview; not navigation or body text.
- **Brand / title:** application identity and local section labels.
- **Body:** navigation, descriptions, JVM names, and data rows.
- **Label / caption:** buttons, supporting status, column headers, pagination, and counts. Bold is used for active navigation, primary actions, and section emphasis.
- **Code:** YAML, logs, parameters, SQL, and raw evidence. Preserve identifiers and allow selection/copy.

**The Evidence Type Rule.** Use system type for navigation and tables, and monospace for code and raw evidence. Keep units and measurement meaning visible.

## Layout

The native main window starts at 1440 × 900 and supports a minimum window size of 1080 × 720. These are desktop window constraints, not web breakpoints. Content flexes and scrolls within the persistent shell; there is no mobile navigation mode.

The header spans the window and holds the target identity, capture state, elapsed time, and start/stop controls. The bottom status bar keeps recent feedback, connection state, and compact JVM snapshots visible. The left rail has a preferred width of 176 and minimum of 160 JavaFX layout units. Main pages use the `page` spacing token on all sides, with a 20-unit gap below the heading.

Scope selection uses a non-parent-resizing package column (190 minimum, 240 preferred, 280 maximum) and a flexible class table. The initial split position is 23%; adjacent panes contribute 14-unit internal spacing. Page actions share the title row to leave more height for evidence. Package actions and rule summaries wrap; each package-rule summary is capped at 420 units with ellipsis and a full-text tooltip. Class and evidence tables own their scroll regions. Table rows use the frontmatter's data-row height. A compact window displays fewer rows without removing capture actions or selection checkboxes.

Diagnostics has its own category list (160 preferred, 144–180 allowed) inside the main content. Evidence tables and full raw details use a vertical split initially at 65%. Call details occupy a same-page side pane; exclusion editing replaces the package/class browser and provides a return action. These interactions retain workspace context.

**Capture references:** [wide](monitor-app/build/ui-preview/workspace-wide.png), [compact](monitor-app/build/ui-preview/workspace-compact.png), [calls](monitor-app/build/ui-preview/workspace-calls.png), [overview](monitor-app/build/ui-preview/workspace-overview.png), [advanced configuration](monitor-app/build/ui-preview/workspace-advanced.png), [diagnostics](monitor-app/build/ui-preview/workspace-diagnostics.png), [rules](monitor-app/build/ui-preview/workspace-rules.png), [sessions](monitor-app/build/ui-preview/workspace-sessions.png), and [embedded selector](monitor-app/build/ui-preview/selector-embedded.png). These generated local captures use labeled illustrative data and are not committed assets. Current captures are from macOS; Windows/Linux rendering is not established by them.

## Elevation & Depth

The evidence workspace is flat. Tonal changes, spacing, and one-pixel borders distinguish navigation, content, tables, and inspectors. Workbench pages and the inline call-detail pane have square outside edges. No decorative motion is used; diagnostics charts and exclusion expansion explicitly disable animation.

One existing exception remains in the loading overlay: `dropshadow(gaussian, rgba(29,41,61,0.12), 24, 0, 0, 8)`. It separates transient loading content and is not a general card shadow. Native dialogs/menus may also receive platform skin effects. Do not claim that every native popup is shadow-free.

**The Flat Workspace Rule.** Keep the evidence workspace flat. Separate regions with spacing, tonal surfaces, or a single border; do not recreate the former nested rounded panel grid.

## Shapes

Controls use the small `control` radius, checkboxes the `checkbox` radius, and state labels the `status` radius. Process cards and code surfaces use `panel`; the transient loading panel uses `loading`. The main workspace and inline inspector remain `flat`.

Use thin, restrained borders for controls and structural separators. Line icons have transparent fill, a 1.5-unit stroke, and rounded stroke ends and joins. Buttons retain readable action text next to icons; icons are not decorative imagery.

## Components

### Buttons

Primary capture/apply actions are solid deep teal, bold, and use the frontmatter's primary padding. Hover darkens the fill; pressed state darkens it further. Keyboard focus uses the deep focus border with a two-unit outward inset. Secondary actions use white fill, a quiet border, and compact padding. Stop is a pale red button with red text and border. Disabled buttons retain their dimensions at 0.43 opacity.

### Inputs / Fields

Search fields and native inputs use white fill, a quiet one-pixel border, native body typography, and the control radius. Hover strengthens the border; focus changes it to teal without a glow. YAML remains in the dark code surface. Invalid configuration retains its draft and displays an inline error. Returning to the page must not silently discard unsaved text.

SearchBox keeps the native field, adds a labeled clear button when nonempty, and supports Escape to clear the filter. Command/Ctrl + F selects the active page's primary search, including the selected diagnostics category. Search text reserves 36 units of right padding for the clear action. YAML exposes clean, unapplied, and applied states in text; application is disabled while clean. Clearing the capture scope offers an inline undo action until another scope edit or import replaces that choice.

### Navigation

Main navigation uses left-aligned line icons and labels, with 10 units between them. The active item has a white surface and bold teal text/icon; hover uses the rail hover surface. Keyboard focus remains visible. Pages are created once and retain state. The first five pages support Command/Ctrl + 1–5; utilities sit at the bottom of the rail.

Diagnostics category navigation is a separate in-page list. Its selected row uses the soft teal surface and bold teal text. Keep all categories reachable within the page instead of exposing a crowded horizontal tab strip.

### Chips / Status

State labels pair semantic text with a pale filled surface. Compact metric chips use neutral colors and caption typography. The bottom connection chip reduces padding to 3 × 7 units; normal status labels use the frontmatter padding. A state color never replaces its text label.

### Cards / Containers

The process selector uses bordered process rows with the panel radius and compact padding. Selection changes both the surface and border to teal. Runtime summaries use a subtle surface. Overview metrics use flat bottom separators rather than a grid of elevated cards. The general evidence page is a continuous surface, not a card.

### Data tables and inspectors

Data rows use muted column headers, subtle alternating rows, and a soft green selected row. Keep full thread names, identifiers, parameters, SQL, and exceptions available in the same-page inspector when the summary table truncates them. Double-click, Enter, or the visible detail action opens call details; the detail content follows the selected call. Diagnostic results replace the evidence content temporarily and provide a return action.

### Empty and asynchronous states

Empty states explain what is missing and name the next action. Scope indexing may dim the browser while status text explains progress. The loading overlay covers page content while global capture controls and navigation remain reachable. Preview fixtures explicitly say that no real JVM is connected.

## Do's and Don'ts

### Do:

- **Do** retain the fixed navigation rail and persistent target and capture controls across workbench pages.
- **Do** use the documented semantic colors and native System typography for new desktop controls.
- **Do** preserve page search, selection, and unapplied editor drafts when switching pages.
- **Do** retain visible focus, text labels, and keyboard access alongside icon affordances.
- **Do** check layouts at the supported compact and wide desktop sizes with long JVM names and empty evidence.
- **Do** keep global capture totals, the browsed package, filtered results, and actual enhancement results visibly distinct.

### Don't:

- **Don't** replace the native workbench with a web/mobile layout or add an invented branding metaphor.
- **Don't** use decorative imagery, animation, gradients, or nested elevated cards in the evidence workspace.
- **Don't** move routine call details, exclusion editing, or diagnostic results into additional windows.
- **Don't** treat generated tonal ramps or HTML specimens as additional production tokens or executable JavaFX controls.
- **Don't** present illustrative preview data as a live capture or a rule preview as actual enhancement results.
