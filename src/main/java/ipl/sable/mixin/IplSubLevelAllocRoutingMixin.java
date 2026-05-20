package ipl.sable.mixin;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.SubLevel;
import ipl.sable.dim.SableSubLevelDimension;
import ipl.sable.duck.IplSubLevelDuck;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.ClientWorldLoader;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase 2: route allocation calls (both {@code allocateNewSubLevel(Pose3d)} and
 * {@code allocateSubLevel(UUID, int, int, Pose3d)}) through the {@code ipl_sable:sublevels}
 * dimension regardless of which parent the caller targeted. Captures parent identity into
 * {@link IplSubLevelDuck#ipl$setParentLevel} so the airship still knows where it visually appears.
 *
 * <p><b>Why intercept at the container level instead of each caller:</b> Sable has many
 * allocation entry points (commands, {@code SubLevelAssemblyHelper}, schematic placement,
 * Create-contraption integration, and the client-side {@code ClientboundStartTrackingSubLevelPacket}
 * handler). They all funnel through one of these two container methods. Intercepting both at the
 * container level captures every path at once.
 *
 * <p><b>Both methods get intercepted because the client uses the UUID variant:</b>
 * {@link dev.ryanhcode.sable.network.packets.tcp.ClientboundStartTrackingSubLevelPacket#handle}
 * at line 69 calls {@code clientContainer.allocateSubLevel(uuid, x, z, pose)} - the deterministic
 * variant - using {@code context.level()} (the player's current dim) as the container source.
 * Without intercepting this, the client constructs the {@code ClientSubLevel} in the player's
 * current container while the server has it in {@code sable_sublevels}, breaking sync.
 *
 * <p><b>Recursion safety:</b> when we forward the call to {@code sableContainer.allocateX(...)},
 * the injection fires again with {@code this == sableContainer}. The dimension check at the top
 * short-circuits, so the recursive call falls through to Sable's normal allocation path.
 *
 * <p><b>Client-side container resolution:</b> uses
 * {@link ClientWorldLoader#getWorld(ResourceKey)} to look up the {@code sable_sublevels}
 * {@code ClientLevel}. IP's {@code ClientWorldLoader} maintains a per-dim {@code ClientLevel} map
 * ({@code CLIENT_WORLD_MAP} at line 57); the {@code sable_sublevels} entry is populated when the
 * client learns about the dim from the server's dim-type-mapping packet (confirmed in our latest
 * log at 02:35:21.176).
 *
 * <p><b>What about existing airships from pre-phase-2 worlds?</b> They still appear in their
 * parent dim's container's {@code allSubLevels} list, with {@code ipl$parentLevel == ipl$hostingLevel}
 * (both pointing at the parent). They render through the parent's container path - "invisible"
 * to the new sable_sublevels render pipeline until phase 7's migrator relocates them.
 */
@Mixin(SubLevelContainer.class)
public abstract class IplSubLevelAllocRoutingMixin {

    @Unique
    private static final Logger IPL$LOG = LoggerFactory.getLogger("ipl-sable-alloc");

    @Unique
    private static volatile boolean ipl$warnedAboutMissingDim = false;

    /**
     * One-shot success log per parent dim. So the first allocation from each parent dim leaves
     * a clear breadcrumb in the log; subsequent allocations stay quiet to avoid spam.
     */
    @Unique
    private static final Set<ResourceKey<Level>> ipl$loggedRouting = ConcurrentHashMap.newKeySet();

    @Shadow
    public abstract Level getLevel();

    @Inject(
        method = "allocateNewSubLevel(Ldev/ryanhcode/sable/companion/math/Pose3d;)Ldev/ryanhcode/sable/sublevel/SubLevel;",
        at = @At("HEAD"),
        cancellable = true
    )
    private void ipl$routeAllocateNewSubLevel(Pose3d pose, CallbackInfoReturnable<SubLevel> cir) {
        Level thisLevel = this.getLevel();
        if (thisLevel.dimension().equals(SableSubLevelDimension.SUBLEVELS)) {
            return; // already on host - normal path (also catches our recursion)
        }
        SubLevelContainer hostContainer = ipl$resolveHostContainer(thisLevel);
        if (hostContainer == null) {
            return; // dim missing or unreachable - fall through to original behavior
        }
        SubLevel sub = hostContainer.allocateNewSubLevel(pose);
        ((IplSubLevelDuck) sub).ipl$setParentLevel(thisLevel);
        ipl$logFirstRouting(thisLevel, sub, "allocateNewSubLevel");
        cir.setReturnValue(sub);
    }

    @Inject(
        method = "allocateSubLevel(Ljava/util/UUID;IILdev/ryanhcode/sable/companion/math/Pose3d;)Ldev/ryanhcode/sable/sublevel/SubLevel;",
        at = @At("HEAD"),
        cancellable = true
    )
    private void ipl$routeAllocateSubLevel(UUID uuid, int x, int z, Pose3d pose, CallbackInfoReturnable<SubLevel> cir) {
        Level thisLevel = this.getLevel();
        if (thisLevel.dimension().equals(SableSubLevelDimension.SUBLEVELS)) {
            return; // already on host
        }
        SubLevelContainer hostContainer = ipl$resolveHostContainer(thisLevel);
        if (hostContainer == null) {
            return;
        }
        SubLevel sub = hostContainer.allocateSubLevel(uuid, x, z, pose);
        ((IplSubLevelDuck) sub).ipl$setParentLevel(thisLevel);
        ipl$logFirstRouting(thisLevel, sub, "allocateSubLevel");
        cir.setReturnValue(sub);
    }

    /**
     * Find the {@code sable_sublevels} container for the side ({@code ServerLevel}/{@code ClientLevel})
     * of the calling code. Returns null if the host dim isn't loaded - caller falls through to the
     * original allocation path in that case (degraded behavior, but no crash).
     */
    @Unique
    @Nullable
    private static SubLevelContainer ipl$resolveHostContainer(Level fromLevel) {
        if (fromLevel instanceof ServerLevel serverFromLevel) {
            MinecraftServer server = serverFromLevel.getServer();
            if (server == null) return null;
            ServerLevel host = server.getLevel(SableSubLevelDimension.SUBLEVELS);
            if (host == null) {
                ipl$warnMissingDim(fromLevel);
                return null;
            }
            return SubLevelContainer.getContainer(host);
        }
        if (fromLevel instanceof ClientLevel) {
            // ClientWorldLoader maintains a per-dim ClientLevel cache. The sable_sublevels entry is
            // populated when the client receives the dim-type-mapping packet from the server (this
            // happens automatically at world join because sable_sublevels is in MinecraftServer's
            // level map - it's a real registered dim, not a fake one).
            ClientLevel host;
            try {
                host = ClientWorldLoader.getWorld(SableSubLevelDimension.SUBLEVELS);
            } catch (Throwable t) {
                // getWorld throws if the dim is invalid or main-thread check fails. Log once and bail.
                ipl$warnMissingDim(fromLevel);
                return null;
            }
            if (host == null) {
                ipl$warnMissingDim(fromLevel);
                return null;
            }
            return SubLevelContainer.getContainer(host);
        }
        return null;
    }

    @Unique
    private static void ipl$warnMissingDim(Level fromLevel) {
        if (!ipl$warnedAboutMissingDim) {
            ipl$warnedAboutMissingDim = true;
            IPL$LOG.warn(
                "[IPL-SABLE-ALLOC] sable_sublevels dim not loaded on side={} - falling back to "
                    + "parent-dim allocation for sub-level on {}. Cross-portal transit will not "
                    + "work for this airship. Verify datapack registration (phase 0 kill-switch).",
                fromLevel.isClientSide ? "CLIENT" : "SERVER",
                fromLevel.dimension().location()
            );
        }
    }

    @Unique
    private static void ipl$logFirstRouting(Level parentLevel, SubLevel sub, String method) {
        if (ipl$loggedRouting.add(parentLevel.dimension())) {
            IPL$LOG.info(
                "[IPL-SABLE-ALLOC] routed first sub-level via {} from parent={} (side={}) uuid={}",
                method,
                parentLevel.dimension().location(),
                parentLevel.isClientSide ? "CLIENT" : "SERVER",
                sub.getUniqueId()
            );
        }
    }
}
