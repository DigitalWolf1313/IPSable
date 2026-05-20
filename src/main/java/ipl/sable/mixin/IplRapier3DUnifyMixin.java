package ipl.sable.mixin;

import dev.ryanhcode.sable.mixinterface.physics.ServerLevelSceneExtension;
import dev.ryanhcode.sable.physics.impl.rapier.Rapier3D;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Force every Rapier scene lookup and initialization to operate on a single canonical scene.
 *
 * <p><b>Why:</b> Sable's per-Level physics scene model assumes each {@code ServerLevel} owns its
 * own Rapier scene and that any sub-level's body lookup uses that Level's scene. After our phase 2
 * routing puts sub-levels in {@code sable_sublevels}, body registration happens in
 * {@code sable_sublevels}'s scene but parent-terrain collision and various callback paths still
 * resolve scenes through the parent Level. Rapier's dispatcher
 * ({@code common/src/main/rust/rapier/src/dispatcher.rs}) doesn't support cross-scene collisions:
 * <code>get_scene_ref(g1.scene_id)</code> is called once per contact pair and never checks
 * {@code g2.scene_id}. So the airship would (a) panic on lookup mismatches and (b) fall through
 * the world even if the panic were fixed because parent terrain colliders are in a different scene.
 *
 * <p><b>Fix:</b> unify all Rapier state into a single global scene by:
 *
 * <ul>
 *   <li>Redirecting {@link Rapier3D#getID(ServerLevel)} to always return {@link #IPL$GLOBAL_SCENE}
 *       regardless of which Level is asked. Every {@code RapierPhysicsPipeline} (per-Level) ends
 *       up with {@code this.sceneId == 0}, so every {@code Rapier3D.*(sceneId, id, ...)} call uses
 *       the same scene. The scene-mismatch class of panics becomes unreachable.</li>
 *   <li>Also writing {@code IPL$GLOBAL_SCENE} into each Level's {@code sable$setSceneID} so any
 *       code that reads the scene ID directly (bypassing {@code Rapier3D.getID}) also sees the
 *       unified value.</li>
 * </ul>
 *
 * <p><b>What about {@code Rapier3D.initialize}?</b> Not intercepted. It's a {@code native} method
 * and Mixin can't {@code @At("HEAD")} into a native method (no bytecode body). The benign outcome:
 * every pipeline calls {@code Rapier3D.initialize(0, gravity, drag)} during server startup, and
 * the Rust side {@code state.scenes.insert(0, ...)} overwrites the previous scene with a fresh
 * empty one. Since all initialize calls happen at startup before any bodies are registered, the
 * overwrites lose no data. After startup, scene 0 exists and is canonical for the rest of the
 * session.
 *
 * <p><b>Consequence:</b> all sub-levels across all Minecraft dimensions share one Rapier
 * {@code PhysicsScene}: one {@code RigidBodySet}, one {@code ColliderSet}, one broadphase, one
 * island solver. Parent terrain blocks from every Minecraft dim register into the same scene.
 * This is what enables Portal-style cross-boundary physics in the future (no cross-scene bridge
 * needed - everything's already in one scene).
 *
 * <p><b>Gravity caveat:</b> the first {@code initialize} call sets the scene's gravity from that
 * caller's Level. Subsequent calls are skipped, so other Levels' gravity values are ignored.
 * For the current scope (overworld-like gravity for all sub-levels) this is correct. If we ever
 * want per-sub-level gravity (e.g., space airships, swimming), we'd apply per-body gravity scaling
 * instead of per-scene gravity - a phase 9 enhancement.
 *
 * <p><b>Performance:</b> single global broadphase. Rapier's island solver still parallelizes
 * non-interacting bodies (an airship 10k blocks away ends up in a different island from local
 * physics). For our scope this is strictly better than Sable's current per-Minecraft-dim
 * partitioning, which couples physics locality to dimension boundaries (wrong axis). If
 * performance ever regresses, two escape hatches: (a) flip the {@code parallel} feature flag in
 * Sable's bundled Rapier crate, (b) custom spatial partitioning in the Sable Rust dispatcher.
 *
 * @see ipl.sable.mixin.IplSubLevelAllocRoutingMixin phase 2 routing this complements
 * @see ipl.sable.mixin.IplSubLevelFieldSplitMixin phase 1 field split
 */
@Mixin(Rapier3D.class)
public abstract class IplRapier3DUnifyMixin {

    @Unique
    private static final Logger IPL$LOG = LoggerFactory.getLogger("ipl-sable-rapier-unify");

    /**
     * The single Rapier scene ID every Sable physics operation gets unified onto. Zero is the
     * default first-assigned scene ID by Sable's {@code countingSceneID} (which starts at 0 and
     * increments). Choosing 0 means even unmodified code paths that bypass our redirect (e.g.,
     * literal {@code Rapier3D.initialize(0, ...)} calls if any exist) still hit the same scene.
     */
    @Unique
    private static final int IPL$GLOBAL_SCENE = 0;

    @Unique
    private static volatile boolean ipl$loggedGetIdRedirect = false;

    /**
     * Redirect {@link Rapier3D#getID(ServerLevel)} to always return the unified scene ID. Also
     * mirror that value into the Level's {@code sable$setSceneID} extension so any code reading
     * the scene ID directly (without going through {@code Rapier3D.getID}) gets the same answer.
     */
    @Inject(
        method = "getID(Lnet/minecraft/server/level/ServerLevel;)I",
        at = @At("HEAD"),
        cancellable = true
    )
    private static void ipl$unifySceneId(ServerLevel level, CallbackInfoReturnable<Integer> cir) {
        // Mirror the unified ID into the extension so direct readers see it too.
        if (level instanceof ServerLevelSceneExtension extension) {
            if (extension.sable$getSceneID() != IPL$GLOBAL_SCENE) {
                extension.sable$setSceneID(IPL$GLOBAL_SCENE);
            }
        }
        if (!ipl$loggedGetIdRedirect) {
            ipl$loggedGetIdRedirect = true;
            IPL$LOG.info(
                "[IPL-RAPIER-UNIFY] Rapier3D.getID(ServerLevel) redirected to canonical scene {} "
                    + "(first hit on dim={}). All subsequent physics operations across all dims "
                    + "share this scene.",
                IPL$GLOBAL_SCENE,
                level.dimension().location()
            );
        }
        cir.setReturnValue(IPL$GLOBAL_SCENE);
    }
}
