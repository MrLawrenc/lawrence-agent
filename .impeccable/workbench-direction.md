# Desktop investigation workbench

Mode: Operate. User confirmed full workbench and layout redesign, fewer windows, direct implementation with verification. This native JavaFX desktop has no web/mobile surface.

## THESIS
A performance investigation stays in one window, with the target and capture controls always in reach.

## OWN-WORLD
A quiet, continuous white workspace, an understated pale green-gray navigation rail, deep teal selection and action color. System typography supports Chinese UI and dense JVM names. No decorative imagery, motion or shadows. The old nested rounded panel grid is the anti-reference.

## STORY
Select the process in the application window. Choose scope. Start and stop from the persistent header. Move between calls, metrics and diagnostics without losing context. Finish with explicit restoration and report actions.

## FIRST VIEWPORT
At 1440px, a 176px rail anchors the left edge; the rest belongs to the scope browser with a restrained package column and a flexible class table. At 1080px all capture controls and scope checkboxes remain visible. Secondary metrics leave the scope surface. The page title is the only large type.

## FORM
Seed 68deac7b, direction assignment 7 acknowledged. Explicit user preference for direct native workbench implementation governs form. Seven considered task structures: overview dashboard; sequential capture wizard; IDE-style dock; session notebook; trace-first console; command palette shell; persistent task rail with a continuous evidence workspace. The seventh carries the implementation. Catalog visual metaphors are declined for weaker developer identification and evidence clarity; retain disciplined tonal hierarchy, predictable navigation and density, not metaphor decoration. Code-led; no approved comp or raster assets.

Signature interaction: navigate between persistent pages with retained search, selection and YAML drafts. Native focus and shortcuts. Exclusion editing replaces the browser instead of squeezing it. Diagnostic categories and command results remain inside the page.

## Verification boundary
Preview uses the production MainController with fixture-only JVM service adapters, never a real attach. Required captures: workspace-wide.png (1440), workspace-compact.png (1080), workspace-calls.png, workspace-overview.png, workspace-advanced.png, workspace-diagnostics.png, workspace-rules.png, workspace-sessions.png and selector-embedded.png, all under monitor-app/build/ui-preview/. Main capture completion behavior retains the existing core contract. No web CSS detector is applicable to JavaFX. Windows/Linux screenshots are not available in this macOS run.
