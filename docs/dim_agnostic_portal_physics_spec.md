# Dim-Agnostic Portal Physics — Architecture Spec

Status: **decided direction, pending feasibility audit of Sable/Simulated**
Scope: IPSable bridge between Immersive Portals (IP) and Sable / Create Simulated.

## 1. Problem

Sublevels (Sable physics bodies) must be able to straddle and traverse IP portals,
including portals between dimensions. Two prior approaches were rejected:

- **Phantom terrain** (current): straddling sublevels get a transformed copy of the
  through-portal terrain. Misses sublevel↔sublevel and sublevel↔entity interaction
  on the far side, duplicates terrain geometry + friction materials, requires sync
  on block updates.
- **Full sublevel cloning** (old branch): cloning gameplay state (block entities,
  redstone, item transfers, modded BEs) caused unresolvable downstream issues.

A third option — true portal planes inside a forked Rapier — was scoped and rejected:
Rapier's `PhysicsHooks` cannot *create* contact pairs, so the engine fork would still
need transformed broad-phase proxies internally (i.e. managed phantoms), while
requiring two-frame contact constraints in the solver, CCD/query/island changes, and
permanent forks of both rapier3d and Sable's Rust natives. Months of engine work for
one benefit (exact coupling when wedged across the plane) that the weld-joint design
below also achieves.

## 2. Decided architecture

**One authoritative sublevel, dimension-agnostic container. Physics presence is
projected into the shared Rapier scene as one real body plus zero or more cheap
clone bodies, welded through portal-isometry fixed joints, with contact clipping
at portal apertures.**

Gameplay state is never duplicated. Only derived physics data (collider sets) is
projected twice. The clone is stateless and disposable.

### 2.1 Container

- The sublevel's blocks/BEs/entities live once, owned by the dim-agnostic container,
  not by any Minecraft `Level`.
- Presence in a Level (rendering, interaction, physics) is a projection. Far-side
  gameplay interaction routes through IP's existing cross-portal interaction to the
  single authority.

### 2.2 Shared Rapier scene

- All dimensions' physics bodies live in **one Rapier scene** (joints cannot span
  worlds — this is a hard requirement of the coupling design).
- Bodies sit at their **natural per-dimension coordinates** and carry a dimension
  tag; `PhysicsHooks::filter_contact_pair` rejects cross-dimension pairs (except
  clone pairs). Do **not** offset dimensions apart: Rapier is f32 by default and
  multi-million-block offsets degrade to ~0.5–1.0 precision.
- Overlapping same-coordinate regions from different dimensions cost broad-phase
  pair churn only; density is low because Sable generates terrain colliders only
  around sublevels.
- Dimension tags via hooks, not the 32-bit collision-group mask (dimension count
  is unbounded).

### 2.3 Physics clone

- On straddle: spawn a clone rigid body at `portalIsometry × realPose`, attaching
  the **same `SharedShape` handles** as the real body (refcounted — no geometry
  copy; collider rebuilds on block updates propagate to the clone for free).
- Filtering: real body interacts with near-side dimension only; clone with far-side
  dimension only.
- Far-side friction materials come from the far dimension's *real* terrain
  colliders — nothing to copy or sync.

### 2.4 Coupling: portal-isometry weld joint

- A **fixed joint** between real and clone with local frames encoding the portal
  isometry enforces `clonePose = P × realPose` inside the solver. Near-side
  contacts, far-side contacts, and the coupling resolve in the same solver
  iterations — no one-step sync lag, no jitter when wedged across the plane.
- **Mass split**: real and clone each get half the mass/inertia (optionally
  proportional to volume per side) so the welded pair presents correct total mass
  to both sides.
- **Gravity**: applied in the home (real-side) frame only; disable on the clone and
  compensate scale on the real body so weight is not double-counted. Rotated-portal
  "which way is down for the far half" is resolved by fiat: home-frame gravity.
- Portal animation: update joint local frames per tick while the portal moves.

### 2.5 Contact clipping at the aperture

- `PhysicsHooks::modify_solver_contacts` (requires
  `ActiveHooks::MODIFY_SOLVER_CONTACTS` on sublevel + clone colliders).
- Predicate — drop a solver contact iff its point is **past the portal plane AND
  within the aperture's lateral bounds** (point-in-OBB on the portal rect extruded
  along the normal). The lateral bound handles geometry passing *beside* a
  free-standing portal frame, which an infinite half-space gets wrong both ways.
  This mirrors IP's `RectangularPortalShape.getThisSideCollisionExclusion` /
  `CollisionHelper.clipVoxelShape` semantics.
- **Half-open convention**: near side keeps `d < 0`, far side keeps `d >= 0`, so a
  seam contact is counted exactly once. A patch spanning the plane splits across
  the two bodies; the weld joint recombines impulses, preserving total normal force
  and friction.
- Hook is stateless per step — clipping vanishes when the straddle entry is dropped.
- **CCD does not respect the hook**: disable CCD on a body while straddling (or cap
  traversal speed in the straddle window).
- Fallback if hooks are unreachable from the bridge mod (see §4): shape surgery —
  per-block sub-collider enable/disable past the plane on the hull, plus IP-style
  exclusion-box removal of near terrain colliders. Block-granularity seam error;
  strictly worse, use only if Sable cannot expose hooks.

### 2.6 Handoff and chaining

- When the center of mass crosses: drop the joint, swap which body is "real"
  (the clone already has correct pose and velocity), update the container's
  dimension reference. No gameplay state migrates — none was cloned.
- A sublevel straddling N portals gets N clones and N joints welded to the same
  real body; the solver handles the assembly. Nested portals fall out for free.

