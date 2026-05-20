package ipl.sable.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelTrackingSystem;
import ipl.sable.duck.IplSubLevelDuck;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase 4 fix: redirect {@code SubLevelTrackingSystem}'s player-collection calls to use each
 * sub-level's {@code parentLevel} instead of the tracking system's own {@code this.level}.
 *
 * <p><b>Why this is needed:</b> after phase 2, every sub-level lives in the {@code sable_sublevels}
 * dim. Sable's tracking system is bound per-Level (via {@code LevelsMixin}), so the tracking
 * system that holds these sub-levels is bound to {@code sable_sublevels}. Its
 * {@code this.level.players()} call returns the player list of {@code sable_sublevels} - which
 * is always empty because no player ever physically stands in that dim. Result: the tracking
 * system never finds any players to notify, never sends the {@code ClientboundStartTrackingSubLevelPacket},
 * and the airship is silently invisible client-side.
 *
 * <p><b>What this mixin does:</b> wraps the two {@code players()} calls in
 * {@code SubLevelTrackingSystem.tick} (at the addition-queue path and the "add new trackers"
 * path) and the one in {@code collectPlayers}. For each, looks up the surrounding loop's
 * {@code subLevel} variable via {@code @Local} and returns players from
 * {@code subLevel.parentLevel} instead.
 *
 * <p><b>Multi-parent natural:</b> if {@code sable_sublevels}'s container holds sub-levels with
 * different parents (an OW airship + a Nether airship), each gets its own correct player pool
 * - the redirect is per-sub-level, not container-wide.
 *
 * <p><b>Transit-correct:</b> when an airship's {@code parentLevel} flips during cross-portal
 * transit (phase 6), the next tick's player collection automatically picks up the new player
 * set. No special transit code needed for player tracking.
 *
 * <p><b>What about the {@code getPlayerByUUID} call at line 197?</b> That path is
 * {@code this.level.getPlayerByUUID(uuid)} - returns null for players not in {@code sable_sublevels}
 * (i.e., everyone). The original code's intent is "if this player has left the tracking dim, remove
 * them from the tracking list." After our refactor, the player is technically always "not in
 * sable_sublevels," which would cause Sable to incorrectly mark every player as having "left"
 * every tick. We redirect this too so the lookup hits the correct parent dim.
 */
@Mixin(SubLevelTrackingSystem.class)
public abstract class IplTrackingSystemParentRedirectMixin {

    @Unique
    private static final Logger IPL$LOG = LoggerFactory.getLogger("ipl-sable-tracking");

    @Unique
    private static final Set<String> ipl$loggedRedirect = ConcurrentHashMap.newKeySet();

    /**
     * Redirects every {@code ServerLevel.players()} call inside {@code SubLevelTrackingSystem.tick}
     * to use the loop's current sub-level's {@code parentLevel} when that parent is a different
     * ServerLevel than the tracking system's own. The {@code @Local} sugar captures the surrounding
     * loop variable; matches both the addition-queue iteration (line ~139) and the
     * "add new trackers" pass (line ~215).
     */
    @WrapOperation(
        method = "tick(Ldev/ryanhcode/sable/api/sublevel/SubLevelContainer;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;players()Ljava/util/List;")
    )
    private List<ServerPlayer> ipl$tickPlayersFromParent(
        ServerLevel trackingSystemLevel,
        Operation<List<ServerPlayer>> original,
        @Local SubLevel currentSubLevel
    ) {
        Level parent = ((IplSubLevelDuck) currentSubLevel).ipl$getParentLevel();
        if (parent instanceof ServerLevel parentServer && parentServer != trackingSystemLevel) {
            ipl$logFirstRedirect(trackingSystemLevel, parentServer, "tick");
            return parentServer.players();
        }
        return original.call(trackingSystemLevel);
    }

    /**
     * Same redirect, but for the {@code players()} call inside the private {@code collectPlayers}
     * method (line ~93). The caller (line ~150 in {@code tick}) iterates the addition queue with
     * a {@code subLevel} local in scope before invoking {@code collectPlayers(position, tracking)};
     * Mixin's call-stack-aware {@code @Local} resolution finds it.
     */
    @WrapOperation(
        method = "collectPlayers",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;players()Ljava/util/List;")
    )
    private List<ServerPlayer> ipl$collectPlayersFromParent(
        ServerLevel trackingSystemLevel,
        Operation<List<ServerPlayer>> original,
        @Local(argsOnly = false) SubLevel currentSubLevel
    ) {
        Level parent = ((IplSubLevelDuck) currentSubLevel).ipl$getParentLevel();
        if (parent instanceof ServerLevel parentServer && parentServer != trackingSystemLevel) {
            return parentServer.players();
        }
        return original.call(trackingSystemLevel);
    }

    /**
     * Redirect the per-UUID player lookup ({@code this.level.getPlayerByUUID}) to the sub-level's
     * parentLevel. Without this, every tracked player would be reported as "not in the tracking
     * dim" every tick (because no one is ever in {@code sable_sublevels}), triggering Sable's
     * "player left the area" removal path on every iteration.
     */
    @WrapOperation(
        method = "tick(Ldev/ryanhcode/sable/api/sublevel/SubLevelContainer;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getPlayerByUUID(Ljava/util/UUID;)Lnet/minecraft/world/entity/player/Player;")
    )
    private net.minecraft.world.entity.player.Player ipl$getPlayerFromParent(
        Level trackingSystemLevel,
        java.util.UUID uuid,
        Operation<net.minecraft.world.entity.player.Player> original,
        @Local SubLevel currentSubLevel
    ) {
        Level parent = ((IplSubLevelDuck) currentSubLevel).ipl$getParentLevel();
        if (parent != null && parent != trackingSystemLevel) {
            return parent.getPlayerByUUID(uuid);
        }
        return original.call(trackingSystemLevel, uuid);
    }

    @Unique
    private static void ipl$logFirstRedirect(ServerLevel tracking, ServerLevel parent, String method) {
        String key = method + ":" + tracking.dimension().location() + "->" + parent.dimension().location();
        if (ipl$loggedRedirect.add(key)) {
            IPL$LOG.info(
                "[IPL-SABLE-TRACKING] redirected player collection in {} from tracking-dim={} to parent-dim={}",
                method,
                tracking.dimension().location(),
                parent.dimension().location()
            );
        }
    }
}
