# Issue tracker: Linear

Issues and specs for this repo live in Linear: team **Personal** (key `PER`), project **Sleep Cycle App**. Use the Linear MCP tools (`mcp__claude_ai_Linear__*`) for all operations. Issues are referred to by identifier, e.g. `PER-12`.

The GitHub repo is public and Linear is private. Never copy anything from Linear (night logs, device details) into commits, PRs or GitHub issues without checking it is safe to publish. A Bluetooth MAC address goes nowhere, masked or not.

## Conventions

- **Create an issue**: `save_issue` with `team: "Personal"`, `project: "Sleep Cycle App"`, a title, a Markdown description, and one type label where it fits: `Bug`, `Feature` or `Improvement`. New issues start in `Backlog`.
- **Read an issue**: `get_issue` with the identifier, then `list_comments` for the discussion.
- **List issues**: `list_issues` with `project: "Sleep Cycle App"`, filtered by `label` and `state` as needed.
- **Comment on an issue**: `save_comment`.
- **Apply / remove labels**: `save_issue` with `id` and `addLabels` / `removeLabels` (never `labels`, which replaces the whole set).
- **Statuses**: `Backlog`, `Todo`, `In Progress`, `Done`, `Canceled`, `Duplicate`.
- **Close**: comment with the outcome, then set `state: "Done"`. A wontfix issue goes to `Canceled`.
- **Link code**: mention the identifier (`PER-12`) in the commit body or PR description. Branch names keep the `nikita/<type>/<kebab-description>` format.

## Pull requests as a triage surface

**PRs as a request surface: no.** This is a solo repo; external PRs are not feature requests.

## When a skill says "publish to the issue tracker"

Create a Linear issue in the Sleep Cycle App project as above.

## When a skill says "fetch the relevant ticket"

`get_issue` with the identifier, plus `list_comments`.

## Wayfinding operations

Used by `/wayfinder`. The **map** is a single issue with **child** issues as tickets.

- **Map**: one issue labelled `wayfinder:map`, holding the Notes / Decisions-so-far / Fog body.
- **Child ticket**: an issue with `parentId` set to the map. Labels: `wayfinder:<type>` (`research`/`prototype`/`grilling`/`task`).
- **Blocking**: Linear's native relations: `save_issue` with `blockedBy` on the child (`removeBlockedBy` to undo). A ticket is unblocked when every blocker is `Done` or `Canceled`.
- **Frontier query**: `list_issues` with `parentId` set to the map and an open state; drop any with an open blocker or an assignee; first in map order wins.
- **Claim**: `save_issue` with `assignee: "me"` and `state: "In Progress"`, the session's first write.
- **Resolve**: `save_comment` with the answer, set `state: "Done"`, then append a context pointer (gist + identifier) to the map's Decisions-so-far.