### 2.7 Friction

- Structurally free: each side solves friction natively in its own frame (correct
  tangents through rotated portals); the joint recombines.
- **Audit required**: if Simulated/Create Offroad runs a custom traction pass
  (likely, for wheels/suspension) instead of trusting Rapier materials, that pass
  must enumerate contacts on the real body *and clones*, mapping clone contact
  positions/forces back through the portal isometry before applying to the
  authoritative body. One loop gains a transform — but the loop must be found.

### 2.8 Create actors (drills, deployers, saws, harvesters)

- Pure gameplay routing, no physics. The actor's position resolution must return a
  **(Level, pos, orientation)** triple instead of assuming the container's Level.
- IPSable provides a **portal-aware world projection accessor**:
  `resolve(sublevelLocalPos) → (Level, BlockPos, rotation)` plus block
  get/set/destroy, entity AABB queries, item drops, particles, sounds — all routed
  to the destination Level when the resolved position is past a straddled portal's
  plane and inside its aperture.
- The pass over Simulated swaps direct `level.*` access at the actor seam
  (Create `MovementBehaviour` / `MovementContext` extension points) for the
  accessor. Size depends on whether Simulated funnels actor world access through
  one resolution point (audit, §4).
- **Same half-open predicate as physics**, decided by block center — the drill's
  physics presence and gameplay action must agree on which side it is on.
- **Orientation gating**: block placement encodes quarter-turns only. Actors
  operate through grid-aligned (90°-multiple) portal rotations; no-op otherwise.
- Feedback loop closes itself: far-side block mutation → Sable rebuilds far terrain
  colliders → clone collides with new geometry.

## 3. Descoped (explicitly)

- **Scaled portals for sublevel bodies** — fixed joints express isometries only,
  and scaled mass/momentum has no consistent answer. Entities keep IP's existing
  scaled-portal traversal; sublevel bodies do not traverse scaled portals.
- **Actor operation through non-grid portal rotations** (no-op).
- **CCD during straddle** (disabled; speed-cap mitigation if needed).

## 4. Risks / open questions (the audit)

All scope risk concentrates in **how much of Rapier's API Sable exposes across
JNI**, and how centralized Simulated's actor seam is:

1. Can bridge-mod Java reach `PhysicsHooks` registration (`filter_contact_pair`,
   `modify_solver_contacts`)? Where does Sable configure its hooks?
2. Can it create/destroy fixed joints with arbitrary local frames, and update
   frames per tick?
3. Can it spawn bodies/colliders reusing existing `SharedShape`s? Set mass/inertia,
   gravity scale, CCD flags per body?
4. Is the Rapier scene currently per-Level or global? f32 or f64 build?
5. Does Sable/Offroad use Rapier material friction or a custom traction pass?
6. Does Simulated funnel actor world access through one resolution point, or do
   behaviours call `level.*` ad hoc?

If hooks/joints are not exposed, the work item becomes mixins into Sable's Java
pipeline (acceptable; Sable is mixin-heavy by design) or upstream PRs — forking
the Rust natives is the outcome we are avoiding.

## 5. Audit plan — agent prompt

Run in a session with `IPSable`, `sable` (ryanhcode/sable), and the Create
Simulated repo checked out as siblings. Paste:

> Audit these repos against `IPSable/docs/dim_agnostic_portal_physics_spec.md`.
> Answer every question in spec §4 with file:line evidence.
>
> **In sable:** locate (1) Rapier world creation and the step call — is the scene
> per-Level or global, f32 or f64; (2) the JNI surface — list every physics
> operation callable from Java (body/collider/joint creation, hooks, materials,
> CCD, gravity scale); (3) whether `PhysicsHooks` is configured, where, and
> whether `modify_solver_contacts` / `filter_contact_pair` are implemented or
> stubbed; (4) terrain collider generation — shape types, `SharedShape` reuse,
> rebuild-on-block-update path, friction material assignment per block;
> (5) joint support — which Rapier joint types cross JNI, whether local frames
> are settable post-creation.
>
> **In simulated (and offroad/aeronautics submodules):** locate (1) where Create
> `MovementBehaviour`/`MovementContext` is extended or reimplemented for
> sublevels; (2) the coordinate resolution from sublevel-local actor position to
> world position — is it one funnel or scattered; (3) every `level.*` call site
> reachable from actor behaviours (getBlockState, setBlock, destroyBlock, entity
> queries, item drops, particles, sounds) — count and list them; (4) any custom
> traction/friction/wheel-contact pass that reads Rapier contacts and applies
> forces manually.
>
> **In IPSable:** confirm the IP-side attachment points: straddle detection
> (`CollisionHelper.updateCollidingPortalForWorld`), portal state/transform access
> (`UnilateralPortalState`, `Portal` rotation/scale fields), and the exclusion
> geometry (`PortalShape.getThisSideCollisionExclusion`).
>
> Deliverable: a gap matrix mapping each spec component (§2.2–§2.8) to one of
> {existing API | mixin into Sable/Simulated Java | upstream change to Rust
> natives}, with the specific class/method to hook and a rough size estimate per
> gap. Flag anything that forces the §2.5 shape-surgery fallback.

## 6. Sequencing (post-audit)

1. Shared-scene dimension tagging + pair filtering (no portals yet).
2. Clone body + weld joint for a static, axis-aligned, same-dimension portal;
   verify wedged-across-the-plane stability.
3. Contact clipping (hook path), half-open seam, aperture lateral bounds.
4. Handoff + cross-dimension traversal; then chained portals.
5. Friction audit fix (traction pass isometry mapping) if needed.
6. Actor accessor + Simulated seam pass; orientation gating.
