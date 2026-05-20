package ipl.sable.mixin;

import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.physics.impl.rapier.Rapier3D;
import dev.ryanhcode.sable.physics.impl.rapier.RapierPhysicsPipeline;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import org.joml.Vector3dc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Ensure every rigid body registered into the unified Rapier scene has a non-null
 * {@code center_of_mass} and {@code local_bounds} on the Rust side, even when the sub-level has
 * zero mass at registration time.
 *
 * <p><b>Why:</b> Rapier's per-tick {@code compute_buoyancy}
 * ({@code common/src/main/rust/rapier/src/buoyancy.rs:14-38}) iterates every rigid body in the
 * scene and unconditionally unwraps {@code info.center_of_mass} and {@code info.local_bounds_*}.
 * A {@code None} value triggers a non-unwinding Rust panic - process abort, no JVM exception, no
 * crash report.
 *
 * <p>{@link RapierPhysicsPipeline#add(ServerSubLevel, Pose3dc)} at line 311 only calls
 * {@code onStatsChanged} (which sets CoM and bounds in Rapier) when the sub-level's mass tracker
 * already has a non-null CoM. For a freshly-allocated empty sub-level - which is what our phase 2
 * routing produces, since {@code SubLevelContainer.allocateSubLevel} fires observers (including
 * {@code pipeline.add}) BEFORE the caller fills the plot with blocks - CoM is null, the if-branch
 * is skipped, and Rapier never receives initial values. The first tick of {@code compute_buoyancy}
 * then panics.
 *
 * <p><b>Fix:</b> after Sable's {@code pipeline.add} completes, if the sub-level still has no CoM,
 * write dummy defaults to Rapier so buoyancy (and any other per-tick traversal) doesn't panic. The
 * defaults are harmless: any subsequent block-add triggers {@code updateMassDataFromBlockChange}
 * which calls {@code onStatsChanged} which overwrites the dummies with real values. For airships
 * that never receive blocks (aborted builds), the dummy physics is meaningless but non-fatal.
 *
 * <p><b>Default values chosen:</b>
 * <ul>
 *   <li>{@code centerOfMass = (0, 0, 0)} - origin of the sub-level's local space. Won't match the
 *       real CoM but is a valid 3D point.</li>
 *   <li>{@code localBounds = (0,0,0) to (1,1,1)} - one-block AABB. Smallest sensible non-degenerate
 *       bounds; avoids zero-volume edge cases in the broadphase.</li>
 * </ul>
 *
 * <p>This mixin is a workaround for an ordering assumption baked into Sable's spawn path. A
 * cleaner long-term fix would be to either (a) defer {@code pipeline.add} until the first block
 * is placed, or (b) have Rust's {@code compute_buoyancy} skip bodies with no CoM rather than
 * panicking. Either is outside our current scope.
 */
@Mixin(RapierPhysicsPipeline.class)
public abstract class IplPipelineAddDefaultsMixin {

    @Unique
    private static final Logger IPL$LOG = LoggerFactory.getLogger("ipl-sable-pipeline-defaults");

    @Unique
    private static volatile boolean ipl$loggedFirstDefault = false;

    @Shadow(remap = false)
    private int sceneId;

    @Inject(
        method = "add(Ldev/ryanhcode/sable/sublevel/ServerSubLevel;Ldev/ryanhcode/sable/companion/math/Pose3dc;)V",
        at = @At("TAIL")
    )
    private void ipl$ensureDefaultsForEmptyBody(ServerSubLevel subLevel, Pose3dc pose, CallbackInfo ci) {
        Vector3dc com = subLevel.getMassTracker().getCenterOfMass();
        if (com != null) {
            // Real CoM was computed and onStatsChanged already fired - nothing to do.
            return;
        }

        int id = Rapier3D.getID(subLevel);

        // Write the smallest sensible non-degenerate defaults. Both calls reach the same Rapier
        // scene as the body was just registered into (because every sceneId resolves to the
        // unified scene 0 via IplRapier3DUnifyMixin), so the lookup-by-id always succeeds.
        Rapier3D.setCenterOfMass(this.sceneId, id, 0.0, 0.0, 0.0);
        Rapier3D.setLocalBounds(this.sceneId, id, 0, 0, 0, 1, 1, 1);

        if (!ipl$loggedFirstDefault) {
            ipl$loggedFirstDefault = true;
            IPL$LOG.info(
                "[IPL-PIPELINE-DEFAULTS] first sub-level with null CoM at pipeline.add - "
                    + "writing defaults (CoM=(0,0,0), bounds=(0,0,0)..(1,1,1)) so per-tick "
                    + "buoyancy/etc. don't panic. Real values arrive when blocks are placed. "
                    + "uuid={} sceneId={} bodyId={}",
                subLevel.getUniqueId(),
                this.sceneId,
                id
            );
        }
    }
}
